package com.example.domain.invoice

import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.TemperatureZone
import java.util.UUID

data class ParsedInvoiceDraft(
    val invoiceNumber: String = "",
    val accessKey: String = "",
    val supplierName: String = "",
    val supplierCnpj: String = "",
    val sourceType: InvoiceSourceType = InvoiceSourceType.MANUAL,
    val notes: String = "",
    val items: List<ParsedInvoiceItemDraft> = emptyList()
)

data class ParsedInvoiceItemDraft(
    val localId: String = UUID.randomUUID().toString(),
    val rawDescription: String = "",
    val meatType: MeatType = MeatType.BOVINA,
    val cutName: String = "",
    val temperatureZone: TemperatureZone = TemperatureZone.RESFRIADO,
    val measuredTempCelsius: Double = TemperatureZone.RESFRIADO.defaultTempCelsius,
    val chamberName: String = TemperatureZone.RESFRIADO.defaultChamber,
    val quantity: Double = 0.0,
    val unit: MeasurementUnit = MeasurementUnit.KG,
    val unitCostBrl: Double = 0.0,
    val batchCode: String = "",
    val sifCode: String = "",
    val expiryEpochDay: Long = DateUtils.addDaysToTodayEpochDay(MeatType.BOVINA.defaultChilledDays),
    val notes: String = ""
)

object MeatCatalogMatcher {

    fun classifyDescription(
        rawDescription: String,
        rawUnit: String? = null,
        explicitMeatType: String? = null,
        explicitCutName: String? = null,
        explicitZone: String? = null,
        explicitExpiryEpochDay: Long? = null
    ): ClassificationResult {
        val normalized = normalizeText(rawDescription)

        val zone = if (!explicitZone.isNullOrBlank()) {
            TemperatureZone.fromRaw(explicitZone)
        } else {
            when {
                normalized.contains("CONG") || normalized.contains("IQF") || normalized.contains("FREEZ") ->
                    TemperatureZone.CONGELADO
                normalized.contains("MATUR") || normalized.contains("DRY AGED") ->
                    TemperatureZone.MATURACAO
                else -> TemperatureZone.RESFRIADO
            }
        }

        val meatType = if (!explicitMeatType.isNullOrBlank()) {
            MeatType.fromRaw(explicitMeatType)
        } else {
            inferMeatType(normalized)
        }

        val cutName = if (!explicitCutName.isNullOrBlank()) {
            explicitCutName.trim()
        } else {
            inferCutName(normalized, meatType, rawDescription)
        }

        val unit = MeasurementUnit.fromRaw(rawUnit)

        val defaultDays = if (zone == TemperatureZone.CONGELADO) {
            meatType.defaultFrozenDays
        } else {
            meatType.defaultChilledDays
        }
        val expiryEpochDay = explicitExpiryEpochDay ?: DateUtils.addDaysToTodayEpochDay(defaultDays)

        val sifMatch = Regex("""\bSIF[:\s-]*([0-9]{2,6})\b""", RegexOption.IGNORE_CASE)
            .find(rawDescription)?.groupValues?.getOrNull(1).orEmpty()

        val loteMatch = Regex("""\bLOTE[:\s-]*([A-Z0-9/-]{2,20})\b""", RegexOption.IGNORE_CASE)
            .find(rawDescription)?.groupValues?.getOrNull(1).orEmpty()

        return ClassificationResult(
            meatType = meatType,
            cutName = cutName,
            temperatureZone = zone,
            unit = unit,
            expiryEpochDay = expiryEpochDay,
            sifCode = sifMatch,
            batchCode = loteMatch
        )
    }

    private fun inferMeatType(norm: String): MeatType {
        return when {
            norm.containsAny("FRANGO", "AVE", "PEITO", "COXA", "SOBRECOXA", "SASSAMI", "TULIPA", "GALINHA", "PERU") ->
                MeatType.AVES
            norm.containsAny("SUIN", "PERNIL", "BISTECA", "COSTELINHA", "LOMBO", "PANCETA", "COPA LOMBO", "PORCO", "CARRE SUINO") ->
                MeatType.SUINA
            norm.containsAny("CORDEIRO", "OVIN", "CAPRIN", "CABRITO", "CARNEIRO") ->
                MeatType.OVINA
            norm.containsAny("PEIXE", "TILAPIA", "SALMAO", "BACALHAU", "CAMARAO", "MERLUZA", "ATUM", "PESCADA") ->
                MeatType.PEIXES
            norm.containsAny("LINGUICA", "CALABRESA", "BACON", "CHARQUE", "CARNE SECA", "SALSICHA", "HAMBURGUER", "PRESUNTO", "TOSCANA") ->
                MeatType.EMBUTIDOS_OUTROS
            else -> MeatType.BOVINA
        }
    }

    private fun inferCutName(norm: String, meatType: MeatType, fallbackRaw: String): String {
        val allPresets = meatType.presetCuts + MeatType.entries.flatMap { it.presetCuts }
        for (preset in allPresets) {
            val presetNorm = normalizeText(preset)
            if (norm.contains(presetNorm)) {
                return preset
            }
        }
        // Check common abbreviations on Brazilian NF-e
        val abbreviationMap = mapOf(
            "CONTRA FILE" to "Contrafilé",
            "CONTRA-FILE" to "Contrafilé",
            "C. FILE" to "Contrafilé",
            "C FILE" to "Contrafilé",
            "FILE MIGNON" to "Filé Mignon",
            "COXAO MOLE" to "Coxão Mole",
            "COXAO DURO" to "Coxão Duro",
            "CHÃ DE DENTRO" to "Coxão Mole",
            "CHA DE DENTRO" to "Coxão Mole",
            "CHA DE FORA" to "Coxão Duro",
            "COSTELA MINGA" to "Costela",
            "COSTELA PONTA" to "Costela",
            "PONTA DE AGULHA" to "Costela",
            "MIOLO ALCATRA" to "Alcatra",
            "BIFE ANCHO" to "Ancho",
            "BIFE CHORIZO" to "Chorizo",
            "PEITO C/ OSSO" to "Peito de Frango",
            "PEITO S/ OSSO" to "Filé de Peito",
            "MEIO DA ASA" to "Meio da Asa (Tulipa)"
        )
        for ((key, value) in abbreviationMap) {
            if (norm.contains(key)) return value
        }
        return fallbackRaw.trim()
            .replace(Regex("""\s+"""), " ")
            .take(40)
            .ifBlank { "Corte Diverso" }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }

    private fun normalizeText(input: String): String {
        val upper = input.uppercase()
        return upper
            .replace("Á", "A").replace("À", "A").replace("Ã", "A").replace("Â", "A")
            .replace("É", "E").replace("Ê", "E")
            .replace("Í", "I")
            .replace("Ó", "O").replace("Ô", "O").replace("Õ", "O")
            .replace("Ú", "U")
            .replace("Ç", "C")
    }

    data class ClassificationResult(
        val meatType: MeatType,
        val cutName: String,
        val temperatureZone: TemperatureZone,
        val unit: MeasurementUnit,
        val expiryEpochDay: Long,
        val sifCode: String,
        val batchCode: String
    )
}
