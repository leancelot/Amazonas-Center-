package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.ExpiryUrgency
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.MovementType
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

enum class UrgencyFilter(val label: String) {
    ALL("Todos"),
    DUE_7_DAYS("Até 7 dias"),
    DUE_15_DAYS("8 a 15 dias"),
    EXPIRED("Vencidos"),
    ON_TRACK("Em dia (> 15d)")
}

data class InventoryUiState(
    val searchQuery: String = "",
    val selectedMeatType: MeatType? = null,
    val selectedZone: TemperatureZone? = null,
    val selectedSupplier: String? = null,
    val urgencyFilter: UrgencyFilter = UrgencyFilter.ALL,
    val availableSuppliers: List<String> = emptyList(),
    val filteredItems: List<ColdStorageItemEntity> = emptyList(),
    val totalFilteredKg: Double = 0.0,
    val totalFilteredOtherUnits: Int = 0,
    val activeRole: StaffRole = StaffRole.OPERADOR,
    val feedbackMessage: String? = null,
    val streamError: String? = null
)

class InventoryViewModel(
    private val repository: ColdStorageRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedMeat = MutableStateFlow<MeatType?>(null)
    private val _selectedZone = MutableStateFlow<TemperatureZone?>(null)
    private val _selectedSupplier = MutableStateFlow<String?>(null)
    private val _urgencyFilter = MutableStateFlow(UrgencyFilter.ALL)
    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<InventoryUiState> = combine(
        repository.activeItemsFefo,
        repository.sessionState,
        _searchQuery,
        _selectedMeat,
        _selectedZone,
        _selectedSupplier,
        _urgencyFilter,
        _feedback
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val items = values[0] as List<ColdStorageItemEntity>
        val session = values[1] as ShopSessionState
        val query = values[2] as String
        val meat = values[3] as MeatType?
        val zone = values[4] as TemperatureZone?
        val supplier = values[5] as String?
        val urgency = values[6] as UrgencyFilter
        val feedback = values[7] as String?

        val availableSuppliers = items.map { it.supplierName }.filter { it.isNotBlank() }.distinct().sorted()

        val q = query.trim().lowercase()
        val filtered = items.filter { item ->
            val matchesQuery = q.isEmpty() ||
                item.cutName.lowercase().contains(q) ||
                item.batchCode.lowercase().contains(q) ||
                item.supplierName.lowercase().contains(q) ||
                item.invoiceNumber.lowercase().contains(q) ||
                item.sifCode.lowercase().contains(q) ||
                item.chamberName.lowercase().contains(q) ||
                item.notes.lowercase().contains(q)
            val matchesMeat = meat == null || item.meatTypeEnum == meat
            val matchesZone = zone == null || item.zoneEnum == zone
            val matchesSupplier = supplier == null || item.supplierName.equals(supplier, ignoreCase = true)
            val matchesUrgency = when (urgency) {
                UrgencyFilter.ALL -> true
                UrgencyFilter.DUE_7_DAYS -> item.urgency == ExpiryUrgency.VENCE_HOJE ||
                    item.urgency == ExpiryUrgency.CRITICO_7_DIAS
                UrgencyFilter.DUE_15_DAYS -> item.urgency == ExpiryUrgency.ATENCAO_15_DIAS
                UrgencyFilter.EXPIRED -> item.urgency == ExpiryUrgency.VENCIDO
                UrgencyFilter.ON_TRACK -> item.urgency == ExpiryUrgency.EM_DIA
            }
            matchesQuery && matchesMeat && matchesZone && matchesSupplier && matchesUrgency
        }

        val totalKg = filtered
            .filter { it.unitEnum == MeasurementUnit.KG }
            .sumOf { it.currentQuantity }
        val totalOtherUnits = filtered
            .filter { it.unitEnum != MeasurementUnit.KG }
            .sumOf { it.currentQuantity.toInt() }

        InventoryUiState(
            searchQuery = query,
            selectedMeatType = meat,
            selectedZone = zone,
            selectedSupplier = supplier,
            urgencyFilter = urgency,
            availableSuppliers = availableSuppliers,
            filteredItems = filtered,
            totalFilteredKg = totalKg,
            totalFilteredOtherUnits = totalOtherUnits,
            activeRole = session.activeRole,
            feedbackMessage = feedback,
            streamError = session.errorMessage
        )
    }.catch { error ->
        val rootError = unwrapFirestoreStreamError(error)
        Log.w("InventoryVM", "Error observing inventory", rootError)
        emit(
            InventoryUiState(
                streamError = friendlyFirestoreMessage(rootError, "Falha ao carregar estoque.")
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = InventoryUiState()
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleMeatFilter(meatType: MeatType?) {
        _selectedMeat.value = if (_selectedMeat.value == meatType) null else meatType
    }

    fun toggleZoneFilter(zone: TemperatureZone?) {
        _selectedZone.value = if (_selectedZone.value == zone) null else zone
    }

    fun toggleSupplierFilter(supplierName: String?) {
        _selectedSupplier.value = if (_selectedSupplier.value == supplierName) null else supplierName
    }

    fun setUrgencyFilter(filter: UrgencyFilter) {
        _urgencyFilter.value = filter
    }

    fun applyDashboardFilter(filter: UrgencyFilter, zone: TemperatureZone? = null) {
        _searchQuery.value = ""
        _selectedMeat.value = null
        _selectedSupplier.value = null
        _urgencyFilter.value = filter
        _selectedZone.value = zone
    }

    fun observeMovementsForItem(itemId: String): Flow<List<StockMovementEntity>> =
        repository.observeMovementsForItem(itemId).catch { error ->
            val rootError = unwrapFirestoreStreamError(error)
            Log.w("InventoryVM", "Error observing item movements", rootError)
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
                        "Baixa registrada com sucesso."
                    } else {
                        "Quantidade inválida para baixa."
                    }
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Erro ao registrar baixa.")
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

    fun clearFeedback() {
        _feedback.value = null
    }
}
