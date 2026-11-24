package com.example.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "receiving_sessions")
data class ReceivingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNumber: String,
    val accessKey: String = "",
    val supplierName: String,
    val supplierCnpj: String = "",
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val measuredTempCelsius: Double,
    val targetZone: String,
    val thermalStatus: String,
    val responsibleName: String = "",
    val sourceType: String,
    val notes: String = ""
)

@Entity(
    tableName = "cold_storage_items",
    foreignKeys = [
        ForeignKey(
            entity = ReceivingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["expiryDateEpochDay"]),
        Index(value = ["meatType"])
    ]
)
data class ColdStorageItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val meatType: String,
    val cutName: String,
    val temperatureZone: String,
    val measuredTempCelsius: Double,
    val chamberName: String,
    val batchCode: String = "",
    val sifCode: String = "",
    val initialQuantity: Double,
    val currentQuantity: Double,
    val unit: String,
    val unitCostBrl: Double = 0.0,
    val expiryDateEpochDay: Long,
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val supplierName: String = "",
    val invoiceNumber: String = "",
    val notes: String = ""
) {
    val meatTypeEnum: MeatType get() = MeatType.fromRaw(meatType)
    val zoneEnum: TemperatureZone get() = TemperatureZone.fromRaw(temperatureZone)
    val unitEnum: MeasurementUnit get() = MeasurementUnit.fromRaw(unit)
    val urgency: ExpiryUrgency get() = ExpiryUrgency.fromEpochDay(expiryDateEpochDay)
    val thermalCompliance: ThermalCompliance
        get() = ThermalCompliance.evaluate(zoneEnum, measuredTempCelsius)
}

@Entity(
    tableName = "stock_movements",
    indices = [
        Index(value = ["itemId"]),
        Index(value = ["createdAtMillis"]),
        Index(value = ["movementType"])
    ]
)
data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val cutNameSnapshot: String,
    val meatTypeSnapshot: String,
    val unitSnapshot: String,
    val movementType: String,
    val quantity: Double,
    val estimatedLossBrl: Double = 0.0,
    val reason: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    val movementTypeEnum: MovementType get() = MovementType.fromRaw(movementType)
    val meatTypeEnum: MeatType get() = MeatType.fromRaw(meatTypeSnapshot)
    val unitEnum: MeasurementUnit get() = MeasurementUnit.fromRaw(unitSnapshot)
}

data class ReceivingSessionSummary(
    val id: Long,
    val invoiceNumber: String,
    val supplierName: String,
    val receivedAtMillis: Long,
    val measuredTempCelsius: Double,
    val targetZone: String,
    val thermalStatus: String,
    val sourceType: String,
    val responsibleName: String,
    val itemCount: Int,
    val totalKg: Double,
    val notes: String = ""
)
