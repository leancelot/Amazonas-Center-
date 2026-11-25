package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import com.example.data.ReceivingSessionEntity
import com.example.data.ReceivingSessionSummary
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.DanfeQrParser
import com.example.domain.invoice.InvoiceAiExtractor
import com.example.domain.invoice.InvoiceLocalExtractor
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
import java.util.Locale
import kotlin.math.abs

sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data class Loading(val label: String) : ExtractionUiState
    data class Success(
        val message: String,
        val rawOcrText: String = "",
        val canRetryWithCloud: Boolean = false
    ) : ExtractionUiState
    data class Error(
        val message: String,
        val rawOcrText: String = "",
        val canRetryWithCloud: Boolean = false
    ) : ExtractionUiState
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
    private val localExtractor: InvoiceLocalExtractor,
    private val aiExtractor: InvoiceAiExtractor
) : ViewModel() {

    private val _formState = MutableStateFlow(ReceivingFormState())
    val formState: StateFlow<ReceivingFormState> = _formState.asStateFlow()

    private val _extractionState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)
    val extractionState: StateFlow<ExtractionUiState> = _extractionState.asStateFlow()

    private var lastMediaUri: Uri? = null
    private var lastMediaIsPdf: Boolean = false

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
    fun updateResponsibleName(value: String) = _formState.update { it.copy(responsibleName = value) }
    fun updateNotes(value: String) = _formState.update { it.copy(notes = value) }

    fun updateMeasuredTemp(value: String) {
        val sanitized = sanitizeTemperatureInput(value)
        _formState.update { current ->
            val oldTemp = current.parsedTempCelsius ?: current.targetZone.defaultTempCelsius
            val newTemp = sanitized.replace(',', '.').toDoubleOrNull()
            val syncedItems = if (newTemp != null) {
                current.items.map { item ->
                    val matchesZone = item.temperatureZone == current.targetZone
                    val wasSyncedWithSession = abs(item.measuredTempCelsius - oldTemp) < 0.05 ||
                        abs(item.measuredTempCelsius - item.temperatureZone.defaultTempCelsius) < 0.05
                    if (matchesZone && wasSyncedWithSession) {
                        item.copy(measuredTempCelsius = newTemp)
                    } else {
                        item
                    }
                }
            } else {
                current.items
            }
            current.copy(measuredTempText = sanitized, items = syncedItems)
        }
    }

    fun updateTargetZone(zone: TemperatureZone) {
        _formState.update { current ->
            val currentTemp = current.parsedTempCelsius
            val isStillDefault = currentTemp == null ||
                abs(currentTemp - current.targetZone.defaultTempCelsius) < 0.05
            val updatedTempText = if (isStillDefault) {
                formatTempForInput(zone.defaultTempCelsius)
            } else {
                current.measuredTempText
            }
            current.copy(targetZone = zone, measuredTempText = updatedTempText)
        }
    }

    fun syncAllItemsToSessionTemp() {
        _formState.update { current ->
            val temp = current.parsedTempCelsius ?: current.targetZone.defaultTempCelsius
            val updated = current.items.map { item ->
                if (item.temperatureZone == current.targetZone) {
                    item.copy(measuredTempCelsius = temp)
                } else {
                    item
                }
            }
            current.copy(items = updated)
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
        lastMediaUri = null
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
                        message = "XML NF-e ${draft.invoiceNumber.ifBlank { "" }} importado com ${draft.items.size} produto(s)."
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Erro ao processar XML da NF-e."
                    )
                }
            )
        }
    }

    fun extractFromImageUri(context: Context, uri: Uri) {
        lastMediaUri = uri
        lastMediaIsPdf = false
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Lendo foto e anotações no aparelho (OCR offline)...")
            val result = localExtractor.extractFromImageUri(context, uri)
            result.fold(
                onSuccess = { draft ->
                    lastRawOcrText = draft.rawOcrText
                    applyParsedDraft(draft, replaceItems = false)
                    val warning = checkThermalConsistency(
                        draft.items,
                        draft.extractedTempCelsius ?: _formState.value.parsedTempCelsius,
                        draft.items.firstOrNull()?.temperatureZone ?: _formState.value.targetZone
                    )
                    _extractionState.value = ExtractionUiState.Success(
                        message = buildLocalSuccessMessage("Foto lida offline", draft, warning),
                        rawOcrText = draft.rawOcrText,
                        canRetryWithCloud = true
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Não foi possível extrair os dados da foto localmente.",
                        canRetryWithCloud = true
                    )
                }
            )
        }
    }

    fun extractFromPdfUri(context: Context, uri: Uri) {
        lastMediaUri = uri
        lastMediaIsPdf = true
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Lendo PDF e anotações no aparelho (OCR offline)...")
            val result = localExtractor.extractFromPdfUri(context, uri)
            result.fold(
                onSuccess = { draft ->
                    lastRawOcrText = draft.rawOcrText
                    applyParsedDraft(draft, replaceItems = false)
                    val warning = checkThermalConsistency(
                        draft.items,
                        draft.extractedTempCelsius ?: _formState.value.parsedTempCelsius,
                        draft.items.firstOrNull()?.temperatureZone ?: _formState.value.targetZone
                    )
                    _extractionState.value = ExtractionUiState.Success(
                        message = buildLocalSuccessMessage("PDF lido offline", draft, warning),
                        rawOcrText = draft.rawOcrText,
                        canRetryWithCloud = true
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Não foi possível extrair os dados do PDF localmente.",
                        canRetryWithCloud = true
                    )
                }
            )
        }
    }

    private var lastRawOcrText: String = ""

    fun retryLastMediaWithCloudReserve(context: Context) {
        val ocrText = lastRawOcrText
        if (ocrText.isNotBlank()) {
            viewModelScope.launch {
                _extractionState.value = ExtractionUiState.Loading("Estruturando com IA na nuvem (modo texto eficiente)...")
                val result = aiExtractor.extractFromOcrText(context, ocrText)
                result.fold(
                    onSuccess = { draft ->
                        applyParsedDraft(draft, replaceItems = false)
                        _extractionState.value = ExtractionUiState.Success(
                            message = "Nuvem (reserva): ${draft.items.size} produto(s) estruturado(s) com sucesso. Revise antes de salvar."
                        )
                    },
                    onFailure = { err ->
                        _extractionState.value = ExtractionUiState.Error(
                            message = err.localizedMessage ?: "Não foi possível consultar a IA na nuvem.",
                            rawOcrText = ocrText,
                            canRetryWithCloud = true
                        )
                    }
                )
            }
            return
        }

        val uri = lastMediaUri ?: return
        if (lastMediaIsPdf) {
            extractWithCloudReserveFromPdfUri(context, uri)
        } else {
            extractWithCloudReserveFromImageUri(context, uri)
        }
    }

    fun extractWithCloudReserveFromImageUri(context: Context, uri: Uri) {
        lastMediaUri = uri
        lastMediaIsPdf = false
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Reanalisando foto com IA na nuvem (reserva)...")
            val result = aiExtractor.extractFromImageUri(context, uri)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = false)
                    _extractionState.value = ExtractionUiState.Success(
                        message = "Nuvem (reserva): ${draft.items.size} produto(s) extraído(s). Revise antes de salvar."
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Não foi possível consultar a IA na nuvem.",
                        canRetryWithCloud = true
                    )
                }
            )
        }
    }

    fun extractWithCloudReserveFromPdfUri(context: Context, uri: Uri) {
        lastMediaUri = uri
        lastMediaIsPdf = true
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Reanalisando PDF com IA na nuvem (reserva)...")
            val result = aiExtractor.extractFromPdfUri(context, uri)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = false)
                    _extractionState.value = ExtractionUiState.Success(
                        message = "Nuvem (reserva): ${draft.items.size} produto(s) extraído(s). Revise antes de salvar."
                    )
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Não foi possível consultar a IA na nuvem.",
                        canRetryWithCloud = true
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
                lastMediaUri = null
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
        lastMediaUri = null
        _formState.value = ReceivingFormState()
        _extractionState.value = ExtractionUiState.Idle
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    private fun checkThermalConsistency(items: List<ParsedInvoiceItemDraft>, sessionTemp: Double?, targetZone: TemperatureZone): String? {
        val sessionCompliance = sessionTemp?.let { ThermalCompliance.evaluate(targetZone, it) }
        if (sessionCompliance == ThermalCompliance.CRITICO) {
            return "⚠️ ALERTA CRÍTICO: Temperatura de chegada (${DateUtils.formatTemp(sessionTemp!!)}) fora do padrão para ${targetZone.displayName}!"
        }
        val criticalItems = items.filter { ThermalCompliance.evaluate(it.temperatureZone, it.measuredTempCelsius) == ThermalCompliance.CRITICO }
        if (criticalItems.isNotEmpty()) {
            return "⚠️ ATENÇÃO: ${criticalItems.size} lote(s) com temperatura crítica fora do padrão térmico."
        }
        val warningItems = items.filter { ThermalCompliance.evaluate(it.temperatureZone, it.measuredTempCelsius) == ThermalCompliance.ATENCAO }
        if (warningItems.isNotEmpty()) {
            return "⚠️ Nota: ${warningItems.size} lote(s) com temperatura em faixa de atenção."
        }
        if (sessionCompliance == ThermalCompliance.ATENCAO) {
            return "⚠️ Nota: Temperatura de chegada em faixa de atenção."
        }
        return null
    }

    private fun buildLocalSuccessMessage(prefix: String, draft: ParsedInvoiceDraft, consistencyWarning: String?): String {
        val parts = mutableListOf<String>()
        if (draft.invoiceNumber.isNotBlank()) parts.add("NF ${draft.invoiceNumber}")
        parts.add("${draft.items.size} produto(s)")
        if (draft.extractedTempCelsius != null) parts.add("temp. detectada")
        val base = if (draft.items.isEmpty()) {
            "$prefix (${parts.joinToString(", ")}). Confira o texto OCR."
        } else {
            "$prefix: ${parts.joinToString(", ")} preenchido(s). Revise antes de salvar."
        }
        return if (consistencyWarning != null) "$base\n$consistencyWarning" else base
    }

    private fun applyParsedDraft(draft: ParsedInvoiceDraft, replaceItems: Boolean) {
        _formState.update { current ->
            val inferredZone = draft.items.firstOrNull()?.temperatureZone ?: current.targetZone
            val currentSessionTemp = current.parsedTempCelsius
            val userCustomizedTemp = currentSessionTemp != null &&
                abs(currentSessionTemp - current.targetZone.defaultTempCelsius) >= 0.05

            val effectiveSessionTemp = when {
                draft.extractedTempCelsius != null -> draft.extractedTempCelsius
                userCustomizedTemp && inferredZone == current.targetZone -> currentSessionTemp!!
                inferredZone != current.targetZone -> inferredZone.defaultTempCelsius
                else -> currentSessionTemp ?: inferredZone.defaultTempCelsius
            }

            val normalizedDraftItems = draft.items.map { item ->
                val isItemStillDefault = abs(item.measuredTempCelsius - item.temperatureZone.defaultTempCelsius) < 0.05
                if (item.temperatureZone == inferredZone && isItemStillDefault) {
                    item.copy(measuredTempCelsius = effectiveSessionTemp)
                } else {
                    item
                }
            }

            val mergedItems = if (replaceItems) {
                normalizedDraftItems
            } else {
                current.items + normalizedDraftItems
            }

            current.copy(
                invoiceNumber = draft.invoiceNumber.ifBlank { current.invoiceNumber },
                accessKey = draft.accessKey.ifBlank { current.accessKey },
                supplierName = draft.supplierName.ifBlank { current.supplierName },
                supplierCnpj = draft.supplierCnpj.ifBlank { current.supplierCnpj },
                sourceType = draft.sourceType,
                targetZone = inferredZone,
                measuredTempText = formatTempForInput(effectiveSessionTemp),
                notes = listOf(current.notes, draft.notes)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" • "),
                items = mergedItems
            )
        }
    }

    fun addQuickEntryItem(
        supplier: String,
        invoiceNum: String,
        responsible: String,
        meatType: com.example.data.MeatType,
        cutName: String = "",
        zone: TemperatureZone,
        tempCelsius: Double,
        quantity: Double,
        unit: com.example.data.MeasurementUnit,
        batch: String,
        expiryEpochDay: Long
    ) {
        val resolvedCut = cutName.trim().ifBlank { meatType.displayName }
        _formState.update { current ->
            val newItem = ParsedInvoiceItemDraft(
                rawDescription = resolvedCut,
                meatType = meatType,
                cutName = resolvedCut,
                temperatureZone = zone,
                measuredTempCelsius = tempCelsius,
                chamberName = zone.defaultChamber,
                quantity = quantity,
                unit = unit,
                batchCode = batch,
                expiryEpochDay = expiryEpochDay
            )
            current.copy(
                supplierName = supplier.ifBlank { current.supplierName },
                invoiceNumber = invoiceNum.ifBlank { current.invoiceNumber },
                responsibleName = responsible.ifBlank { current.responsibleName },
                targetZone = zone,
                measuredTempText = String.format(Locale.US, "%.1f", tempCelsius),
                items = current.items + newItem
            )
        }
    }

    private fun formatTempForInput(temp: Double): String {
        return String.format(Locale.US, "%.1f", temp)
    }

    companion object {
        fun sanitizeTemperatureInput(raw: String): String {
            val sb = StringBuilder()
            var hasSeparator = false
            raw.forEachIndexed { index, ch ->
                when {
                    ch == '-' && index == 0 -> sb.append(ch)
                    ch.isDigit() -> sb.append(ch)
                    (ch == '.' || ch == ',') && !hasSeparator -> {
                        hasSeparator = true
                        sb.append(ch)
                    }
                }
            }
            return sb.toString()
        }

        fun toggleTemperatureSign(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("-")) {
                trimmed.removePrefix("-")
            } else if (trimmed.isEmpty()) {
                "-"
            } else {
                "-$trimmed"
            }
        }
    }
}
