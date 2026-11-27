package com.example.data

import kotlinx.coroutines.flow.Flow

data class ShopSessionState(
    val isLoading: Boolean = true,
    val shopInfo: ShopInfo? = null,
    val currentMember: ShopMember? = null,
    val errorMessage: String? = null
) {
    val activeRole: StaffRole
        get() = currentMember?.roleEnum ?: StaffRole.OPERADOR

    val isMemberActive: Boolean
        get() = currentMember?.active == true
}

interface ColdStorageRepository {
    val sessionState: Flow<ShopSessionState>
    val activeItemsFefo: Flow<List<ColdStorageItemEntity>>
    val allItems: Flow<List<ColdStorageItemEntity>>
    val sessionSummaries: Flow<List<ReceivingSessionSummary>>
    val allMovements: Flow<List<StockMovementEntity>>
    val teamMembers: Flow<List<ShopMember>>
    val teamInvites: Flow<List<TeamInvite>>

    fun observeItemsForSession(sessionId: String): Flow<List<ColdStorageItemEntity>>
    fun observeMovementsForItem(itemId: String): Flow<List<StockMovementEntity>>

    suspend fun ensureSessionReady(): Result<Unit>

    suspend fun saveReceivingSession(
        session: ReceivingSessionEntity,
        items: List<ColdStorageItemEntity>
    ): Result<String>

    suspend fun recordStockOut(
        itemId: String,
        movementType: MovementType,
        quantity: Double,
        reason: String,
        updatedNotes: String? = null
    ): Result<Boolean>

    suspend fun recordBatchStockOut(
        requests: List<BatchStockOutRequest>
    ): Result<Int>

    suspend fun recordPositiveStockAdjustment(
        itemId: String,
        addQuantity: Double,
        reason: String
    ): Result<Boolean>

    suspend fun transferChamber(
        itemId: String,
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double
    ): Result<Unit>

    suspend fun updateExpiry(itemId: String, expiryEpochDay: Long): Result<Unit>
    suspend fun deleteItem(itemId: String): Result<Unit>
    suspend fun deleteSession(sessionId: String): Result<Unit>

    suspend fun inviteCollaborator(email: String, role: StaffRole): Result<Unit>
    suspend fun updateMemberRole(memberUid: String, newRole: StaffRole, active: Boolean): Result<Unit>
    suspend fun revokeInvite(inviteId: String): Result<Unit>
}
