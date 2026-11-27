package com.example.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BackupRestoreSummary
import com.example.data.BatchStockOutRequest
import com.example.data.ChamberTempLog
import com.example.data.ChamberTempLogStore
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.CutFefoGroup
import com.example.data.DateUtils
import com.example.data.ExpiryUrgency
import com.example.data.FastClosurePlan
import com.example.data.FefoLotAllocation
import com.example.data.LocalColdStorageBackupEnvelope
import com.example.data.LocalColdStorageRepository
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.ReceivingSessionSummary
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
import com.example.notifications.ExpiryNotificationManager
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
    val chamberTempLogs: List<ChamberTempLog> = emptyList(),
    val todayTempLogs: List<ChamberTempLog> = emptyList(),
    val latestTempLog: ChamberTempLog? = null,
    val backupSummary: BackupRestoreSummary? = null,
    val reconciliationProfiles: List<ReconciliationProfile> = emptyList(),
    val reconciliationRecords: List<ScaleReconciliationRecord> = emptyList(),
    val allMovements: List<StockMovementEntity> = emptyList(),
    val dueReconciliationCount: Int = 0,
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
    private val repository: ColdStorageRepository,
    private val chamberTempLogStore: ChamberTempLogStore? = null,
    private val scaleReconciliationStore: ScaleReconciliationStore? = null,
    private val appContext: Context? = null
) : ViewModel() {

    private val _feedback = MutableStateFlow<String?>(null)
    private val emptyTempLogsFlow = MutableStateFlow<List<ChamberTempLog>>(emptyList())
    private val tempLogsFlow: StateFlow<List<ChamberTempLog>> =
        chamberTempLogStore?.logs ?: emptyTempLogsFlow

    private val defaultProfilesFlow = MutableStateFlow(ScaleReconciliationStore.defaultProfiles())
    private val profilesFlow: StateFlow<List<ReconciliationProfile>> =
        scaleReconciliationStore?.profiles ?: defaultProfilesFlow

    private val emptyRecordsFlow = MutableStateFlow<List<ScaleReconciliationRecord>>(emptyList())
    private val recordsFlow: StateFlow<List<ScaleReconciliationRecord>> =
        scaleReconciliationStore?.records ?: emptyRecordsFlow

    private val emptyBackupTimeFlow = MutableStateFlow(0L)
    private val lastBackupTimeFlow: StateFlow<Long> =
        (repository as? LocalColdStorageRepository)?.lastBackupAtMillis ?: emptyBackupTimeFlow

    private val reconciliationCombinedFlow = combine(
        repository.allMovements,
        profilesFlow,
        recordsFlow
    ) { movements, profiles, records ->
        Triple(movements, profiles, records)
    }

    private val baseDataFlow = combine(
        repository.activeItemsFefo,
        repository.sessionSummaries,
        repository.sessionState
    ) { items, sessions, session ->
        Triple(items, sessions, session)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        baseDataFlow,
        tempLogsFlow,
        lastBackupTimeFlow,
        reconciliationCombinedFlow,
        _feedback
    ) { (items, sessions, session), tempLogs, lastBackupMillis, (movements, profiles, records), feedback ->
        appContext?.let { ExpiryNotificationManager.updateCachedLots(it, items) }

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

        val todayTempLogs = tempLogs.filter { DateUtils.isToday(it.recordedAtMillis) }

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

        val backupSummary = (repository as? LocalColdStorageRepository)?.snapshotCounts()
            ?.copy(
                temperatureLogCount = tempLogs.size,
                exportedAtMillis = lastBackupMillis
            )

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

        DashboardUiState(
            activeItems = items,
            priorityFefoItems = items.take(8),
            todaySessions = todaySessions,
            chamberSummaries = chamberSummaries,
            chamberTempLogs = tempLogs,
            todayTempLogs = todayTempLogs,
            latestTempLog = tempLogs.firstOrNull(),
            backupSummary = backupSummary,
            reconciliationProfiles = profiles,
            reconciliationRecords = records,
            allMovements = movements,
            dueReconciliationCount = dueReconciliationCount,
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

    fun recordChamberTemperature(
        chamberName: String,
        zone: TemperatureZone,
        measuredTempCelsius: Double,
        responsibleName: String,
        notes: String
    ) {
        val store = chamberTempLogStore ?: return
        viewModelScope.launch {
            store.recordRound(
                chamberName = chamberName,
                zone = zone,
                measuredTempCelsius = measuredTempCelsius,
                recordedByName = responsibleName.ifBlank {
                    uiState.value.sessionState.currentMember?.displayName.orEmpty()
                },
                notes = notes
            ).fold(
                onSuccess = { entry ->
                    _feedback.value = "Ronda registrada: ${entry.chamberName} (${DateUtils.formatTemp(entry.measuredTempCelsius)} • ${entry.complianceEnum.displayName})."
                },
                onFailure = { err ->
                    _feedback.value = err.localizedMessage ?: "Erro ao registrar temperatura da câmara."
                }
            )
        }
    }

    fun deleteChamberTemperature(logId: String) {
        val store = chamberTempLogStore ?: return
        viewModelScope.launch {
            store.deleteRound(logId).fold(
                onSuccess = { _feedback.value = "Registro térmico removido." },
                onFailure = { err -> _feedback.value = err.localizedMessage ?: "Erro ao remover registro térmico." }
            )
        }
    }

    suspend fun exportLocalBackupJson(): Result<String> {
        val localRepo = repository as? LocalColdStorageRepository
            ?: return Result.failure(IllegalStateException("Backup JSON disponível no Modo Local."))
        return localRepo.exportBackupJson()
    }

    fun parseBackupEnvelope(rawJson: String): Result<LocalColdStorageBackupEnvelope> {
        val localRepo = repository as? LocalColdStorageRepository
            ?: return Result.failure(IllegalStateException("Restauração JSON disponível no Modo Local."))
        return localRepo.parseBackupEnvelope(rawJson)
    }

    fun restoreBackupEnvelope(envelope: LocalColdStorageBackupEnvelope) {
        val localRepo = repository as? LocalColdStorageRepository ?: return
        viewModelScope.launch {
            localRepo.restoreFromBackupEnvelope(envelope).fold(
                onSuccess = { summary ->
                    _feedback.value = "Backup restaurado: ${summary.sessionCount} sessão(ões), ${summary.itemCount} corte(s) e ${summary.temperatureLogCount} ronda(s)."
                },
                onFailure = { err ->
                    _feedback.value = err.localizedMessage ?: "Não foi possível restaurar o backup JSON."
                }
            )
        }
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

    fun setFeedback(message: String) {
        _feedback.value = message
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}
