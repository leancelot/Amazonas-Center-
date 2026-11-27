package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BatchStockOutRequest
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.CutFefoGroup
import com.example.data.DateUtils
import com.example.data.ExpiryUrgency
import com.example.data.FastClosurePlan
import com.example.data.FefoLotAllocation
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.ReconciliationCadenceStatus
import com.example.data.ReconciliationProfile
import com.example.data.ReconciliationScopeType
import com.example.data.ScaleDifferencePlan
import com.example.data.ScaleReconciliationEngine
import com.example.data.ScaleReconciliationRecord
import com.example.data.ScaleReconciliationStore
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
    val activeItems: List<ColdStorageItemEntity> = emptyList(),
    val filteredItems: List<ColdStorageItemEntity> = emptyList(),
    val totalFilteredKg: Double = 0.0,
    val totalFilteredOtherUnits: Int = 0,
    val activeRole: StaffRole = StaffRole.OPERADOR,
    val reconciliationProfiles: List<ReconciliationProfile> = emptyList(),
    val reconciliationRecords: List<ScaleReconciliationRecord> = emptyList(),
    val allMovements: List<StockMovementEntity> = emptyList(),
    val dueReconciliationCount: Int = 0,
    val feedbackMessage: String? = null,
    val streamError: String? = null
)

class InventoryViewModel(
    private val repository: ColdStorageRepository,
    private val scaleReconciliationStore: ScaleReconciliationStore? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedMeat = MutableStateFlow<MeatType?>(null)
    private val _selectedZone = MutableStateFlow<TemperatureZone?>(null)
    private val _selectedSupplier = MutableStateFlow<String?>(null)
    private val _urgencyFilter = MutableStateFlow(UrgencyFilter.ALL)
    private val _feedback = MutableStateFlow<String?>(null)

    private val defaultProfilesFlow = MutableStateFlow(ScaleReconciliationStore.defaultProfiles())
    private val profilesFlow: StateFlow<List<ReconciliationProfile>> =
        scaleReconciliationStore?.profiles ?: defaultProfilesFlow

    private val emptyRecordsFlow = MutableStateFlow<List<ScaleReconciliationRecord>>(emptyList())
    private val recordsFlow: StateFlow<List<ScaleReconciliationRecord>> =
        scaleReconciliationStore?.records ?: emptyRecordsFlow

    private val filterInputsFlow = combine(
        _searchQuery,
        _selectedMeat,
        _selectedZone,
        _selectedSupplier,
        _urgencyFilter
    ) { query, meat, zone, supplier, urgency ->
        FilterInputs(query, meat, zone, supplier, urgency)
    }

    private val reconciliationBundleFlow = combine(
        repository.allMovements,
        profilesFlow,
        recordsFlow
    ) { movements, profiles, records ->
        Triple(movements, profiles, records)
    }

    private data class FilterInputs(
        val query: String,
        val meat: MeatType?,
        val zone: TemperatureZone?,
        val supplier: String?,
        val urgency: UrgencyFilter
    )

    val uiState: StateFlow<InventoryUiState> = combine(
        repository.activeItemsFefo,
        repository.sessionState,
        filterInputsFlow,
        reconciliationBundleFlow,
        _feedback
    ) { items, session, filters, (movements, profiles, records), feedback ->
        val query = filters.query
        val meat = filters.meat
        val zone = filters.zone
        val supplier = filters.supplier
        val urgency = filters.urgency

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

        val now = System.currentTimeMillis()
        val dueReconciliationCount = profiles.count { profile ->
            val hasActiveKg = ScaleReconciliationEngine.filterMatchingLots(
                items,
                profile.scopeTypeEnum,
                profile.scopeFilter
            ).isNotEmpty()
            if (!hasActiveKg) {
                false
            } else {
                val st = profile.evaluateStatus(movements, now).status
                st == ReconciliationCadenceStatus.DUE || st == ReconciliationCadenceStatus.NEVER_CHECKED
            }
        }

        InventoryUiState(
            searchQuery = query,
            selectedMeatType = meat,
            selectedZone = zone,
            selectedSupplier = supplier,
            urgencyFilter = urgency,
            availableSuppliers = availableSuppliers,
            activeItems = items,
            filteredItems = filtered,
            totalFilteredKg = totalKg,
            totalFilteredOtherUnits = totalOtherUnits,
            activeRole = session.activeRole,
            reconciliationProfiles = profiles,
            reconciliationRecords = records,
            allMovements = movements,
            dueReconciliationCount = dueReconciliationCount,
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

    fun recordCutStockOutFefo(
        group: CutFefoGroup,
        movementType: MovementType,
        allocations: List<FefoLotAllocation>,
        reason: String
    ) {
        if (allocations.isEmpty()) return
        val requests = allocations.map { alloc ->
            BatchStockOutRequest(
                itemId = alloc.item.id,
                movementType = movementType,
                quantity = alloc.deductQuantity,
                reason = reason,
                updatedNotes = alloc.updatedNotes
            )
        }
        val totalDeducted = allocations.sumOf { it.deductQuantity }
        viewModelScope.launch {
            repository.recordBatchStockOut(requests).fold(
                onSuccess = { count ->
                    _feedback.value = if (count > 0) {
                        "Saída FEFO aplicada: -${DateUtils.formatWeightOrQty(totalDeducted, group.unit)} de ${group.cutName} em $count lote(s)."
                    } else {
                        "Não foi possível aplicar a saída FEFO."
                    }
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Erro ao registrar saída rápida FEFO.")
                }
            )
        }
    }

    fun applyFastStockClosure(plan: FastClosurePlan) {
        if (plan.requests.isEmpty()) return
        viewModelScope.launch {
            repository.recordBatchStockOut(plan.requests).fold(
                onSuccess = { count ->
                    _feedback.value = if (count > 0) {
                        val ptBr = java.util.Locale.forLanguageTag("pt-BR")
                        val qtyDesc = buildList {
                            if (plan.totalDeductedKg > 0.0) {
                                add("${String.format(ptBr, "%.2f", plan.totalDeductedKg)} kg")
                            }
                            if (plan.totalDeductedOtherUnits > 0) {
                                add("${plan.totalDeductedOtherUnits} pç/cx")
                            }
                        }.joinToString(" + ")
                        "Fechamento aplicado: $qtyDesc baixados em $count lote(s) (FEFO)."
                    } else {
                        "Nenhuma baixa aplicada no fechamento."
                    }
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Erro ao aplicar fechamento rápido.")
                }
            )
        }
    }

    fun applyScaleDifferencePlan(plan: ScaleDifferencePlan) {
        viewModelScope.launch {
            val ptBr = java.util.Locale.forLanguageTag("pt-BR")
            when {
                plan.isLossOrOutflow -> {
                    repository.recordBatchStockOut(plan.requests).fold(
                        onSuccess = { count ->
                            scaleReconciliationStore?.recordCheckResult(plan, count)
                            _feedback.value = if (count > 0) {
                                "Ajuste aplicado: -${String.format(ptBr, "%.2f", plan.differenceKg)} kg (${String.format(ptBr, "%.1f", plan.shrinkagePercent)}% quebra) em $count lote(s)."
                            } else {
                                "Nenhum lote alterado."
                            }
                        },
                        onFailure = { err ->
                            _feedback.value = friendlyFirestoreMessage(err, "Erro ao aplicar ajuste por diferença.")
                        }
                    )
                }
                plan.isSurplus && plan.surplusTargetLot != null -> {
                    val reason = "[Rotina:${plan.profileId}] Ajuste balança (${plan.profileName}): sobra +${String.format(ptBr, "%.2f", plan.surplusAddKg)} kg"
                    repository.recordPositiveStockAdjustment(plan.surplusTargetLot.id, plan.surplusAddKg, reason).fold(
                        onSuccess = { ok ->
                            if (ok) scaleReconciliationStore?.recordCheckResult(plan, 1)
                            _feedback.value = if (ok) {
                                "Ajuste positivo registrado: +${String.format(ptBr, "%.2f", plan.surplusAddKg)} kg na balança."
                            } else {
                                "Não foi possível aplicar o ajuste positivo."
                            }
                        },
                        onFailure = { err ->
                            _feedback.value = friendlyFirestoreMessage(err, "Erro ao registrar sobra na balança.")
                        }
                    )
                }
                else -> {
                    scaleReconciliationStore?.recordCheckResult(plan, 0)
                    _feedback.value = "Conferência registrada (${plan.profileName}): estoque 100% exato (0% quebra)."
                }
            }
        }
    }

    fun saveReconciliationProfile(
        id: String?,
        name: String,
        scopeType: ReconciliationScopeType,
        scopeFilter: String,
        intervalDays: Int,
        preferredMovementType: MovementType
    ) {
        val store = scaleReconciliationStore ?: return
        viewModelScope.launch {
            store.saveProfile(id, name, scopeType, scopeFilter, intervalDays, preferredMovementType).fold(
                onSuccess = { profile ->
                    _feedback.value = "Rotina salva: ${profile.name} (a cada ${profile.intervalDays}d)."
                },
                onFailure = { err ->
                    _feedback.value = err.localizedMessage ?: "Erro ao salvar rotina de balança."
                }
            )
        }
    }

    fun deleteReconciliationProfile(profileId: String) {
        val store = scaleReconciliationStore ?: return
        viewModelScope.launch {
            store.deleteProfile(profileId).fold(
                onSuccess = { _feedback.value = "Rotina removida." },
                onFailure = { err -> _feedback.value = err.localizedMessage ?: "Erro ao remover rotina." }
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
