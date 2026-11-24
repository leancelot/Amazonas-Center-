package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.InvoiceSourceType
import com.example.data.ReceivingSessionEntity
import com.example.data.ReceivingSessionSummary
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.DanfeQrParser
import com.example.domain.invoice.InvoiceAiExtractor
import com.example.domain.invoice.NfeXmlParser
import com.example.domain.invoice.ParsedInvoiceDraft
import com.example.domain.invoice.ParsedInvoiceItemDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data class Loading(val label: String) : ExtractionUiState
    data class Success(val message: String) : ExtractionUiState
    data class Error(val message: String) : ExtractionUiState
}

data class ReceivingFormState(
    val invoiceNumber: String = "",
    val accessKey: String = "",
    val supplierName: String = "",
    val supplierCnpj: String = "",
    val measuredTempText: String = "2.0",
    val targetZone: TemperatureZone = TemperatureZone.RESFRIADO,
    val responsibleName: String = "",
    val sourceType: InvoiceSourceType = InvoiceSourceType.MANUAL,
    val notes: String = "",
    val items: List<ParsedInvoiceItemDraft> = emptyList(),
    val isSaving: Boolean = false
) {
    val parsedTempCelsius: Double?
        get() = measuredTempText.replace(',', '.').toDoubleOrNull()

    val thermalCompliance: ThermalCompliance?
        get() = parsedTempCelsius?.let { ThermalCompliance.evaluate(targetZone, it) }
}

class ReceivingViewModel(
    private val repository: ColdStorageRepository,
    private val aiExtractor: InvoiceAiExtractor
) : ViewModel() {

    private val _formState = MutableStateFlow(ReceivingFormState())
    val formState: StateFlow<ReceivingFormState> = _formState.asStateFlow()

    private val _extractionState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)
    val extractionState: StateFlow<ExtractionUiState> = _extractionState.asStateFlow()

    val pastSessions: StateFlow<List<ReceivingSessionSummary>> = repository.sessionSummaries
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun updateInvoiceNumber(value: String) = _formState.update { it.copy(invoiceNumber = value) }
    fun updateAccessKey(value: String) = _formState.update { it.copy(accessKey = value) }
    fun updateSupplierName(value: String) = _formState.update { it.copy(supplierName = value) }
    fun updateSupplierCnpj(value: String) = _formState.update { it.copy(supplierCnpj = value) }
    fun updateMeasuredTemp(value: String) = _formState.update { it.copy(measuredTempText = value) }
    fun updateResponsibleName(value: String) = _formState.update { it.copy(responsibleName = value) }
    fun updateNotes(value: String) = _formState.update { it.copy(notes = value) }

    fun updateTargetZone(zone: TemperatureZone) {
        _formState.update { current ->
            val currentTemp = current.parsedTempCelsius
            val updatedTemp = if (currentTemp == null || currentTemp == current.targetZone.defaultTempCelsius) {
                zone.defaultTempCelsius.toString()
            } else {
                current.measuredTempText
            }
            current.copy(targetZone = zone, measuredTempText = updatedTemp)
        }
    }

    fun addOrUpdateDraftItem(item: ParsedInvoiceItemDraft) {
        _formState.update { current ->
            val exists = current.items.any { it.localId == item.localId }
            val updatedList = if (exists) {
                current.items.map { if (it.localId == item.localId) item else it }
            } else {
                current.items + item
            }
            current.copy(items = updatedList)
        }
    }

    fun removeDraftItem(localId: String) {
        _formState.update { current ->
            current.copy(items = current.items.filterNot { it.localId == localId })
        }
    }

    fun clearExtractionBanner() {
        _extractionState.value = ExtractionUiState.Idle
    }

    fun importXmlUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Lendo arquivo XML da NF-e...")
            val result = withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure<ParsedInvoiceDraft>(
                        IllegalArgumentException("Não foi possível abrir o arquivo XML.")
                    )
                stream.use { NfeXmlParser.parse(it) }
            }
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = true)
                    _extractionState.value = ExtractionUiState.Success(
                        "XML NF-e ${draft.invoiceNumber.ifBlank { "" }} importado com ${draft.items.size} produto(s)."
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        err.localizedMessage ?: "Erro ao processar XML da NF-e."
                    )
                }
            )
        }
    }

    fun extractFromImageUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Analisando foto da nota fiscal com IA...")
            val result = aiExtractor.extractFromImageUri(context, uri)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = false)
                    _extractionState.value = ExtractionUiState.Success(
                        "Foto lida: ${draft.items.size} produto(s) preenchido(s). Revise antes de salvar."
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        err.localizedMessage ?: "Não foi possível extrair os dados da foto."
                    )
                }
            )
        }
    }

    fun extractFromPdfUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Analisando PDF da nota fiscal com IA...")
            val result = aiExtractor.extractFromPdfUri(context, uri)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = false)
                    _extractionState.value = ExtractionUiState.Success(
                        "PDF lido: ${draft.items.size} produto(s) preenchido(s). Revise antes de salvar."
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        err.localizedMessage ?: "Não foi possível extrair os dados do PDF."
                    )
                }
            )
        }
    }

    fun applyScannedBarcode(rawValue: String) {
        val result = DanfeQrParser.parseRawCode(rawValue)
        result.fold(
            onSuccess = { draft ->
                applyParsedDraft(draft, replaceItems = false)
                val info = if (draft.invoiceNumber.isNotBlank()) {
                    "NF ${draft.invoiceNumber} preenchida via leitor."
                } else {
                    "Código registrado na sessão de recebimento."
                }
                _extractionState.value = ExtractionUiState.Success(info)
            },
            onFailure = { err ->
                _extractionState.value = ExtractionUiState.Error(
                    err.localizedMessage ?: "Código inválido."
                )
            }
        )
    }

    fun confirmAndSaveSession() {
        val state = _formState.value
        if (state.items.isEmpty()) {
            _extractionState.value = ExtractionUiState.Error(
                "Adicione pelo menos um produto (via NF ou manual) antes de confirmar."
            )
            return
        }
        val temp = state.parsedTempCelsius ?: state.targetZone.defaultTempCelsius
        val supplier = state.supplierName.trim().ifBlank { "Fornecedor não informado" }
        val nf = state.invoiceNumber.trim()
        val compliance = ThermalCompliance.evaluate(state.targetZone, temp)
        val now = System.currentTimeMillis()

        viewModelScope.launch {
            _formState.update { it.copy(isSaving = true) }
            try {
                val session = ReceivingSessionEntity(
                    invoiceNumber = nf,
                    accessKey = state.accessKey.trim(),
                    supplierName = supplier,
                    supplierCnpj = state.supplierCnpj.trim(),
                    receivedAtMillis = now,
                    measuredTempCelsius = temp,
                    targetZone = state.targetZone.name,
                    thermalStatus = compliance.name,
                    responsibleName = state.responsibleName.trim(),
                    sourceType = state.sourceType.name,
                    notes = state.notes.trim()
                )
                val entities = state.items.map { item ->
                    ColdStorageItemEntity(
                        meatType = item.meatType.name,
                        cutName = item.cutName.trim().ifBlank { "Corte Diverso" },
                        temperatureZone = item.temperatureZone.name,
                        measuredTempCelsius = item.measuredTempCelsius,
                        chamberName = item.chamberName.ifBlank { item.temperatureZone.defaultChamber },
                        batchCode = item.batchCode.trim(),
                        sifCode = item.sifCode.trim(),
                        initialQuantity = item.quantity.coerceAtLeast(0.01),
                        currentQuantity = item.quantity.coerceAtLeast(0.01),
                        unit = item.unit.name,
                        unitCostBrl = item.unitCostBrl.coerceAtLeast(0.0),
                        expiryDateEpochDay = item.expiryEpochDay,
                        receivedAtMillis = now,
                        supplierName = supplier,
                        invoiceNumber = nf,
                        notes = item.notes.trim()
                    )
                }
                repository.saveReceivingSession(session, entities)
                _formState.value = ReceivingFormState()
                _extractionState.value = ExtractionUiState.Success(
                    "Recebimento salvo! ${entities.size} lote(s) entraram na câmara fria."
                )
            } catch (e: Exception) {
                _formState.update { it.copy(isSaving = false) }
                _extractionState.value = ExtractionUiState.Error(
                    "Erro ao salvar no banco local: ${e.localizedMessage ?: "tente novamente"}"
                )
            }
        }
    }

    fun resetForm() {
        _formState.value = ReceivingFormState()
        _extractionState.value = ExtractionUiState.Idle
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    private fun applyParsedDraft(draft: ParsedInvoiceDraft, replaceItems: Boolean) {
        _formState.update { current ->
            val mergedItems = if (replaceItems) {
                draft.items
            } else {
                current.items + draft.items
            }
            val inferredZone = draft.items.firstOrNull()?.temperatureZone ?: current.targetZone
            current.copy(
                invoiceNumber = draft.invoiceNumber.ifBlank { current.invoiceNumber },
                accessKey = draft.accessKey.ifBlank { current.accessKey },
                supplierName = draft.supplierName.ifBlank { current.supplierName },
                supplierCnpj = draft.supplierCnpj.ifBlank { current.supplierCnpj },
                sourceType = draft.sourceType,
                targetZone = inferredZone,
                measuredTempText = if (current.measuredTempText == "2.0" && inferredZone != TemperatureZone.RESFRIADO) {
                    inferredZone.defaultTempCelsius.toString()
                } else {
                    current.measuredTempText
                },
                notes = listOf(current.notes, draft.notes)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" • "),
                items = mergedItems
            )
        }
    }
}
