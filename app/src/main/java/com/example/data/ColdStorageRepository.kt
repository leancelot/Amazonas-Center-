package com.example.data

import kotlinx.coroutines.flow.Flow

class ColdStorageRepository(private val dao: ColdStorageDao) {

    val activeItemsFefo: Flow<List<ColdStorageItemEntity>> = dao.observeActiveItemsFefo()
    val allItems: Flow<List<ColdStorageItemEntity>> = dao.observeAllItems()
    val sessionSummaries: Flow<List<ReceivingSessionSummary>> = dao.observeSessionSummaries()
    val allMovements: Flow<List<StockMovementEntity>> = dao.observeAllMovements()

    fun observeItemsForSession(sessionId: Long): Flow<List<ColdStorageItemEntity>> =
        dao.observeItemsForSession(sessionId)

    fun observeMovementsForItem(itemId: Long): Flow<List<StockMovementEntity>> =
        dao.observeMovementsForItem(itemId)

    suspend fun saveReceivingSession(
        session: ReceivingSessionEntity,
        items: List<ColdStorageItemEntity>
    ): Long {
        return dao.saveReceivingSessionWithItems(session, items)
    }

    suspend fun recordStockOut(
        itemId: Long,
        movementType: MovementType,
        quantity: Double,
        reason: String
    ): Boolean {
        return dao.recordStockMovementAndDecrement(
            itemId = itemId,
            movementType = movementType,
            quantityToDeduct = quantity,
            reason = reason
        )
    }

    suspend fun transferChamber(
        itemId: Long,
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double
    ) {
        dao.updateItemChamberAndTemp(
            itemId = itemId,
            chamberName = chamberName,
            temperatureZone = zone.name,
            tempCelsius = tempCelsius
        )
    }

    suspend fun updateExpiry(itemId: Long, expiryEpochDay: Long) {
        dao.updateItemExpiry(itemId, expiryEpochDay)
    }

    suspend fun deleteItem(itemId: Long) {
        dao.deleteItemById(itemId)
    }

    suspend fun deleteSession(sessionId: Long) {
        dao.deleteSessionById(sessionId)
    }
}
