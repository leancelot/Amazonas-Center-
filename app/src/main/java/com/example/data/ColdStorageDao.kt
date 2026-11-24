package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ColdStorageDao {

    @Query(
        """
        SELECT * FROM cold_storage_items 
        WHERE currentQuantity > 0.001 
        ORDER BY expiryDateEpochDay ASC, receivedAtMillis ASC
        """
    )
    fun observeActiveItemsFefo(): Flow<List<ColdStorageItemEntity>>

    @Query("SELECT * FROM cold_storage_items ORDER BY expiryDateEpochDay ASC, receivedAtMillis DESC")
    fun observeAllItems(): Flow<List<ColdStorageItemEntity>>

    @Query("SELECT * FROM cold_storage_items WHERE id = :itemId LIMIT 1")
    suspend fun getItemById(itemId: Long): ColdStorageItemEntity?

    @Query(
        """
        SELECT s.id, s.invoiceNumber, s.supplierName, s.receivedAtMillis, 
               s.measuredTempCelsius, s.targetZone, s.thermalStatus, s.sourceType,
               s.responsibleName,
               COUNT(i.id) AS itemCount,
               COALESCE(SUM(CASE WHEN i.unit = 'KG' THEN i.initialQuantity ELSE 0 END), 0.0) AS totalKg,
               s.notes AS notes
        FROM receiving_sessions s
        LEFT JOIN cold_storage_items i ON i.sessionId = s.id
        GROUP BY s.id
        ORDER BY s.receivedAtMillis DESC
        """
    )
    fun observeSessionSummaries(): Flow<List<ReceivingSessionSummary>>

    @Query("SELECT * FROM cold_storage_items WHERE sessionId = :sessionId ORDER BY expiryDateEpochDay ASC")
    fun observeItemsForSession(sessionId: Long): Flow<List<ColdStorageItemEntity>>

    @Query("SELECT * FROM stock_movements ORDER BY createdAtMillis DESC")
    fun observeAllMovements(): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE itemId = :itemId ORDER BY createdAtMillis DESC")
    fun observeMovementsForItem(itemId: Long): Flow<List<StockMovementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReceivingSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ColdStorageItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ColdStorageItemEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovement(movement: StockMovementEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovements(movements: List<StockMovementEntity>)

    @Query("UPDATE cold_storage_items SET currentQuantity = :newQuantity WHERE id = :itemId")
    suspend fun updateItemQuantity(itemId: Long, newQuantity: Double)

    @Query(
        """
        UPDATE cold_storage_items 
        SET chamberName = :chamberName, temperatureZone = :temperatureZone, measuredTempCelsius = :tempCelsius 
        WHERE id = :itemId
        """
    )
    suspend fun updateItemChamberAndTemp(
        itemId: Long,
        chamberName: String,
        temperatureZone: String,
        tempCelsius: Double
    )

    @Query("UPDATE cold_storage_items SET expiryDateEpochDay = :expiryEpochDay WHERE id = :itemId")
    suspend fun updateItemExpiry(itemId: Long, expiryEpochDay: Long)

    @Query("DELETE FROM cold_storage_items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: Long)

    @Query("DELETE FROM receiving_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Transaction
    suspend fun saveReceivingSessionWithItems(
        session: ReceivingSessionEntity,
        items: List<ColdStorageItemEntity>
    ): Long {
        val sessionId = insertSession(session)
        val linkedItems = items.map { it.copy(sessionId = sessionId) }
        val insertedIds = insertItems(linkedItems)
        val entryMovements = linkedItems.zip(insertedIds).map { (item, itemId) ->
            StockMovementEntity(
                itemId = itemId,
                cutNameSnapshot = item.cutName,
                meatTypeSnapshot = item.meatType,
                unitSnapshot = item.unit,
                movementType = MovementType.ENTRADA.name,
                quantity = item.initialQuantity,
                estimatedLossBrl = 0.0,
                reason = if (session.invoiceNumber.isNotBlank()) {
                    "Recebimento NF ${session.invoiceNumber}"
                } else {
                    "Recebimento ${session.supplierName}".trim()
                },
                createdAtMillis = session.receivedAtMillis
            )
        }
        insertMovements(entryMovements)
        return sessionId
    }

    @Transaction
    suspend fun recordStockMovementAndDecrement(
        itemId: Long,
        movementType: MovementType,
        quantityToDeduct: Double,
        reason: String
    ): Boolean {
        val item = getItemById(itemId) ?: return false
        val safeDeduct = quantityToDeduct.coerceIn(0.0, item.currentQuantity)
        if (safeDeduct <= 0.0) return false

        val remaining = (item.currentQuantity - safeDeduct).let {
            if (it < 0.001) 0.0 else it
        }
        updateItemQuantity(itemId, remaining)

        val estimatedLoss = if (movementType.isLoss && item.unitCostBrl > 0.0) {
            safeDeduct * item.unitCostBrl
        } else {
            0.0
        }

        insertMovement(
            StockMovementEntity(
                itemId = itemId,
                cutNameSnapshot = item.cutName,
                meatTypeSnapshot = item.meatType,
                unitSnapshot = item.unit,
                movementType = movementType.name,
                quantity = safeDeduct,
                estimatedLossBrl = estimatedLoss,
                reason = reason,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        return true
    }
}
