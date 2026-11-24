package com.example

import com.example.data.ColdStorageDao
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.DateUtils
import com.example.data.ExpiryUrgency
import com.example.data.InvoiceSourceType
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.ReceivingSessionEntity
import com.example.data.ReceivingSessionSummary
import com.example.data.StockMovementEntity
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.DanfeQrParser
import com.example.domain.invoice.MeatCatalogMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceAndDomainUnitTest {

    @Test
    fun `meat catalog matcher classifies cuts, zones, sif, and batch codes`() {
        val bovine = MeatCatalogMatcher.classifyDescription(
            rawDescription = "CARNE BOV PICANHA RESF SIF 1234 LOTE LT99",
            rawUnit = "KG"
        )
        assertEquals(MeatType.BOVINA, bovine.meatType)
        assertEquals("Picanha", bovine.cutName)
        assertEquals(TemperatureZone.RESFRIADO, bovine.temperatureZone)
        assertEquals(MeasurementUnit.KG, bovine.unit)
        assertEquals("1234", bovine.sifCode)
        assertEquals("LT99", bovine.batchCode)

        val poultry = MeatCatalogMatcher.classifyDescription(
            rawDescription = "COXA E SOBRECOXA FRANGO CONGELADA CX",
            rawUnit = "CX"
        )
        assertEquals(MeatType.AVES, poultry.meatType)
        assertEquals("Coxa e Sobrecoxa", poultry.cutName)
        assertEquals(TemperatureZone.CONGELADO, poultry.temperatureZone)
        assertEquals(MeasurementUnit.CAIXA, poultry.unit)
    }

    @Test
    fun `danfe qr parser extracts invoice number and cnpj from 44 digit key`() {
        val url = "https://www.nfce.fazenda.sp.gov.br/qrcode?p=35261112345678000199550010000987651000000012|2|1|1|ABC"
        val result = DanfeQrParser.parseRawCode(url)
        assertTrue(result.isSuccess)
        val draft = result.getOrThrow()
        assertEquals("98765", draft.invoiceNumber)
        assertEquals("12.345.678/0001-99", draft.supplierCnpj)
        assertEquals(44, draft.accessKey.length)
    }

    @Test
    fun `thermal compliance and expiry urgency evaluate thresholds accurately`() {
        assertEquals(ThermalCompliance.CONFORME, ThermalCompliance.evaluate(TemperatureZone.RESFRIADO, 2.5))
        assertEquals(ThermalCompliance.ATENCAO, ThermalCompliance.evaluate(TemperatureZone.RESFRIADO, 5.5))
        assertEquals(ThermalCompliance.CRITICO, ThermalCompliance.evaluate(TemperatureZone.RESFRIADO, 9.0))
        assertEquals(ThermalCompliance.CONFORME, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -18.5))
        assertEquals(ThermalCompliance.CRITICO, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -5.0))

        assertEquals(ExpiryUrgency.VENCIDO, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(-1)))
        assertEquals(ExpiryUrgency.VENCE_HOJE, ExpiryUrgency.fromEpochDay(DateUtils.todayEpochDay()))
        assertEquals(ExpiryUrgency.CRITICO_3_DIAS, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(3)))
        assertEquals(ExpiryUrgency.EM_DIA, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(14)))
    }

    @Test
    fun `repository saves receiving session and processes atomic stock out and loss`() = runTest {
        val fakeDao = FakeColdStorageDao()
        val repository = ColdStorageRepository(fakeDao)

        val session = ReceivingSessionEntity(
            invoiceNumber = "5501",
            supplierName = "Frigorífico Boi Bom",
            measuredTempCelsius = 2.0,
            targetZone = TemperatureZone.RESFRIADO.name,
            thermalStatus = ThermalCompliance.CONFORME.name,
            sourceType = InvoiceSourceType.MANUAL.name
        )

        repository.saveReceivingSession(
            session = session,
            items = listOf(
                ColdStorageItemEntity(
                    meatType = MeatType.BOVINA.name,
                    cutName = "Alcatra",
                    temperatureZone = TemperatureZone.RESFRIADO.name,
                    measuredTempCelsius = 2.0,
                    chamberName = TemperatureZone.RESFRIADO.defaultChamber,
                    initialQuantity = 20.0,
                    currentQuantity = 20.0,
                    unit = MeasurementUnit.KG.name,
                    unitCostBrl = 40.0,
                    expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(10)
                ),
                ColdStorageItemEntity(
                    meatType = MeatType.BOVINA.name,
                    cutName = "Contrafilé",
                    temperatureZone = TemperatureZone.RESFRIADO.name,
                    measuredTempCelsius = 2.0,
                    chamberName = TemperatureZone.RESFRIADO.defaultChamber,
                    initialQuantity = 15.0,
                    currentQuantity = 15.0,
                    unit = MeasurementUnit.KG.name,
                    unitCostBrl = 50.0,
                    expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(2)
                )
            )
        )

        val fefoItems = repository.activeItemsFefo.first()
        assertEquals(2, fefoItems.size)
        assertEquals("Contrafilé", fefoItems[0].cutName)

        val itemId = fefoItems[0].id
        assertTrue(repository.recordStockOut(itemId, MovementType.SAIDA_BALCAO, 10.0, "Balcão"))
        assertTrue(repository.recordStockOut(itemId, MovementType.PERDA_APARA, 2.0, "Apara"))

        val updated = repository.activeItemsFefo.first().first { it.id == itemId }
        assertEquals(3.0, updated.currentQuantity, 0.01)

        val movements = repository.allMovements.first()
        assertEquals(4, movements.size)
        val loss = movements.first { it.movementType == MovementType.PERDA_APARA.name }
        assertEquals(100.0, loss.estimatedLossBrl, 0.01)
    }

    private class FakeColdStorageDao : ColdStorageDao {
        private var nextSessionId = 1L
        private var nextItemId = 1L
        private var nextMovementId = 1L

        private val sessions = MutableStateFlow<List<ReceivingSessionEntity>>(emptyList())
        private val items = MutableStateFlow<List<ColdStorageItemEntity>>(emptyList())
        private val movements = MutableStateFlow<List<StockMovementEntity>>(emptyList())

        override fun observeActiveItemsFefo(): Flow<List<ColdStorageItemEntity>> =
            items.map { list ->
                list.filter { it.currentQuantity > 0.001 }
                    .sortedWith(compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }.thenBy { it.receivedAtMillis })
            }

        override fun observeAllItems(): Flow<List<ColdStorageItemEntity>> = items

        override suspend fun getItemById(itemId: Long): ColdStorageItemEntity? =
            items.value.firstOrNull { it.id == itemId }

        override fun observeSessionSummaries(): Flow<List<ReceivingSessionSummary>> =
            sessions.map { emptyList() }

        override fun observeItemsForSession(sessionId: Long): Flow<List<ColdStorageItemEntity>> =
            items.map { list -> list.filter { it.sessionId == sessionId } }

        override fun observeAllMovements(): Flow<List<StockMovementEntity>> = movements

        override fun observeMovementsForItem(itemId: Long): Flow<List<StockMovementEntity>> =
            movements.map { list -> list.filter { it.itemId == itemId } }

        override suspend fun insertSession(session: ReceivingSessionEntity): Long {
            val id = nextSessionId++
            sessions.value = sessions.value + session.copy(id = id)
            return id
        }

        override suspend fun insertItem(item: ColdStorageItemEntity): Long {
            val id = nextItemId++
            items.value = items.value + item.copy(id = id)
            return id
        }

        override suspend fun insertItems(items: List<ColdStorageItemEntity>): List<Long> =
            items.map { insertItem(it) }

        override suspend fun insertMovement(movement: StockMovementEntity): Long {
            val id = nextMovementId++
            movements.value = movements.value + movement.copy(id = id)
            return id
        }

        override suspend fun insertMovements(movements: List<StockMovementEntity>) {
            movements.forEach { insertMovement(it) }
        }

        override suspend fun updateItemQuantity(itemId: Long, newQuantity: Double) {
            items.value = items.value.map {
                if (it.id == itemId) it.copy(currentQuantity = newQuantity) else it
            }
        }

        override suspend fun updateItemChamberAndTemp(
            itemId: Long,
            chamberName: String,
            temperatureZone: String,
            tempCelsius: Double
        ) {
            items.value = items.value.map {
                if (it.id == itemId) {
                    it.copy(chamberName = chamberName, temperatureZone = temperatureZone, measuredTempCelsius = tempCelsius)
                } else it
            }
        }

        override suspend fun updateItemExpiry(itemId: Long, expiryEpochDay: Long) {
            items.value = items.value.map {
                if (it.id == itemId) it.copy(expiryDateEpochDay = expiryEpochDay) else it
            }
        }

        override suspend fun deleteItemById(itemId: Long) {
            items.value = items.value.filterNot { it.id == itemId }
        }

        override suspend fun deleteSessionById(sessionId: Long) {
            sessions.value = sessions.value.filterNot { it.id == sessionId }
        }
    }
}
