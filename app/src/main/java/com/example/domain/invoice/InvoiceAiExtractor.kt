package com.example.domain.invoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.example.ai.inspectAppCheckTokenDiagnostic
import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "InvoiceAiExtractor"
private const val MAX_INLINE_BYTES = 18 * 1024 * 1024 // Keep safely below 20MB limit
private const val AI_IMAGE_MAX_DIM = 1280
private const val AI_IMAGE_JPEG_QUALITY = 82
private const val AI_REQUEST_TIMEOUT_MS = 45_000L

class InvoiceAiExtractor {

    private val invoiceSchema = Schema.obj(
        properties = mapOf(
            "invoiceNumber" to Schema.string(description = "Número da NF-e / DANFE / cupom"),
            "accessKey" to Schema.string(description = "Chave de acesso de 44 dígitos, se visível"),
            "supplierName" to Schema.string(description = "Fornecedor ou frigorífico"),
            "supplierCnpj" to Schema.string(description = "CNPJ do fornecedor"),
            "additionalNotes" to Schema.string(description = "Observações, dados adicionais ou anotações"),
            "arrivalTempCelsius" to Schema.double(description = "Temperatura anotada em °C, ou null", nullable = true),
            "items" to Schema.array(
                description = "Produtos cárneos da nota",
                items = Schema.obj(
                    properties = mapOf(
                        "rawDescription" to Schema.string(description = "Descrição original do item"),
                        "meatType" to Schema.enumeration(
                            values = listOf("BOVINA", "SUINA", "AVES", "OVINA", "PEIXES", "EMBUTIDOS_OUTROS"),
                            description = "Tipo de carne"
                        ),
                        "cutName" to Schema.string(description = "Nome do corte limpo, ex: Picanha, Alcatra"),
                        "temperatureZone" to Schema.enumeration(
                            values = listOf("RESFRIADO", "CONGELADO", "MATURACAO"),
                            description = "Conservação térmica"
                        ),
                        "quantity" to Schema.double(description = "Peso ou quantidade numérica"),
                        "unit" to Schema.enumeration(
                            values = listOf("KG", "PECA", "CAIXA"),
                            description = "Unidade"
                        ),
                        "unitCostBrl" to Schema.double(description = "Valor unitário R$"),
                        "batchCode" to Schema.string(description = "Lote, se houver"),
                        "sifCode" to Schema.string(description = "SIF, se houver"),
                        "expiryDateIso" to Schema.string(description = "Validade YYYY-MM-DD, se houver"),
                        "itemNotes" to Schema.string(description = "Observação do item, se houver")
                    ),
                    optionalProperties = listOf("unitCostBrl", "batchCode", "sifCode", "expiryDateIso", "itemNotes")
                )
            )
        ),
        optionalProperties = listOf("invoiceNumber", "accessKey", "supplierName", "supplierCnpj", "additionalNotes", "arrivalTempCelsius")
    )

    private val fastModel by lazy {
        createGenerativeModel("gemini-3.5-flash-lite")
    }

    private val accurateModel by lazy {
        createGenerativeModel("gemini-3.8-flash")
    }

    private fun createGenerativeModel(modelName: String): GenerativeModel {
        return Firebase.ai.generativeModel(
            modelName = modelName,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = invoiceSchema
                temperature = 0.0f
            }
        )
    }

    suspend fun extractFromImageUri(context: Context, imageUri: Uri): Result<ParsedInvoiceDraft> {
        return try {
            if (!ensureFirebaseInitialized(context.applicationContext)) {
                return Result.failure(IllegalStateException(MISSING_FIREBASE_CONFIG_MESSAGE))
            }
            val jpegBytes = withContext(Dispatchers.IO) {
                encodeCompressedInvoiceJpeg(context, imageUri, AI_IMAGE_MAX_DIM, AI_IMAGE_JPEG_QUALITY)
            } ?: return Result.failure(IllegalArgumentException("Não foi possível abrir a imagem selecionada."))

            val requestContent = content {
                inlineData(jpegBytes, "image/jpeg")
                text(buildPrompt())
            }
            val response = generateContentWithFastFallback(requestContent, escalateIfEmptyItems = true)
            val jsonText = response.text
                ?: return Result.failure(IllegalStateException("A IA não retornou conteúdo para esta nota."))
            Result.success(parseJsonToDraft(jsonText, InvoiceSourceType.IA_FOTO))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI image extraction timed out", e)
            Result.failure(IllegalStateException("Tempo limite excedido (45s) ao ler a foto da nota. Tente novamente."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI image extraction failed", e)
            Result.failure(IllegalStateException(formatAiFailureMessage("Falha na leitura por IA", e, context)))
        }
    }

    suspend fun extractFromPdfUri(context: Context, pdfUri: Uri): Result<ParsedInvoiceDraft> {
        return try {
            if (!ensureFirebaseInitialized(context.applicationContext)) {
                return Result.failure(IllegalStateException(MISSING_FIREBASE_CONFIG_MESSAGE))
            }
            val pdfBytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(pdfUri)?.use { input ->
                    val buffer = ByteArrayOutputStream()
                    input.copyTo(buffer)
                    buffer.toByteArray()
                }
            } ?: return Result.failure(IllegalArgumentException("Não foi possível ler o arquivo PDF selecionado."))

            if (pdfBytes.size > MAX_INLINE_BYTES) {
                return Result.failure(IllegalArgumentException("O PDF excede o limite de 18 MB para leitura automática."))
            }

            val requestContent = content {
                inlineData(pdfBytes, "application/pdf")
                text(buildPrompt())
            }
            val response = generateContentWithFastFallback(requestContent, escalateIfEmptyItems = true)
            val jsonText = response.text
                ?: return Result.failure(IllegalStateException("A IA não retornou conteúdo para este PDF."))
            Result.success(parseJsonToDraft(jsonText, InvoiceSourceType.IA_PDF))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI PDF extraction timed out", e)
            Result.failure(IllegalStateException("Tempo limite excedido (45s) ao ler o PDF da nota. Tente novamente."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI PDF extraction failed", e)
            Result.failure(IllegalStateException(formatAiFailureMessage("Falha ao ler PDF com IA", e, context)))
        }
    }

    suspend fun extractFromOcrText(
        context: Context,
        rawText: String,
        sourceType: InvoiceSourceType = InvoiceSourceType.IA_FOTO
    ): Result<ParsedInvoiceDraft> {
        return try {
            if (!ensureFirebaseInitialized(context.applicationContext)) {
                return Result.failure(IllegalStateException(MISSING_FIREBASE_CONFIG_MESSAGE))
            }
            val trimmedText = rawText.trim()
            if (trimmedText.isBlank()) {
                return Result.failure(IllegalArgumentException("O texto OCR está vazio para enviar à IA."))
            }

            // Cap text around 12k chars so structured extraction stays sub-3s
            val compactText = trimmedText.take(12_000)
            val requestContent = content {
                text("Texto OCR da nota fiscal:\n$compactText\n\n${buildPrompt()}")
            }
            val response = generateContentWithFastFallback(requestContent, escalateIfEmptyItems = false)
            val jsonText = response.text
                ?: return Result.failure(IllegalStateException("A IA não retornou conteúdo estruturado."))
            Result.success(parseJsonToDraft(jsonText, sourceType, rawOcrText = trimmedText))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI text extraction timed out", e)
            Result.failure(IllegalStateException("Tempo limite excedido ao estruturar com IA."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI text extraction failed", e)
            Result.failure(IllegalStateException(formatAiFailureMessage("Falha na estruturação por IA", e, context)))
        }
    }

    private suspend fun generateContentWithFastFallback(
        requestContent: Content,
        escalateIfEmptyItems: Boolean
    ): GenerateContentResponse {
        return withTimeout(AI_REQUEST_TIMEOUT_MS) {
            val fastResult = runCatching { fastModel.generateContent(requestContent) }
            val firstResponse = fastResult.getOrNull()
            if (firstResponse != null) {
                val hasItems = !escalateIfEmptyItems || hasAnyExtractedItem(firstResponse.text)
                if (hasItems) {
                    return@withTimeout firstResponse
                }
                Log.i(TAG, "Fast model returned no items on multimodal input, escalating to accurate model")
                return@withTimeout accurateModel.generateContent(requestContent)
            }

            val err = fastResult.exceptionOrNull()
            if (err is CancellationException) throw err
            val ex = err as? Exception ?: IllegalStateException("Erro ao consultar IA.")
            if (!isTransientCapacityError(ex) && !escalateIfEmptyItems) {
                throw ex
            }

            Log.w(TAG, "Primary fast model failed, falling back to accurate model", ex)
            delay(250L)
            try {
                accurateModel.generateContent(requestContent)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun hasAnyExtractedItem(jsonText: String?): Boolean {
        if (jsonText.isNullOrBlank()) return false
        return runCatching {
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("items")
            arr != null && arr.length() > 0
        }.getOrDefault(false)
    }

    private fun isTransientCapacityError(error: Exception): Boolean {
        val msg = error.localizedMessage.orEmpty()
        return msg.contains("high demand", ignoreCase = true) ||
            msg.contains("overloaded", ignoreCase = true) ||
            msg.contains("temporarily unavailable", ignoreCase = true) ||
            msg.contains("try again later", ignoreCase = true) ||
            msg.contains("503") ||
            msg.contains("429") ||
            msg.contains("UNAVAILABLE", ignoreCase = true) ||
            msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
    }

    fun isAppCheckFailure(error: Throwable?): Boolean {
        val rawMessage = error?.localizedMessage ?: error?.message ?: return false
        return rawMessage.contains("App Check", ignoreCase = true) ||
            rawMessage.contains("AppCheck", ignoreCase = true) ||
            rawMessage.contains("403")
    }

    private suspend fun formatAiFailureMessage(prefix: String, error: Exception, context: Context): String {
        val rawMessage = error.localizedMessage?.takeIf { it.isNotBlank() } ?: "erro de rede ou configuração"
        return when {
            isAppCheckFailure(error) -> {
                val diagnostic = runCatching {
                    inspectAppCheckTokenDiagnostic(context.applicationContext)
                }.getOrDefault("")
                buildString {
                    append("$prefix: $rawMessage")
                    if (diagnostic.isNotBlank()) {
                        append("\n\nDiagnóstico: $diagnostic")
                    }
                    append("\n\nAPKs instalados fora da Play Store ou do emulador do AI Studio falham na validação App Check/reCAPTCHA do projeto Firebase remoto. Use o botão \"Extrair Agora (OCR Offline)\" abaixo ou XML NF-e / QR DANFE para preencher tudo localmente.")
                }
            }
            isTransientCapacityError(error) -> {
                "$prefix: $rawMessage\n\n" +
                    "O servidor da IA está com pico temporário de demanda no momento. " +
                    "Aguarde alguns segundos e tente novamente ou utilize o OCR Offline / XML NF-e / QR DANFE."
            }
            else -> "$prefix: $rawMessage"
        }
    }

    private fun ensureFirebaseInitialized(appContext: Context): Boolean {
        return try {
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                true
            } else {
                FirebaseApp.initializeApp(appContext) != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FirebaseApp", e)
            false
        }
    }

    private companion object {
        const val MISSING_FIREBASE_CONFIG_MESSAGE =
            "Firebase não está configurado neste APK (arquivo google-services.json ausente na compilação). " +
                "Gere o APK pelo AI Studio ou adicione o google-services.json no repositório/Secrets do GitHub. " +
                "As funções offline (XML NF-e, QR/Código DANFE e lançamento manual) continuam funcionando normalmente."
    }

    private fun buildPrompt(): String {
        return """
            Extraia os dados de recebimento desta nota fiscal/romaneio de açougue no Brasil.
            Se houver dados adicionais/observações ou temperatura anotada (°C), preencha additionalNotes e arrivalTempCelsius.
            Para cada produto real da nota (não invente itens): tipo de carne (BOVINA, SUINA, AVES, OVINA, PEIXES, EMBUTIDOS_OUTROS), corte limpo em português, zona (RESFRIADO, CONGELADO, MATURACAO), quantidade, unidade (KG, PECA, CAIXA), custo unitário, lote, SIF, validade (YYYY-MM-DD) e observação do item.
        """.trimIndent()
    }

    private fun parseJsonToDraft(
        jsonString: String,
        sourceType: InvoiceSourceType,
        rawOcrText: String = ""
    ): ParsedInvoiceDraft {
        val root = JSONObject(jsonString)
        val invoiceNumber = root.optString("invoiceNumber", "").trim()
        val accessKey = root.optString("accessKey", "").filter { it.isDigit() }.take(44)
        val supplierName = root.optString("supplierName", "").trim()
        val supplierCnpj = root.optString("supplierCnpj", "").trim()
        val additionalNotes = root.optString("additionalNotes", "").trim()
        val arrivalTemp = if (root.has("arrivalTempCelsius") && !root.isNull("arrivalTempCelsius")) {
            root.optDouble("arrivalTempCelsius", Double.NaN).takeIf { !it.isNaN() && it in -40.0..30.0 }
        } else {
            null
        }

        val itemsArray = root.optJSONArray("items")
        val parsedItems = mutableListOf<ParsedInvoiceItemDraft>()
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.optJSONObject(i) ?: continue
                val rawDesc = obj.optString("rawDescription", "").trim()
                val explicitMeat = obj.optString("meatType", "")
                val explicitCut = obj.optString("cutName", "")
                val explicitZone = obj.optString("temperatureZone", "")
                val rawUnit = obj.optString("unit", "KG")
                val qty = obj.optDouble("quantity", 0.0).let { if (it.isNaN() || it <= 0.0) 1.0 else it }
                val cost = obj.optDouble("unitCostBrl", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
                val batch = obj.optString("batchCode", "").trim()
                val sif = obj.optString("sifCode", "").trim()
                val expiryIso = obj.optString("expiryDateIso", "").trim()
                val itemNotes = obj.optString("itemNotes", "").trim()

                val classified = MeatCatalogMatcher.classifyDescription(
                    rawDescription = rawDesc.ifBlank { explicitCut },
                    rawUnit = rawUnit,
                    explicitMeatType = explicitMeat,
                    explicitCutName = explicitCut,
                    explicitZone = explicitZone,
                    explicitExpiryEpochDay = DateUtils.parseIsoDateToEpochDay(expiryIso)
                )

                parsedItems.add(
                    ParsedInvoiceItemDraft(
                        rawDescription = rawDesc.ifBlank { classified.cutName },
                        meatType = classified.meatType,
                        cutName = classified.cutName,
                        temperatureZone = classified.temperatureZone,
                        measuredTempCelsius = arrivalTemp ?: classified.temperatureZone.defaultTempCelsius,
                        chamberName = classified.temperatureZone.defaultChamber,
                        quantity = qty,
                        unit = classified.unit,
                        unitCostBrl = cost,
                        batchCode = batch.ifBlank { classified.batchCode },
                        sifCode = sif.ifBlank { classified.sifCode },
                        expiryEpochDay = classified.expiryEpochDay,
                        notes = itemNotes
                    )
                )
            }
        }

        val combinedNotes = listOf("Extraído via IA Rápida", additionalNotes)
            .filter { it.isNotBlank() }
            .joinToString(" • ")

        return ParsedInvoiceDraft(
            invoiceNumber = invoiceNumber,
            accessKey = accessKey,
            supplierName = supplierName,
            supplierCnpj = supplierCnpj,
            sourceType = sourceType,
            notes = combinedNotes,
            rawOcrText = rawOcrText,
            extractedTempCelsius = arrivalTemp,
            items = parsedItems
        )
    }

    private fun encodeCompressedInvoiceJpeg(
        context: Context,
        uri: Uri,
        maxDimension: Int,
        jpegQuality: Int
    ): ByteArray? {
        val orientation = context.contentResolver.openInputStream(uri)?.use { exifStream ->
            runCatching {
                ExifInterface(exifStream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val maxSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
        if (maxSide <= 0) return null

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

        val oriented = if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            runCatching {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    if (it != decoded) decoded.recycle()
                }
            }.getOrDefault(decoded)
        } else {
            decoded
        }

        val currentMax = max(oriented.width, oriented.height)
        val scaled = if (currentMax > maxDimension) {
            val ratio = maxDimension.toFloat() / currentMax.toFloat()
            val targetW = (oriented.width * ratio).roundToInt().coerceAtLeast(1)
            val targetH = (oriented.height * ratio).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(oriented, targetW, targetH, true).also {
                if (it != oriented) oriented.recycle()
            }
        } else {
            oriented
        }

        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                out.toByteArray()
            }
        } finally {
            scaled.recycle()
        }
    }
}
