package com.example.domain.invoice

import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.InputStreamReader

object NfeXmlParser {

    fun parse(inputStream: InputStream): Result<ParsedInvoiceDraft> {
        return try {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val parser = factory.newPullParser()
            parser.setInput(InputStreamReader(inputStream, Charsets.UTF_8))

            var invoiceNumber = ""
            var accessKey = ""
            var supplierName = ""
            var supplierCnpj = ""
            val items = mutableListOf<ParsedInvoiceItemDraft>()

            val tagStack = ArrayDeque<String>()
            var inDet = false
            var inProd = false
            var inRastro = false
            var inEmit = false
            var inIde = false
            var inInfProt = false

            var currentDesc = ""
            var currentQty = 0.0
            var currentUnit = "KG"
            var currentUnitCost = 0.0
            var currentBatch = ""
            var currentExpiryEpochDay: Long? = null
            var currentInfAdProd = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.orEmpty()
                        tagStack.addLast(name)
                        when (name) {
                            "infNFe" -> {
                                val idAttr = parser.getAttributeValue(null, "Id").orEmpty()
                                if (idAttr.startsWith("NFe") && idAttr.length >= 47) {
                                    accessKey = idAttr.removePrefix("NFe").take(44)
                                }
                            }
                            "ide" -> inIde = true
                            "emit" -> inEmit = true
                            "det" -> {
                                inDet = true
                                currentDesc = ""
                                currentQty = 0.0
                                currentUnit = "KG"
                                currentUnitCost = 0.0
                                currentBatch = ""
                                currentExpiryEpochDay = null
                                currentInfAdProd = ""
                            }
                            "prod" -> if (inDet) inProd = true
                            "rastro" -> if (inProd) inRastro = true
                            "infProt" -> inInfProt = true
                        }
                    }

                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (tagStack.lastOrNull()) {
                                "nNF" -> if (inIde && invoiceNumber.isBlank()) invoiceNumber = text
                                "xNome" -> if (inEmit && supplierName.isBlank()) supplierName = text
                                "CNPJ" -> if (inEmit && supplierCnpj.isBlank()) supplierCnpj = text
                                "chNFe" -> if (inInfProt && accessKey.isBlank()) accessKey = text
                                "xProd" -> if (inProd) currentDesc = text
                                "qCom" -> if (inProd) currentQty = text.replace(',', '.').toDoubleOrNull() ?: currentQty
                                "uCom" -> if (inProd) currentUnit = text
                                "vUnCom" -> if (inProd) currentUnitCost = text.replace(',', '.').toDoubleOrNull() ?: currentUnitCost
                                "nLote" -> if (inRastro && currentBatch.isBlank()) currentBatch = text
                                "dVal" -> if (inRastro && currentExpiryEpochDay == null) {
                                    currentExpiryEpochDay = DateUtils.parseIsoDateToEpochDay(text)
                                }
                                "infAdProd" -> if (inDet) currentInfAdProd = text
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        val name = parser.name.orEmpty()
                        when (name) {
                            "ide" -> inIde = false
                            "emit" -> inEmit = false
                            "infProt" -> inInfProt = false
                            "rastro" -> inRastro = false
                            "prod" -> inProd = false
                            "det" -> {
                                if (inDet && currentDesc.isNotBlank()) {
                                    val combinedText = listOf(currentDesc, currentInfAdProd)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" ")
                                    val classified = MeatCatalogMatcher.classifyDescription(
                                        rawDescription = combinedText,
                                        rawUnit = currentUnit,
                                        explicitExpiryEpochDay = currentExpiryEpochDay
                                            ?: extractDateFromInfo(currentInfAdProd)
                                    )
                                    items.add(
                                        ParsedInvoiceItemDraft(
                                            rawDescription = currentDesc,
                                            meatType = classified.meatType,
                                            cutName = classified.cutName,
                                            temperatureZone = classified.temperatureZone,
                                            measuredTempCelsius = classified.temperatureZone.defaultTempCelsius,
                                            chamberName = classified.temperatureZone.defaultChamber,
                                            quantity = if (currentQty > 0.0) currentQty else 1.0,
                                            unit = classified.unit,
                                            unitCostBrl = currentUnitCost,
                                            batchCode = currentBatch.ifBlank { classified.batchCode },
                                            sifCode = classified.sifCode,
                                            expiryEpochDay = classified.expiryEpochDay,
                                            notes = currentInfAdProd
                                        )
                                    )
                                }
                                inDet = false
                            }
                        }
                        if (tagStack.isNotEmpty()) {
                            tagStack.removeLast()
                        }
                    }
                }
                eventType = parser.next()
            }

            if (items.isEmpty() && invoiceNumber.isBlank() && supplierName.isBlank()) {
                Result.failure(IllegalArgumentException("Arquivo XML não parece ser uma NF-e válida."))
            } else {
                Result.success(
                    ParsedInvoiceDraft(
                        invoiceNumber = invoiceNumber,
                        accessKey = accessKey,
                        supplierName = supplierName,
                        supplierCnpj = formatCnpj(supplierCnpj),
                        sourceType = InvoiceSourceType.XML_NFE,
                        notes = "Importado via XML NF-e",
                        items = items
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Erro ao ler XML da NF-e: ${e.localizedMessage ?: "formato inválido"}"))
        }
    }

    private fun extractDateFromInfo(info: String): Long? {
        if (info.isBlank()) return null
        val matchBr = Regex("""\b(\d{2}/\d{2}/\d{4})\b""").find(info)?.groupValues?.getOrNull(1)
        if (matchBr != null) return DateUtils.parseIsoDateToEpochDay(matchBr)
        val matchIso = Regex("""\b(\d{4}-\d{2}-\d{2})\b""").find(info)?.groupValues?.getOrNull(1)
        return DateUtils.parseIsoDateToEpochDay(matchIso)
    }

    private fun formatCnpj(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != 14) return raw
        return "${digits.substring(0, 2)}.${digits.substring(2, 5)}.${digits.substring(5, 8)}/" +
            "${digits.substring(8, 12)}-${digits.substring(12, 14)}"
    }
}
