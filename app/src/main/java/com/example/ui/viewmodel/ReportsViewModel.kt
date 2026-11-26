package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChamberTempLog
import com.example.data.ChamberTempLogStore
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.DateUtils
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.StockMovementEntity
import com.example.data.TemperatureZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.util.Locale

enum class ReportPeriod(val label: String, val days: Int?) {
    DAYS_7("7 dias", 7),
    DAYS_30("30 dias", 30),
    ALL("Todo período", null)
}

enum class ReportStockGrouping(val label: String) {
    MEAT_TYPE("Tipo de Carne"),
    SUPPLIER("Frigorífico"),
    EXPIRY("Validade")
}

data class MeatStockBreakdown(
    val meatType: MeatType,
    val lotCount: Int,
    val totalKg: Double,
    val sharePercent: Float
)

data class SupplierStockBreakdown(
    val supplierName: String,
    val lotCount: Int,
    val totalKg: Double,
    val sharePercent: Float
)

data class ExpiryStockBreakdown(
    val label: String,
    val isUrgent: Boolean,
    val isWarning: Boolean,
    val lotCount: Int,
    val totalKg: Double,
    val sharePercent: Float
)

data class ZoneStockBreakdown(
    val zone: TemperatureZone,
    val lotCount: Int,
    val totalKg: Double
)

data class LossReasonBreakdown(
    val movementType: MovementType,
    val count: Int,
    val totalKg: Double,
    val totalBrl: Double
)

data class ReportsUiState(
    val period: ReportPeriod = ReportPeriod.DAYS_30,
    val stockGrouping: ReportStockGrouping = ReportStockGrouping.MEAT_TYPE,
    val totalStockKg: Double = 0.0,
    val totalStockOtherUnits: Int = 0,
    val totalActiveLots: Int = 0,
    val totalOutgoingKg: Double = 0.0,
    val totalLossKg: Double = 0.0,
    val totalLossBrl: Double = 0.0,
    val lossRatePercent: Double = 0.0,
    val meatBreakdown: List<MeatStockBreakdown> = emptyList(),
    val supplierBreakdown: List<SupplierStockBreakdown> = emptyList(),
    val expiryBreakdown: List<ExpiryStockBreakdown> = emptyList(),
    val zoneBreakdown: List<ZoneStockBreakdown> = emptyList(),
    val lossReasons: List<LossReasonBreakdown> = emptyList(),
    val recentMovements: List<StockMovementEntity> = emptyList(),
    val recentTempLogs: List<ChamberTempLog> = emptyList(),
    val todayTempRoundsCount: Int = 0,
    val activeItemsSnapshot: List<ColdStorageItemEntity> = emptyList(),
    val streamError: String? = null
)

class ReportsViewModel(
    private val repository: ColdStorageRepository,
    private val chamberTempLogStore: ChamberTempLogStore? = null
) : ViewModel() {

    private val _period = MutableStateFlow(ReportPeriod.DAYS_30)
    private val _stockGrouping = MutableStateFlow(ReportStockGrouping.MEAT_TYPE)
    private val emptyTempLogs = MutableStateFlow<List<ChamberTempLog>>(emptyList())
    private val tempLogsFlow: StateFlow<List<ChamberTempLog>> = chamberTempLogStore?.logs ?: emptyTempLogs

    val uiState: StateFlow<ReportsUiState> = combine(
        repository.activeItemsFefo,
        repository.allMovements,
        tempLogsFlow,
        _period,
        _stockGrouping
    ) { activeItems, allMovements, tempLogs, period, grouping ->
        val cutoffMillis = period.days?.let { days ->
            System.currentTimeMillis() - (days.toLong() * 86_400_000L)
        } ?: 0L

        val periodMovements = allMovements.filter { it.createdAtMillis >= cutoffMillis }
        val totalStockKg = activeItems.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
        val totalStockOtherUnits = activeItems.filter { it.unitEnum != MeasurementUnit.KG }.sumOf { it.currentQuantity.toInt() }

        val outgoingKg = periodMovements
            .filter { it.movementTypeEnum.isOutgoing && !it.movementTypeEnum.isLoss && it.unitEnum == MeasurementUnit.KG }
            .sumOf { it.quantity }

        val lossMovements = periodMovements.filter { it.movementTypeEnum.isLoss }
        val totalLossKg = lossMovements.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.quantity }
        val totalLossBrl = lossMovements.sumOf { it.estimatedLossBrl }

        val totalMovedKg = outgoingKg + totalLossKg
        val lossRate = if (totalMovedKg > 0.0) (totalLossKg / totalMovedKg) * 100.0 else 0.0

        val meatGroups = activeItems.groupBy { it.meatTypeEnum }
            .map { (meat, list) ->
                val kg = list.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
                MeatStockBreakdown(
                    meatType = meat,
                    lotCount = list.size,
                    totalKg = kg,
                    sharePercent = if (totalStockKg > 0.0) (kg / totalStockKg).toFloat() else 0f
                )
            }
            .sortedByDescending { it.totalKg }

        val supplierGroups = activeItems.groupBy {
            it.supplierName.trim().ifBlank { "Fornecedor não informado" }
        }
            .map { (supplier, list) ->
                val kg = list.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
                SupplierStockBreakdown(
                    supplierName = supplier,
                    lotCount = list.size,
                    totalKg = kg,
                    sharePercent = if (totalStockKg > 0.0) (kg / totalStockKg).toFloat() else 0f
                )
            }
            .sortedByDescending { it.totalKg }

        val expiryBuckets = listOf(
            Triple("Vencidos", true to false) { days: Int -> days < 0 },
            Triple("Crítico (0 a 7 dias)", true to false) { days: Int -> days in 0..7 },
            Triple("Atenção (8 a 15 dias)", false to true) { days: Int -> days in 8..15 },
            Triple("Seguro (> 15 dias)", false to false) { days: Int -> days > 15 }
        ).mapNotNull { (label, flags, predicate) ->
            val list = activeItems.filter { predicate(DateUtils.daysUntil(it.expiryDateEpochDay)) }
            if (list.isEmpty()) null else {
                val kg = list.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
                ExpiryStockBreakdown(
                    label = label,
                    isUrgent = flags.first,
                    isWarning = flags.second,
                    lotCount = list.size,
                    totalKg = kg,
                    sharePercent = if (totalStockKg > 0.0) (kg / totalStockKg).toFloat() else 0f
                )
            }
        }

        val zoneGroups = activeItems.groupBy { it.zoneEnum }
            .map { (zone, list) ->
                ZoneStockBreakdown(
                    zone = zone,
                    lotCount = list.size,
                    totalKg = list.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
                )
            }
            .sortedByDescending { it.totalKg }

        val lossByReason = lossMovements.groupBy { it.movementTypeEnum }
            .map { (type, list) ->
                LossReasonBreakdown(
                    movementType = type,
                    count = list.size,
                    totalKg = list.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.quantity },
                    totalBrl = list.sumOf { it.estimatedLossBrl }
                )
            }
            .sortedByDescending { it.totalKg }

        val periodTempLogs = tempLogs.filter { it.recordedAtMillis >= cutoffMillis }
        val todayTempRounds = tempLogs.count { DateUtils.isToday(it.recordedAtMillis) }

        ReportsUiState(
            period = period,
            stockGrouping = grouping,
            totalStockKg = totalStockKg,
            totalStockOtherUnits = totalStockOtherUnits,
            totalActiveLots = activeItems.size,
            totalOutgoingKg = outgoingKg,
            totalLossKg = totalLossKg,
            totalLossBrl = totalLossBrl,
            lossRatePercent = lossRate,
            meatBreakdown = meatGroups,
            supplierBreakdown = supplierGroups,
            expiryBreakdown = expiryBuckets,
            zoneBreakdown = zoneGroups,
            lossReasons = lossByReason,
            recentMovements = periodMovements.take(30),
            recentTempLogs = periodTempLogs.take(15),
            todayTempRoundsCount = todayTempRounds,
            activeItemsSnapshot = activeItems
        )
    }.catch { error ->
        val rootError = unwrapFirestoreStreamError(error)
        Log.w("ReportsVM", "Error observing reports", rootError)
        emit(
            ReportsUiState(
                streamError = friendlyFirestoreMessage(rootError, "Falha ao carregar relatórios.")
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReportsUiState()
    )

    fun setPeriod(period: ReportPeriod) {
        _period.value = period
    }

    fun setStockGrouping(grouping: ReportStockGrouping) {
        _stockGrouping.value = grouping
    }

    fun buildTextSummary(): String {
        val state = uiState.value
        val ptBr = Locale.forLanguageTag("pt-BR")
        val sb = StringBuilder()
        sb.appendLine("RELATÓRIO DE CONFERÊNCIA — AMAZONAS CENTER (${state.period.label})")
        sb.appendLine("Gerado em: ${DateUtils.formatDateTime(System.currentTimeMillis())}")
        sb.appendLine("----------------------------------------")
        val otherSuffix = if (state.totalStockOtherUnits > 0) " + ${state.totalStockOtherUnits} pç/cx" else ""
        sb.appendLine("Estoque Atual: ${String.format(ptBr, "%.2f", state.totalStockKg)} kg$otherSuffix (${state.totalActiveLots} lotes)")
        sb.appendLine("Saídas p/ Balcão/Desossa: ${String.format(ptBr, "%.2f", state.totalOutgoingKg)} kg")
        sb.appendLine("Perdas no Período: ${String.format(ptBr, "%.2f", state.totalLossKg)} kg (${DateUtils.formatCurrencyBrl(state.totalLossBrl)})")
        sb.appendLine("Índice de Quebra: ${String.format(ptBr, "%.1f", state.lossRatePercent)}%")
        if (state.zoneBreakdown.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("POR ARMAZENAMENTO:")
            state.zoneBreakdown.forEach {
                sb.appendLine("- ${it.zone.displayName}: ${String.format(ptBr, "%.2f", it.totalKg)} kg (${it.lotCount} lotes)")
            }
        }
        sb.appendLine()
        sb.appendLine("ESTOQUE POR TIPO DE CARNE:")
        state.meatBreakdown.forEach {
            sb.appendLine("- ${it.meatType.displayName}: ${String.format(ptBr, "%.2f", it.totalKg)} kg (${it.lotCount} lotes)")
        }
        if (state.supplierBreakdown.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("ESTOQUE POR FRIGORÍFICO:")
            state.supplierBreakdown.forEach {
                sb.appendLine("- ${it.supplierName}: ${String.format(ptBr, "%.2f", it.totalKg)} kg (${it.lotCount} lotes)")
            }
        }
        if (state.expiryBreakdown.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("ESTOQUE POR VALIDADE (FEFO):")
            state.expiryBreakdown.forEach {
                sb.appendLine("- ${it.label}: ${String.format(ptBr, "%.2f", it.totalKg)} kg (${it.lotCount} lotes)")
            }
        }
        if (state.lossReasons.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("DETALHAMENTO DE PERDAS:")
            state.lossReasons.forEach {
                sb.appendLine("- ${it.movementType.displayName}: ${String.format(ptBr, "%.2f", it.totalKg)} kg (${it.count}x)")
            }
        }
        if (state.recentTempLogs.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("RONDAS TÉRMICAS (${state.todayTempRoundsCount} hoje):")
            state.recentTempLogs.take(8).forEach { log ->
                sb.appendLine("- ${log.chamberName}: ${DateUtils.formatTemp(log.measuredTempCelsius)} (${log.complianceEnum.displayName}) em ${DateUtils.formatShortDate(log.recordedAtMillis)}")
            }
        }
        return sb.toString()
    }

    fun createConferenceCsvUri(context: Context): Uri? {
        return try {
            val state = uiState.value
            val ptBr = Locale.forLanguageTag("pt-BR")
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(dir, "conferencia_camara_${System.currentTimeMillis()}.csv")
            val csv = buildString {
                appendLine("Tipo;Corte;Zona;Temp_C;Camara;Saldo;Unidade;Validade;Lote;SIF;Fornecedor;NF;Observacoes")
                state.activeItemsSnapshot.forEach { item ->
                    appendLine(
                        listOf(
                            item.meatTypeEnum.displayName,
                            item.cutName,
                            item.zoneEnum.displayName,
                            String.format(ptBr, "%.1f", item.measuredTempCelsius),
                            item.chamberName,
                            String.format(ptBr, "%.2f", item.currentQuantity),
                            item.unitEnum.shortLabel,
                            DateUtils.formatEpochDay(item.expiryDateEpochDay),
                            item.batchCode,
                            item.sifCode,
                            item.supplierName,
                            item.invoiceNumber,
                            item.notes
                        ).joinToString(";") { cell -> cell.replace(";", ",") }
                    )
                }
            }
            file.writeText(csv, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    }
}
