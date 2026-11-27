package com.example.data

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

enum class PackUnitType(
    val shortLabel: String,
    val singularLabel: String,
    val pluralLabel: String
) {
    CAIXA("cx", "Caixa", "Caixas"),
    PECA("pç", "Peça", "Peças");

    companion object {
        fun fromRaw(raw: String?): PackUnitType {
            val clean = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull {
                it.name == clean || it.shortLabel.equals(clean, ignoreCase = true)
            } ?: CAIXA
        }
    }
}

data class PackSpec(
    val remainingCount: Int,
    val packType: PackUnitType,
    val avgWeightPerPack: Double
) {
    fun formatSummary(unit: MeasurementUnit): String {
        val ptBr = Locale.forLanguageTag("pt-BR")
        val typeWord = if (remainingCount == 1) packType.singularLabel.lowercase() else packType.pluralLabel.lowercase()
        return if (unit == MeasurementUnit.KG && avgWeightPerPack > 0.0) {
            "$remainingCount $typeWord (~${String.format(ptBr, "%.1f", avgWeightPerPack)} kg/${packType.shortLabel})"
        } else {
            "$remainingCount ${packType.shortLabel}"
        }
    }

    fun formatSinglePackAction(): String {
        val ptBr = Locale.forLanguageTag("pt-BR")
        return if (avgWeightPerPack > 0.0) {
            "-1 ${packType.shortLabel} (~${String.format(ptBr, "%.1f", avgWeightPerPack)} kg)"
        } else {
            "-1 ${packType.shortLabel}"
        }
    }
}

object PackSpecHelper {
    private val PACK_TAG_REGEX = Regex("""\[Vol:(\d+)\|(CAIXA|PECA)\|([0-9]+(?:\.[0-9]+)?)\]""")

    fun parse(notes: String?): PackSpec? {
        if (notes.isNullOrBlank()) return null
        val match = PACK_TAG_REGEX.find(notes) ?: return null
        val count = match.groupValues[1].toIntOrNull() ?: return null
        val type = PackUnitType.fromRaw(match.groupValues[2])
        val avg = match.groupValues[3].toDoubleOrNull() ?: 0.0
        if (count <= 0 && avg <= 0.0) return null
        return PackSpec(
            remainingCount = count.coerceAtLeast(0),
            packType = type,
            avgWeightPerPack = avg.coerceAtLeast(0.0)
        )
    }

    fun stripMetadata(notes: String?): String {
        if (notes.isNullOrBlank()) return ""
        return notes
            .replace(PACK_TAG_REGEX, "")
            .replace(Regex("""\s*•\s*$"""), "")
            .replace(Regex("""^\s*•\s*"""), "")
            .trim()
    }

    fun applyPackMetadata(
        userNotes: String,
        packCount: Int,
        packType: PackUnitType,
        totalQuantity: Double,
        unit: MeasurementUnit
    ): String {
        val cleanNotes = stripMetadata(userNotes)
        if (unit != MeasurementUnit.KG || packCount <= 0 || totalQuantity <= 0.0) {
            return cleanNotes.take(500)
        }
        val avg = (totalQuantity / packCount.toDouble()).coerceAtLeast(0.01)
        val tag = buildTag(packCount, packType, avg)
        return combineNotesAndTag(cleanNotes, tag)
    }

    fun updatePackAfterDeduction(
        existingNotes: String,
        remainingQuantity: Double,
        packsDeductedExplicitly: Int = 0
    ): String? {
        val existingSpec = parse(existingNotes) ?: return null
        val cleanNotes = stripMetadata(existingNotes)
        val newRemainingPacks = when {
            remainingQuantity <= 0.001 -> 0
            packsDeductedExplicitly > 0 -> {
                (existingSpec.remainingCount - packsDeductedExplicitly).coerceAtLeast(1)
            }
            existingSpec.avgWeightPerPack > 0.0 -> {
                ceil(remainingQuantity / existingSpec.avgWeightPerPack).toInt().coerceAtLeast(1)
            }
            else -> existingSpec.remainingCount
        }
        val tag = buildTag(newRemainingPacks, existingSpec.packType, existingSpec.avgWeightPerPack)
        return combineNotesAndTag(cleanNotes, tag)
    }

    private fun buildTag(count: Int, packType: PackUnitType, avgWeight: Double): String {
        val formattedAvg = String.format(Locale.US, "%.2f", avgWeight.coerceAtLeast(0.0))
        return "[Vol:${count.coerceAtLeast(0)}|${packType.name}|$formattedAvg]"
    }

    private fun combineNotesAndTag(cleanNotes: String, tag: String): String {
        if (cleanNotes.isBlank()) return tag.take(500)
        val maxCleanLength = (500 - tag.length - 3).coerceAtLeast(0)
        val truncatedClean = cleanNotes.take(maxCleanLength).trim()
        return if (truncatedClean.isEmpty()) tag.take(500) else "$truncatedClean • $tag"
    }
}

data class CutFefoGroup(
    val key: String,
    val cutName: String,
    val meatType: MeatType,
    val unit: MeasurementUnit,
    val zones: Set<TemperatureZone>,
    val chamberNames: List<String>,
    val lots: List<ColdStorageItemEntity>,
    val totalQuantity: Double,
    val nearestExpiryEpochDay: Long,
    val totalRemainingPacks: Int,
    val primaryPackType: PackUnitType?,
    val firstFefoPackAvgWeight: Double
) {
    val urgency: ExpiryUrgency get() = ExpiryUrgency.fromEpochDay(nearestExpiryEpochDay)
}

data class FefoLotAllocation(
    val item: ColdStorageItemEntity,
    val deductQuantity: Double,
    val remainingAfter: Double,
    val updatedNotes: String?,
    val packsDeducted: Int
)

data class BatchStockOutRequest(
    val itemId: String,
    val movementType: MovementType,
    val quantity: Double,
    val reason: String,
    val updatedNotes: String? = null
)

data class FastClosureCutEntry(
    val group: CutFefoGroup,
    val countedRemaining: Double
)

data class FastClosurePlan(
    val allocationsByCut: List<Pair<CutFefoGroup, List<FefoLotAllocation>>>,
    val requests: List<BatchStockOutRequest>,
    val totalDeductedKg: Double,
    val totalDeductedOtherUnits: Int,
    val affectedCutCount: Int,
    val affectedLotCount: Int,
    val overCountedCutNames: List<String>
)

object FefoStockAllocationEngine {

    fun groupActiveItemsByCut(items: List<ColdStorageItemEntity>): List<CutFefoGroup> {
        return items
            .filter { it.currentQuantity > 0.001 }
            .sortedWith(
                compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }
                    .thenBy { it.receivedAtMillis }
            )
            .groupBy { item ->
                val normCut = item.cutName.trim().lowercase().ifBlank { item.meatTypeEnum.displayName.lowercase() }
                "$normCut|${item.unitEnum.name}"
            }
            .map { (key, groupLots) ->
                val firstLot = groupLots.first()
                val specs = groupLots.mapNotNull { lot ->
                    PackSpecHelper.parse(lot.notes)?.let { lot to it }
                }
                val totalPacks = specs.sumOf { it.second.remainingCount }
                val firstSpec = specs.firstOrNull { it.second.remainingCount > 0 }?.second
                    ?: specs.firstOrNull()?.second
                CutFefoGroup(
                    key = key,
                    cutName = firstLot.cutName.ifBlank { firstLot.meatTypeEnum.displayName },
                    meatType = firstLot.meatTypeEnum,
                    unit = firstLot.unitEnum,
                    zones = groupLots.map { it.zoneEnum }.toSet(),
                    chamberNames = groupLots.map { it.chamberName.ifBlank { it.zoneEnum.defaultChamber } }.distinct(),
                    lots = groupLots,
                    totalQuantity = groupLots.sumOf { it.currentQuantity },
                    nearestExpiryEpochDay = groupLots.minOf { it.expiryDateEpochDay },
                    totalRemainingPacks = totalPacks,
                    primaryPackType = firstSpec?.packType,
                    firstFefoPackAvgWeight = firstSpec?.avgWeightPerPack ?: 0.0
                )
            }
            .sortedWith(
                compareBy<CutFefoGroup> { it.nearestExpiryEpochDay }
                    .thenBy { it.cutName.lowercase() }
            )
    }

    fun planQuantityDeduction(
        lotsInOrder: List<ColdStorageItemEntity>,
        quantityToDeduct: Double
    ): List<FefoLotAllocation> {
        if (quantityToDeduct <= 0.001) return emptyList()
        var remainingToTake = quantityToDeduct
        val sortedLots = lotsInOrder
            .filter { it.currentQuantity > 0.001 }
            .sortedWith(
                compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }
                    .thenBy { it.receivedAtMillis }
            )

        val result = mutableListOf<FefoLotAllocation>()
        for (lot in sortedLots) {
            if (remainingToTake <= 0.001) break
            val take = min(remainingToTake, lot.currentQuantity)
            if (take <= 0.001) continue
            val after = (lot.currentQuantity - take).let { if (it < 0.001) 0.0 else it }
            val updatedNotes = PackSpecHelper.updatePackAfterDeduction(
                existingNotes = lot.notes,
                remainingQuantity = after,
                packsDeductedExplicitly = 0
            )
            result += FefoLotAllocation(
                item = lot,
                deductQuantity = take,
                remainingAfter = after,
                updatedNotes = updatedNotes,
                packsDeducted = 0
            )
            remainingToTake -= take
        }
        return result
    }

    fun planPackDeduction(
        lotsInOrder: List<ColdStorageItemEntity>,
        packsToDeduct: Int
    ): List<FefoLotAllocation> {
        if (packsToDeduct <= 0) return emptyList()
        var remainingPacks = packsToDeduct
        val sortedLots = lotsInOrder
            .filter { it.currentQuantity > 0.001 }
            .sortedWith(
                compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }
                    .thenBy { it.receivedAtMillis }
            )

        val result = mutableListOf<FefoLotAllocation>()
        for (lot in sortedLots) {
            if (remainingPacks <= 0) break
            val spec = PackSpecHelper.parse(lot.notes) ?: continue
            if (spec.remainingCount <= 0 || spec.avgWeightPerPack <= 0.0) continue

            val takePacks = min(remainingPacks, spec.remainingCount)
            val rawWeight = if (takePacks >= spec.remainingCount) {
                lot.currentQuantity
            } else {
                min(lot.currentQuantity, takePacks * spec.avgWeightPerPack)
            }
            if (rawWeight <= 0.001) continue

            val after = (lot.currentQuantity - rawWeight).let { if (it < 0.001) 0.0 else it }
            val updatedNotes = PackSpecHelper.updatePackAfterDeduction(
                existingNotes = lot.notes,
                remainingQuantity = after,
                packsDeductedExplicitly = takePacks
            )
            result += FefoLotAllocation(
                item = lot,
                deductQuantity = rawWeight,
                remainingAfter = after,
                updatedNotes = updatedNotes,
                packsDeducted = takePacks
            )
            remainingPacks -= takePacks
        }
        return result
    }

    fun planFastClosure(
        entries: List<FastClosureCutEntry>,
        movementType: MovementType,
        reasonPrefix: String = "Fechamento rápido (contagem de sobra)"
    ): FastClosurePlan {
        val allocationsByCut = mutableListOf<Pair<CutFefoGroup, List<FefoLotAllocation>>>()
        val requests = mutableListOf<BatchStockOutRequest>()
        val overCountedCutNames = mutableListOf<String>()
        var totalDeductedKg = 0.0
        var totalDeductedOther = 0

        entries.forEach { entry ->
            val group = entry.group
            val counted = entry.countedRemaining.coerceAtLeast(0.0)
            when {
                counted > group.totalQuantity + 0.05 -> {
                    overCountedCutNames += group.cutName
                }
                counted < group.totalQuantity - 0.001 -> {
                    val deductAmount = group.totalQuantity - counted
                    val allocations = planQuantityDeduction(group.lots, deductAmount)
                    if (allocations.isNotEmpty()) {
                        allocationsByCut += group to allocations
                        val ptBr = Locale.forLanguageTag("pt-BR")
                        val cleanReason = buildString {
                            append(reasonPrefix.trim().ifBlank { "Fechamento rápido" })
                            append(" • Sobra: ")
                            append(DateUtils.formatWeightOrQty(counted, group.unit))
                        }.take(300)
                        allocations.forEach { alloc ->
                            requests += BatchStockOutRequest(
                                itemId = alloc.item.id,
                                movementType = movementType,
                                quantity = alloc.deductQuantity,
                                reason = cleanReason,
                                updatedNotes = alloc.updatedNotes
                            )
                            if (group.unit == MeasurementUnit.KG) {
                                totalDeductedKg += alloc.deductQuantity
                            } else {
                                totalDeductedOther += alloc.deductQuantity.roundToInt()
                            }
                        }
                    }
                }
            }
        }

        return FastClosurePlan(
            allocationsByCut = allocationsByCut,
            requests = requests,
            totalDeductedKg = totalDeductedKg,
            totalDeductedOtherUnits = totalDeductedOther,
            affectedCutCount = allocationsByCut.size,
            affectedLotCount = requests.size,
            overCountedCutNames = overCountedCutNames
        )
    }
}
