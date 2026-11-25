package com.example.domain.invoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.data.InvoiceSourceType
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "InvoiceLocalExtractor"
private const val MAX_IMAGE_DIMENSION = 1600
private const val MAX_PDF_PAGES = 4
private const val PDF_TARGET_WIDTH_PX = 1440

data class LocalOcrSnapshot(
    val pages: List<OcrPageInput>,
    val reconstructedText: String,
    val scannedBarcodes: List<String>
) {
    val lineCount: Int get() = pages.sumOf { it.lines.size }
}

class InvoiceLocalExtractor {

    suspend fun extractSnapshotFromImageUri(context: Context, imageUri: Uri): Result<LocalOcrSnapshot> {
        return try {
            val bitmap = withContext(Dispatchers.IO) {
                decodeOrientedBitmap(context, imageUri, MAX_IMAGE_DIMENSION)
            } ?: return Result.failure(IllegalArgumentException("Não foi possível abrir a imagem selecionada."))

            val page = try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val scanner = BarcodeScanning.getClient()
                try {
                    runOcrAndBarcodeOnBitmap(bitmap, recognizer, scanner)
                } finally {
                    recognizer.close()
                    scanner.close()
                }
            } finally {
                bitmap.recycle()
            }

            Result.success(buildSnapshot(listOf(page)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local OCR image snapshot failed", e)
            Result.failure(
                IllegalStateException(
                    "Falha na leitura local da foto: ${e.localizedMessage ?: "verifique a nitidez da imagem"}"
                )
            )
        }
    }

    suspend fun extractSnapshotFromPdfUri(context: Context, pdfUri: Uri): Result<LocalOcrSnapshot> {
        return try {
            val pages = withContext(Dispatchers.IO) {
                renderPdfPagesAndRecognize(context, pdfUri)
            }
            if (pages.isEmpty()) {
                return Result.failure(IllegalArgumentException("O arquivo PDF não possui páginas legíveis."))
            }
            Result.success(buildSnapshot(pages))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local OCR PDF snapshot failed", e)
            Result.failure(
                IllegalStateException(
                    "Falha na leitura local do PDF: ${e.localizedMessage ?: "arquivo protegido ou corrompido"}"
                )
            )
        }
    }

    suspend fun extractFromImageUri(context: Context, imageUri: Uri): Result<ParsedInvoiceDraft> {
        val snapshot = extractSnapshotFromImageUri(context, imageUri).getOrElse { return Result.failure(it) }
        return LocalInvoiceOcrParser.parsePages(snapshot.pages, InvoiceSourceType.OCR_FOTO)
    }

    suspend fun extractFromPdfUri(context: Context, pdfUri: Uri): Result<ParsedInvoiceDraft> {
        val snapshot = extractSnapshotFromPdfUri(context, pdfUri).getOrElse { return Result.failure(it) }
        return LocalInvoiceOcrParser.parsePages(snapshot.pages, InvoiceSourceType.OCR_PDF)
    }

    private fun buildSnapshot(pages: List<OcrPageInput>): LocalOcrSnapshot {
        val reconstructedRows = pages.flatMap { LocalInvoiceOcrParser.reconstructRows(it.lines) }
        val barcodes = pages.flatMap { it.scannedBarcodes }.distinct()
        val combinedText = buildString {
            append(reconstructedRows.joinToString("\n"))
            if (barcodes.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("CODIGOS DETECTADOS: ")
                append(barcodes.joinToString(" | "))
            }
        }.trim()
        return LocalOcrSnapshot(
            pages = pages,
            reconstructedText = combinedText,
            scannedBarcodes = barcodes
        )
    }

    private suspend fun renderPdfPagesAndRecognize(
        context: Context,
        pdfUri: Uri
    ): List<OcrPageInput> {
        val pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
            ?: throw IllegalArgumentException("Não foi possível abrir o arquivo PDF selecionado.")

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val scanner = BarcodeScanning.getClient()
        val recognizedPages = mutableListOf<OcrPageInput>()
        try {
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
                    for (pageIndex in 0 until pageCount) {
                        val bitmap = renderer.openPage(pageIndex).use { page ->
                            val scale = (PDF_TARGET_WIDTH_PX.toFloat() / max(page.width, 1)).coerceIn(1.2f, 2.5f)
                            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bmp ->
                                Canvas(bmp).drawColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                        val pageInput = try {
                            runOcrAndBarcodeOnBitmap(bitmap, recognizer, scanner)
                        } finally {
                            bitmap.recycle()
                        }
                        if (pageInput.lines.isNotEmpty() || pageInput.scannedBarcodes.isNotEmpty() || pageIndex == 0) {
                            recognizedPages.add(pageInput)
                        }
                    }
                }
            }
        } finally {
            recognizer.close()
            scanner.close()
        }
        return recognizedPages
    }

    private suspend fun runOcrAndBarcodeOnBitmap(
        bitmap: Bitmap,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner
    ): OcrPageInput = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        val textDeferred = async { recognizer.process(image).awaitResult() }
        val barcodeDeferred = async {
            try {
                scanner.process(image).awaitResult()
            } catch (e: Exception) {
                Log.w(TAG, "Barcode detection skipped on OCR frame", e)
                emptyList<Barcode>()
            }
        }

        val visionText = textDeferred.await()
        val barcodes = barcodeDeferred.await()

        val lines = visionText.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val rect = line.boundingBox
                OcrLineBox(
                    text = line.text,
                    left = rect?.left?.toFloat() ?: 0f,
                    top = rect?.top?.toFloat() ?: 0f,
                    right = rect?.right?.toFloat() ?: 0f,
                    bottom = rect?.bottom?.toFloat() ?: 0f
                )
            }
        }

        val rawCodes = barcodes.mapNotNull { it.rawValue?.takeIf { raw -> raw.isNotBlank() } }
        OcrPageInput(lines = lines, scannedBarcodes = rawCodes)
    }

    private fun decodeOrientedBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val orientation = context.contentResolver.openInputStream(uri)?.use { exifStream ->
            runCatching {
                ExifInterface(exifStream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (maxSide / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotationDegrees == 0f) return decoded

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return try {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) decoded.recycle()
            rotated
        } catch (_: Exception) {
            decoded
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
}
