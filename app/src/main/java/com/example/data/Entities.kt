package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val activeShopId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ShopInfo(
    @DocumentId val id: String = "",
    val name: String = "",
    val ownerId: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ShopMember(
    @DocumentId val id: String = "",
    val userId: String = "",
    val shopId: String = "",
    val shopOwnerId: String = "",
    val managerUids: List<String> = emptyList(),
    val email: String = "",
    val displayName: String = "",
    val role: String = StaffRole.OPERADOR.name,
    val active: Boolean = true,
    val inviteId: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val roleEnum: StaffRole get() = StaffRole.fromRaw(role)
}

@Keep
@IgnoreExtraProperties
data class TeamInvite(
    @DocumentId val id: String = "",
    val shopId: String = "",
    val shopName: String = "",
    val emailLower: String = "",
    val assignedRole: String = StaffRole.OPERADOR.name,
    val invitedByUid: String = "",
    val status: String = "PENDING",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val roleEnum: StaffRole get() = StaffRole.fromRaw(assignedRole)
}

@Keep
@IgnoreExtraProperties
data class ReceivingSessionEntity(
    @DocumentId val id: String = "",
    val shopId: String = "",
    val memberUids: List<String> = emptyList(),
    val createdByUid: String = "",
    val invoiceNumber: String = "",
    val accessKey: String = "",
    val supplierName: String = "",
    val supplierCnpj: String = "",
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val measuredTempCelsius: Double = 2.0,
    val targetZone: String = TemperatureZone.RESFRIADO.name,
    val thermalStatus: String = ThermalCompliance.CONFORME.name,
    val responsibleName: String = "",
    val sourceType: String = InvoiceSourceType.MANUAL.name,
    val notes: String = "",
    val itemCount: Int = 0,
    val totalKg: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ColdStorageItemEntity(
    @DocumentId val id: String = "",
    val shopId: String = "",
    val memberUids: List<String> = emptyList(),
    val createdByUid: String = "",
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
    val expiryDateEpochDay: Long = DateUtils.addDaysToTodayEpochDay(7),
    val receivedAtMillis: Long = System.currentTimeMillis(),
    val supplierName: String = "",
    val invoiceNumber: String = "",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val meatTypeEnum: MeatType get() = MeatType.fromRaw(meatType)
    val zoneEnum: TemperatureZone get() = TemperatureZone.fromRaw(temperatureZone)
    val unitEnum: MeasurementUnit get() = MeasurementUnit.fromRaw(unit)
    val urgency: ExpiryUrgency get() = ExpiryUrgency.fromEpochDay(expiryDateEpochDay)
    val thermalCompliance: ThermalCompliance
        get() = ThermalCompliance.evaluate(zoneEnum, measuredTempCelsius)
}

@Keep
@IgnoreExtraProperties
data class StockMovementEntity(
    @DocumentId val id: String = "",
    val shopId: String = "",
    val memberUids: List<String> = emptyList(),
    val createdByUid: String = "",
    val createdByName: String = "",
    val itemId: String = "",
    val cutNameSnapshot: String = "",
    val meatTypeSnapshot: String = MeatType.BOVINA.name,
    val unitSnapshot: String = MeasurementUnit.KG.name,
    val movementType: String = MovementType.SAIDA_BALCAO.name,
    val quantity: Double = 0.0,
    val estimatedLossBrl: Double = 0.0,
    val reason: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val movementTypeEnum: MovementType get() = MovementType.fromRaw(movementType)
    val meatTypeEnum: MeatType get() = MeatType.fromRaw(meatTypeSnapshot)
    val unitEnum: MeasurementUnit get() = MeasurementUnit.fromRaw(unitSnapshot)
}

data class ReceivingSessionSummary(
    val id: String = "",
    val invoiceNumber: String = "",
    val accessKey: String = "",
    val supplierName: String = "",
    val supplierCnpj: String = "",
    val receivedAtMillis: Long = 0L,
    val measuredTempCelsius: Double = 2.0,
    val targetZone: String = TemperatureZone.RESFRIADO.name,
    val thermalStatus: String = ThermalCompliance.CONFORME.name,
    val sourceType: String = InvoiceSourceType.MANUAL.name,
    val responsibleName: String = "",
    val itemCount: Int = 0,
    val totalKg: Double = 0.0,
    val notes: String = ""
)
