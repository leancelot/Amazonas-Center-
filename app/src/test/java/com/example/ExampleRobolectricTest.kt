package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChamberTempLogStore
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.FastClosureCutEntry
import com.example.data.FefoStockAllocationEngine
import com.example.data.LocalColdStorageRepository
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.PackSpecHelper
import com.example.data.PackUnitType
import com.example.data.ReceivingSessionEntity
import com.example.data.ReconciliationCadenceStatus
import com.example.data.ReconciliationScopeType
import com.example.data.ScaleReconciliationEngine
import com.example.data.ScaleReconciliationStore
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.InvoiceAiExtractor
import com.example.domain.invoice.InvoiceLocalExtractor
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.example.notifications.ExpiryNotificationManager
import com.example.ui.auth.AuthDeviceContext
import com.example.ui.auth.AuthFailureCategory
import com.example.ui.auth.GoogleAuthExceptionHandler
import com.example.ui.auth.chooseMoreInformativeThrowable
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.InventoryViewModel
import com.example.ui.viewmodel.ReceivingViewModel
import com.example.ui.viewmodel.UrgencyFilter
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Amazonas Center", appName)
    }

    @Test
    fun `confirmAndSaveSession ignores duplicate submissions for same payload`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LocalColdStorageRepository(context)
        repository.clearAllLocalData()

        val viewModel = ReceivingViewModel(
            repository = repository,
            localExtractor = InvoiceLocalExtractor(),
            aiExtractor = InvoiceAiExtractor()
        )

        viewModel.addQuickEntryItem(
            supplier = "Frigorífico Norte",
            invoiceNum = "7788",
            responsible = "Leandro",
            meatType = MeatType.BOVINA,
            cutName = "Picanha",
            zone = TemperatureZone.RESFRIADO,
            tempCelsius = 2.0,
            quantity = 12.0,
            unit = MeasurementUnit.KG,
            batch = "B1",
            expiryEpochDay = DateUtils.addDaysToTodayEpochDay(5)
        )
        viewModel.confirmAndSaveSession()

        val deadline = System.currentTimeMillis() + 5_000L
        while (repository.sessionSummaries.first().isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals(1, repository.sessionSummaries.first().size)

        // Immediately re-submitting identical payload should be ignored by idempotency guard
        viewModel.addQuickEntryItem(
            supplier = "Frigorífico Norte",
            invoiceNum = "7788",
            responsible = "Leandro",
            meatType = MeatType.BOVINA,
            cutName = "Picanha",
            zone = TemperatureZone.RESFRIADO,
            tempCelsius = 2.0,
            quantity = 12.0,
            unit = MeasurementUnit.KG,
            batch = "B1",
            expiryEpochDay = DateUtils.addDaysToTodayEpochDay(5)
        )
        viewModel.confirmAndSaveSession()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, repository.sessionSummaries.first().size)
    }

    @Test
    fun `dashboard shows only today sessions and aligns 7d 15d and zone filters with inventory`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LocalColdStorageRepository(context)
        repository.clearAllLocalData()

        val now = System.currentTimeMillis()
        val twoDaysAgo = now - (86_400_000L * 2L)

        repository.saveReceivingSession(
            session = ReceivingSessionEntity(
                invoiceNumber = "100",
                supplierName = "Antigo",
                receivedAtMillis = twoDaysAgo
            ),
            items = listOf(
                ColdStorageItemEntity(
                    cutName = "Fraldinha",
                    temperatureZone = TemperatureZone.RESFRIADO.name,
                    initialQuantity = 5.0,
                    currentQuantity = 5.0,
                    unit = MeasurementUnit.KG.name,
                    expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(4),
                    receivedAtMillis = twoDaysAgo
                )
            )
        )
        repository.saveReceivingSession(
            session = ReceivingSessionEntity(
                invoiceNumber = "200",
                supplierName = "Hoje",
                receivedAtMillis = now
            ),
            items = listOf(
                ColdStorageItemEntity(
                    cutName = "Maminha",
                    temperatureZone = TemperatureZone.CONGELADO.name,
                    initialQuantity = 8.0,
                    currentQuantity = 8.0,
                    unit = MeasurementUnit.KG.name,
                    expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(12),
                    receivedAtMillis = now
                )
            )
        )

        val dashboardVm = DashboardViewModel(repository)
        val inventoryVm = InventoryViewModel(repository)

        val dash = dashboardVm.uiState.awaitMatching { it.activeLotCount == 2 }
        assertEquals(1, dash.todaySessions.size)
        assertEquals("Hoje", dash.todaySessions.first().supplierName)
        assertEquals(1, dash.due7DaysCount)
        assertEquals(1, dash.due15DaysCount)
        assertEquals(0, dash.expiredCount)

        inventoryVm.applyDashboardFilter(UrgencyFilter.DUE_7_DAYS, null)
        val inv7 = inventoryVm.uiState.awaitMatching {
            it.urgencyFilter == UrgencyFilter.DUE_7_DAYS && it.filteredItems.size == 1
        }
        assertEquals(listOf("Fraldinha"), inv7.filteredItems.map { it.cutName })

        inventoryVm.applyDashboardFilter(UrgencyFilter.DUE_15_DAYS, null)
        val inv15 = inventoryVm.uiState.awaitMatching {
            it.urgencyFilter == UrgencyFilter.DUE_15_DAYS && it.filteredItems.size == 1
        }
        assertEquals(listOf("Maminha"), inv15.filteredItems.map { it.cutName })

        inventoryVm.applyDashboardFilter(UrgencyFilter.ALL, TemperatureZone.CONGELADO)
        val invZone = inventoryVm.uiState.awaitMatching {
            it.selectedZone == TemperatureZone.CONGELADO &&
                it.urgencyFilter == UrgencyFilter.ALL &&
                it.filteredItems.size == 1
        }
        assertEquals(listOf("Maminha"), invZone.filteredItems.map { it.cutName })
    }

    @Test
    fun `chamber temperature rounds, backup json roundtrip, and expiry alert cache work end to end`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempStore = ChamberTempLogStore(context)
        tempStore.clearAll()
        val repository = LocalColdStorageRepository(context, tempStore)
        repository.clearAllLocalData()

        repository.saveReceivingSession(
            session = ReceivingSessionEntity(
                invoiceNumber = "9001",
                supplierName = "Frigorífico Cerrado",
                receivedAtMillis = System.currentTimeMillis()
            ),
            items = listOf(
                ColdStorageItemEntity(
                    cutName = "Contrafilé",
                    temperatureZone = TemperatureZone.RESFRIADO.name,
                    initialQuantity = 14.0,
                    currentQuantity = 14.0,
                    unit = MeasurementUnit.KG.name,
                    expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(5)
                )
            )
        )

        val dashboardVm = DashboardViewModel(repository, tempStore, appContext = context)
        dashboardVm.recordChamberTemperature(
            chamberName = "Congelado",
            zone = TemperatureZone.CONGELADO,
            measuredTempCelsius = -19.5,
            responsibleName = "Leandro",
            notes = "Porta vedada"
        )

        val afterRound = dashboardVm.uiState.awaitMatching {
            it.activeLotCount == 1 && it.todayTempLogs.size == 1
        }
        assertEquals(ThermalCompliance.CONFORME, afterRound.latestTempLog?.complianceEnum)
        assertEquals("Congelado", afterRound.latestTempLog?.chamberName)

        // Cached expiry lots for alarm receiver should reflect active lot
        val cachedDigest = ExpiryNotificationManager.readCachedDigest(context)
        assertEquals(1, cachedDigest.due7DaysCount)

        // Export JSON backup -> wipe local data -> reject invalid JSON -> restore valid JSON
        val exportedJson = repository.exportBackupJson().getOrThrow()
        assertTrue(exportedJson.contains("Frigorífico Cerrado"))
        assertTrue(exportedJson.contains("Contrafilé"))
        assertTrue(exportedJson.contains("Porta vedada"))

        repository.clearAllLocalData()
        val afterClear = dashboardVm.uiState.awaitMatching {
            it.activeLotCount == 0 && it.chamberTempLogs.isEmpty()
        }
        assertEquals(0, afterClear.todaySessions.size)

        assertTrue(repository.parseBackupEnvelope("not valid json").isFailure)
        assertTrue(repository.parseBackupEnvelope("{\"hello\":1}").isFailure)

        val parsedEnvelope = repository.parseBackupEnvelope(exportedJson).getOrThrow()
        val restoreSummary = repository.restoreFromBackupEnvelope(parsedEnvelope).getOrThrow()
        assertEquals(1, restoreSummary.sessionCount)
        assertEquals(1, restoreSummary.itemCount)
        assertEquals(1, restoreSummary.temperatureLogCount)

        val restoredState = dashboardVm.uiState.awaitMatching {
            it.activeLotCount == 1 && it.chamberTempLogs.size == 1
        }
        assertEquals("Contrafilé", restoredState.activeItems.first().cutName)
        assertEquals("Porta vedada", restoredState.chamberTempLogs.first().notes)

        val updatedSettings = ExpiryNotificationManager.setAlertsEnabled(
            context = context,
            enabled = true,
            hour = 7,
            minute = 0
        )
        assertTrue(updatedSettings.enabled)
        assertEquals(7, updatedSettings.hour)
        val disabledSettings = ExpiryNotificationManager.setAlertsEnabled(context, enabled = false)
        assertFalse(disabledSettings.enabled)
    }

    @Test
    fun `google auth exception handler classifies code 16 reauth failed and builds full diagnostic report`() {
        val rootCause = IllegalStateException("16: Account reauth failed.")
        val wrapped = GetCredentialCancellationException("[16] Account reauth failed.")
        wrapped.initCause(rootCause)

        assertEquals(AuthFailureCategory.REAUTH_16, GoogleAuthExceptionHandler.classify(wrapped))
        assertTrue(GoogleAuthExceptionHandler.shouldAttemptFallback(wrapped))

        val nestedUnknown = GetCredentialUnknownException("Failed").apply { initCause(rootCause) }
        assertEquals(AuthFailureCategory.REAUTH_16, GoogleAuthExceptionHandler.classify(nestedUnknown))

        val plainCancel = GetCredentialCancellationException("User cancelled the selector")
        assertEquals(AuthFailureCategory.USER_CANCELLED, GoogleAuthExceptionHandler.classify(plainCancel))
        assertFalse(GoogleAuthExceptionHandler.shouldAttemptFallback(plainCancel))

        val noCred = NoCredentialException("No credentials available")
        assertEquals(AuthFailureCategory.NO_CREDENTIAL, GoogleAuthExceptionHandler.classify(noCred))
        assertTrue(GoogleAuthExceptionHandler.shouldAttemptFallback(noCred))

        val selected = chooseMoreInformativeThrowable(wrapped, noCred)
        assertEquals(wrapped, selected)

        val deviceCtx = AuthDeviceContext(
            buildType = "Release",
            packageName = "com.aistudio.frigstock.krmvqt",
            androidSdk = 35,
            deviceModel = "Samsung SM-A546E",
            sha1 = "AA:BB:CC:DD:EE:FF",
            sha256 = "11:22:33",
            matchesDebugSha1 = false,
            webClientIdMasked = "388813631254-...apps.googleusercontent.com",
            playServicesVersion = "24.47.38 (244738000)",
            playServicesEnabled = true
        )

        val report = GoogleAuthExceptionHandler.buildReport(
            throwable = wrapped,
            deviceContext = deviceCtx,
            traceLines = listOf("[03:52:00.001] INFO [Init] Test trace"),
            phase = "FallbackGetGoogleId"
        )

        assertEquals(AuthFailureCategory.REAUTH_16, report.category)
        assertTrue(report.suggestGuestMode)
        assertTrue(report.headline.contains("16"))
        assertTrue(report.probableCause.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(report.technicalLog.contains("GetCredentialCancellationException"))
        assertTrue(report.technicalLog.contains("16: Account reauth failed."))
        assertTrue(report.technicalLog.contains("Test trace"))
    }

    @Test
    fun `pack metadata and cut-level FEFO 1-tap exit and fast closure allocate earliest expiry lots first`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = LocalColdStorageRepository(context)
        runBlocking {
            repo.clearAllLocalData()

            val lot1Notes = PackSpecHelper.applyPackMetadata(
                userNotes = "Vácuo íntegro",
                packCount = 2,
                packType = PackUnitType.CAIXA,
                totalQuantity = 40.0,
                unit = MeasurementUnit.KG
            )
            assertEquals("Vácuo íntegro", PackSpecHelper.stripMetadata(lot1Notes))

            val lot2Notes = PackSpecHelper.applyPackMetadata(
                userNotes = "",
                packCount = 3,
                packType = PackUnitType.CAIXA,
                totalQuantity = 60.0,
                unit = MeasurementUnit.KG
            )

            repo.saveReceivingSession(
                session = ReceivingSessionEntity(
                    invoiceNumber = "901",
                    supplierName = "Frigo FEFO",
                    receivedAtMillis = System.currentTimeMillis() - 10_000L
                ),
                items = listOf(
                    ColdStorageItemEntity(
                        meatType = MeatType.BOVINA.name,
                        cutName = "Picanha",
                        initialQuantity = 40.0,
                        currentQuantity = 40.0,
                        unit = MeasurementUnit.KG.name,
                        expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(2),
                        notes = lot1Notes
                    ),
                    ColdStorageItemEntity(
                        meatType = MeatType.BOVINA.name,
                        cutName = "Alcatra",
                        initialQuantity = 30.0,
                        currentQuantity = 30.0,
                        unit = MeasurementUnit.KG.name,
                        expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(3)
                    )
                )
            ).getOrThrow()

            repo.saveReceivingSession(
                session = ReceivingSessionEntity(
                    invoiceNumber = "902",
                    supplierName = "Frigo FEFO 2",
                    receivedAtMillis = System.currentTimeMillis()
                ),
                items = listOf(
                    ColdStorageItemEntity(
                        meatType = MeatType.BOVINA.name,
                        cutName = "Picanha",
                        initialQuantity = 60.0,
                        currentQuantity = 60.0,
                        unit = MeasurementUnit.KG.name,
                        expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(10),
                        notes = lot2Notes
                    )
                )
            ).getOrThrow()

            val active = repo.activeItemsFefo.first()
            val groups = FefoStockAllocationEngine.groupActiveItemsByCut(active)
            assertEquals(2, groups.size)

            val picanhaGroup = groups.first { it.cutName == "Picanha" }
            assertEquals(100.0, picanhaGroup.totalQuantity, 0.01)
            assertEquals(5, picanhaGroup.totalRemainingPacks)
            assertEquals(PackUnitType.CAIXA, picanhaGroup.primaryPackType)

            // 1) Deduct 3 boxes -> should take 2 boxes (40 kg) from lot1 + 1 box (20 kg) from lot2
            val packAllocations = FefoStockAllocationEngine.planPackDeduction(picanhaGroup.lots, packsToDeduct = 3)
            assertEquals(2, packAllocations.size)
            assertEquals(40.0, packAllocations[0].deductQuantity, 0.01)
            assertEquals(0.0, packAllocations[0].remainingAfter, 0.01)
            assertEquals(20.0, packAllocations[1].deductQuantity, 0.01)
            assertEquals(40.0, packAllocations[1].remainingAfter, 0.01)
            assertEquals(2, PackSpecHelper.parse(packAllocations[1].updatedNotes)?.remainingCount)

            val vm = InventoryViewModel(repo)
            vm.recordCutStockOutFefo(picanhaGroup, MovementType.SAIDA_BALCAO, packAllocations, "Saída rápida: 3 cx")
            vm.uiState.awaitMatching { state ->
                state.activeItems.firstOrNull { it.cutName == "Picanha" }?.currentQuantity?.let { qty ->
                    kotlin.math.abs(qty - 40.0) < 0.05
                } == true && state.activeItems.count { it.cutName == "Picanha" } == 1
            }

            // 2) Fast end-of-day closure: Picanha left = 15 kg (deduct 25 kg), Alcatra left = 10 kg (deduct 20 kg)
            val afterPackGroups = FefoStockAllocationEngine.groupActiveItemsByCut(repo.activeItemsFefo.first())
            val picanhaAfter = afterPackGroups.first { it.cutName == "Picanha" }
            val alcatraAfter = afterPackGroups.first { it.cutName == "Alcatra" }

            val closurePlan = FefoStockAllocationEngine.planFastClosure(
                entries = listOf(
                    FastClosureCutEntry(picanhaAfter, countedRemaining = 15.0),
                    FastClosureCutEntry(alcatraAfter, countedRemaining = 10.0)
                ),
                movementType = MovementType.SAIDA_BALCAO
            )
            assertEquals(2, closurePlan.affectedCutCount)
            assertEquals(45.0, closurePlan.totalDeductedKg, 0.01)

            vm.applyFastStockClosure(closurePlan)
            val finalState = vm.uiState.awaitMatching { state ->
                val picQty = state.activeItems.filter { it.cutName == "Picanha" }.sumOf { it.currentQuantity }
                val alcQty = state.activeItems.filter { it.cutName == "Alcatra" }.sumOf { it.currentQuantity }
                kotlin.math.abs(picQty - 15.0) < 0.05 && kotlin.math.abs(alcQty - 10.0) < 0.05
            }
            assertEquals(2, finalState.activeItems.size)
            val movements = repo.allMovements.first()
            assertTrue(movements.any { it.movementTypeEnum == MovementType.SAIDA_BALCAO && it.cutNameSnapshot == "Picanha" })
        }
    }

    @Test
    fun `scale difference adjustment matches Friesul and Bairro de Fatima calculates shrinkage FEFO and updates cadence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = LocalColdStorageRepository(context)
        val scaleStore = ScaleReconciliationStore(context)
        runBlocking {
            repo.clearAllLocalData()
            scaleStore.clearRecordsOnly()

            val profiles = scaleStore.profiles.first()
            val friesulProfile = profiles.first { it.id == "friesul_semanal" }
            val fatimaProfile = profiles.first { it.id == "bairro_fatima_diario" }
            val geralProfile = profiles.first { it.id == "camara_geral_semanal" }
            assertEquals(7, friesulProfile.intervalDays)
            assertEquals(1, fatimaProfile.intervalDays)
            assertEquals(7, geralProfile.intervalDays)

            repo.saveReceivingSession(
                session = ReceivingSessionEntity(
                    invoiceNumber = "1201",
                    supplierName = "FRIESUL Alimentos",
                    receivedAtMillis = System.currentTimeMillis() - 5_000L
                ),
                items = listOf(
                    ColdStorageItemEntity(
                        meatType = MeatType.AVES.name,
                        cutName = "Peito de Frango",
                        supplierName = "FRIESUL Alimentos",
                        chamberName = "Resfriado",
                        initialQuantity = 40.0,
                        currentQuantity = 40.0,
                        unit = MeasurementUnit.KG.name,
                        unitCostBrl = 15.0,
                        expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(2)
                    ),
                    ColdStorageItemEntity(
                        meatType = MeatType.AVES.name,
                        cutName = "Coxa e Sobrecoxa",
                        supplierName = "FRIESUL Alimentos",
                        chamberName = "Bairro de Fatima",
                        initialQuantity = 60.0,
                        currentQuantity = 60.0,
                        unit = MeasurementUnit.KG.name,
                        unitCostBrl = 12.0,
                        expiryDateEpochDay = DateUtils.addDaysToTodayEpochDay(6)
                    )
                )
            ).getOrThrow()

            val vm = DashboardViewModel(repo, scaleReconciliationStore = scaleStore)
            val initialState = vm.uiState.awaitMatching { it.activeItems.size == 2 }
            assertEquals(3, initialState.dueReconciliationCount)

            // Check Friesul (40 + 60 = 100 kg expected); scale reads 95 kg -> 5 kg shrinkage (5.0%)
            val friesulPlan = ScaleReconciliationEngine.planScaleDifference(
                items = initialState.activeItems,
                profileId = friesulProfile.id,
                profileName = friesulProfile.name,
                scopeType = friesulProfile.scopeTypeEnum,
                scopeFilter = friesulProfile.scopeFilter,
                scaleWeightKg = 95.0,
                movementType = MovementType.PERDA_APARA
            )
            assertEquals(100.0, friesulPlan.expectedKg, 0.01)
            assertEquals(5.0, friesulPlan.differenceKg, 0.01)
            assertEquals(5.0, friesulPlan.shrinkagePercent, 0.01)
            assertEquals(75.0, friesulPlan.estimatedLossBrl, 0.01) // Deducted from earliest lot (Peito R$ 15/kg)
            assertTrue(friesulPlan.isLossOrOutflow)

            vm.applyScaleDifferencePlan(friesulPlan)
            val afterFriesul = vm.uiState.awaitMatching { state ->
                val peito = state.activeItems.firstOrNull { it.cutName == "Peito de Frango" }
                peito != null && kotlin.math.abs(peito.currentQuantity - 35.0) < 0.05 && state.reconciliationRecords.isNotEmpty()
            }
            val updatedFriesulProfile = afterFriesul.reconciliationProfiles.first { it.id == "friesul_semanal" }
            assertEquals(ReconciliationCadenceStatus.ON_TRACK, updatedFriesulProfile.evaluateStatus(afterFriesul.allMovements).status)

            // Check Bairro de Fátima (accent-insensitive match with "Bairro de Fatima"): 60 kg expected -> scale 57 kg -> 5% shrinkage
            val fatimaPlan = ScaleReconciliationEngine.planScaleDifference(
                items = afterFriesul.activeItems,
                profileId = fatimaProfile.id,
                profileName = fatimaProfile.name,
                scopeType = ReconciliationScopeType.CHAMBER,
                scopeFilter = fatimaProfile.scopeFilter,
                scaleWeightKg = 57.0,
                movementType = MovementType.PERDA_APARA
            )
            assertEquals(60.0, fatimaPlan.expectedKg, 0.01)
            assertEquals(3.0, fatimaPlan.differenceKg, 0.01)
            assertEquals(5.0, fatimaPlan.shrinkagePercent, 0.01)

            vm.applyScaleDifferencePlan(fatimaPlan)
            val afterFatima = vm.uiState.awaitMatching { state ->
                val coxa = state.activeItems.firstOrNull { it.cutName == "Coxa e Sobrecoxa" }
                coxa != null && kotlin.math.abs(coxa.currentQuantity - 57.0) < 0.05 && state.reconciliationRecords.size >= 2
            }
            val updatedFatimaProfile = afterFatima.reconciliationProfiles.first { it.id == "bairro_fatima_diario" }
            assertEquals(ReconciliationCadenceStatus.ON_TRACK, updatedFatimaProfile.evaluateStatus(afterFatima.allMovements).status)
            assertEquals(1, afterFatima.dueReconciliationCount)

            // Check Câmara Geral (ALL_KG): 35 + 57 = 92 kg expected -> scale reads 94 kg -> +2 kg surplus
            val geralPlan = ScaleReconciliationEngine.planScaleDifference(
                items = afterFatima.activeItems,
                profileId = geralProfile.id,
                profileName = geralProfile.name,
                scopeType = geralProfile.scopeTypeEnum,
                scopeFilter = geralProfile.scopeFilter,
                scaleWeightKg = 94.0,
                movementType = MovementType.PERDA_APARA
            )
            assertEquals(92.0, geralPlan.expectedKg, 0.01)
            assertEquals(-2.0, geralPlan.differenceKg, 0.01)
            assertTrue(geralPlan.isSurplus)
            assertEquals(2.0, geralPlan.surplusAddKg, 0.01)

            vm.applyScaleDifferencePlan(geralPlan)
            val afterGeral = vm.uiState.awaitMatching { state ->
                val totalKg = state.activeItems.sumOf { it.currentQuantity }
                kotlin.math.abs(totalKg - 94.0) < 0.05 && state.reconciliationRecords.size >= 3
            }
            assertEquals(0, afterGeral.dueReconciliationCount)
        }
    }

    private fun <T> StateFlow<T>.awaitMatching(
        timeoutMs: Long = 5_000L,
        predicate: (T) -> Boolean
    ): T {
        val latest = AtomicReference(value)
        val job = CoroutineScope(Dispatchers.Default).launch {
            collect { latest.set(it) }
        }
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!predicate(latest.get()) && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            shadowOf(Looper.getMainLooper()).idle()
            val current = latest.get()
            if (!predicate(current)) {
                throw AssertionError("Timed out awaiting StateFlow condition. Latest=$current")
            }
            return current
        } finally {
            job.cancel()
        }
    }
}
