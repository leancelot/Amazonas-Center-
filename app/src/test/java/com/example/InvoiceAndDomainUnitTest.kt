package com.example

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
import com.example.data.ShopInfo
import com.example.data.ShopMember
import com.example.data.ShopSessionState
import com.example.data.StaffRole
import com.example.data.StockMovementEntity
import com.example.data.TeamInvite
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.DanfeQrParser
import com.example.domain.invoice.LocalInvoiceOcrParser
import com.example.domain.invoice.MeatCatalogMatcher
import com.example.domain.invoice.OcrLineBox
import com.example.domain.invoice.OcrPageInput
import com.example.notifications.ExpiryAlertCalculator
import com.example.ui.viewmodel.ReceivingViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(ThermalCompliance.CONFORME, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -18.0))
        assertEquals(ThermalCompliance.CONFORME, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -18.5))
        assertEquals(ThermalCompliance.ATENCAO, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -15.0))
        assertEquals(ThermalCompliance.CRITICO, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -5.0))

        assertEquals(ExpiryUrgency.VENCIDO, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(-1)))
        assertEquals(ExpiryUrgency.VENCE_HOJE, ExpiryUrgency.fromEpochDay(DateUtils.todayEpochDay()))
        assertEquals(ExpiryUrgency.CRITICO_7_DIAS, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(3)))
        assertEquals(ExpiryUrgency.CRITICO_7_DIAS, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(7)))
        assertEquals(ExpiryUrgency.ATENCAO_15_DIAS, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(8)))
        assertEquals(ExpiryUrgency.ATENCAO_15_DIAS, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(15)))
        assertEquals(ExpiryUrgency.EM_DIA, ExpiryUrgency.fromEpochDay(DateUtils.addDaysToTodayEpochDay(16)))
    }

    @Test
    fun `date utils isToday matches current local calendar day only`() {
        val now = System.currentTimeMillis()
        assertTrue(DateUtils.isToday(now))
        assertFalse(DateUtils.isToday(now - 86_400_000L * 2L))
        assertFalse(DateUtils.isToday(0L))
    }

    @Test
    fun `expiry alert calculator aggregates expired, 7d, 15d and excludes clean items`() {
        val items = listOf(
            ColdStorageItemEntity(
                id = "1",
                cutName = "Picanha",
                currentQuantity = 5.0,
                expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(-2)
            ),
            ColdStorageItemEntity(
                id = "2",
                cutName = "Alcatra",
                currentQuantity = 8.0,
                expiryDateEpochDay = DateUtils.todayEpochDay()
            ),
            ColdStorageItemEntity(
                id = "3",
                cutName = "Costela",
                currentQuantity = 10.0,
                expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(7)
            ),
            ColdStorageItemEntity(
                id = "4",
                cutName = "Maminha",
                currentQuantity = 4.0,
                expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(15)
            ),
            ColdStorageItemEntity(
                id = "5",
                cutName = "Cupim",
                currentQuantity = 12.0,
                expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(30)
            ),
            ColdStorageItemEntity(
                id = "6",
                cutName = "Zerado",
                currentQuantity = 0.0,
                expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(-5)
            )
        )

        val digest = ExpiryAlertCalculator.fromItems(items)
        assertEquals(1, digest.expiredCount)
        assertEquals(2, digest.due7DaysCount)
        assertEquals(1, digest.due15DaysCount)
        assertEquals(4, digest.totalAlertCount)
        assertTrue(digest.hasUrgentAlerts)
        assertTrue(digest.buildNotificationTitle().contains("1 vencido(s)"))
        assertTrue(digest.buildNotificationTitle().contains("2 até 7d"))
        assertTrue(digest.buildNotificationTitle().contains("1 em 8-15d"))
        assertTrue(digest.buildNotificationBody().contains("Picanha"))

        val cleanDigest = ExpiryAlertCalculator.fromItems(
            listOf(
                ColdStorageItemEntity(
                    id = "ok",
                    cutName = "Lombo",
                    currentQuantity = 5.0,
                    expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(25)
                )
            )
        )
        assertFalse(cleanDigest.hasUrgentAlerts)
        assertEquals(0, cleanDigest.totalAlertCount)
    }

    @Test
    fun `local ocr parser reconstructs danfe rows, items, lot, sif, expiry and annotations`() {
        val page = OcrPageInput(
            lines = listOf(
                OcrLineBox("FRIGORIFICO AMAZONIA CARNES LTDA", left = 10f, top = 20f, right = 300f, bottom = 40f),
                OcrLineBox("CNPJ: 12.345.678/0001-99", left = 10f, top = 46f, right = 220f, bottom = 64f),
                OcrLineBox("NF-e Nº 004521", left = 310f, top = 46f, right = 450f, bottom = 64f),
                OcrLineBox("CHAVE DE ACESSO: 3526 1112 3456 7800 0199 5500 1000 0045 2110 0000 0012", left = 10f, top = 74f, right = 500f, bottom = 92f),
                OcrLineBox("DADOS DOS PRODUTOS", left = 10f, top = 110f, right = 220f, bottom = 128f),
                OcrLineBox("CARNE BOV PICANHA RESF", left = 10f, top = 140f, right = 220f, bottom = 158f),
                OcrLineBox("24,50 KG", left = 240f, top = 141f, right = 310f, bottom = 159f),
                OcrLineBox("59,90", left = 330f, top = 140f, right = 380f, bottom = 158f),
                OcrLineBox("1467,55", left = 400f, top = 140f, right = 470f, bottom = 158f),
                OcrLineBox("LOTE LT882 SIF 1450 VAL 28/12/2026", left = 16f, top = 164f, right = 300f, bottom = 180f),
                OcrLineBox("COXA E SOBRECOXA CONG  15,00 CX  180,00  2700,00", left = 10f, top = 192f, right = 460f, bottom = 210f),
                OcrLineBox("DADOS ADICIONAIS", left = 10f, top = 230f, right = 180f, bottom = 248f),
                OcrLineBox("TEMP CHEGADA: -18,5 °C OBS: Carga lacrada sem avarias", left = 10f, top = 254f, right = 450f, bottom = 272f)
            )
        )

        val result = LocalInvoiceOcrParser.parsePages(listOf(page), InvoiceSourceType.OCR_FOTO)
        assertTrue(result.isSuccess)
        val draft = result.getOrThrow()

        assertEquals("4521", draft.invoiceNumber)
        assertEquals("12.345.678/0001-99", draft.supplierCnpj)
        assertTrue(draft.supplierName.contains("FRIGORIFICO AMAZONIA"))
        assertEquals(44, draft.accessKey.length)
        assertEquals(-18.5, draft.extractedTempCelsius ?: 0.0, 0.01)
        assertTrue(draft.notes.contains("Carga lacrada sem avarias"))

        assertEquals(2, draft.items.size)
        val picanha = draft.items[0]
        assertEquals("Picanha", picanha.cutName)
        assertEquals(MeatType.BOVINA, picanha.meatType)
        assertEquals(24.5, picanha.quantity, 0.01)
        assertEquals(MeasurementUnit.KG, picanha.unit)
        assertEquals(59.90, picanha.unitCostBrl, 0.01)
        assertEquals("LT882", picanha.batchCode)
        assertEquals("1450", picanha.sifCode)
        assertEquals(DateUtils.parseIsoDateToEpochDay("28/12/2026"), picanha.expiryEpochDay)

        val frango = draft.items[1]
        assertEquals("Coxa e Sobrecoxa", frango.cutName)
        assertEquals(MeatType.AVES, frango.meatType)
        assertEquals(TemperatureZone.CONGELADO, frango.temperatureZone)
        assertEquals(15.0, frango.quantity, 0.01)
        assertEquals(MeasurementUnit.CAIXA, frango.unit)
    }

    @Test
    fun `local ocr parser handles informal handwritten notes and signed temperature helpers`() {
        val raw = """
            FORNECEDOR: Casa de Carnes Norte
            ALCATRA RESF 12,5 KG LOTE A10 SIF 902
            OBS: Conferido pelo turno da manhã
            TEMP: -1,2°C
        """.trimIndent()
        val parsed = LocalInvoiceOcrParser.parseRawText(raw, InvoiceSourceType.OCR_FOTO).getOrThrow()
        assertEquals("Casa de Carnes Norte", parsed.supplierName)
        assertEquals(-1.2, parsed.extractedTempCelsius ?: 0.0, 0.01)
        assertTrue(parsed.notes.contains("Conferido pelo turno da manhã"))
        assertEquals(1, parsed.items.size)
        assertEquals("Alcatra", parsed.items[0].cutName)
        assertEquals("A10", parsed.items[0].batchCode)
        assertEquals("902", parsed.items[0].sifCode)

        assertEquals("-18.5", ReceivingViewModel.sanitizeTemperatureInput("-18.5abc-"))
        assertEquals("-18.52", ReceivingViewModel.sanitizeTemperatureInput("-18.5abc-2"))
        assertEquals("-18.0", ReceivingViewModel.toggleTemperatureSign("18.0"))
        assertEquals("18.0", ReceivingViewModel.toggleTemperatureSign("-18.0"))
    }

    @Test
    fun `thermal compliance evaluates critical and warning thresholds correctly`() {
        assertEquals(ThermalCompliance.CRITICO, ThermalCompliance.evaluate(TemperatureZone.RESFRIADO, 8.5))
        assertEquals(ThermalCompliance.ATENCAO, ThermalCompliance.evaluate(TemperatureZone.RESFRIADO, 5.5))
        assertEquals(ThermalCompliance.CONFORME, ThermalCompliance.evaluate(TemperatureZone.RESFRIADO, 2.0))
        assertEquals(ThermalCompliance.CRITICO, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -5.0))
        assertEquals(ThermalCompliance.ATENCAO, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -15.0))
        assertEquals(ThermalCompliance.CONFORME, ThermalCompliance.evaluate(TemperatureZone.CONGELADO, -20.0))
    }

    @Test
    fun `staff roles enforce permissions for gerente, conferente, camara fria, and operador`() {
        assertTrue(StaffRole.GERENTE.canReceive)
        assertTrue(StaffRole.GERENTE.canEditReceivingOrStock)
        assertTrue(StaffRole.GERENTE.canDeleteRecords)
        assertTrue(StaffRole.GERENTE.canManageTeam)

        assertTrue(StaffRole.CONFERENTE.canReceive)
        assertTrue(StaffRole.CONFERENTE.canEditReceivingOrStock)
        assertFalse(StaffRole.CONFERENTE.canDeleteRecords)
        assertFalse(StaffRole.CONFERENTE.canManageTeam)

        assertTrue(StaffRole.CAMARA_FRIA.canReceive)
        assertTrue(StaffRole.CAMARA_FRIA.canEditReceivingOrStock)
        assertFalse(StaffRole.CAMARA_FRIA.canDeleteRecords)
        assertFalse(StaffRole.CAMARA_FRIA.canManageTeam)

        assertTrue(StaffRole.OPERADOR.canReceive)
        assertFalse(StaffRole.OPERADOR.canEditReceivingOrStock)
        assertFalse(StaffRole.OPERADOR.canDeleteRecords)
        assertFalse(StaffRole.OPERADOR.canManageTeam)
    }

    @Test
    fun `repository saves receiving session and processes atomic stock out and loss`() = runTest {
        val repository: ColdStorageRepository = FakeColdStorageRepository()

        val session = ReceivingSessionEntity(
            invoiceNumber = "5501",
            supplierName = "Frigorífico Boi Bom",
            measuredTempCelsius = 2.0,
            targetZone = TemperatureZone.RESFRIADO.name,
            thermalStatus = ThermalCompliance.CONFORME.name,
            sourceType = InvoiceSourceType.MANUAL.name
        )

        val saveResult = repository.saveReceivingSession(
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
        assertTrue(saveResult.isSuccess)

        val fefoItems = repository.activeItemsFefo.first()
        assertEquals(2, fefoItems.size)
        assertEquals("Contrafilé", fefoItems[0].cutName)

        val itemId = fefoItems[0].id
        assertTrue(repository.recordStockOut(itemId, MovementType.SAIDA_BALCAO, 10.0, "Balcão").getOrThrow())
        assertTrue(repository.recordStockOut(itemId, MovementType.PERDA_APARA, 2.0, "Apara").getOrThrow())

        val updated = repository.activeItemsFefo.first().first { it.id == itemId }
        assertEquals(3.0, updated.currentQuantity, 0.01)

        val movements = repository.allMovements.first()
        assertEquals(4, movements.size)
        val loss = movements.first { it.movementType == MovementType.PERDA_APARA.name }
        assertEquals(100.0, loss.estimatedLossBrl, 0.01)
    }

    @Test
    fun `receiving viewmodel manages truck dock queue park, resume, and remove cleanly`() {
        val repository = FakeColdStorageRepository()
        val viewModel = ReceivingViewModel(
            repository = repository,
            localExtractor = com.example.domain.invoice.InvoiceLocalExtractor(),
            aiExtractor = com.example.domain.invoice.InvoiceAiExtractor()
        )

        viewModel.addQuickEntryItem(
            supplier = "Frigorífico A",
            invoiceNum = "1001",
            responsible = "Leandro",
            meatType = MeatType.BOVINA,
            cutName = "Picanha",
            zone = TemperatureZone.RESFRIADO,
            tempCelsius = 2.0,
            quantity = 18.5,
            unit = MeasurementUnit.KG,
            batch = "L01",
            expiryEpochDay = DateUtils.addDaysToTodayEpochDay(7)
        )
        assertTrue(viewModel.parkCurrentEntryInQueue())
        assertEquals(1, viewModel.pendingQueue.value.size)
        assertTrue(viewModel.formState.value.items.isEmpty())
        assertEquals("Leandro", viewModel.formState.value.responsibleName)

        viewModel.addQuickEntryItem(
            supplier = "Frigorífico B",
            invoiceNum = "1002",
            responsible = "Leandro",
            meatType = MeatType.AVES,
            cutName = "Peito de Frango",
            zone = TemperatureZone.CONGELADO,
            tempCelsius = -18.0,
            quantity = 30.0,
            unit = MeasurementUnit.CAIXA,
            batch = "L02",
            expiryEpochDay = DateUtils.addDaysToTodayEpochDay(90)
        )

        val parkedId = viewModel.pendingQueue.value.first().id
        viewModel.resumeParkedEntry(parkedId)

        assertEquals("1001", viewModel.formState.value.invoiceNumber)
        assertEquals("Picanha", viewModel.formState.value.items.first().cutName)
        // Active entry 1002 should have been swapped into queue cleanly
        assertEquals(1, viewModel.pendingQueue.value.size)
        assertEquals("1002", viewModel.pendingQueue.value.first().form.invoiceNumber)

        viewModel.removeParkedEntry(viewModel.pendingQueue.value.first().id)
        assertTrue(viewModel.pendingQueue.value.isEmpty())
    }

    private class FakeColdStorageRepository : ColdStorageRepository {
        private var nextSessionId = 1
        private var nextItemId = 1
        private var nextMovementId = 1

        private val sessions = MutableStateFlow<List<ReceivingSessionEntity>>(emptyList())
        private val items = MutableStateFlow<List<ColdStorageItemEntity>>(emptyList())
        private val movements = MutableStateFlow<List<StockMovementEntity>>(emptyList())

        override val sessionState: Flow<ShopSessionState> = MutableStateFlow(
            ShopSessionState(
                isLoading = false,
                shopInfo = ShopInfo(id = "amazonas_center", name = "Amazonas Center", ownerId = "owner"),
                currentMember = ShopMember(
                    id = "owner",
                    userId = "owner",
                    shopId = "amazonas_center",
                    displayName = "Leandro",
                    role = StaffRole.GERENTE.name,
                    active = true
                )
            )
        )

        override val activeItemsFefo: Flow<List<ColdStorageItemEntity>> = items.map { list ->
            list.filter { it.currentQuantity > 0.001 }
                .sortedWith(compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }.thenBy { it.receivedAtMillis })
        }

        override val allItems: Flow<List<ColdStorageItemEntity>> = items

        override val sessionSummaries: Flow<List<ReceivingSessionSummary>> =
            kotlinx.coroutines.flow.combine(sessions, items) { sessionList, itemList ->
                sessionList.sortedByDescending { it.receivedAtMillis }.map { s ->
                    val sItems = itemList.filter { it.sessionId == s.id }
                    ReceivingSessionSummary(
                        id = s.id,
                        invoiceNumber = s.invoiceNumber,
                        accessKey = s.accessKey,
                        supplierName = s.supplierName,
                        supplierCnpj = s.supplierCnpj,
                        receivedAtMillis = s.receivedAtMillis,
                        measuredTempCelsius = s.measuredTempCelsius,
                        targetZone = s.targetZone,
                        thermalStatus = s.thermalStatus,
                        sourceType = s.sourceType,
                        responsibleName = s.responsibleName,
                        itemCount = sItems.size,
                        totalKg = sItems.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.initialQuantity },
                        notes = s.notes
                    )
                }
            }

        override val allMovements: Flow<List<StockMovementEntity>> = movements

        override val teamMembers: Flow<List<ShopMember>> = MutableStateFlow(emptyList())

        override val teamInvites: Flow<List<TeamInvite>> = MutableStateFlow(emptyList())

        override fun observeItemsForSession(sessionId: String): Flow<List<ColdStorageItemEntity>> =
            items.map { list -> list.filter { it.sessionId == sessionId } }

        override fun observeMovementsForItem(itemId: String): Flow<List<StockMovementEntity>> =
            movements.map { list -> list.filter { it.itemId == itemId } }

        override suspend fun ensureSessionReady(): Result<Unit> = Result.success(Unit)

        override suspend fun saveReceivingSession(
            session: ReceivingSessionEntity,
            items: List<ColdStorageItemEntity>
        ): Result<String> {
            val sessionId = "s_${nextSessionId++}"
            sessions.value = sessions.value + session.copy(id = sessionId)
            val savedItems = items.map { it.copy(id = "i_${nextItemId++}", sessionId = sessionId) }
            this.items.value = this.items.value + savedItems
            val entryMovements = savedItems.map { item ->
                StockMovementEntity(
                    id = "m_${nextMovementId++}",
                    itemId = item.id,
                    cutNameSnapshot = item.cutName,
                    meatTypeSnapshot = item.meatType,
                    unitSnapshot = item.unit,
                    movementType = MovementType.ENTRADA.name,
                    quantity = item.initialQuantity,
                    createdAtMillis = session.receivedAtMillis
                )
            }
            movements.value = movements.value + entryMovements
            return Result.success(sessionId)
        }

        override suspend fun recordStockOut(
            itemId: String,
            movementType: MovementType,
            quantity: Double,
            reason: String
        ): Result<Boolean> {
            val item = items.value.firstOrNull { it.id == itemId } ?: return Result.success(false)
            val deduct = quantity.coerceIn(0.0, item.currentQuantity)
            if (deduct <= 0.0) return Result.success(false)
            val remaining = (item.currentQuantity - deduct).let { if (it < 0.001) 0.0 else it }
            items.value = items.value.map {
                if (it.id == itemId) it.copy(currentQuantity = remaining) else it
            }
            val loss = if (movementType.isLoss) deduct * item.unitCostBrl else 0.0
            movements.value = movements.value + StockMovementEntity(
                id = "m_${nextMovementId++}",
                itemId = itemId,
                cutNameSnapshot = item.cutName,
                meatTypeSnapshot = item.meatType,
                unitSnapshot = item.unit,
                movementType = movementType.name,
                quantity = deduct,
                estimatedLossBrl = loss,
                reason = reason
            )
            return Result.success(true)
        }

        override suspend fun transferChamber(
            itemId: String,
            chamberName: String,
            zone: TemperatureZone,
            tempCelsius: Double
        ): Result<Unit> = Result.success(Unit)

        override suspend fun updateExpiry(itemId: String, expiryEpochDay: Long): Result<Unit> =
            Result.success(Unit)

        override suspend fun deleteItem(itemId: String): Result<Unit> = Result.success(Unit)

        override suspend fun deleteSession(sessionId: String): Result<Unit> = Result.success(Unit)

        override suspend fun inviteCollaborator(email: String, role: StaffRole): Result<Unit> =
            Result.success(Unit)

        override suspend fun updateMemberRole(memberUid: String, newRole: StaffRole, active: Boolean): Result<Unit> =
            Result.success(Unit)

        override suspend fun revokeInvite(inviteId: String): Result<Unit> = Result.success(Unit)
    }
}
