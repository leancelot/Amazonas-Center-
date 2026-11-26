package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.ChamberTempLogStore
import com.example.data.ColdStorageRepository
import com.example.domain.invoice.InvoiceAiExtractor
import com.example.domain.invoice.InvoiceLocalExtractor

class FrigStockViewModelFactory(
    private val repository: ColdStorageRepository,
    private val localExtractor: InvoiceLocalExtractor,
    private val aiExtractor: InvoiceAiExtractor,
    private val chamberTempLogStore: ChamberTempLogStore? = null,
    private val appContext: Context? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(DashboardViewModel::class.java) ->
                DashboardViewModel(repository, chamberTempLogStore, appContext) as T
            modelClass.isAssignableFrom(ReceivingViewModel::class.java) ->
                ReceivingViewModel(repository, localExtractor, aiExtractor) as T
            modelClass.isAssignableFrom(InventoryViewModel::class.java) ->
                InventoryViewModel(repository) as T
            modelClass.isAssignableFrom(ReportsViewModel::class.java) ->
                ReportsViewModel(repository, chamberTempLogStore) as T
            modelClass.isAssignableFrom(TeamViewModel::class.java) ->
                TeamViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
