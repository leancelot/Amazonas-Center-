package com.example.data

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

enum class ReconciliationScopeType(val label: String, val filterHint: String) {
    SUPPLIER("Por Fornecedor", "Ex.: Friesul"),
    CHAMBER("Por Câmara / Unidade", "Ex.: Bairro de Fátima"),
    CUT("Por Corte", "Ex.: Picanha"),
    ALL_KG("Todo Estoque (kg)", "")
}

enum class ReconciliationIntervalPreset(val label: String, val days: Int) {
    DAILY("Diário (~1 dia)", 1),
    EVERY_3_DAYS("A cada 3 dias", 3),
    WEEKLY("Semanal (~7 dias)", 7),
    BIWEEKLY("Quinzenal (~15 dias)", 15)
}

enum class ReconciliationCadenceStatus(val label: String) {
    NEVER_CHECKED("Nunca conferido"),
    DUE("Intervalo vencido"),
    ON_TRACK("Em dia")
}

data class ReconciliationStatusSummary(
    val status: ReconciliationCadenceStatus,
    val effectiveLastCheckedMillis: Long,
    val daysSinceLastCheck: Int?,
    val daysUntilDue: Int?
) {
    val badgeText: String
        get() = when (status) {
            ReconciliationCadenceStatus.NEVER_CHECKED -> "Nunca conferido"
            ReconciliationCadenceStatus.DUE -> {
                val d = daysSinceLastCheck ?: 0
                if (d == 0) "Conferir hoje" else "Há ${d}d (pendente)"
            }
            ReconciliationCadenceStatus.ON_TRACK -> {
                val d = daysSinceLastCheck ?: 0
                if (d == 0) "Conferido hoje" else "Há ${d}d (em dia)"
            }
        }
}

@Serializable
data class ReconciliationProfile(
    val id: String = "",
    val name: String = "",
    val scopeType: String = ReconciliationScopeType.ALL_KG.name,
    val scopeFilter: String = "",
    val intervalDays: Int = 7,
    val preferredMovementType: String = MovementType.PERDA_APARA.name,
    val lastCheckedAtMillis: Long = 0L,
    val lastExpectedKg: Double = 0.0,
    val lastScaleWeightKg: Double = 0.0,
    val lastDiffKg: Double = 0.0,
    val lastShrinkagePercent: Double = 0.0
) {
    val scopeTypeEnum: ReconciliationScopeType
        get() = ReconciliationScopeType.entries.firstOrNull {
            it.name.equals(scopeType, ignoreCase = true)
        } ?: ReconciliationScopeType.ALL_KG

    val preferredMovementEnum: MovementType
        get() = MovementType.fromRaw(preferredMovementType)

    fun scopeDescription(): String {
        return when (scopeTypeEnum) {
            ReconciliationScopeType.SUPPLIER -> "Fornecedor: ${scopeFilter.ifBlank { "Todos" }}"
            ReconciliationScopeType.CHAMBER -> "Unidade/Câmara: ${scopeFilter.ifBlank { "Todas" }}"
            ReconciliationScopeType.CUT -> "Corte: ${scopeFilter.ifBlank { "Todos" }}"
            ReconciliationScopeType.ALL_KG -> "Todo o estoque em kg"
        }
    }

    fun evaluateStatus(
        movements: List<StockMovementEntity> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): ReconciliationStatusSummary {
        val marker = "[Rotina:$id]"
        val latestFromMovements = movements
            .asSequence()
            .filter { it.reason.contains(marker, ignoreCase = true) }
            .maxOfOrNull { it.createdAtMillis }
            ?: 0L
        val effectiveMillis = maxOf(lastCheckedAtMillis, latestFromMovements)
        if (effectiveMillis <= 0L) {
            return ReconciliationStatusSummary(
                status = ReconciliationCadenceStatus.NEVER_CHECKED,
                effectiveLastCheckedMillis = 0L,
                daysSinceLastCheck = null,
                daysUntilDue = 0
            )
        }
        val elapsedMillis = (nowMillis - effectiveMillis).coerceAtLeast(0L)
        val elapsedDays = (elapsedMillis / MILLIS_PER_DAY).toInt()
        val safeInterval = intervalDays.coerceAtLeast(1)
        val remainingDays = safeInterval - elapsedDays
        val isDue = elapsedMillis >= safeInterval * MILLIS_PER_DAY
        return ReconciliationStatusSummary(
            status = if (isDue) ReconciliationCadenceStatus.DUE else ReconciliationCadenceStatus.ON_TRACK,
            effectiveLastCheckedMillis = effectiveMillis,
            daysSinceLastCheck = elapsedDays,
            daysUntilDue = remainingDays
        )
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}

@Serializable
data class ScaleReconciliationRecord(
    val id: String = "",
    val profileId: String = "",
    val profileName: String = "",
    val scopeType: String = ReconciliationScopeType.ALL_KG.name,
    val scopeFilter: String = "",
    val expectedKg: Double = 0.0,
    val scaleWeightKg: Double = 0.0,
    val differenceKg: Double = 0.0,
    val shrinkagePercent: Double = 0.0,
    val estimatedLossBrl: Double = 0.0,
    val movementType: String = MovementType.PERDA_APARA.name,
    val affectedLotCount: Int = 0,
    val notes: String = "",
    val recordedAtMillis: Long = System.currentTimeMillis()
) {
    val movementTypeEnum: MovementType
        get() = MovementType.fromRaw(movementType)
}

data class ScaleDifferencePlan(
    val profileId: String,
    val profileName: String,
    val scopeType: ReconciliationScopeType,
    val scopeFilter: String,
    val matchingLots: List<ColdStorageItemEntity>,
    val expectedKg: Double,
    val scaleWeightKg: Double,
    val differenceKg: Double,
    val shrinkagePercent: Double,
    val estimatedLossBrl: Double,
    val movementType: MovementType,
    val allocations: List<FefoLotAllocation>,
    val requests: List<BatchStockOutRequest>,
    val surplusTargetLot: ColdStorageItemEntity?,
    val surplusAddKg: Double,
    val note: String
) {
    val isLossOrOutflow: Boolean get() = differenceKg > 0.001 && requests.isNotEmpty()
    val isSurplus: Boolean get() = differenceKg < -0.001 && surplusTargetLot != null && surplusAddKg > 0.001
    val isExactMatch: Boolean get() = abs(differenceKg) <= 0.001
    val hasAdjustmentAction: Boolean get() = isLossOrOutflow || isSurplus
}

object ScaleReconciliationEngine {

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun normalizeMatchText(raw: String): String {
        val decomposed = Normalizer.normalize(raw.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun filterMatchingLots(
        items: List<ColdStorageItemEntity>,
        scopeType: ReconciliationScopeType,
        scopeFilter: String
    ): List<ColdStorageItemEntity> {
        val normFilter = normalizeMatchText(scopeFilter)
        return items
            .asSequence()
            .filter { it.unitEnum == MeasurementUnit.KG && it.currentQuantity > 0.001 }
            .filter { item ->
                when (scopeType) {
                    ReconciliationScopeType.ALL_KG -> true
                    ReconciliationScopeType.SUPPLIER -> {
                        normFilter.isEmpty() || normalizeMatchText(item.supplierName).contains(normFilter)
                    }
                    ReconciliationScopeType.CHAMBER -> {
                        normFilter.isEmpty() || normalizeMatchText(item.chamberName).contains(normFilter)
                    }
                    ReconciliationScopeType.CUT -> {
                        normFilter.isEmpty() || normalizeMatchText(item.cutName).contains(normFilter)
                    }
                }
            }
            .sortedWith(
                compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }
                    .thenBy { it.receivedAtMillis }
            )
            .toList()
    }

    fun planScaleDifference(
        items: List<ColdStorageItemEntity>,
        profileId: String,
        profileName: String,
        scopeType: ReconciliationScopeType,
        scopeFilter: String,
        scaleWeightKg: Double,
        movementType: MovementType,
        note: String = ""
    ): ScaleDifferencePlan {
        val ptBr = Locale.forLanguageTag("pt-BR")
        val matchingLots = filterMatchingLots(items, scopeType, scopeFilter)
        val expectedKg = matchingLots.sumOf { it.currentQuantity }.coerceAtLeast(0.0)
        val safeScaleKg = scaleWeightKg.coerceAtLeast(0.0)
        val rawDiff = expectedKg - safeScaleKg
        val differenceKg = if (abs(rawDiff) <= 0.001) 0.0 else rawDiff
        val shrinkagePercent = if (expectedKg > 0.001) {
            (abs(differenceKg) / expectedKg) * 100.0
        } else {
            0.0
        }

        val cleanProfileId = profileId.trim().ifBlank { "avulso" }
        val cleanProfileName = profileName.trim().ifBlank { "Conferência balança" }
        val cleanNote = note.trim()

        val reasonBase = buildString {
            append("[Rotina:$cleanProfileId] ")
            append("Ajuste balança ($cleanProfileName): ")
            append("${String.format(ptBr, "%.2f", safeScaleKg)} kg na balança vs ")
            append("${String.format(ptBr, "%.2f", expectedKg)} kg sistema")
            when {
                differenceKg > 0.001 -> {
                    append(" • Quebra -${String.format(ptBr, "%.2f", differenceKg)} kg (${String.format(ptBr, "%.1f", shrinkagePercent)}%)")
                }
                differenceKg < -0.001 -> {
                    append(" • Sobra +${String.format(ptBr, "%.2f", abs(differenceKg))} kg")
                }
                else -> append(" • Exato (0% quebra)")
            }
            if (cleanNote.isNotEmpty()) {
                append(" • $cleanNote")
            }
        }.take(300)

        return if (differenceKg > 0.001) {
            val allocations = FefoStockAllocationEngine.planQuantityDeduction(matchingLots, differenceKg)
            val requests = allocations.map { alloc ->
                BatchStockOutRequest(
                    itemId = alloc.item.id,
                    movementType = movementType,
                    quantity = alloc.deductQuantity,
                    reason = reasonBase,
                    updatedNotes = alloc.updatedNotes
                )
            }
            val estLoss = if (movementType.isLoss) {
                allocations.sumOf { it.deductQuantity * it.item.unitCostBrl.coerceAtLeast(0.0) }
            } else {
                0.0
            }
            ScaleDifferencePlan(
                profileId = cleanProfileId,
                profileName = cleanProfileName,
                scopeType = scopeType,
                scopeFilter = scopeFilter,
                matchingLots = matchingLots,
                expectedKg = expectedKg,
                scaleWeightKg = safeScaleKg,
                differenceKg = differenceKg,
                shrinkagePercent = shrinkagePercent,
                estimatedLossBrl = estLoss,
                movementType = movementType,
                allocations = allocations,
                requests = requests,
                surplusTargetLot = null,
                surplusAddKg = 0.0,
                note = cleanNote
            )
        } else if (differenceKg < -0.001 && matchingLots.isNotEmpty()) {
            val targetLot = matchingLots.maxByOrNull { it.receivedAtMillis } ?: matchingLots.last()
            ScaleDifferencePlan(
                profileId = cleanProfileId,
                profileName = cleanProfileName,
                scopeType = scopeType,
                scopeFilter = scopeFilter,
                matchingLots = matchingLots,
                expectedKg = expectedKg,
                scaleWeightKg = safeScaleKg,
                differenceKg = differenceKg,
                shrinkagePercent = shrinkagePercent,
                estimatedLossBrl = 0.0,
                movementType = MovementType.ENTRADA,
                allocations = emptyList(),
                requests = emptyList(),
                surplusTargetLot = targetLot,
                surplusAddKg = abs(differenceKg),
                note = cleanNote
            )
        } else {
            ScaleDifferencePlan(
                profileId = cleanProfileId,
                profileName = cleanProfileName,
                scopeType = scopeType,
                scopeFilter = scopeFilter,
                matchingLots = matchingLots,
                expectedKg = expectedKg,
                scaleWeightKg = safeScaleKg,
                differenceKg = 0.0,
                shrinkagePercent = 0.0,
                estimatedLossBrl = 0.0,
                movementType = movementType,
                allocations = emptyList(),
                requests = emptyList(),
                surplusTargetLot = null,
                surplusAddKg = 0.0,
                note = cleanNote
            )
        }
    }
}
