package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.DateUtils
import com.example.data.ExpiryUrgency
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.ReceivingSessionSummary
import com.example.data.ShopSessionState
import com.example.data.StaffRole
import com.example.data.StockMovementEntity
import com.example.data.TemperatureZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChamberSummary(
    val chamberName: String,
    val zone: TemperatureZone,
    val lotCount: Int,
    val totalKg: Double,
    val totalOtherUnits: Int,
    val avgTempCelsius: Double,
    val hasAlert: Boolean
)

data class DashboardUiState(
    val activeItems: List<ColdStorageItemEntity> = emptyList(),
    val priorityFefoItems: List<ColdStorageItemEntity> = emptyList(),
    val todaySessions: List<ReceivingSessionSummary> = emptyList(),
    val chamberSummaries: List<ChamberSummary> = emptyList(),
    val totalKg: Double = 0.0,
    val totalOtherUnits: Int = 0,
    val activeLotCount: Int = 0,
    val expiredCount: Int = 0,
    val due7DaysCount: Int = 0,
    val due15DaysCount: Int = 0,
    val onTrackCount: Int = 0,
    val sessionState: ShopSessionState = ShopSessionState(),
    val feedbackMessage: String? = null,
    val streamError: String? = null
) {
    val activeRole: StaffRole get() = sessionState.activeRole
}

class DashboardViewModel(
    private val repository: ColdStorageRepository
) : ViewModel() {

    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.activeItemsFefo,
        repository.sessionSummaries,
        repository.sessionState,
        _feedback
    ) { items, sessions, session, feedback ->
        val totalKg = items.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
        val otherUnits = items.filter { it.unitEnum != MeasurementUnit.KG }.sumOf { it.currentQuantity.toInt() }
        val expiredCount = items.count { it.urgency == ExpiryUrgency.VENCIDO }
        val due7DaysCount = items.count {
            it.urgency == ExpiryUrgency.VENCE_HOJE || it.urgency == ExpiryUrgency.CRITICO_7_DIAS
        }
        val due15DaysCount = items.count { it.urgency == ExpiryUrgency.ATENCAO_15_DIAS }
        val onTrackCount = items.count { it.urgency == ExpiryUrgency.EM_DIA }

        val todaySessions = sessions
            .filter { DateUtils.isToday(it.receivedAtMillis) }
            .distinctBy { it.id }

        val chamberSummaries = items
            .groupBy { it.chamberName.ifBlank { it.zoneEnum.defaultChamber } }
            .map { (chamber, chamberItems) ->
                val primaryZone = chamberItems
                    .groupingBy { it.zoneEnum }
                    .eachCount()
                    .maxByOrNull { it.value }?.key
                    ?: chamberItems.first().zoneEnum
                val kg = chamberItems.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.currentQuantity }
                val chamberOtherUnits = chamberItems
                    .filter { it.unitEnum != MeasurementUnit.KG }
                    .sumOf { it.currentQuantity.toInt() }
                val avgTemp = chamberItems.map { it.measuredTempCelsius }.average()
                val hasAlert = chamberItems.any {
                    it.urgency == ExpiryUrgency.VENCIDO ||
                        it.urgency == ExpiryUrgency.VENCE_HOJE ||
                        it.urgency == ExpiryUrgency.CRITICO_7_DIAS
                }
                ChamberSummary(
                    chamberName = chamber,
                    zone = primaryZone,
                    lotCount = chamberItems.size,
                    totalKg = kg,
                    totalOtherUnits = chamberOtherUnits,
                    avgTempCelsius = avgTemp,
                    hasAlert = hasAlert
                )
            }
            .sortedByDescending { it.lotCount }

        DashboardUiState(
            activeItems = items,
            priorityFefoItems = items.take(8),
            todaySessions = todaySessions,
            chamberSummaries = chamberSummaries,
            totalKg = totalKg,
            totalOtherUnits = otherUnits,
            activeLotCount = items.size,
            expiredCount = expiredCount,
            due7DaysCount = due7DaysCount,
            due15DaysCount = due15DaysCount,
            onTrackCount = onTrackCount,
            sessionState = session,
            feedbackMessage = feedback,
            streamError = session.errorMessage
        )
    }.catch { error ->
        val rootError = unwrapFirestoreStreamError(error)
        Log.w("DashboardVM", "Error observing dashboard", rootError)
        emit(
            DashboardUiState(
                streamError = friendlyFirestoreMessage(rootError, "Falha ao carregar painel.")
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    fun observeItemsForSession(sessionId: String): Flow<List<ColdStorageItemEntity>> =
        repository.observeItemsForSession(sessionId).catch { error ->
            val rootError = unwrapFirestoreStreamError(error)
            Log.w("DashboardVM", "Error observing session items", rootError)
            emit(emptyList())
        }

    fun observeMovementsForItem(itemId: String): Flow<List<StockMovementEntity>> =
        repository.observeMovementsForItem(itemId).catch { error ->
            val rootError = unwrapFirestoreStreamError(error)
            Log.w("DashboardVM", "Error observing item movements", rootError)
            emit(emptyList())
        }

    fun recordStockOut(
        itemId: String,
        movementType: MovementType,
        quantity: Double,
        reason: String
    ) {
        viewModelScope.launch {
            repository.recordStockOut(itemId, movementType, quantity, reason).fold(
                onSuccess = { ok ->
                    _feedback.value = if (ok) {
                        "Baixa registrada: ${movementType.displayName}"
                    } else {
                        "Não foi possível registrar a baixa. Verifique a quantidade."
                    }
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Erro ao registrar baixa no estoque.")
                }
            )
        }
    }

    fun transferChamber(
        itemId: String,
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double
    ) {
        viewModelScope.launch {
            repository.transferChamber(itemId, chamberName, zone, tempCelsius).fold(
                onSuccess = {
                    _feedback.value = "Lote atualizado para $chamberName."
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Erro ao atualizar câmara/temperatura.")
                }
            )
        }
    }

    fun updateExpiry(itemId: String, expiryEpochDay: Long) {
        viewModelScope.launch {
            repository.updateExpiry(itemId, expiryEpochDay).fold(
                onSuccess = {
                    _feedback.value = "Validade atualizada."
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Erro ao atualizar validade.")
                }
            )
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            repository.deleteItem(itemId).fold(
                onSuccess = {
                    _feedback.value = "Lote removido do estoque."
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Apenas o gerente pode excluir lotes.")
                }
            )
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId).fold(
                onSuccess = {
                    _feedback.value = "Sessão removida do histórico."
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Apenas o gerente pode excluir sessões.")
                }
            )
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}
