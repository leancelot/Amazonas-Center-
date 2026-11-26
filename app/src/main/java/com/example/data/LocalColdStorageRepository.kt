package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class LocalColdStorageBackupEnvelope(
    val schemaVersion: Int = 1,
    val appId: String = "com.aistudio.frigstock.krmvqt",
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val activeRole: String = StaffRole.GERENTE.name,
    val sessions: List<LocalSessionDto> = emptyList(),
    val items: List<LocalItemDto> = emptyList(),
    val movements: List<LocalMovementDto> = emptyList(),
    val temperatureLogs: List<ChamberTempLog> = emptyList()
)

data class BackupRestoreSummary(
    val sessionCount: Int,
    val itemCount: Int,
    val movementCount: Int,
    val temperatureLogCount: Int,
    val exportedAtMillis: Long
)

@Serializable
data class LocalSessionDto(
    val id: String = "",
    val invoiceNumber: String = "",
    val accessKey: String = "",
    val supplierName: String = "",
    val supplierCnpj: String = "",
    val receivedAtMillis: Long = 0L,
    val measuredTempCelsius: Double = 2.0,
    val targetZone: String = TemperatureZone.RESFRIADO.name,
    val thermalStatus: String = ThermalCompliance.CONFORME.name,
    val responsibleName: String = "",
    val sourceType: String = InvoiceSourceType.MANUAL.name,
    val notes: String = "",
    val itemCount: Int = 0,
    val totalKg: Double = 0.0
)

@Serializable
data class LocalItemDto(
    val id: String = "",
    val sessionId: String = "",
    val meatType: String = MeatType.BOVINA.name,
    val cutName: String = "",
    val temperatureZone: String = TemperatureZone.RESFRIADO.name,
    val measuredTempCelsius: Double = 2.0,
    val chamberName: String = TemperatureZone.RESFRIADO.defaultChamber,
    val batchCode: String = "",
    val sifCode: String = "",
    val initialQuantity: Double = 0.0,
    val currentQuantity: Double = 0.0,
    val unit: String = MeasurementUnit.KG.name,
    val unitCostBrl: Double = 0.0,
    val expiryDateEpochDay: Long = 0L,
    val receivedAtMillis: Long = 0L,
    val supplierName: String = "",
    val invoiceNumber: String = "",
    val notes: String = ""
)

@Serializable
data class LocalMovementDto(
    val id: String = "",
    val createdByName: String = "Teste Local",
    val itemId: String = "",
    val cutNameSnapshot: String = "",
    val meatTypeSnapshot: String = MeatType.BOVINA.name,
    val unitSnapshot: String = MeasurementUnit.KG.name,
    val movementType: String = MovementType.SAIDA_BALCAO.name,
    val quantity: Double = 0.0,
    val estimatedLossBrl: Double = 0.0,
    val reason: String = "",
    val createdAtMillis: Long = 0L
)

@Serializable
private data class LocalColdStorageSnapshot(
    val activeRole: String = StaffRole.GERENTE.name,
    val sessions: List<LocalSessionDto> = emptyList(),
    val items: List<LocalItemDto> = emptyList(),
    val movements: List<LocalMovementDto> = emptyList()
)

class LocalColdStorageRepository(
    context: Context,
    private val chamberTempLogStore: ChamberTempLogStore? = null
) : ColdStorageRepository {

    private val appContext = context.applicationContext
    private val storageFile = File(appContext.filesDir, "local_frigstock_test_data.json")
    private val backupPrefs = appContext.getSharedPreferences(BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val backupJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()

    private val _snapshot = MutableStateFlow(loadInitialSnapshot())
    private val _lastBackupAtMillis = MutableStateFlow(backupPrefs.getLong(KEY_LAST_BACKUP_AT, 0L))
    val lastBackupAtMillis: kotlinx.coroutines.flow.StateFlow<Long> = _lastBackupAtMillis

    override val sessionState: Flow<ShopSessionState> = _snapshot.map { snap ->
        val role = StaffRole.fromRaw(snap.activeRole)
        ShopSessionState(
            isLoading = false,
            shopInfo = ShopInfo(
                id = LOCAL_SHOP_ID,
                name = LOCAL_SHOP_NAME,
                ownerId = LOCAL_USER_ID,
                memberUids = listOf(LOCAL_USER_ID)
            ),
            currentMember = ShopMember(
                id = LOCAL_USER_ID,
                userId = LOCAL_USER_ID,
                shopId = LOCAL_SHOP_ID,
                shopOwnerId = LOCAL_USER_ID,
                managerUids = listOf(LOCAL_USER_ID),
                email = LOCAL_USER_EMAIL,
                displayName = LOCAL_USER_NAME,
                role = role.name,
                active = true
            ),
            errorMessage = null
        )
    }

    override val allItems: Flow<List<ColdStorageItemEntity>> = _snapshot.map { snap ->
        snap.items
            .map { it.toEntity() }
            .sortedBy { it.expiryDateEpochDay }
    }

    override val activeItemsFefo: Flow<List<ColdStorageItemEntity>> = allItems.map { items ->
        items.filter { it.currentQuantity > 0.001 }
    }

    override val sessionSummaries: Flow<List<ReceivingSessionSummary>> = _snapshot.map { snap ->
        snap.sessions
            .distinctBy { it.id }
            .sortedByDescending { it.receivedAtMillis }
            .map {
                ReceivingSessionSummary(
                    id = it.id,
                    invoiceNumber = it.invoiceNumber,
                    accessKey = it.accessKey,
                    supplierName = it.supplierName,
                    supplierCnpj = it.supplierCnpj,
                    receivedAtMillis = it.receivedAtMillis,
                    measuredTempCelsius = it.measuredTempCelsius,
                    targetZone = it.targetZone,
                    thermalStatus = it.thermalStatus,
                    sourceType = it.sourceType,
                    responsibleName = it.responsibleName,
                    itemCount = it.itemCount,
                    totalKg = it.totalKg,
                    notes = it.notes
                )
            }
    }

    override val allMovements: Flow<List<StockMovementEntity>> = _snapshot.map { snap ->
        snap.movements
            .sortedByDescending { it.createdAtMillis }
            .map { it.toEntity() }
    }

    override val teamMembers: Flow<List<ShopMember>> = _snapshot.map { snap ->
        listOf(
            ShopMember(
                id = LOCAL_USER_ID,
                userId = LOCAL_USER_ID,
                shopId = LOCAL_SHOP_ID,
                shopOwnerId = LOCAL_USER_ID,
                managerUids = listOf(LOCAL_USER_ID),
                email = LOCAL_USER_EMAIL,
                displayName = LOCAL_USER_NAME,
                role = StaffRole.fromRaw(snap.activeRole).name,
                active = true
            )
        )
    }

    override val teamInvites: Flow<List<TeamInvite>> = MutableStateFlow(emptyList())

    override fun observeItemsForSession(sessionId: String): Flow<List<ColdStorageItemEntity>> =
        allItems.map { items -> items.filter { it.sessionId == sessionId } }

    override fun observeMovementsForItem(itemId: String): Flow<List<StockMovementEntity>> =
        allMovements.map { movements -> movements.filter { it.itemId == itemId } }

    override suspend fun ensureSessionReady(): Result<Unit> = Result.success(Unit)

    override suspend fun saveReceivingSession(
        session: ReceivingSessionEntity,
        items: List<ColdStorageItemEntity>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val current = _snapshot.value
                val sessionId = newId()
                val totalKg = items.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.initialQuantity }
                val receivedMillis = session.receivedAtMillis.coerceAtLeast(1L)

                val sessionDto = LocalSessionDto(
                    id = sessionId,
                    invoiceNumber = session.invoiceNumber.take(60),
                    accessKey = session.accessKey.take(60),
                    supplierName = session.supplierName.ifBlank { "Fornecedor não informado" }.take(140),
                    supplierCnpj = session.supplierCnpj.take(30),
                    receivedAtMillis = receivedMillis,
                    measuredTempCelsius = session.measuredTempCelsius.coerceIn(-50.0, 50.0),
                    targetZone = session.targetZone,
                    thermalStatus = session.thermalStatus,
                    responsibleName = session.responsibleName.take(100),
                    sourceType = session.sourceType,
                    notes = session.notes.take(500),
                    itemCount = items.size,
                    totalKg = totalKg.coerceAtLeast(0.0)
                )

                val newItems = mutableListOf<LocalItemDto>()
                val newMovements = mutableListOf<LocalMovementDto>()

                items.forEach { item ->
                    val itemId = newId()
                    val safeQty = item.initialQuantity.coerceAtLeast(0.01)
                    val safeCurrent = item.currentQuantity.coerceAtLeast(0.01)
                    val safeCut = item.cutName.ifBlank { "Corte Diverso" }.take(120)

                    newItems += LocalItemDto(
                        id = itemId,
                        sessionId = sessionId,
                        meatType = item.meatType,
                        cutName = safeCut,
                        temperatureZone = item.temperatureZone,
                        measuredTempCelsius = item.measuredTempCelsius.coerceIn(-50.0, 50.0),
                        chamberName = item.chamberName.ifBlank { item.zoneEnum.defaultChamber }.take(80),
                        batchCode = item.batchCode.take(60),
                        sifCode = item.sifCode.take(40),
                        initialQuantity = safeQty,
                        currentQuantity = safeCurrent,
                        unit = item.unit,
                        unitCostBrl = item.unitCostBrl.coerceAtLeast(0.0),
                        expiryDateEpochDay = item.expiryDateEpochDay,
                        receivedAtMillis = item.receivedAtMillis.coerceAtLeast(1L),
                        supplierName = item.supplierName.take(140),
                        invoiceNumber = item.invoiceNumber.take(60),
                        notes = item.notes.take(500)
                    )

                    newMovements += LocalMovementDto(
                        id = newId(),
                        createdByName = LOCAL_USER_NAME,
                        itemId = itemId,
                        cutNameSnapshot = safeCut,
                        meatTypeSnapshot = item.meatType,
                        unitSnapshot = item.unit,
                        movementType = MovementType.ENTRADA.name,
                        quantity = safeQty,
                        estimatedLossBrl = 0.0,
                        reason = (
                            if (session.invoiceNumber.isNotBlank()) "Recebimento NF ${session.invoiceNumber}"
                            else "Recebimento ${session.supplierName}".trim()
                            ).take(300),
                        createdAtMillis = receivedMillis
                    )
                }

                val updated = current.copy(
                    sessions = current.sessions + sessionDto,
                    items = current.items + newItems,
                    movements = current.movements + newMovements
                )
                persistLocked(updated)
                sessionId
            }
        }
    }

    override suspend fun recordStockOut(
        itemId: String,
        movementType: MovementType,
        quantity: Double,
        reason: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val current = _snapshot.value
                val target = current.items.firstOrNull { it.id == itemId } ?: return@withLock false
                val safeDeduct = quantity.coerceIn(0.0, target.currentQuantity)
                if (safeDeduct <= 0.0) return@withLock false

                val remaining = (target.currentQuantity - safeDeduct).let { if (it < 0.001) 0.0 else it }
                val estimatedLoss = if (movementType.isLoss && target.unitCostBrl > 0.0) {
                    safeDeduct * target.unitCostBrl
                } else {
                    0.0
                }

                val updatedItems = current.items.map {
                    if (it.id == itemId) it.copy(currentQuantity = remaining) else it
                }
                val newMovement = LocalMovementDto(
                    id = newId(),
                    createdByName = LOCAL_USER_NAME,
                    itemId = itemId,
                    cutNameSnapshot = target.cutName.ifBlank { "Corte Diverso" }.take(120),
                    meatTypeSnapshot = target.meatType,
                    unitSnapshot = target.unit,
                    movementType = movementType.name,
                    quantity = safeDeduct,
                    estimatedLossBrl = estimatedLoss.coerceAtLeast(0.0),
                    reason = reason.take(300),
                    createdAtMillis = System.currentTimeMillis()
                )
                persistLocked(
                    current.copy(
                        items = updatedItems,
                        movements = current.movements + newMovement
                    )
                )
                true
            }
        }
    }

    override suspend fun transferChamber(
        itemId: String,
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val current = _snapshot.value
                val updatedItems = current.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(
                            chamberName = chamberName.ifBlank { zone.defaultChamber }.take(80),
                            temperatureZone = zone.name,
                            measuredTempCelsius = tempCelsius.coerceIn(-50.0, 50.0)
                        )
                    } else {
                        item
                    }
                }
                persistLocked(current.copy(items = updatedItems))
            }
        }
    }

    override suspend fun updateExpiry(itemId: String, expiryEpochDay: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val current = _snapshot.value
                val updatedItems = current.items.map { item ->
                    if (item.id == itemId) item.copy(expiryDateEpochDay = expiryEpochDay) else item
                }
                persistLocked(current.copy(items = updatedItems))
            }
        }
    }

    override suspend fun deleteItem(itemId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val current = _snapshot.value
                persistLocked(current.copy(items = current.items.filterNot { it.id == itemId }))
            }
        }
    }

    override suspend fun deleteSession(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val current = _snapshot.value
                persistLocked(current.copy(sessions = current.sessions.filterNot { it.id == sessionId }))
            }
        }
    }

    override suspend fun inviteCollaborator(email: String, role: StaffRole): Result<Unit> =
        Result.failure(
            IllegalStateException("Convites de equipe exigem login com Google para sincronizar na nuvem.")
        )

    override suspend fun updateMemberRole(memberUid: String, newRole: StaffRole, active: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                mutex.withLock {
                    val current = _snapshot.value
                    persistLocked(current.copy(activeRole = newRole.name))
                }
            }
        }

    override suspend fun revokeInvite(inviteId: String): Result<Unit> = Result.success(Unit)

    suspend fun clearAllLocalData() = withContext(Dispatchers.IO) {
        mutex.withLock {
            persistLocked(LocalColdStorageSnapshot(activeRole = _snapshot.value.activeRole))
        }
        chamberTempLogStore?.clearAll()
    }

    suspend fun exportBackupJson(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val snap = mutex.withLock { _snapshot.value }
            val tempLogs = chamberTempLogStore?.currentSnapshot().orEmpty()
            val now = System.currentTimeMillis()
            val envelope = LocalColdStorageBackupEnvelope(
                schemaVersion = 1,
                appId = appContext.packageName,
                exportedAtMillis = now,
                activeRole = snap.activeRole,
                sessions = snap.sessions,
                items = snap.items,
                movements = snap.movements,
                temperatureLogs = tempLogs
            )
            val encoded = backupJson.encodeToString(LocalColdStorageBackupEnvelope.serializer(), envelope)
            recordBackupTimestamp(now)
            encoded
        }
    }

    fun parseBackupEnvelope(rawJson: String): Result<LocalColdStorageBackupEnvelope> {
        return runCatching {
            val trimmed = rawJson.trim()
            require(trimmed.startsWith("{")) {
                "Arquivo inválido: o backup deve estar em formato JSON."
            }
            val parsed = json.decodeFromString(LocalColdStorageBackupEnvelope.serializer(), trimmed)
            val hasAnyRecognizedField = parsed.sessions.isNotEmpty() ||
                parsed.items.isNotEmpty() ||
                parsed.movements.isNotEmpty() ||
                parsed.temperatureLogs.isNotEmpty() ||
                trimmed.contains("\"sessions\"") ||
                trimmed.contains("\"items\"")
            require(hasAnyRecognizedField) {
                "Este arquivo JSON não contém dados válidos do Amazonas Center."
            }
            parsed
        }
    }

    suspend fun restoreFromBackupEnvelope(envelope: LocalColdStorageBackupEnvelope): Result<BackupRestoreSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeSessions = envelope.sessions
                    .filter { it.id.isNotBlank() || it.supplierName.isNotBlank() || it.invoiceNumber.isNotBlank() }
                    .map { s ->
                        s.copy(
                            id = s.id.ifBlank { newId() },
                            measuredTempCelsius = s.measuredTempCelsius.coerceIn(-50.0, 50.0),
                            targetZone = TemperatureZone.fromRaw(s.targetZone).name,
                            sourceType = InvoiceSourceType.fromRaw(s.sourceType).name,
                            itemCount = s.itemCount.coerceAtLeast(0),
                            totalKg = s.totalKg.coerceAtLeast(0.0)
                        )
                    }
                    .distinctBy { it.id }

                val safeItems = envelope.items
                    .filter { it.cutName.isNotBlank() || it.id.isNotBlank() }
                    .map { item ->
                        val zone = TemperatureZone.fromRaw(item.temperatureZone)
                        item.copy(
                            id = item.id.ifBlank { newId() },
                            meatType = MeatType.fromRaw(item.meatType).name,
                            cutName = item.cutName.ifBlank { "Corte Diverso" }.take(120),
                            temperatureZone = zone.name,
                            measuredTempCelsius = item.measuredTempCelsius.coerceIn(-50.0, 50.0),
                            chamberName = item.chamberName.ifBlank { zone.defaultChamber }.take(80),
                            initialQuantity = item.initialQuantity.coerceAtLeast(0.0),
                            currentQuantity = item.currentQuantity.coerceAtLeast(0.0),
                            unit = MeasurementUnit.fromRaw(item.unit).name,
                            unitCostBrl = item.unitCostBrl.coerceAtLeast(0.0)
                        )
                    }
                    .distinctBy { it.id }

                val safeMovements = envelope.movements
                    .filter { it.itemId.isNotBlank() || it.cutNameSnapshot.isNotBlank() }
                    .map { mov ->
                        mov.copy(
                            id = mov.id.ifBlank { newId() },
                            meatTypeSnapshot = MeatType.fromRaw(mov.meatTypeSnapshot).name,
                            unitSnapshot = MeasurementUnit.fromRaw(mov.unitSnapshot).name,
                            movementType = MovementType.fromRaw(mov.movementType).name,
                            quantity = mov.quantity.coerceAtLeast(0.0),
                            estimatedLossBrl = mov.estimatedLossBrl.coerceAtLeast(0.0)
                        )
                    }
                    .distinctBy { it.id }

                val restoredSnapshot = LocalColdStorageSnapshot(
                    activeRole = StaffRole.fromRaw(envelope.activeRole).name,
                    sessions = safeSessions,
                    items = safeItems,
                    movements = safeMovements
                )

                mutex.withLock {
                    persistLocked(restoredSnapshot)
                }
                chamberTempLogStore?.replaceAll(envelope.temperatureLogs)
                recordBackupTimestamp(System.currentTimeMillis())

                BackupRestoreSummary(
                    sessionCount = safeSessions.size,
                    itemCount = safeItems.size,
                    movementCount = safeMovements.size,
                    temperatureLogCount = envelope.temperatureLogs.size,
                    exportedAtMillis = envelope.exportedAtMillis
                )
            }
        }

    fun snapshotCounts(): BackupRestoreSummary {
        val snap = _snapshot.value
        val tempCount = chamberTempLogStore?.currentSnapshot()?.size ?: 0
        return BackupRestoreSummary(
            sessionCount = snap.sessions.size,
            itemCount = snap.items.size,
            movementCount = snap.movements.size,
            temperatureLogCount = tempCount,
            exportedAtMillis = _lastBackupAtMillis.value
        )
    }

    private fun recordBackupTimestamp(millis: Long) {
        _lastBackupAtMillis.value = millis
        backupPrefs.edit().putLong(KEY_LAST_BACKUP_AT, millis).apply()
    }

    private fun loadInitialSnapshot(): LocalColdStorageSnapshot {
        return try {
            if (storageFile.exists() && storageFile.length() > 0L) {
                json.decodeFromString<LocalColdStorageSnapshot>(storageFile.readText())
            } else {
                LocalColdStorageSnapshot()
            }
        } catch (e: Exception) {
            Log.w("LocalRepo", "Failed to parse local snapshot; resetting empty store", e)
            LocalColdStorageSnapshot()
        }
    }

    private fun persistLocked(snapshot: LocalColdStorageSnapshot) {
        _snapshot.value = snapshot
        try {
            storageFile.writeText(json.encodeToString(LocalColdStorageSnapshot.serializer(), snapshot))
        } catch (e: Exception) {
            Log.w("LocalRepo", "Failed to write local test snapshot", e)
        }
    }

    private fun LocalItemDto.toEntity(): ColdStorageItemEntity = ColdStorageItemEntity(
        id = id,
        shopId = LOCAL_SHOP_ID,
        memberUids = listOf(LOCAL_USER_ID),
        createdByUid = LOCAL_USER_ID,
        sessionId = sessionId,
        meatType = meatType,
        cutName = cutName,
        temperatureZone = temperatureZone,
        measuredTempCelsius = measuredTempCelsius,
        chamberName = chamberName,
        batchCode = batchCode,
        sifCode = sifCode,
        initialQuantity = initialQuantity,
        currentQuantity = currentQuantity,
        unit = unit,
        unitCostBrl = unitCostBrl,
        expiryDateEpochDay = expiryDateEpochDay,
        receivedAtMillis = receivedAtMillis,
        supplierName = supplierName,
        invoiceNumber = invoiceNumber,
        notes = notes
    )

    private fun LocalMovementDto.toEntity(): StockMovementEntity = StockMovementEntity(
        id = id,
        shopId = LOCAL_SHOP_ID,
        memberUids = listOf(LOCAL_USER_ID),
        createdByUid = LOCAL_USER_ID,
        createdByName = createdByName,
        itemId = itemId,
        cutNameSnapshot = cutNameSnapshot,
        meatTypeSnapshot = meatTypeSnapshot,
        unitSnapshot = unitSnapshot,
        movementType = movementType,
        quantity = quantity,
        estimatedLossBrl = estimatedLossBrl,
        reason = reason,
        createdAtMillis = createdAtMillis
    )

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        private const val LOCAL_SHOP_ID = "local_test_shop"
        private const val LOCAL_SHOP_NAME = "Amazonas Center (Local)"
        private const val LOCAL_USER_ID = "local_tester"
        private const val LOCAL_USER_EMAIL = "teste.local@aparelho"
        private const val LOCAL_USER_NAME = "Teste Local"
        private const val BACKUP_PREFS_NAME = "frigstock_local_backup_prefs"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at_millis"
    }
}
