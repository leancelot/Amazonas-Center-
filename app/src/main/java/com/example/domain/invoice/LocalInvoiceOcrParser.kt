package com.example.domain.invoice

import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class OcrLineBox(
    val text: String,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
)

data class OcrPageInput(
    val lines: List<OcrLineBox>,
    val scannedBarcodes: List<String> = emptyList()
)

object LocalInvoiceOcrParser {

    fun parseRawText(
        rawText: String,
        sourceType: InvoiceSourceType = InvoiceSourceType.OCR_FOTO,
        scannedBarcodes: List<String> = emptyList()
    ): Result<ParsedInvoiceDraft> {
        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapIndexed { idx, line ->
                OcrLineBox(
                    text = line,
                    left = 0f,
                    top = idx * 30f,
                    right = line.length * 10f,
                    bottom = (idx * 30f) + 22f
                )
            }
        return parsePages(listOf(OcrPageInput(lines = lines, scannedBarcodes = scannedBarcodes)), sourceType)
    }

    fun parsePages(
        pages: List<OcrPageInput>,
        sourceType: InvoiceSourceType
    ): Result<ParsedInvoiceDraft> {
        val reconstructedRows = pages.flatMap { reconstructRows(it.lines) }
        val allBarcodes = pages.flatMap { it.scannedBarcodes }.distinct()
        val rawOcrText = reconstructedRows.joinToString("\n").trim()

        if (rawOcrText.isBlank() && allBarcodes.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("Nenhum texto legível ou código DANFE encontrado na mídia selecionada.")
            )
        }

        // 1. Barcode / Access Key (44 digits) + QR fallback
        var accessKey = ""
        var invoiceNumber = ""
        var supplierCnpj = ""

        for (barcode in allBarcodes) {
            val qrDraft = DanfeQrParser.parseRawCode(barcode).getOrNull()
            if (qrDraft != null) {
                if (accessKey.isBlank() && qrDraft.accessKey.length == 44) accessKey = qrDraft.accessKey
                if (invoiceNumber.isBlank() && qrDraft.invoiceNumber.isNotBlank()) invoiceNumber = qrDraft.invoiceNumber
                if (supplierCnpj.isBlank() && qrDraft.supplierCnpj.isNotBlank()) supplierCnpj = qrDraft.supplierCnpj
            }
        }

        if (accessKey.isBlank()) {
            accessKey = extract44DigitKeyFromRows(reconstructedRows)
        }
        if (accessKey.length == 44) {
            if (invoiceNumber.isBlank()) {
                invoiceNumber = accessKey.substring(25, 34).trimStart('0').ifBlank { "0" }
            }
            if (supplierCnpj.isBlank()) {
                supplierCnpj = formatCnpj(accessKey.substring(6, 20))
            }
        }

        // 2. Explicit Invoice Number, Issuer CNPJ & Supplier Name
        val explicitNf = extractInvoiceNumber(reconstructedRows)
        if (explicitNf.isNotBlank()) invoiceNumber = explicitNf

        val explicitCnpj = extractIssuerCnpj(reconstructedRows)
        if (explicitCnpj.isNotBlank()) supplierCnpj = explicitCnpj

        val supplierName = extractSupplierName(reconstructedRows)

        // 3. Annotations, Additional Info & Arrival Temperature
        val (extractedNotes, extractedTempCelsius) = extractAnnotationsAndTemp(reconstructedRows)

        // 4. Product Items
        val items = extractItems(reconstructedRows)

        if (items.isEmpty() && invoiceNumber.isBlank() && accessKey.isBlank() &&
            supplierName.isBlank() && extractedNotes.isBlank()
        ) {
            return Result.failure(
                IllegalArgumentException("Não foi possível identificar itens ou dados da nota no texto capturado.")
            )
        }

        val finalNotes = buildString {
            append(
                if (sourceType == InvoiceSourceType.OCR_PDF) {
                    "Leitura local offline (PDF OCR)"
                } else {
                    "Leitura local offline (Foto OCR)"
                }
            )
            if (extractedNotes.isNotBlank()) {
                append(" • ")
                append(extractedNotes)
            }
        }

        return Result.success(
            ParsedInvoiceDraft(
                invoiceNumber = invoiceNumber,
                accessKey = accessKey,
                supplierName = supplierName,
                supplierCnpj = supplierCnpj,
                sourceType = sourceType,
                notes = finalNotes,
                rawOcrText = rawOcrText,
                extractedTempCelsius = extractedTempCelsius,
                items = items
            )
        )
    }

    internal fun reconstructRows(lines: List<OcrLineBox>): List<String> {
        val cleanLines = lines.filter { it.text.isNotBlank() }
        if (cleanLines.isEmpty()) return emptyList()

        val hasGeometry = cleanLines.any { it.bottom > it.top }
        if (!hasGeometry) {
            return cleanLines.map { it.text.replace(Regex("""\s+"""), " ").trim() }
        }

        val heights = cleanLines.mapNotNull { box ->
            val h = box.bottom - box.top
            if (h > 1f) h else null
        }.sorted()
        val medianHeight = if (heights.isNotEmpty()) heights[heights.size / 2] else 20f
        val tolerance = max(medianHeight * 0.58f, 12f)

        val sorted = cleanLines.sortedWith(compareBy<OcrLineBox> { (it.top + it.bottom) / 2f }.thenBy { it.left })
        val rows = mutableListOf<MutableList<OcrLineBox>>()
        var currentRowMidY = 0f

        for (box in sorted) {
            val midY = (box.top + box.bottom) / 2f
            if (rows.isEmpty()) {
                rows.add(mutableListOf(box))
                currentRowMidY = midY
            } else if (abs(midY - currentRowMidY) <= tolerance) {
                val row = rows.last()
                row.add(box)
                currentRowMidY = row.map { (it.top + it.bottom) / 2f }.average().toFloat()
            } else {
                rows.add(mutableListOf(box))
                currentRowMidY = midY
            }
        }

        return rows.map { row ->
            row.sortedBy { it.left }
                .joinToString("  ") { it.text.replace(Regex("""\s+"""), " ").trim() }
                .trim()
        }.filter { it.isNotEmpty() }
    }

    private fun extract44DigitKeyFromRows(rows: List<String>): String {
        val exactRegex = Regex("""(?<!\d)(\d{44})(?!\d)""")
        for (row in rows) {
            val digitsOnly = row.replace(Regex("""[\s.\-/]"""), "")
            val match = exactRegex.find(digitsOnly)?.groupValues?.getOrNull(1)
            if (match != null) return match
        }
        val combinedDigits = rows.joinToString(" ")
            .replace(Regex("""[\s.\-/]"""), "")
        return exactRegex.find(combinedDigits)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractInvoiceNumber(rows: List<String>): String {
        val patterns = listOf(
            Regex("""\bNF[-\s]*E?\s*(?:N[º°O\.]?)?\s*[:\-]?\s*0*([1-9]\d{1,8})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bN[º°]\s*[:\-]?\s*0*([1-9][\d.]{1,10})\b""", RegexOption.IGNORE_CASE),
            Regex("""\bNOTA\s+(?:FISCAL)?\s*(?:N[º°O\.]?)?\s*[:\-]?\s*0*([1-9]\d{1,8})\b""", RegexOption.IGNORE_CASE)
        )
        for (row in rows.take(28)) {
            if (row.contains("TELEFONE", ignoreCase = true) || row.contains("CEP", ignoreCase = true)) continue
            for (pattern in patterns) {
                val raw = pattern.find(row)?.groupValues?.getOrNull(1).orEmpty()
                val digits = raw.filter { it.isDigit() }.trimStart('0')
                if (digits.length in 2..9) return digits
            }
        }
        return ""
    }

    private fun extractIssuerCnpj(rows: List<String>): String {
        val cnpjRegex = Regex("""\b(\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2})\b""")
        for (row in rows) {
            val norm = MeatCatalogMatcher.normalizeText(row)
            if (norm.contains("DESTINATARIO") || norm.contains("REMETENTE")) break
            val match = cnpjRegex.find(row)?.groupValues?.getOrNull(1)
            if (match != null) {
                val digits = match.filter { it.isDigit() }
                if (digits.length == 14) return formatCnpj(digits)
            }
        }
        for (row in rows) {
            val match = cnpjRegex.find(row)?.groupValues?.getOrNull(1)
            if (match != null) {
                val digits = match.filter { it.isDigit() }
                if (digits.length == 14) return formatCnpj(digits)
            }
        }
        return ""
    }

    private fun extractSupplierName(rows: List<String>): String {
        val companyHints = listOf(
            "FRIGOR", "AGRO", "CARNES", "ALIMENTOS", "ACOUGUE", "DISTRIBUIDORA",
            "COMERCIO", "ABATEDOURO", "PECUARIA", "LTDA", "S/A", "S.A.", "EIRELI"
        )
        val ignoredHeaders = listOf(
            "DANFE", "DOCUMENTO AUXILIAR", "CHAVE DE ACESSO", "NOTA FISCAL",
            "PROTOCOLO DE AUTORIZACAO", "NATUREZA DA OPERACAO", "DESTINATARIO",
            "INSCRICAO ESTADUAL", "CALCULO DO IMPOSTO", "DADOS DOS PRODUTOS",
            "RECEBEMOS", "PRODUTOS CONSTANTES", "INDICADA AO LADO", "IDENTIFICACAO", "ASSINATURA"
        )

        val topRows = rows.take(25)
        for (row in topRows) {
            val cleaned = cleanSupplierCandidate(row)
            val norm = MeatCatalogMatcher.normalizeText(cleaned)
            if (cleaned.length < 4) continue
            if (ignoredHeaders.any { norm.contains(it) }) continue
            if (companyHints.any { norm.contains(it) }) {
                return cleaned.take(64)
            }
        }

        // Explicit label "FORNECEDOR:" or "EMITENTE:"
        for (row in rows) {
            val labeled = Regex("""(?:FORNECEDOR|EMITENTE|FRIGOR[IÍ]FICO)\s*[:\-]\s*(.+)""", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.getOrNull(1)?.trim()
            if (!labeled.isNullOrBlank() && labeled.length >= 3) {
                val cleaned = cleanSupplierCandidate(labeled)
                if (cleaned.isNotBlank() && !cleaned.contains("RECEBEMOS", ignoreCase = true)) {
                    return cleaned.take(64)
                }
            }
        }

        // Fallback to first non-ignored top row with company hint or LTDA
        for (row in topRows) {
            val cleaned = cleanSupplierCandidate(row)
            val norm = MeatCatalogMatcher.normalizeText(cleaned)
            if (cleaned.length >= 4 && !ignoredHeaders.any { norm.contains(it) } && !norm.contains("RECEBEMOS")) {
                if (companyHints.any { norm.contains(it) } || cleaned.contains("LTDA", ignoreCase = true)) {
                    return cleaned.take(64)
                }
            }
        }
        return ""
    }

    private fun cleanSupplierCandidate(row: String): String {
        val norm = MeatCatalogMatcher.normalizeText(row)
        if (norm.contains("RECEBEMOS") || norm.contains("PRODUTOS CONSTANTES") || norm.contains("NOTA FISCAL") || norm.contains("INDICADA AO LADO")) {
            return ""
        }
        return row
            .replace(Regex("""^(?:FORNECEDOR|EMITENTE|EMITENTE\s*/\s*REMETENTE|RECEBEMOS\s+DE)\s*[:\-]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bCNPJ[:\s]*\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:TEL|FONE|CEP|IE|INSC\.?\s*EST\.?)[:\s].*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ':', '|', '•', '"', '\'')
    }

    private fun extractAnnotationsAndTemp(rows: List<String>): Pair<String, Double?> {
        val noteLines = mutableListOf<String>()
        var inAdditionalSection = false

        val explicitAnnotationPrefixes = listOf(
            "OBS:", "OBS.", "OBSERVAC", "ANOTAC", "CONFERENC", "NOTA:", "AVARIA",
            "DEVOLUC", "TEMPERATURA", "TEMP:", "TEMP.", "INF. COMPLEMENTAR", "INFORMACOES COMPLEMENTARES"
        )

        for (row in rows) {
            val norm = MeatCatalogMatcher.normalizeText(row)
            if (norm.contains("DADOS ADICIONAIS") || norm.contains("INFORMACOES COMPLEMENTARES")) {
                inAdditionalSection = true
                val afterColon = row.substringAfter(":", "").trim()
                if (afterColon.length >= 4 && !norm.endsWith("DADOS ADICIONAIS")) {
                    noteLines.add(afterColon)
                }
                continue
            }
            if (inAdditionalSection) {
                if (norm.contains("RESERVADO AO FISCO") || norm.startsWith("DADOS DO ") || norm.startsWith("CALCULO DO ")) {
                    inAdditionalSection = false
                } else if (!norm.startsWith("INF. COMPLEMENTARES") && !norm.startsWith("CONTROLE DO FISCO") && row.length >= 3) {
                    noteLines.add(row)
                }
                continue
            }

            if (explicitAnnotationPrefixes.any { norm.contains(it) }) {
                noteLines.add(row)
            }
        }

        val extractedTemp = extractTemperatureFromRows(noteLines.ifEmpty { rows })
        val compactNotes = noteLines
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length >= 3 }
            .distinct()
            .joinToString(" | ")
            .take(360)

        return compactNotes to extractedTemp
    }

    private fun extractTemperatureFromRows(rows: List<String>): Double? {
        val labeledTempRegex = Regex(
            """(?:TEMP(?:ERATURA)?(?:\s*(?:CHEGADA|AFERIDA|CARGA|CÂMARA|CAMARA))?\s*[:=\-]?\s*)(-?\s*\d{1,2}(?:[.,]\d{1,2})?)\s*°?\s*C?\b""",
            RegexOption.IGNORE_CASE
        )
        val unitTempRegex = Regex("""(-?\s*\d{1,2}(?:[.,]\d{1,2})?)\s*°\s*C\b""", RegexOption.IGNORE_CASE)

        for (row in rows) {
            val m1 = labeledTempRegex.find(row)?.groupValues?.getOrNull(1)
            val candidate1 = m1?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull()
            if (candidate1 != null && candidate1 in -40.0..30.0) return candidate1

            val m2 = unitTempRegex.find(row)?.groupValues?.getOrNull(1)
            val candidate2 = m2?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull()
            if (candidate2 != null && candidate2 in -40.0..30.0) return candidate2
        }
        return null
    }

    private fun extractItems(rows: List<String>): List<ParsedInvoiceItemDraft> {
        val items = mutableListOf<ParsedInvoiceItemDraft>()
        val ignoredRowKeywords = listOf(
            "VALOR TOTAL", "TOTAL DA NOTA", "BASE DE CALCULO", "VALOR DO ICMS",
            "VALOR DO FRETE", "DESCONTO", "DADOS DOS PRODUTOS", "CODIGO PRODUTO",
            "DESCRICAO DO PRODUTO", "RAZAO SOCIAL", "CHAVE DE ACESSO",
            "PROTOCOLO DE AUTORIZACAO", "ENDERECO", "MUNICIPIO", "TRANSPORTADOR",
            "DADOS ADICIONAIS", "INFORMACOES COMPLEMENTARES", "RESERVADO AO FISCO",
            "INSCRICAO ESTADUAL", "NATUREZA DA OPERACAO", "PESO BRUTO", "PESO LIQUIDO",
            "CFOP", "CST", "NCM", "ICMS", "PIS", "COFINS", "IPI", "ALIQ", "VALOR"
        )

        val unitQtyRegex = Regex(
            """(\d{1,4}(?:[.,]\d{1,3})?)\s*(KG|KGS|QUILO|CX|CXS|CAIXA|PC|PÇ|PECA|UN|UND|UNID)\b""",
            RegexOption.IGNORE_CASE
        )
        val qtyAfterUnitRegex = Regex(
            """\b(KG|KGS|QUILO|CX|CXS|CAIXA|PC|PÇ|PECA|UN|UND|UNID)\s+(\d{1,4}(?:[.,]\d{1,3})?)\b""",
            RegexOption.IGNORE_CASE
        )

        var inProductsSection = false

        for (rawRow in rows) {
            val norm = MeatCatalogMatcher.normalizeText(rawRow)
            if (norm.contains("DADOS DOS PRODUTOS")) {
                inProductsSection = true
                continue
            }
            if (inProductsSection && (norm.contains("CALCULO DO ISSQN") || norm.contains("DADOS ADICIONAIS") || norm.contains("INFORMACOES COMPLEMENTARES"))) {
                inProductsSection = false
            }

            if (ignoredRowKeywords.any { norm.contains(it) }) continue
            if (norm.contains("CFOP") || norm.contains("NCM")) continue

            // Check if this is a metadata sub-line for the previous item (LOTE / SIF / VAL / OBS)
            if (items.isNotEmpty() && isMetadataContinuationRow(rawRow, norm, unitQtyRegex, qtyAfterUnitRegex)) {
                items[items.lastIndex] = enrichItemFromSubLine(items.last(), rawRow)
                continue
            }

            val parsedItem = parseSingleProductRow(rawRow, norm, unitQtyRegex, qtyAfterUnitRegex)
            if (parsedItem != null) {
                items.add(parsedItem)
            }
        }

        return items
    }

    private fun isMetadataContinuationRow(
        rawRow: String,
        norm: String,
        unitQtyRegex: Regex,
        qtyAfterUnitRegex: Regex
    ): Boolean {
        val hasMetaMarker = norm.contains("LOTE") || norm.contains("SIF") ||
            norm.contains("VAL") || norm.contains("FAB") || norm.startsWith("OBS")
        if (!hasMetaMarker) return false
        val hasUnitQty = unitQtyRegex.containsMatchIn(rawRow) || qtyAfterUnitRegex.containsMatchIn(rawRow)
        return !hasUnitQty
    }

    private fun enrichItemFromSubLine(item: ParsedInvoiceItemDraft, subLine: String): ParsedInvoiceItemDraft {
        val sif = extractSif(subLine).ifBlank { item.sifCode }
        val batch = extractBatch(subLine).ifBlank { item.batchCode }
        val expiry = extractExpiryEpochDay(subLine) ?: item.expiryEpochDay
        val temp = extractTemperatureFromRows(listOf(subLine)) ?: item.measuredTempCelsius
        val mergedNotes = listOf(item.notes, subLine.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" • ")
        return item.copy(
            sifCode = sif,
            batchCode = batch,
            expiryEpochDay = expiry,
            measuredTempCelsius = temp,
            notes = mergedNotes
        )
    }

    private fun parseSingleProductRow(
        rawRow: String,
        norm: String,
        unitQtyRegex: Regex,
        qtyAfterUnitRegex: Regex
    ): ParsedInvoiceItemDraft? {
        val matchBefore = unitQtyRegex.find(rawRow)
        val matchAfter = if (matchBefore == null) qtyAfterUnitRegex.find(rawRow) else null

        val qtyStr: String
        val unitStr: String
        val qtyMatchRange: IntRange

        if (matchBefore != null) {
            qtyStr = matchBefore.groupValues[1]
            unitStr = matchBefore.groupValues[2]
            qtyMatchRange = matchBefore.range
        } else if (matchAfter != null) {
            unitStr = matchAfter.groupValues[1]
            qtyStr = matchAfter.groupValues[2]
            qtyMatchRange = matchAfter.range
        } else {
            return null
        }

        val quantity = parseBrazilianNumber(qtyStr) ?: return null
        if (quantity <= 0.0 || quantity > 50_000.0) return null

        val prefixText = rawRow.substring(0, qtyMatchRange.first).trim()
        val suffixText = rawRow.substring(qtyMatchRange.last + 1).trim()

        val cleanedDesc = cleanProductDescription(prefixText.ifBlank { suffixText })
        if (cleanedDesc.length < 3 || cleanedDesc.all { it.isDigit() || !it.isLetter() }) return null
        if (cleanedDesc.matches(Regex("""^[1-7]\d{3}$""")) || cleanedDesc.matches(Regex("""^\d{8}$"""))) return null

        // Avoid matching tax/weight summary lines like "120,00 KG PESO LIQUIDO"
        val descNorm = MeatCatalogMatcher.normalizeText(cleanedDesc)
        if (descNorm.contains("PESO") || descNorm.contains("VOLUME") || descNorm.contains("ESPECIE") ||
            descNorm.contains("FRETE") || descNorm.contains("TOTAL") || descNorm.contains("CFOP") || descNorm.contains("NCM")
        ) {
            return null
        }

        // Require at least either a meat/cut hint OR a price column in the suffix
        val trailingPrices = extractPricesFromSuffix(suffixText)
        val unitCostBrl = resolveUnitPrice(trailingPrices, quantity)
        val looksLikeMeat = containsMeatHint(norm)
        if (!looksLikeMeat && trailingPrices.isEmpty()) {
            return null
        }

        val explicitExpiry = extractExpiryEpochDay(rawRow)
        val classified = MeatCatalogMatcher.classifyDescription(
            rawDescription = rawRow,
            rawUnit = unitStr,
            explicitCutName = if (looksLikeMeat) null else cleanedDesc,
            explicitExpiryEpochDay = explicitExpiry
        )

        val sif = extractSif(rawRow).ifBlank { classified.sifCode }
        val batch = extractBatch(rawRow).ifBlank { classified.batchCode }
        val inlineTemp = extractTemperatureFromRows(listOf(rawRow))
            ?: classified.temperatureZone.defaultTempCelsius

        val itemNotes = extractInlineItemAnnotation(rawRow)

        return ParsedInvoiceItemDraft(
            rawDescription = cleanedDesc,
            meatType = classified.meatType,
            cutName = classified.cutName,
            temperatureZone = classified.temperatureZone,
            measuredTempCelsius = inlineTemp,
            chamberName = classified.temperatureZone.defaultChamber,
            quantity = quantity,
            unit = classified.unit,
            unitCostBrl = unitCostBrl,
            batchCode = batch,
            sifCode = sif,
            expiryEpochDay = classified.expiryEpochDay,
            notes = itemNotes
        )
    }

    private fun cleanProductDescription(raw: String): String {
        return raw
            .replace(Regex("""^\d{1,14}\s+"""), "") // leading product SKU
            .replace(Regex("""\b\d{8}\b"""), "") // NCM 8-digit code
            .replace(Regex("""\b[1-7]\.?\d{3}\b"""), "") // CFOP 4 digits
            .replace(Regex("""\b(?:CFOP|CST|NCM|UN|KG)[:\s-]*[A-Z0-9./-]*\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ':', '.', ',')
    }

    private fun extractPricesFromSuffix(suffix: String): List<Double> {
        if (suffix.isBlank()) return emptyList()
        val cleaned = suffix
            .replace(Regex("""\b(?:LOTE|SIF|VAL(?:IDADE)?)[:\s-]*[A-Z0-9./-]+\b""", RegexOption.IGNORE_CASE), "")
        return Regex("""\b(\d{1,5}(?:\.\d{3})*,\d{2}|\d{1,5}\.\d{2})\b""")
            .findAll(cleaned)
            .mapNotNull { parseBrazilianNumber(it.groupValues[1]) }
            .filter { it in 0.01..50_000.0 }
            .toList()
    }

    private fun resolveUnitPrice(prices: List<Double>, quantity: Double): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size == 1) return prices[0]
        val first = prices[0]
        val second = prices[1]
        // If first * quantity ≈ second, first is clearly unit price
        if (quantity > 0.0 && abs((first * quantity) - second) <= max(1.0, second * 0.08)) {
            return first
        }
        return first
    }

    private fun extractSif(text: String): String {
        return Regex("""\bSIF[:\s-]*([0-9]{2,6})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractBatch(text: String): String {
        return Regex("""\bLOTE[:\s-]*([A-Z0-9/-]{2,20})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractExpiryEpochDay(text: String): Long? {
        val labeledBr = Regex("""\b(?:VAL(?:IDADE)?|VENC(?:IMENTO)?)[:\s-]*(\d{2}/\d{2}/\d{2,4})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        if (labeledBr != null) {
            return parseFlexibleBrDate(labeledBr)
        }
        val plainBr = Regex("""\b(\d{2}/\d{2}/\d{4})\b""").find(text)?.groupValues?.getOrNull(1)
        if (plainBr != null) {
            return DateUtils.parseIsoDateToEpochDay(plainBr)
        }
        val iso = Regex("""\b(\d{4}-\d{2}-\d{2})\b""").find(text)?.groupValues?.getOrNull(1)
        return DateUtils.parseIsoDateToEpochDay(iso)
    }

    private fun parseFlexibleBrDate(dateStr: String): Long? {
        val parts = dateStr.split("/")
        if (parts.size != 3) return null
        val expanded = if (parts[2].length == 2) "${parts[0]}/${parts[1]}/20${parts[2]}" else dateStr
        return DateUtils.parseIsoDateToEpochDay(expanded)
    }

    private fun extractInlineItemAnnotation(rawRow: String): String {
        val obsMatch = Regex("""\b(?:OBS|NOTA)[:\s-]+(.+)$""", RegexOption.IGNORE_CASE)
            .find(rawRow)?.groupValues?.getOrNull(1)?.trim()
        return obsMatch.orEmpty().take(120)
    }

    private fun containsMeatHint(norm: String): Boolean {
        val keywords = listOf(
            "CARNE", "BOV", "SUIN", "FRANGO", "AVE", "PEIXE", "CORDEIRO", "OVIN",
            "PICANHA", "ALCATRA", "CONTRA", "MIGNON", "COSTELA", "CUPIM", "PATINHO",
            "COXAO", "MAMINHA", "FRALDINHA", "ACEM", "PALETA", "MUSCULO", "ANCHO",
            "CHORIZO", "PERNIL", "BISTECA", "LOMBO", "PANCETA", "PEITO", "COXA",
            "SOBRECOXA", "ASA", "TULIPA", "SASSAMI", "TILAPIA", "SALMAO", "BACALHAU",
            "CAMARAO", "LINGUICA", "CALABRESA", "BACON", "CHARQUE", "HAMBURGUER",
            "RESF", "CONG", "MATUR", "SIF"
        )
        return keywords.any { norm.contains(it) }
    }

    internal fun parseBrazilianNumber(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val normalized = when {
            trimmed.contains(',') && trimmed.contains('.') -> {
                if (trimmed.lastIndexOf(',') > trimmed.lastIndexOf('.')) {
                    trimmed.replace(".", "").replace(',', '.')
                } else {
                    trimmed.replace(",", "")
                }
            }
            trimmed.contains(',') -> trimmed.replace(',', '.')
            else -> trimmed
        }
        return normalized.toDoubleOrNull()
    }

    private fun formatCnpj(digits: String): String {
        if (digits.length != 14) return digits
        return String.format(
            Locale.ROOT,
            "%s.%s.%s/%s-%s",
            digits.substring(0, 2),
            digits.substring(2, 5),
            digits.substring(5, 8),
            digits.substring(8, 12),
            digits.substring(12, 14)
        )
    }
}
