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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChamberTempLog(
    val id: String = "",
    val chamberName: String = TemperatureZone.RESFRIADO.defaultChamber,
    val zone: String = TemperatureZone.RESFRIADO.name,
    val measuredTempCelsius: Double = 2.0,
    val thermalStatus: String = ThermalCompliance.CONFORME.name,
    val recordedByName: String = "",
    val notes: String = "",
    val recordedAtMillis: Long = System.currentTimeMillis()
) {
    val zoneEnum: TemperatureZone
        get() = TemperatureZone.fromRaw(zone)

    val complianceEnum: ThermalCompliance
        get() = ThermalCompliance.evaluate(zoneEnum, measuredTempCelsius)
}

class ChamberTempLogStore(context: Context) {

    private val appContext = context.applicationContext
    private val storageFile = File(appContext.filesDir, "chamber_temp_logs.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()

    private val _logs = MutableStateFlow(loadInitialLogs())
    val logs: StateFlow<List<ChamberTempLog>> = _logs.asStateFlow()

    fun currentSnapshot(): List<ChamberTempLog> = _logs.value

    suspend fun recordRound(
        chamberName: String,
        zone: TemperatureZone,
        measuredTempCelsius: Double,
        recordedByName: String,
        notes: String = "",
        recordedAtMillis: Long = System.currentTimeMillis()
    ): Result<ChamberTempLog> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val cleanChamber = chamberName.trim().ifBlank { zone.defaultChamber }.take(80)
                val safeTemp = measuredTempCelsius.coerceIn(-50.0, 50.0)
                val compliance = ThermalCompliance.evaluate(zone, safeTemp)
                val entry = ChamberTempLog(
                    id = UUID.randomUUID().toString().replace("-", ""),
                    chamberName = cleanChamber,
                    zone = zone.name,
                    measuredTempCelsius = safeTemp,
                    thermalStatus = compliance.name,
                    recordedByName = recordedByName.trim().ifBlank { "Operador" }.take(80),
                    notes = notes.trim().take(300),
                    recordedAtMillis = recordedAtMillis.coerceAtLeast(1L)
                )
                val updated = (listOf(entry) + _logs.value)
                    .distinctBy { it.id }
                    .sortedByDescending { it.recordedAtMillis }
                    .take(MAX_STORED_LOGS)
                persistLocked(updated)
                entry
            }
        }
    }

    suspend fun deleteRound(logId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val updated = _logs.value.filterNot { it.id == logId }
                persistLocked(updated)
            }
        }
    }

    suspend fun replaceAll(logs: List<ChamberTempLog>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sanitized = logs.mapNotNull { raw ->
                val zone = TemperatureZone.fromRaw(raw.zone)
                val chamber = raw.chamberName.trim().ifBlank { zone.defaultChamber }.take(80)
                val temp = raw.measuredTempCelsius.coerceIn(-50.0, 50.0)
                val compliance = ThermalCompliance.evaluate(zone, temp)
                ChamberTempLog(
                    id = raw.id.ifBlank { UUID.randomUUID().toString().replace("-", "") },
                    chamberName = chamber,
                    zone = zone.name,
                    measuredTempCelsius = temp,
                    thermalStatus = compliance.name,
                    recordedByName = raw.recordedByName.trim().take(80),
                    notes = raw.notes.trim().take(300),
                    recordedAtMillis = raw.recordedAtMillis.coerceAtLeast(1L)
                )
            }
                .distinctBy { it.id }
                .sortedByDescending { it.recordedAtMillis }
                .take(MAX_STORED_LOGS)
            persistLocked(sanitized)
        }
    }

    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            persistLocked(emptyList())
        }
    }

    private fun loadInitialLogs(): List<ChamberTempLog> {
        return try {
            if (storageFile.exists() && storageFile.length() > 0L) {
                json.decodeFromString(
                    ListSerializer(ChamberTempLog.serializer()),
                    storageFile.readText()
                ).sortedByDescending { it.recordedAtMillis }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w("ChamberTempLogStore", "Failed to parse chamber temperature logs", e)
            emptyList()
        }
    }

    private fun persistLocked(logs: List<ChamberTempLog>) {
        _logs.value = logs
        try {
            storageFile.writeText(
                json.encodeToString(ListSerializer(ChamberTempLog.serializer()), logs)
            )
        } catch (e: Exception) {
            Log.w("ChamberTempLogStore", "Failed to write chamber temperature logs", e)
        }
    }

    companion object {
        private const val MAX_STORED_LOGS = 500
    }
}
