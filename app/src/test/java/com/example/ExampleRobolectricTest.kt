package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ChamberTempLogStore
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.LocalColdStorageRepository
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.ReceivingSessionEntity
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

        val dashboardVm = DashboardViewModel(repository, tempStore, context)
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
