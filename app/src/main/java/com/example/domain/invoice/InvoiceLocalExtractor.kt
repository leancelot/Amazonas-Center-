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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "InvoiceLocalExtractor"
private const val MAX_IMAGE_DIMENSION = 2000
private const val MAX_PDF_PAGES = 5
private const val PDF_TARGET_WIDTH_PX = 1800

class InvoiceLocalExtractor {

    suspend fun extractFromImageUri(context: Context, imageUri: Uri): Result<ParsedInvoiceDraft> {
        return try {
            val bitmap = withContext(Dispatchers.IO) {
                decodeOrientedBitmap(context, imageUri, MAX_IMAGE_DIMENSION)
            } ?: return Result.failure(IllegalArgumentException("Não foi possível abrir a imagem selecionada."))

            val page = try {
                runOcrAndBarcodeOnBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }

            LocalInvoiceOcrParser.parsePages(listOf(page), InvoiceSourceType.OCR_FOTO)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local OCR image extraction failed", e)
            Result.failure(
                IllegalStateException(
                    "Falha na leitura local da foto: ${e.localizedMessage ?: "verifique a nitidez da imagem"}"
                )
            )
        }
    }

    suspend fun extractFromPdfUri(context: Context, pdfUri: Uri): Result<ParsedInvoiceDraft> {
        return try {
            val pages = withContext(Dispatchers.IO) {
                renderPdfPagesAndRecognize(context, pdfUri)
            }
            if (pages.isEmpty()) {
                return Result.failure(IllegalArgumentException("O arquivo PDF não possui páginas legíveis."))
            }
            LocalInvoiceOcrParser.parsePages(pages, InvoiceSourceType.OCR_PDF)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local OCR PDF extraction failed", e)
            Result.failure(
                IllegalStateException(
                    "Falha na leitura local do PDF: ${e.localizedMessage ?: "arquivo protegido ou corrompido"}"
                )
            )
        }
    }

    private suspend fun renderPdfPagesAndRecognize(
        context: Context,
        pdfUri: Uri
    ): List<OcrPageInput> {
        val pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
            ?: throw IllegalArgumentException("Não foi possível abrir o arquivo PDF selecionado.")

        val recognizedPages = mutableListOf<OcrPageInput>()
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
                for (pageIndex in 0 until pageCount) {
                    val bitmap = renderer.openPage(pageIndex).use { page ->
                        val scale = (PDF_TARGET_WIDTH_PX.toFloat() / max(page.width, 1)).coerceIn(1.5f, 3.0f)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    try {
                        recognizedPages.add(runOcrAndBarcodeOnBitmap(bitmap))
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        return recognizedPages
    }

    private suspend fun runOcrAndBarcodeOnBitmap(bitmap: Bitmap): OcrPageInput {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val scanner = BarcodeScanning.getClient()

        return try {
            val visionText = recognizer.process(image).awaitResult()
            val barcodes = try {
                scanner.process(image).awaitResult()
            } catch (e: Exception) {
                Log.w(TAG, "Barcode detection skipped on OCR frame", e)
                emptyList<Barcode>()
            }

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
        } finally {
            recognizer.close()
            scanner.close()
        }
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

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
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
