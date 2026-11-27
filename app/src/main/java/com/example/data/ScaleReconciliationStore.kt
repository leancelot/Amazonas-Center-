package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class ScaleReconciliationStateDto(
    val profiles: List<ReconciliationProfile> = emptyList(),
    val records: List<ScaleReconciliationRecord> = emptyList()
)

class ScaleReconciliationStore(context: Context) {

    private val storageFile = File(context.applicationContext.filesDir, "scale_reconciliations.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<ReconciliationProfile>>(emptyList())
    val profiles: StateFlow<List<ReconciliationProfile>> = _profiles.asStateFlow()

    private val _records = MutableStateFlow<List<ScaleReconciliationRecord>>(emptyList())
    val records: StateFlow<List<ScaleReconciliationRecord>> = _records.asStateFlow()

    init {
        val initial = loadInitialState()
        _profiles.value = initial.profiles
        _records.value = initial.records.sortedByDescending { it.recordedAtMillis }
    }

    suspend fun saveProfile(
        id: String?,
        name: String,
        scopeType: ReconciliationScopeType,
        scopeFilter: String,
        intervalDays: Int,
        preferredMovementType: MovementType = MovementType.PERDA_APARA
    ): Result<ReconciliationProfile> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val cleanName = name.trim().ifBlank { "Rotina de Balança" }.take(60)
                val cleanFilter = if (scopeType == ReconciliationScopeType.ALL_KG) "" else scopeFilter.trim().take(80)
                val safeDays = intervalDays.coerceIn(1, 90)
                val existing = id?.let { targetId -> _profiles.value.firstOrNull { it.id == targetId } }
                val profile = ReconciliationProfile(
                    id = existing?.id ?: ("rot_" + UUID.randomUUID().toString().replace("-", "").take(12)),
                    name = cleanName,
                    scopeType = scopeType.name,
                    scopeFilter = cleanFilter,
                    intervalDays = safeDays,
                    preferredMovementType = preferredMovementType.name,
                    lastCheckedAtMillis = existing?.lastCheckedAtMillis ?: 0L,
                    lastExpectedKg = existing?.lastExpectedKg ?: 0.0,
                    lastScaleWeightKg = existing?.lastScaleWeightKg ?: 0.0,
                    lastDiffKg = existing?.lastDiffKg ?: 0.0,
                    lastShrinkagePercent = existing?.lastShrinkagePercent ?: 0.0
                )
                val nextProfiles = if (existing != null) {
                    _profiles.value.map { if (it.id == profile.id) profile else it }
                } else {
                    _profiles.value + profile
                }
                persistLocked(nextProfiles, _records.value)
                profile
            }
        }
    }

    suspend fun deleteProfile(profileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val nextProfiles = _profiles.value.filterNot { it.id == profileId }
                persistLocked(nextProfiles, _records.value)
            }
        }
    }

    suspend fun recordCheckResult(
        plan: ScaleDifferencePlan,
        affectedLotCount: Int,
        recordedAtMillis: Long = System.currentTimeMillis()
    ): Result<ScaleReconciliationRecord> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val entry = ScaleReconciliationRecord(
                    id = UUID.randomUUID().toString().replace("-", "").take(16),
                    profileId = plan.profileId,
                    profileName = plan.profileName,
                    scopeType = plan.scopeType.name,
                    scopeFilter = plan.scopeFilter,
                    expectedKg = plan.expectedKg,
                    scaleWeightKg = plan.scaleWeightKg,
                    differenceKg = plan.differenceKg,
                    shrinkagePercent = plan.shrinkagePercent,
                    estimatedLossBrl = plan.estimatedLossBrl,
                    movementType = plan.movementType.name,
                    affectedLotCount = affectedLotCount,
                    notes = plan.note.take(240),
                    recordedAtMillis = recordedAtMillis
                )
                val nextRecords = (listOf(entry) + _records.value)
                    .distinctBy { it.id }
                    .sortedByDescending { it.recordedAtMillis }
                    .take(MAX_STORED_RECORDS)
                val nextProfiles = _profiles.value.map { profile ->
                    if (profile.id == plan.profileId) {
                        profile.copy(
                            lastCheckedAtMillis = recordedAtMillis,
                            lastExpectedKg = plan.expectedKg,
                            lastScaleWeightKg = plan.scaleWeightKg,
                            lastDiffKg = plan.differenceKg,
                            lastShrinkagePercent = plan.shrinkagePercent
                        )
                    } else {
                        profile
                    }
                }
                persistLocked(nextProfiles, nextRecords)
                entry
            }
        }
    }

    suspend fun deleteRecord(recordId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val nextRecords = _records.value.filterNot { it.id == recordId }
                persistLocked(_profiles.value, nextRecords)
            }
        }
    }

    suspend fun clearRecordsOnly(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val resetProfiles = _profiles.value.map {
                it.copy(
                    lastCheckedAtMillis = 0L,
                    lastExpectedKg = 0.0,
                    lastScaleWeightKg = 0.0,
                    lastDiffKg = 0.0,
                    lastShrinkagePercent = 0.0
                )
            }
            persistLocked(resetProfiles, emptyList())
        }
    }

    private fun loadInitialState(): ScaleReconciliationStateDto {
        return try {
            if (storageFile.exists() && storageFile.length() > 0L) {
                val decoded = json.decodeFromString(
                    ScaleReconciliationStateDto.serializer(),
                    storageFile.readText()
                )
                if (decoded.profiles.isEmpty() && decoded.records.isEmpty()) {
                    ScaleReconciliationStateDto(profiles = defaultProfiles()).also {
                        writeFileSafely(it)
                    }
                } else {
                    decoded
                }
            } else {
                ScaleReconciliationStateDto(profiles = defaultProfiles()).also {
                    writeFileSafely(it)
                }
            }
        } catch (e: Exception) {
            Log.w("ScaleReconcileStore", "Failed to load scale reconciliations, restoring defaults", e)
            ScaleReconciliationStateDto(profiles = defaultProfiles())
        }
    }

    private fun persistLocked(
        profiles: List<ReconciliationProfile>,
        records: List<ScaleReconciliationRecord>
    ) {
        _profiles.value = profiles
        _records.value = records
        writeFileSafely(ScaleReconciliationStateDto(profiles = profiles, records = records))
    }

    private fun writeFileSafely(dto: ScaleReconciliationStateDto) {
        try {
            storageFile.writeText(
                json.encodeToString(ScaleReconciliationStateDto.serializer(), dto)
            )
        } catch (e: Exception) {
            Log.w("ScaleReconcileStore", "Failed to write scale reconciliations", e)
        }
    }

    companion object {
        private const val MAX_STORED_RECORDS = 300

        fun defaultProfiles(): List<ReconciliationProfile> = listOf(
            ReconciliationProfile(
                id = "friesul_semanal",
                name = "Pedidos Friesul",
                scopeType = ReconciliationScopeType.SUPPLIER.name,
                scopeFilter = "Friesul",
                intervalDays = 7,
                preferredMovementType = MovementType.PERDA_APARA.name
            ),
            ReconciliationProfile(
                id = "bairro_fatima_diario",
                name = "Unidade Bairro de Fátima",
                scopeType = ReconciliationScopeType.CHAMBER.name,
                scopeFilter = "Bairro de Fátima",
                intervalDays = 1,
                preferredMovementType = MovementType.PERDA_APARA.name
            ),
            ReconciliationProfile(
                id = "camara_geral_semanal",
                name = "Câmara Geral (Kg)",
                scopeType = ReconciliationScopeType.ALL_KG.name,
                scopeFilter = "",
                intervalDays = 7,
                preferredMovementType = MovementType.PERDA_APARA.name
            )
        )
    }
}
