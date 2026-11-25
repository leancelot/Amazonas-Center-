package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.ExpiryUrgency
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.StockMovementEntity
import com.example.data.TemperatureZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class UrgencyFilter(val label: String) {
    ALL("Todos"),
    DUE_SOON("Até 3 dias"),
    EXPIRED("Vencidos"),
    ON_TRACK("No prazo")
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
    val feedbackMessage: String? = null
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
        _searchQuery,
        _selectedMeat,
        _selectedZone,
        _selectedSupplier,
        _urgencyFilter,
        _feedback
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val items = values[0] as List<ColdStorageItemEntity>
        val query = values[1] as String
        val meat = values[2] as MeatType?
        val zone = values[3] as TemperatureZone?
        val supplier = values[4] as String?
        val urgency = values[5] as UrgencyFilter
        val feedback = values[6] as String?

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
                UrgencyFilter.DUE_SOON -> item.urgency == ExpiryUrgency.VENCE_HOJE ||
                    item.urgency == ExpiryUrgency.CRITICO_3_DIAS
                UrgencyFilter.EXPIRED -> item.urgency == ExpiryUrgency.VENCIDO
                UrgencyFilter.ON_TRACK -> item.urgency == ExpiryUrgency.ATENCAO_7_DIAS ||
                    item.urgency == ExpiryUrgency.EM_DIA
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
            feedbackMessage = feedback
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

    fun observeMovementsForItem(itemId: Long): Flow<List<StockMovementEntity>> =
        repository.observeMovementsForItem(itemId)

    fun recordStockOut(
        itemId: Long,
        movementType: MovementType,
        quantity: Double,
        reason: String
    ) {
        viewModelScope.launch {
            val ok = repository.recordStockOut(itemId, movementType, quantity, reason)
            _feedback.value = if (ok) {
                "Baixa registrada com sucesso."
            } else {
                "Quantidade inválida para baixa."
            }
        }
    }

    fun transferChamber(
        itemId: Long,
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double
    ) {
        viewModelScope.launch {
            repository.transferChamber(itemId, chamberName, zone, tempCelsius)
            _feedback.value = "Lote transferido para $chamberName."
        }
    }

    fun updateExpiry(itemId: Long, expiryEpochDay: Long) {
        viewModelScope.launch {
            repository.updateExpiry(itemId, expiryEpochDay)
            _feedback.value = "Validade atualizada."
        }
    }

    fun deleteItem(itemId: Long) {
        viewModelScope.launch {
            repository.deleteItem(itemId)
            _feedback.value = "Lote removido do estoque."
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}
