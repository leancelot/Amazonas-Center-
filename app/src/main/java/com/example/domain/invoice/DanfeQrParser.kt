package com.example.domain.invoice

import com.example.data.InvoiceSourceType

object DanfeQrParser {

    fun parseRawCode(rawValue: String): Result<ParsedInvoiceDraft> {
        val trimmed = rawValue.trim()
        val keyMatch = Regex("""\d{44}""").find(trimmed)?.value

        if (keyMatch != null && keyMatch.length == 44) {
            val cnpjRaw = keyMatch.substring(6, 20)
            val model = keyMatch.substring(20, 22)
            val series = keyMatch.substring(22, 25).trimStart('0').ifBlank { "0" }
            val nfNumber = keyMatch.substring(25, 34).trimStart('0').ifBlank { "0" }
            val yearMonth = "${keyMatch.substring(4, 6)}/20${keyMatch.substring(2, 4)}"

            val formattedCnpj = "${cnpjRaw.substring(0, 2)}.${cnpjRaw.substring(2, 5)}." +
                "${cnpjRaw.substring(5, 8)}/${cnpjRaw.substring(8, 12)}-${cnpjRaw.substring(12, 14)}"

            val docLabel = if (model == "65") "NFC-e" else "NF-e"

            return Result.success(
                ParsedInvoiceDraft(
                    invoiceNumber = nfNumber,
                    accessKey = keyMatch,
                    supplierName = "",
                    supplierCnpj = formattedCnpj,
                    sourceType = InvoiceSourceType.QR_DANFE,
                    notes = "$docLabel Série $series • Emissão $yearMonth • Chave lida via scanner",
                    items = emptyList()
                )
            )
        }

        // Fallback if barcode is an internal product/GS1-128 batch or EAN code
        val cleanDigits = trimmed.filter { it.isDigit() }
        if (cleanDigits.length in 8..20) {
            return Result.success(
                ParsedInvoiceDraft(
                    invoiceNumber = "",
                    accessKey = "",
                    supplierName = "",
                    supplierCnpj = "",
                    sourceType = InvoiceSourceType.QR_DANFE,
                    notes = "Código de barras lido: $cleanDigits",
                    items = emptyList()
                )
            )
        }

        return Result.failure(
            IllegalArgumentException("Código não reconhecido como chave NF-e/DANFE de 44 dígitos.")
        )
    }
}
