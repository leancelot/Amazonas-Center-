package com.example.domain.invoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

private const val TAG = "InvoiceAiExtractor"
private const val MAX_INLINE_BYTES = 18 * 1024 * 1024 // Keep safely below 20MB limit

class InvoiceAiExtractor {

    private val invoiceSchema = Schema.obj(
        properties = mapOf(
            "invoiceNumber" to Schema.string(description = "Número da nota fiscal (NF-e / DANFE / cupom), vazio se não visível"),
            "accessKey" to Schema.string(description = "Chave de acesso de 44 dígitos se visível, senão vazio"),
            "supplierName" to Schema.string(description = "Nome fantasia ou razão social do fornecedor/frigorífico"),
            "supplierCnpj" to Schema.string(description = "CNPJ do fornecedor formatado ou vazio"),
            "items" to Schema.array(
                description = "Lista de produtos cárneos/alimentos encontrados na nota fiscal",
                items = Schema.obj(
                    properties = mapOf(
                        "rawDescription" to Schema.string(description = "Descrição original do item na nota"),
                        "meatType" to Schema.enumeration(
                            values = listOf("BOVINA", "SUINA", "AVES", "OVINA", "PEIXES", "EMBUTIDOS_OUTROS"),
                            description = "Categoria da carne"
                        ),
                        "cutName" to Schema.string(description = "Nome limpo do corte em português, ex: Picanha, Contrafilé, Peito de Frango"),
                        "temperatureZone" to Schema.enumeration(
                            values = listOf("RESFRIADO", "CONGELADO", "MATURACAO"),
                            description = "Conservação térmica indicada ou inferida"
                        ),
                        "quantity" to Schema.double(description = "Quantidade ou peso numérico do item"),
                        "unit" to Schema.enumeration(
                            values = listOf("KG", "PECA", "CAIXA"),
                            description = "Unidade de medida"
                        ),
                        "unitCostBrl" to Schema.double(description = "Valor unitário em reais (0.0 se não visível)"),
                        "batchCode" to Schema.string(description = "Código de lote se visível, senão vazio"),
                        "sifCode" to Schema.string(description = "Número do SIF se visível, senão vazio"),
                        "expiryDateIso" to Schema.string(description = "Data de validade no formato YYYY-MM-DD se visível na nota, senão vazio")
                    ),
                    optionalProperties = listOf("unitCostBrl", "batchCode", "sifCode", "expiryDateIso")
                )
            )
        ),
        optionalProperties = listOf("invoiceNumber", "accessKey", "supplierName", "supplierCnpj")
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = invoiceSchema
                temperature = 0.1f
            }
        )
    }

    suspend fun extractFromImageUri(context: Context, imageUri: Uri): Result<ParsedInvoiceDraft> {
        return try {
            val bitmap = withContext(Dispatchers.IO) {
                decodeAndScaleBitmap(context, imageUri, maxDimension = 1600)
            } ?: return Result.failure(IllegalArgumentException("Não foi possível abrir a imagem selecionada."))

            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        image(bitmap)
                        text(buildPrompt())
                    }
                )
            }
            val jsonText = response.text
                ?: return Result.failure(IllegalStateException("A IA não retornou conteúdo para esta nota."))
            Result.success(parseJsonToDraft(jsonText, InvoiceSourceType.IA_FOTO))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI image extraction timed out", e)
            Result.failure(IllegalStateException("Tempo limite excedido (60s) ao ler a foto da nota. Verifique sua conexão e tente novamente."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI image extraction failed", e)
            Result.failure(IllegalStateException("Falha na leitura por IA: ${e.localizedMessage ?: "erro de rede ou configuração"}"))
        }
    }

    suspend fun extractFromPdfUri(context: Context, pdfUri: Uri): Result<ParsedInvoiceDraft> {
        return try {
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

            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        inlineData(pdfBytes, "application/pdf")
                        text(buildPrompt())
                    }
                )
            }
            val jsonText = response.text
                ?: return Result.failure(IllegalStateException("A IA não retornou conteúdo para este PDF."))
            Result.success(parseJsonToDraft(jsonText, InvoiceSourceType.IA_PDF))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI PDF extraction timed out", e)
            Result.failure(IllegalStateException("Tempo limite excedido (60s) ao ler o PDF da nota. Tente novamente."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI PDF extraction failed", e)
            Result.failure(IllegalStateException("Falha ao ler PDF com IA: ${e.localizedMessage ?: "erro de conexão"}"))
        }
    }

    private fun buildPrompt(): String {
        return """
            Você é um especialista em conferência de recebimento de açougue e casa de carnes no Brasil.
            Extraia os dados da nota fiscal / DANFE / romaneio de carnes na imagem ou documento anexo.
            Para cada produto:
            - Identifique o tipo de carne (BOVINA, SUINA, AVES, OVINA, PEIXES ou EMBUTIDOS_OUTROS).
            - Limpe o nome do corte (ex: "Picanha", "Contrafilé", "Costela", "Coxa e Sobrecoxa").
            - Identifique se é RESFRIADO, CONGELADO ou MATURACAO pela descrição.
            - Extraia quantidade/peso numérico, unidade (KG, PECA ou CAIXA), valor unitário em reais, lote, SIF e data de validade (YYYY-MM-DD) caso apareça.
            Não invente itens que não estejam no documento.
        """.trimIndent()
    }

    private fun parseJsonToDraft(jsonString: String, sourceType: InvoiceSourceType): ParsedInvoiceDraft {
        val root = JSONObject(jsonString)
        val invoiceNumber = root.optString("invoiceNumber", "").trim()
        val accessKey = root.optString("accessKey", "").filter { it.isDigit() }.take(44)
        val supplierName = root.optString("supplierName", "").trim()
        val supplierCnpj = root.optString("supplierCnpj", "").trim()

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
                        measuredTempCelsius = classified.temperatureZone.defaultTempCelsius,
                        chamberName = classified.temperatureZone.defaultChamber,
                        quantity = qty,
                        unit = classified.unit,
                        unitCostBrl = cost,
                        batchCode = batch.ifBlank { classified.batchCode },
                        sifCode = sif.ifBlank { classified.sifCode },
                        expiryEpochDay = classified.expiryEpochDay
                    )
                )
            }
        }

        return ParsedInvoiceDraft(
            invoiceNumber = invoiceNumber,
            accessKey = accessKey,
            supplierName = supplierName,
            supplierCnpj = supplierCnpj,
            sourceType = sourceType,
            notes = "Extraído automaticamente via IA",
            items = parsedItems
        )
    }

    private fun decodeAndScaleBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val maxSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
        var sampleSize = 1
        while (maxSide / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }
}
