package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.ExpiryUrgency
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.ReceivingSessionSummary
import com.example.data.TemperatureZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChamberSummary(
    val chamberName: String,
    val zone: TemperatureZone,
    val lotCount: Int,
    val totalKg: Double,
    val avgTempCelsius: Double,
    val hasAlert: Boolean
)

data class DashboardUiState(
    val activeItems: List<ColdStorageItemEntity> = emptyList(),
    val priorityFefoItems: List<ColdStorageItemEntity> = emptyList(),
    val recentSessions: List<ReceivingSessionSummary> = emptyList(),
    val chamberSummaries: List<ChamberSummary> = emptyList(),
    val totalKg: Double = 0.0,
    val totalOtherUnits: Int = 0,
    val activeLotCount: Int = 0,
    val expiredCount: Int = 0,
    val dueSoonCount: Int = 0,
    val onTrackCount: Int = 0,
    val feedbackMessage: String? = null
)

class DashboardViewModel(
    private val repository: ColdStorageRepository
) : ViewModel() {

    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.activeItemsFefo,
        repository.sessionSummaries,
        _feedback
    ) { items, sessions, feedback ->
        val totalKg = items.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
        val otherUnits = items.filter { it.unitEnum != MeasurementUnit.KG }.sumOf { it.currentQuantity.toInt() }
        val expiredCount = items.count { it.urgency == ExpiryUrgency.VENCIDO }
        val dueSoonCount = items.count {
            it.urgency == ExpiryUrgency.VENCE_HOJE || it.urgency == ExpiryUrgency.CRITICO_3_DIAS
        }
        val onTrackCount = items.size - expiredCount - dueSoonCount

        val chamberSummaries = items
            .groupBy { it.chamberName.ifBlank { it.zoneEnum.defaultChamber } }
            .map { (chamber, chamberItems) ->
                val primaryZone = chamberItems.first().zoneEnum
                val kg = chamberItems.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
                val avgTemp = chamberItems.map { it.measuredTempCelsius }.average()
                val hasAlert = chamberItems.any {
                    it.urgency == ExpiryUrgency.VENCIDO || it.urgency == ExpiryUrgency.VENCE_HOJE
                }
                ChamberSummary(
                    chamberName = chamber,
                    zone = primaryZone,
                    lotCount = chamberItems.size,
                    totalKg = kg,
                    avgTempCelsius = avgTemp,
                    hasAlert = hasAlert
                )
            }
            .sortedByDescending { it.lotCount }

        DashboardUiState(
            activeItems = items,
            priorityFefoItems = items.take(8),
            recentSessions = sessions.take(5),
            chamberSummaries = chamberSummaries,
            totalKg = totalKg,
            totalOtherUnits = otherUnits,
            activeLotCount = items.size,
            expiredCount = expiredCount,
            dueSoonCount = dueSoonCount,
            onTrackCount = onTrackCount,
            feedbackMessage = feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    fun recordStockOut(
        itemId: Long,
        movementType: MovementType,
        quantity: Double,
        reason: String
    ) {
        viewModelScope.launch {
            val ok = repository.recordStockOut(itemId, movementType, quantity, reason)
            _feedback.value = if (ok) {
                "Baixa registrada: ${movementType.displayName}"
            } else {
                "Não foi possível registrar a baixa. Verifique a quantidade."
            }
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}
