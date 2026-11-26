package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.LocalColdStorageRepository
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.ReceivingSessionEntity
import com.example.data.TemperatureZone
import com.example.domain.invoice.InvoiceAiExtractor
import com.example.domain.invoice.InvoiceLocalExtractor
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
