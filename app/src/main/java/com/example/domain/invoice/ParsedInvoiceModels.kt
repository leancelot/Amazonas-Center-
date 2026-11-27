package com.example.domain.invoice

import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.PackUnitType
import com.example.data.TemperatureZone
import java.util.UUID

data class ParsedInvoiceDraft(
    val invoiceNumber: String = "",
    val accessKey: String = "",
    val supplierName: String = "",
    val supplierCnpj: String = "",
    val sourceType: InvoiceSourceType = InvoiceSourceType.MANUAL,
    val notes: String = "",
    val rawOcrText: String = "",
    val extractedTempCelsius: Double? = null,
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
    val notes: String = "",
    val packCount: Int = 0,
    val packUnit: PackUnitType = PackUnitType.CAIXA
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
        val scores = mutableMapOf<MeatType, Int>()
        MeatType.entries.forEach { scores[it] = 0 }

        val keywords = mapOf(
            MeatType.AVES to listOf("FRANGO", "AVE", "PEITO", "COXA", "SOBRECOXA", "SASSAMI", "TULIPA", "ASA", "GALINHA", "PERU", "FRG", "FRAN"),
            MeatType.SUINA to listOf("SUIN", "PORCO", "PERNIL", "BISTECA", "COSTELINHA", "LOMBO", "PANCETA", "COPA LOMBO", "CARRE", "SUINO"),
            MeatType.OVINA to listOf("CORDEIRO", "OVIN", "CAPRIN", "CABRITO", "CARNEIRO", "CORDEI"),
            MeatType.PEIXES to listOf("PEIXE", "TILAPIA", "SALMAO", "BACALHAU", "CAMARAO", "MERLUZA", "ATUM", "PESCADA", "POSTA"),
            MeatType.EMBUTIDOS_OUTROS to listOf("LINGUICA", "CALABRESA", "BACON", "CHARQUE", "CARNE SECA", "SALSICHA", "HAMBURGUER", "PRESUNTO", "TOSCANA", "LING", "ESPETA")
        )

        for ((meat, words) in keywords) {
            for (word in words) {
                if (norm.contains(word)) {
                    scores[meat] = (scores[meat] ?: 0) + word.length
                }
            }
        }

        val best = scores.maxByOrNull { it.value }
        return if (best != null && best.value > 0) best.key else MeatType.BOVINA
    }

    private fun inferCutName(norm: String, meatType: MeatType, fallbackRaw: String): String {
        val allPresets = meatType.presetCuts + MeatType.entries.flatMap { it.presetCuts }
        for (preset in allPresets) {
            val presetNorm = normalizeText(preset)
            if (norm.contains(presetNorm)) {
                return preset
            }
        }

        val synonymMap = mapOf(
            "ALC" to "Alcatra",
            "PIC" to "Picanha",
            "CONTRA" to "Contrafilé",
            "C FILE" to "Contrafilé",
            "F MIGNON" to "Filé Mignon",
            "COXAO MOLE" to "Coxão Mole",
            "COX MOLE" to "Coxão Mole",
            "CHA DENTRO" to "Coxão Mole",
            "COXAO DURO" to "Coxão Duro",
            "COX DURO" to "Coxão Duro",
            "CHA FORA" to "Coxão Duro",
            "COST" to "Costela",
            "ANCHO" to "Ancho",
            "CHORIZO" to "Chorizo",
            "PEITO" to "Peito de Frango",
            "FILE PEITO" to "Filé de Peito",
            "TULIPA" to "Meio da Asa (Tulipa)",
            "COXA SOBR" to "Coxa e Sobrecoxa",
            "SOBRECOXA" to "Sobrecoxa",
            "MOIDA" to "Carne Moída",
            "PAT" to "Patinho",
            "PATINHO" to "Patinho",
            "ACEM" to "Acém",
            "PALETA" to "Paleta",
            "MUSCULO" to "Músculo",
            "LING TOSC" to "Linguiça Toscana",
            "LING CALAB" to "Linguiça Calabresa",
            "BACON" to "Bacon Manta"
        )

        for ((syn, fullCut) in synonymMap) {
            if (norm.contains(syn)) {
                return fullCut
            }
        }

        val allCutsWithMeat = mutableListOf<String>()
        MeatType.entries.forEach { m ->
            allCutsWithMeat.addAll(m.presetCuts)
        }

        val normTokens = norm.split(Regex("""[\s\-/]+""")).filter { it.length >= 2 }
        var bestCut = ""
        var highestScore = 0

        for (cut in allCutsWithMeat) {
            val cutNorm = normalizeText(cut)
            val cutTokens = cutNorm.split(Regex("""[\s\-/]+""")).filter { it.length >= 2 }
            var score = 0

            for (cTok in cutTokens) {
                for (nTok in normTokens) {
                    if (nTok == cTok) {
                        score += cTok.length * 2
                    } else if (nTok.startsWith(cTok) || cTok.startsWith(nTok)) {
                        score += minOf(nTok.length, cTok.length)
                    }
                }
            }

            if (score > highestScore) {
                highestScore = score
                bestCut = cut
            }
        }

        if (highestScore >= 4 && bestCut.isNotBlank()) {
            return bestCut
        }

        return fallbackRaw.trim()
            .replace(Regex("""\s+"""), " ")
            .take(40)
            .ifBlank { "Corte Diverso" }
    }

    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }

    fun normalizeText(input: String): String {
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
