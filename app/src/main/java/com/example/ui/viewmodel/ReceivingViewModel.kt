package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageItemEntity
import com.example.data.ColdStorageRepository
import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import com.example.data.MovementType
import com.example.data.ReceivingSessionEntity
import com.example.data.ReceivingSessionSummary
import com.example.data.ShopSessionState
import com.example.data.StockMovementEntity
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.DanfeQrParser
import com.example.domain.invoice.InvoiceAiExtractor
import com.example.domain.invoice.InvoiceLocalExtractor
import com.example.domain.invoice.LocalInvoiceOcrParser
import com.example.domain.invoice.LocalOcrSnapshot
import com.example.domain.invoice.NfeXmlParser
import com.example.domain.invoice.ParsedInvoiceDraft
import com.example.domain.invoice.ParsedInvoiceItemDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data class Loading(val label: String) : ExtractionUiState
    data class Success(
        val message: String,
        val rawOcrText: String = "",
        val canRetryWithCloud: Boolean = false,
        val canRetryFullVision: Boolean = false
    ) : ExtractionUiState
    data class Error(
        val message: String,
        val rawOcrText: String = "",
        val canRetryWithCloud: Boolean = false,
        val canRetryFullVision: Boolean = false,
        val canUseOfflineFallback: Boolean = false
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

data class ParkedReceivingDraft(
    val id: String = UUID.randomUUID().toString(),
    val parkedAtMillis: Long = System.currentTimeMillis(),
    val form: ReceivingFormState
)

class ReceivingViewModel(
    private val repository: ColdStorageRepository,
    private val localExtractor: InvoiceLocalExtractor,
    private val aiExtractor: InvoiceAiExtractor
) : ViewModel() {

    private val _formState = MutableStateFlow(ReceivingFormState())
    val formState: StateFlow<ReceivingFormState> = _formState.asStateFlow()

    private val _extractionState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)
    val extractionState: StateFlow<ExtractionUiState> = _extractionState.asStateFlow()

    private val _pendingQueue = MutableStateFlow<List<ParkedReceivingDraft>>(emptyList())
    val pendingQueue: StateFlow<List<ParkedReceivingDraft>> = _pendingQueue.asStateFlow()

    private var lastMediaUri: Uri? = null
    private var lastMediaIsPdf: Boolean = false
    private var lastRawOcrText: String = ""
    private var lastOcrSnapshot: LocalOcrSnapshot? = null
    private val saveMutex = Mutex()
    private var lastSavedFingerprint: String? = null
    private var lastSavedAtMillis: Long = 0L

    fun observeItemsForSession(sessionId: String): Flow<List<ColdStorageItemEntity>> =
        repository.observeItemsForSession(sessionId).catch { error ->
            val rootError = unwrapFirestoreStreamError(error)
            Log.w("ReceivingVM", "Error observing session items", rootError)
            emit(emptyList())
        }

    val sessionState: StateFlow<ShopSessionState> = repository.sessionState
        .catch { error ->
            val rootError = unwrapFirestoreStreamError(error)
            Log.w("ReceivingVM", "Error observing session", rootError)
            emit(ShopSessionState(isLoading = false, errorMessage = rootError.localizedMessage))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ShopSessionState()
        )

    val pastSessions: StateFlow<List<ReceivingSessionSummary>> = repository.sessionSummaries
        .catch { error ->
            val rootError = unwrapFirestoreStreamError(error)
            Log.w("ReceivingVM", "Error observing past sessions", rootError)
            emit(emptyList())
        }
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

    fun parkCurrentEntryInQueue(): Boolean {
        val current = _formState.value
        if (current.items.isEmpty() && current.invoiceNumber.isBlank() && current.supplierName.isBlank()) {
            return false
        }
        val parked = ParkedReceivingDraft(form = current.copy(isSaving = false))
        _pendingQueue.update { listOf(parked) + it }
        val keepResponsible = current.responsibleName
        _formState.value = ReceivingFormState(responsibleName = keepResponsible)
        lastMediaUri = null
        lastRawOcrText = ""
        lastOcrSnapshot = null
        _extractionState.value = ExtractionUiState.Idle
        return true
    }

    fun resumeParkedEntry(id: String) {
        val target = _pendingQueue.value.firstOrNull { it.id == id } ?: return
        val current = _formState.value
        val hasUnsavedCurrent = current.items.isNotEmpty() || current.invoiceNumber.isNotBlank()
        _pendingQueue.update { queue ->
            val filtered = queue.filterNot { it.id == id }
            if (hasUnsavedCurrent) {
                listOf(ParkedReceivingDraft(form = current.copy(isSaving = false))) + filtered
            } else {
                filtered
            }
        }
        _formState.value = target.form
        _extractionState.value = ExtractionUiState.Idle
    }

    fun removeParkedEntry(id: String) {
        _pendingQueue.update { queue -> queue.filterNot { it.id == id } }
    }

    fun clearExtractionBanner() {
        _extractionState.value = ExtractionUiState.Idle
    }

    fun importXmlUri(context: Context, uri: Uri) {
        lastMediaUri = null
        viewModelScope.launch {
            _extractionState.value = ExtractionUiState.Loading("Lendo arquivo XML da NF-e...")
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure<ParsedInvoiceDraft>(
                        IllegalArgumentException("Não foi possível abrir o arquivo XML.")
                    )
                stream.use { NfeXmlParser.parse(it) }
            }
            val elapsed = formatElapsedSeconds(startedAt)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = true)
                    _extractionState.value = ExtractionUiState.Success(
                        message = "XML NF-e ${draft.invoiceNumber.ifBlank { "" }} importado ($elapsed) com ${draft.items.size} produto(s)."
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
            val startedAt = System.currentTimeMillis()
            _extractionState.value = ExtractionUiState.Loading("Lendo foto no aparelho (OCR offline)...")
            val result = localExtractor.extractFromImageUri(context, uri)
            val elapsed = formatElapsedSeconds(startedAt)
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
                        message = buildLocalSuccessMessage("Foto lida offline ($elapsed)", draft, warning),
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
            val startedAt = System.currentTimeMillis()
            _extractionState.value = ExtractionUiState.Loading("Lendo PDF no aparelho (OCR offline)...")
            val result = localExtractor.extractFromPdfUri(context, uri)
            val elapsed = formatElapsedSeconds(startedAt)
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
                        message = buildLocalSuccessMessage("PDF lido offline ($elapsed)", draft, warning),
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

    fun extractFastAiFromImageUri(context: Context, uri: Uri) {
        lastMediaUri = uri
        lastMediaIsPdf = false
        lastOcrSnapshot = null
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _extractionState.value = ExtractionUiState.Loading("1/2 Capturando texto da foto no aparelho...")
            val snapshot = localExtractor.extractSnapshotFromImageUri(context, uri).getOrNull()
            lastOcrSnapshot = snapshot
            val ocrText = snapshot?.reconstructedText.orEmpty()
            lastRawOcrText = ocrText

            if (ocrText.length >= 60 && (snapshot?.lineCount ?: 0) >= 3) {
                _extractionState.value = ExtractionUiState.Loading("2/2 Estruturando itens com IA rápida...")
                val textAiResult = aiExtractor.extractFromOcrText(
                    context = context,
                    rawText = ocrText,
                    sourceType = InvoiceSourceType.IA_FOTO
                )
                val parsedViaText = textAiResult.getOrNull()
                if (parsedViaText != null && parsedViaText.items.isNotEmpty()) {
                    applyParsedDraft(parsedViaText, replaceItems = false)
                    val elapsed = formatElapsedSeconds(startedAt)
                    val warning = checkThermalConsistency(
                        parsedViaText.items,
                        parsedViaText.extractedTempCelsius ?: _formState.value.parsedTempCelsius,
                        parsedViaText.items.firstOrNull()?.temperatureZone ?: _formState.value.targetZone
                    )
                    _extractionState.value = ExtractionUiState.Success(
                        message = buildFastAiSuccessMessage(parsedViaText, elapsed, warning),
                        rawOcrText = ocrText,
                        canRetryFullVision = true
                    )
                    return@launch
                }

                val textErr = textAiResult.exceptionOrNull()
                if (aiExtractor.isAppCheckFailure(textErr)) {
                    _extractionState.value = ExtractionUiState.Error(
                        message = textErr?.localizedMessage ?: "Falha na validação App Check da IA na nuvem.",
                        rawOcrText = ocrText,
                        canRetryWithCloud = true,
                        canUseOfflineFallback = true
                    )
                    return@launch
                }
            }

            // Fallback to compact JPEG upload when OCR is sparse (handwriting / low contrast)
            _extractionState.value = ExtractionUiState.Loading("Analisando imagem compactada com IA...")
            val result = aiExtractor.extractFromImageUri(context, uri)
            val elapsed = formatElapsedSeconds(startedAt)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = false)
                    val warning = checkThermalConsistency(
                        draft.items,
                        draft.extractedTempCelsius ?: _formState.value.parsedTempCelsius,
                        draft.items.firstOrNull()?.temperatureZone ?: _formState.value.targetZone
                    )
                    _extractionState.value = ExtractionUiState.Success(
                        message = buildFastAiSuccessMessage(draft, elapsed, warning),
                        rawOcrText = ocrText
                    )
                },
                onFailure = { err ->
                    val appCheckErr = aiExtractor.isAppCheckFailure(err)
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Não foi possível consultar a IA na nuvem.",
                        rawOcrText = ocrText,
                        canRetryFullVision = !appCheckErr,
                        canUseOfflineFallback = true
                    )
                }
            )
        }
    }

    fun extractFastAiFromPdfUri(context: Context, uri: Uri) {
        lastMediaUri = uri
        lastMediaIsPdf = true
        lastOcrSnapshot = null
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _extractionState.value = ExtractionUiState.Loading("1/2 Extraindo texto das páginas do PDF...")
            val snapshot = localExtractor.extractSnapshotFromPdfUri(context, uri).getOrNull()
            lastOcrSnapshot = snapshot
            val ocrText = snapshot?.reconstructedText.orEmpty()
            lastRawOcrText = ocrText

            if (ocrText.length >= 60 && (snapshot?.lineCount ?: 0) >= 3) {
                _extractionState.value = ExtractionUiState.Loading("2/2 Estruturando itens do PDF com IA rápida...")
                val textAiResult = aiExtractor.extractFromOcrText(
                    context = context,
                    rawText = ocrText,
                    sourceType = InvoiceSourceType.IA_PDF
                )
                val parsedViaText = textAiResult.getOrNull()
                if (parsedViaText != null && parsedViaText.items.isNotEmpty()) {
                    applyParsedDraft(parsedViaText, replaceItems = false)
                    val elapsed = formatElapsedSeconds(startedAt)
                    val warning = checkThermalConsistency(
                        parsedViaText.items,
                        parsedViaText.extractedTempCelsius ?: _formState.value.parsedTempCelsius,
                        parsedViaText.items.firstOrNull()?.temperatureZone ?: _formState.value.targetZone
                    )
                    _extractionState.value = ExtractionUiState.Success(
                        message = buildFastAiSuccessMessage(parsedViaText, elapsed, warning),
                        rawOcrText = ocrText,
                        canRetryFullVision = true
                    )
                    return@launch
                }

                val textErr = textAiResult.exceptionOrNull()
                if (aiExtractor.isAppCheckFailure(textErr)) {
                    _extractionState.value = ExtractionUiState.Error(
                        message = textErr?.localizedMessage ?: "Falha na validação App Check da IA na nuvem.",
                        rawOcrText = ocrText,
                        canRetryWithCloud = true,
                        canUseOfflineFallback = true
                    )
                    return@launch
                }
            }

            _extractionState.value = ExtractionUiState.Loading("Enviando PDF para leitura visual por IA...")
            val result = aiExtractor.extractFromPdfUri(context, uri)
            val elapsed = formatElapsedSeconds(startedAt)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = false)
                    val warning = checkThermalConsistency(
                        draft.items,
                        draft.extractedTempCelsius ?: _formState.value.parsedTempCelsius,
                        draft.items.firstOrNull()?.temperatureZone ?: _formState.value.targetZone
                    )
                    _extractionState.value = ExtractionUiState.Success(
                        message = buildFastAiSuccessMessage(draft, elapsed, warning),
                        rawOcrText = ocrText
                    )
                },
                onFailure = { err ->
                    val appCheckErr = aiExtractor.isAppCheckFailure(err)
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Não foi possível ler o PDF com IA.",
                        rawOcrText = ocrText,
                        canRetryFullVision = !appCheckErr,
                        canUseOfflineFallback = true
                    )
                }
            )
        }
    }

    fun useOfflineFallbackForLastMedia(context: Context) {
        val snapshot = lastOcrSnapshot
        val rawText = lastRawOcrText
        val mediaUri = lastMediaUri
        val isPdf = lastMediaIsPdf
        val sourceType = if (isPdf) InvoiceSourceType.OCR_PDF else InvoiceSourceType.OCR_FOTO

        if (snapshot != null && snapshot.reconstructedText.isNotBlank()) {
            viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                _extractionState.value = ExtractionUiState.Loading("Processando texto reconhecido offline...")
                val result = LocalInvoiceOcrParser.parsePages(snapshot.pages, sourceType)
                val elapsed = formatElapsedSeconds(startedAt)
                result.fold(
                    onSuccess = { draft ->
                        applyParsedDraft(draft, replaceItems = false)
                        val warning = checkThermalConsistency(
                            draft.items,
                            draft.extractedTempCelsius ?: _formState.value.parsedTempCelsius,
                            draft.items.firstOrNull()?.temperatureZone ?: _formState.value.targetZone
                        )
                        _extractionState.value = ExtractionUiState.Success(
                            message = buildLocalSuccessMessage("Nota lida offline ($elapsed)", draft, warning),
                            rawOcrText = draft.rawOcrText,
                            canRetryWithCloud = true
                        )
                    },
                    onFailure = { err ->
                        _extractionState.value = ExtractionUiState.Error(
                            message = err.localizedMessage ?: "Não foi possível extrair os dados offline.",
                            rawOcrText = rawText
                        )
                    }
                )
            }
            return
        }

        if (rawText.isNotBlank()) {
            viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                _extractionState.value = ExtractionUiState.Loading("Estruturando texto offline no aparelho...")
                val result = LocalInvoiceOcrParser.parseRawText(rawText, sourceType)
                val elapsed = formatElapsedSeconds(startedAt)
                result.fold(
                    onSuccess = { draft ->
                        applyParsedDraft(draft, replaceItems = false)
                        _extractionState.value = ExtractionUiState.Success(
                            message = buildLocalSuccessMessage("Nota lida offline ($elapsed)", draft, null),
                            rawOcrText = rawText,
                            canRetryWithCloud = true
                        )
                    },
                    onFailure = { err ->
                        _extractionState.value = ExtractionUiState.Error(
                            message = err.localizedMessage ?: "Não foi possível extrair os dados offline.",
                            rawOcrText = rawText
                        )
                    }
                )
            }
            return
        }

        if (mediaUri != null) {
            if (isPdf) extractFromPdfUri(context, mediaUri) else extractFromImageUri(context, mediaUri)
        }
    }

    fun retryLastMediaWithCloudReserve(context: Context) {
        val ocrText = lastRawOcrText
        if (ocrText.isNotBlank()) {
            viewModelScope.launch {
                val startedAt = System.currentTimeMillis()
                _extractionState.value = ExtractionUiState.Loading("Estruturando com IA rápida (modo texto)...")
                val sourceType = if (lastMediaIsPdf) InvoiceSourceType.IA_PDF else InvoiceSourceType.IA_FOTO
                val result = aiExtractor.extractFromOcrText(context, ocrText, sourceType)
                val elapsed = formatElapsedSeconds(startedAt)
                result.fold(
                    onSuccess = { draft ->
                        applyParsedDraft(draft, replaceItems = false)
                        _extractionState.value = ExtractionUiState.Success(
                            message = buildFastAiSuccessMessage(draft, elapsed, null),
                            rawOcrText = ocrText,
                            canRetryFullVision = lastMediaUri != null
                        )
                    },
                    onFailure = { err ->
                        val appCheckErr = aiExtractor.isAppCheckFailure(err)
                        _extractionState.value = ExtractionUiState.Error(
                            message = err.localizedMessage ?: "Não foi possível consultar a IA na nuvem.",
                            rawOcrText = ocrText,
                            canRetryWithCloud = true,
                            canRetryFullVision = !appCheckErr && lastMediaUri != null,
                            canUseOfflineFallback = true
                        )
                    }
                )
            }
            return
        }

        reanalyzeLastMediaWithFullVision(context)
    }

    fun reanalyzeLastMediaWithFullVision(context: Context) {
        val uri = lastMediaUri ?: return
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _extractionState.value = ExtractionUiState.Loading(
                if (lastMediaIsPdf) "Analisando PDF completo com IA..." else "Analisando imagem completa com IA..."
            )
            val result = if (lastMediaIsPdf) {
                aiExtractor.extractFromPdfUri(context, uri)
            } else {
                aiExtractor.extractFromImageUri(context, uri)
            }
            val elapsed = formatElapsedSeconds(startedAt)
            result.fold(
                onSuccess = { draft ->
                    applyParsedDraft(draft, replaceItems = false)
                    _extractionState.value = ExtractionUiState.Success(
                        message = buildFastAiSuccessMessage(draft, elapsed, null),
                        rawOcrText = lastRawOcrText
                    )
                },
                onFailure = { err ->
                    val appCheckErr = aiExtractor.isAppCheckFailure(err)
                    _extractionState.value = ExtractionUiState.Error(
                        message = err.localizedMessage ?: "Não foi possível consultar a IA na nuvem.",
                        rawOcrText = lastRawOcrText,
                        canRetryFullVision = !appCheckErr,
                        canUseOfflineFallback = true
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
        if (state.isSaving) return
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
        val defaultResponsible = state.responsibleName.trim().ifBlank {
            sessionState.value.currentMember?.displayName.orEmpty()
        }
        val fingerprint = buildSubmissionFingerprint(nf, supplier, state.items)
        if (fingerprint == lastSavedFingerprint && (now - lastSavedAtMillis) < 15_000L) {
            return
        }

        _formState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            saveMutex.withLock {
                if (fingerprint == lastSavedFingerprint &&
                    (System.currentTimeMillis() - lastSavedAtMillis) < 15_000L
                ) {
                    _formState.update { it.copy(isSaving = false) }
                    return@withLock
                }

                val session = ReceivingSessionEntity(
                    invoiceNumber = nf,
                    accessKey = state.accessKey.trim(),
                    supplierName = supplier,
                    supplierCnpj = state.supplierCnpj.trim(),
                    receivedAtMillis = now,
                    measuredTempCelsius = temp,
                    targetZone = state.targetZone.name,
                    thermalStatus = compliance.name,
                    responsibleName = defaultResponsible,
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

                repository.saveReceivingSession(session, entities).fold(
                    onSuccess = {
                        lastSavedFingerprint = fingerprint
                        lastSavedAtMillis = System.currentTimeMillis()
                        lastMediaUri = null
                        lastRawOcrText = ""
                        _formState.value = ReceivingFormState(responsibleName = defaultResponsible)
                        _extractionState.value = ExtractionUiState.Success(
                            "Recebimento salvo! ${entities.size} lote(s) entraram na câmara fria."
                        )
                    },
                    onFailure = { err ->
                        _formState.update { it.copy(isSaving = false) }
                        _extractionState.value = ExtractionUiState.Error(
                            friendlyFirestoreMessage(err, "Erro ao salvar recebimento na nuvem.")
                        )
                    }
                )
            }
        }
    }

    private fun buildSubmissionFingerprint(
        invoiceNumber: String,
        supplierName: String,
        items: List<ParsedInvoiceItemDraft>
    ): String {
        val itemsPart = items.joinToString("|") {
            "${it.cutName.trim().lowercase()}:${it.quantity}:${it.unit.name}:${it.batchCode.trim()}:${it.expiryEpochDay}"
        }
        return "${invoiceNumber.lowercase()}|${supplierName.lowercase()}|$itemsPart"
    }

    fun resetForm() {
        lastMediaUri = null
        lastRawOcrText = ""
        _formState.value = ReceivingFormState()
        _extractionState.value = ExtractionUiState.Idle
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId).fold(
                onSuccess = {
                    _extractionState.value = ExtractionUiState.Success("Sessão removida do histórico.")
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        friendlyFirestoreMessage(err, "Apenas o gerente pode excluir sessões.")
                    )
                }
            )
        }
    }

    fun observeMovementsForItem(itemId: String): Flow<List<StockMovementEntity>> =
        repository.observeMovementsForItem(itemId).catch { error ->
            val rootError = unwrapFirestoreStreamError(error)
            Log.w("ReceivingVM", "Error observing item movements", rootError)
            emit(emptyList())
        }

    fun recordStockOut(
        itemId: String,
        movementType: MovementType,
        quantity: Double,
        reason: String
    ) {
        viewModelScope.launch {
            repository.recordStockOut(itemId, movementType, quantity, reason).fold(
                onSuccess = { ok ->
                    _extractionState.value = if (ok) {
                        ExtractionUiState.Success("Baixa registrada: ${movementType.displayName}")
                    } else {
                        ExtractionUiState.Error("Quantidade inválida para baixa.")
                    }
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        friendlyFirestoreMessage(err, "Erro ao registrar baixa no estoque.")
                    )
                }
            )
        }
    }

    fun transferChamber(
        itemId: String,
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double
    ) {
        viewModelScope.launch {
            repository.transferChamber(itemId, chamberName, zone, tempCelsius).fold(
                onSuccess = {
                    _extractionState.value = ExtractionUiState.Success("Lote atualizado para $chamberName.")
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        friendlyFirestoreMessage(err, "Erro ao atualizar câmara/temperatura.")
                    )
                }
            )
        }
    }

    fun updateExpiry(itemId: String, expiryEpochDay: Long) {
        viewModelScope.launch {
            repository.updateExpiry(itemId, expiryEpochDay).fold(
                onSuccess = {
                    _extractionState.value = ExtractionUiState.Success("Validade atualizada.")
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        friendlyFirestoreMessage(err, "Erro ao atualizar validade.")
                    )
                }
            )
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            repository.deleteItem(itemId).fold(
                onSuccess = {
                    _extractionState.value = ExtractionUiState.Success("Lote removido do estoque.")
                },
                onFailure = { err ->
                    _extractionState.value = ExtractionUiState.Error(
                        friendlyFirestoreMessage(err, "Apenas o gerente pode excluir lotes.")
                    )
                }
            )
        }
    }

    private fun checkThermalConsistency(items: List<ParsedInvoiceItemDraft>, sessionTemp: Double?, targetZone: TemperatureZone): String? {
        if (sessionTemp != null) {
            val sessionCompliance = ThermalCompliance.evaluate(targetZone, sessionTemp)
            if (sessionCompliance == ThermalCompliance.CRITICO) {
                return "⚠️ ALERTA CRÍTICO: Temperatura de chegada (${DateUtils.formatTemp(sessionTemp)}) fora do padrão para ${targetZone.displayName}!"
            }
        }
        val criticalItems = items.filter { ThermalCompliance.evaluate(it.temperatureZone, it.measuredTempCelsius) == ThermalCompliance.CRITICO }
        if (criticalItems.isNotEmpty()) {
            return "⚠️ ATENÇÃO: ${criticalItems.size} lote(s) com temperatura crítica fora do padrão térmico."
        }
        val warningItems = items.filter { ThermalCompliance.evaluate(it.temperatureZone, it.measuredTempCelsius) == ThermalCompliance.ATENCAO }
        if (warningItems.isNotEmpty()) {
            return "⚠️ Nota: ${warningItems.size} lote(s) com temperatura em faixa de atenção."
        }
        if (sessionTemp != null && ThermalCompliance.evaluate(targetZone, sessionTemp) == ThermalCompliance.ATENCAO) {
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

    private fun buildFastAiSuccessMessage(
        draft: ParsedInvoiceDraft,
        elapsedLabel: String,
        consistencyWarning: String?
    ): String {
        val parts = mutableListOf<String>()
        if (draft.invoiceNumber.isNotBlank()) parts.add("NF ${draft.invoiceNumber}")
        parts.add("${draft.items.size} produto(s)")
        val base = "IA Rápida ($elapsedLabel): ${parts.joinToString(", ")} extraído(s). Revise antes de salvar."
        return if (consistencyWarning != null) "$base\n$consistencyWarning" else base
    }

    private fun formatElapsedSeconds(startedAtMillis: Long): String {
        val sec = ((System.currentTimeMillis() - startedAtMillis).coerceAtLeast(100L)) / 1000.0
        return String.format(Locale.US, "%.1fs", sec)
    }

    private fun applyParsedDraft(draft: ParsedInvoiceDraft, replaceItems: Boolean) {
        _formState.update { current ->
            val inferredZone = draft.items.firstOrNull()?.temperatureZone ?: current.targetZone
            val currentSessionTemp = current.parsedTempCelsius
            val userCustomizedTemp = currentSessionTemp != null &&
                abs(currentSessionTemp - current.targetZone.defaultTempCelsius) >= 0.05

            val effectiveSessionTemp = when {
                draft.extractedTempCelsius != null -> draft.extractedTempCelsius
                currentSessionTemp != null && userCustomizedTemp && inferredZone == current.targetZone -> currentSessionTemp
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
