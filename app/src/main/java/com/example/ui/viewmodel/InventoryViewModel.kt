package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.ExpiryUrgency
import com.example.data.MeatType
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
    CRITICAL("Críticos ≤ 3d"),
    EXPIRED("Vencidos")
}

data class InventoryUiState(
    val searchQuery: String = "",
    val selectedMeatType: MeatType? = null,
    val selectedZone: TemperatureZone? = null,
    val urgencyFilter: UrgencyFilter = UrgencyFilter.ALL,
    val filteredItems: List<ColdStorageItemEntity> = emptyList(),
    val totalFilteredKg: Double = 0.0,
    val feedbackMessage: String? = null
)

class InventoryViewModel(
    private val repository: ColdStorageRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedMeat = MutableStateFlow<MeatType?>(null)
    private val _selectedZone = MutableStateFlow<TemperatureZone?>(null)
    private val _urgencyFilter = MutableStateFlow(UrgencyFilter.ALL)
    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<InventoryUiState> = combine(
        repository.activeItemsFefo,
        _searchQuery,
        _selectedMeat,
        _selectedZone,
        _urgencyFilter,
        _feedback
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val items = values[0] as List<ColdStorageItemEntity>
        val query = values[1] as String
        val meat = values[2] as MeatType?
        val zone = values[3] as TemperatureZone?
        val urgency = values[4] as UrgencyFilter
        val feedback = values[5] as String?

        val q = query.trim().lowercase()
        val filtered = items.filter { item ->
            val matchesQuery = q.isEmpty() ||
                item.cutName.lowercase().contains(q) ||
                item.batchCode.lowercase().contains(q) ||
                item.supplierName.lowercase().contains(q) ||
                item.invoiceNumber.lowercase().contains(q) ||
                item.sifCode.lowercase().contains(q)
            val matchesMeat = meat == null || item.meatTypeEnum == meat
            val matchesZone = zone == null || item.zoneEnum == zone
            val matchesUrgency = when (urgency) {
                UrgencyFilter.ALL -> true
                UrgencyFilter.CRITICAL -> item.urgency == ExpiryUrgency.VENCIDO ||
                    item.urgency == ExpiryUrgency.VENCE_HOJE ||
                    item.urgency == ExpiryUrgency.CRITICO_3_DIAS
                UrgencyFilter.EXPIRED -> item.urgency == ExpiryUrgency.VENCIDO
            }
            matchesQuery && matchesMeat && matchesZone && matchesUrgency
        }

        val totalKg = filtered
            .filter { it.unit == "KG" }
            .sumOf { it.currentQuantity }

        InventoryUiState(
            searchQuery = query,
            selectedMeatType = meat,
            selectedZone = zone,
            urgencyFilter = urgency,
            filteredItems = filtered,
            totalFilteredKg = totalKg,
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
