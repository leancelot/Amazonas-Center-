package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.DateUtils
import com.example.data.ReceivingSessionSummary
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.ParsedInvoiceItemDraft
import com.example.ui.components.ExpiryUrgencyBadge
import com.example.ui.components.ManualItemSheet
import com.example.ui.components.MeatTypePill
import com.example.ui.components.TemperatureZonePill
import com.example.ui.components.ThermalComplianceBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.ExtractionUiState
import com.example.ui.viewmodel.ParkedReceivingDraft
import com.example.ui.viewmodel.ReceivingViewModel
import java.io.File
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReceivingScreen(
    viewModel: ReceivingViewModel,
    onOpenBarcodeScanner: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val extractionState by viewModel.extractionState.collectAsStateWithLifecycle()
    val pastSessions by viewModel.pastSessions.collectAsStateWithLifecycle()
    val pendingQueue by viewModel.pendingQueue.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var useFastCloudAi by remember { mutableStateOf(true) }
    var editingItem by remember { mutableStateOf<ParsedInvoiceItemDraft?>(null) }
    var showManualItemSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteSession by remember { mutableStateOf<ReceivingSessionSummary?>(null) }

    var quickStep by remember { mutableIntStateOf(0) } // 0 = Entrada na Portaria, 1 = Lançamento da Nota
    var qSupplier by remember { mutableStateOf("") }
    var qInvoice by remember { mutableStateOf("") }
    var qResponsible by remember { mutableStateOf("") }
    var qMeatType by remember { mutableStateOf(com.example.data.MeatType.BOVINA) }
    var qCutName by remember { mutableStateOf("") }
    var qZone by remember { mutableStateOf(TemperatureZone.RESFRIADO) }
    var qTempText by remember { mutableStateOf("2.0") }
    var qQtyText by remember { mutableStateOf("") }
    var qUnit by remember { mutableStateOf(com.example.data.MeasurementUnit.KG) }
    var qBatch by remember { mutableStateOf("") }
    var qExpiryEpochDay by remember { mutableLongStateOf(DateUtils.addDaysToTodayEpochDay(7)) }
    var qShowDateDialog by remember { mutableStateOf(false) }
    var qErrorMsg by remember { mutableStateOf<String?>(null) }

    val activeMemberName = sessionState.currentMember?.displayName.orEmpty()
    androidx.compose.runtime.LaunchedEffect(activeMemberName) {
        if (qResponsible.isBlank() && activeMemberName.isNotBlank()) {
            qResponsible = activeMemberName
        }
        if (formState.responsibleName.isBlank() && activeMemberName.isNotBlank()) {
            viewModel.updateResponsibleName(activeMemberName)
        }
    }

    androidx.compose.runtime.LaunchedEffect(extractionState) {
        val success = extractionState as? ExtractionUiState.Success
        if (success != null && success.message.startsWith("Recebimento salvo!")) {
            qInvoice = ""
            qResponsible = activeMemberName
            qSupplier = ""
            qCutName = ""
            qQtyText = ""
            qBatch = ""
            qErrorMsg = null
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            if (useFastCloudAi) {
                viewModel.extractFastAiFromImageUri(context, it)
            } else {
                viewModel.extractFromImageUri(context, it)
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            if (useFastCloudAi) {
                viewModel.extractFastAiFromImageUri(context, uri)
            } else {
                viewModel.extractFromImageUri(context, uri)
            }
        }
    }

    val cameraPermissionForPhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createInvoiceImageUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        }
    }

    val xmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importXmlUri(context, it) }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            if (useFastCloudAi) {
                viewModel.extractFastAiFromPdfUri(context, it)
            } else {
                viewModel.extractFromPdfUri(context, it)
            }
        }
    }

    fun launchCameraDirect() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val uri = createInvoiceImageUri(context)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        } else {
            cameraPermissionForPhoto.launch(Manifest.permission.CAMERA)
        }
    }

    fun commitPendingQuickItemIfEntered(): Boolean {
        val qty = qQtyText.replace(',', '.').toDoubleOrNull()
        val temp = qTempText.replace(',', '.').toDoubleOrNull() ?: qZone.defaultTempCelsius
        if (qty != null && qty > 0.0) {
            viewModel.addQuickEntryItem(
                supplier = qSupplier.trim(),
                invoiceNum = qInvoice.trim(),
                responsible = qResponsible.trim(),
                meatType = qMeatType,
                cutName = qCutName.trim(),
                zone = qZone,
                tempCelsius = temp,
                quantity = qty,
                unit = qUnit,
                batch = qBatch.trim(),
                expiryEpochDay = qExpiryEpochDay
            )
            qQtyText = ""
            qBatch = ""
            qCutName = ""
            return true
        }
        if (qSupplier.isNotBlank()) viewModel.updateSupplierName(qSupplier.trim())
        if (qInvoice.isNotBlank()) viewModel.updateInvoiceNumber(qInvoice.trim())
        if (qResponsible.isNotBlank()) viewModel.updateResponsibleName(qResponsible.trim())
        return false
    }

    fun clearQuickFieldsAfterQueueOrSave() {
        qInvoice = ""
        qSupplier = ""
        qCutName = ""
        qQtyText = ""
        qBatch = ""
        qErrorMsg = null
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Sessão de Recebimento",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Conferência rápida na portaria, fila de entregas e leitura com IA",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Nova Sessão (${formState.items.size})") },
                    modifier = Modifier.testTag("tab_new_session")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Histórico (${pastSessions.size})") },
                    modifier = Modifier.testTag("tab_past_sessions")
                )
            }

            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = { quickStep = 0 },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (quickStep == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        contentColor = if (quickStep == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("tab_quick_entry")
                                ) {
                                    Text("1. Entrada de Nota", fontWeight = FontWeight.SemiBold)
                                }
                                Button(
                                    onClick = { quickStep = 1 },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (quickStep == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        contentColor = if (quickStep == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("tab_invoice_launch")
                                ) {
                                    Text("2. Lançamento (${formState.items.size})", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    if (pendingQueue.isNotEmpty()) {
                        item {
                            PendingQueueSection(
                                queue = pendingQueue,
                                onResume = { parked ->
                                    viewModel.resumeParkedEntry(parked.id)
                                    qInvoice = parked.form.invoiceNumber
                                    qSupplier = parked.form.supplierName
                                    if (parked.form.responsibleName.isNotBlank()) {
                                        qResponsible = parked.form.responsibleName
                                    }
                                    quickStep = 1
                                },
                                onRemove = { parked -> viewModel.removeParkedEntry(parked.id) }
                            )
                        }
                    }

                    if (quickStep == 0) {
                        item {
                            ExtractionBanner(
                                state = extractionState,
                                onRetryCloud = { viewModel.retryLastMediaWithCloudReserve(context) },
                                onRetryFullVision = { viewModel.reanalyzeLastMediaWithFullVision(context) },
                                onDismiss = viewModel::clearExtractionBanner
                            )
                        }
                        item {
                            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Entrega na Portaria • Perguntas Rápidas",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "Pese na balança, confira a temperatura e libere o motorista.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = onOpenBarcodeScanner,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            modifier = Modifier.testTag("quick_scan_danfe_button")
                                        ) {
                                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("QR/NF")
                                        }
                                    }

                                    Text("1. Nº da nota e quem recebeu?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = qInvoice.ifBlank { formState.invoiceNumber },
                                            onValueChange = {
                                                qInvoice = it
                                                viewModel.updateInvoiceNumber(it)
                                                qErrorMsg = null
                                            },
                                            label = { Text("Nº da NF *") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("quick_nf_input")
                                        )
                                        OutlinedTextField(
                                            value = qResponsible,
                                            onValueChange = { qResponsible = it; qErrorMsg = null },
                                            label = { Text("Responsável *") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .testTag("quick_responsible_input")
                                        )
                                    }

                                    OutlinedTextField(
                                        value = qSupplier.ifBlank { formState.supplierName },
                                        onValueChange = {
                                            qSupplier = it
                                            viewModel.updateSupplierName(it)
                                            qErrorMsg = null
                                        },
                                        label = { Text("Frigorífico / Fornecedor (opcional agora)") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("quick_supplier_input")
                                    )

                                    val recentSuppliers = remember(pastSessions) {
                                        pastSessions.map { it.supplierName }
                                            .filter { it.isNotBlank() && it != "Fornecedor não informado" }
                                            .distinct()
                                            .take(4)
                                    }
                                    if (recentSuppliers.isNotEmpty() && qSupplier.isBlank() && formState.supplierName.isBlank()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            recentSuppliers.forEach { sup ->
                                                FilterChip(
                                                    selected = false,
                                                    onClick = {
                                                        qSupplier = sup
                                                        viewModel.updateSupplierName(sup)
                                                    },
                                                    label = { Text(sup) }
                                                )
                                            }
                                        }
                                    }

                                    Text("2. Qual tipo de carne está chegando?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        com.example.data.MeatType.entries.forEach { type ->
                                            FilterChip(
                                                selected = qMeatType == type,
                                                onClick = {
                                                    qMeatType = type
                                                    val days = if (qZone == TemperatureZone.CONGELADO) type.defaultFrozenDays else type.defaultChilledDays
                                                    qExpiryEpochDay = DateUtils.addDaysToTodayEpochDay(days)
                                                },
                                                label = { Text(type.displayName) },
                                                modifier = Modifier.testTag("quick_meat_${type.name.lowercase()}")
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = qCutName,
                                        onValueChange = { qCutName = it },
                                        label = { Text("Corte específico (opcional)") },
                                        placeholder = { Text("Ex.: Picanha, Alcatra, Peito...") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("quick_cut_input")
                                    )

                                    val quickCutMatches = remember(qCutName, qMeatType) {
                                        if (qCutName.isNotBlank()) {
                                            qMeatType.presetCuts
                                                .filter { it.contains(qCutName, ignoreCase = true) && !it.equals(qCutName, ignoreCase = true) }
                                                .take(4)
                                        } else {
                                            qMeatType.presetCuts.take(4)
                                        }
                                    }
                                    if (quickCutMatches.isNotEmpty()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            quickCutMatches.forEach { suggestion ->
                                                FilterChip(
                                                    selected = qCutName.equals(suggestion, ignoreCase = true),
                                                    onClick = { qCutName = suggestion },
                                                    label = { Text(suggestion) }
                                                )
                                            }
                                        }
                                    }

                                    Text("3. Produto resfriado ou congelado? Qual a temperatura?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        TemperatureZone.primaryZones.forEach { z ->
                                            FilterChip(
                                                selected = qZone == z,
                                                onClick = {
                                                    qZone = z
                                                    qTempText = String.format(Locale.US, "%.1f", z.defaultTempCelsius)
                                                    val days = if (z == TemperatureZone.CONGELADO) qMeatType.defaultFrozenDays else qMeatType.defaultChilledDays
                                                    qExpiryEpochDay = DateUtils.addDaysToTodayEpochDay(days)
                                                },
                                                label = { Text("${z.displayName} (${z.rangeLabel})") },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("quick_zone_${z.name.lowercase()}")
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = qTempText,
                                            onValueChange = { qTempText = ReceivingViewModel.sanitizeTemperatureInput(it) },
                                            label = { Text("Temp. chegada (°C) *") },
                                            trailingIcon = {
                                                TextButton(onClick = { qTempText = ReceivingViewModel.toggleTemperatureSign(qTempText) }) {
                                                    Text("±", fontWeight = FontWeight.Bold)
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1.1f)
                                                .testTag("quick_temp_input")
                                        )
                                        qTempText.replace(',', '.').toDoubleOrNull()?.let { t ->
                                            ThermalComplianceBadge(
                                                compliance = ThermalCompliance.evaluate(qZone, t),
                                                tempCelsius = t,
                                                showTempValue = false
                                            )
                                        }
                                    }

                                    val tempPresets = remember(qZone) {
                                        if (qZone == TemperatureZone.CONGELADO) {
                                            listOf("-20.0", "-18.0", "-15.0", "-12.0")
                                        } else {
                                            listOf("-1.0", "0.0", "2.0", "4.0", "6.0")
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        tempPresets.forEach { preset ->
                                            FilterChip(
                                                selected = qTempText == preset,
                                                onClick = { qTempText = preset },
                                                label = { Text("${preset}°C") }
                                            )
                                        }
                                    }

                                    Text("4. Peso da balança, lote e validade", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = qQtyText,
                                            onValueChange = { qQtyText = it; qErrorMsg = null },
                                            label = { Text("Peso balança / Qtd *") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .testTag("quick_qty_input")
                                        )
                                        FlowRow(
                                            modifier = Modifier.weight(1.1f),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            com.example.data.MeasurementUnit.entries.forEach { u ->
                                                FilterChip(
                                                    selected = qUnit == u,
                                                    onClick = { qUnit = u },
                                                    label = { Text(u.shortLabel) }
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = qBatch,
                                            onValueChange = { qBatch = it },
                                            label = { Text("Lote") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("quick_batch_input")
                                        )
                                        OutlinedButton(
                                            onClick = { qShowDateDialog = true },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp)
                                        ) {
                                            Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text(DateUtils.formatEpochDay(qExpiryEpochDay))
                                        }
                                    }

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf(5 to "+5d", 7 to "+7d", 15 to "+15d", 30 to "+30d", 90 to "+90d").forEach { (days, label) ->
                                            FilterChip(
                                                selected = DateUtils.daysUntil(qExpiryEpochDay) == days,
                                                onClick = { qExpiryEpochDay = DateUtils.addDaysToTodayEpochDay(days) },
                                                label = { Text(label) }
                                            )
                                        }
                                    }

                                    if (formState.items.isNotEmpty()) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "Conferidos nesta entrega (${formState.items.size} lote(s)):",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                formState.items.forEach { checked ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "${checked.cutName} • ${DateUtils.formatWeightOrQty(checked.quantity, checked.unit)} • ${DateUtils.formatTemp(checked.measuredTempCelsius)}",
                                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        IconButton(
                                                            onClick = { viewModel.removeDraftItem(checked.localId) },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Rounded.Close,
                                                                contentDescription = "Remover corte",
                                                                modifier = Modifier.size(16.dp),
                                                                tint = MaterialTheme.colorScheme.error
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    qErrorMsg?.let { msg ->
                                        Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val qty = qQtyText.replace(',', '.').toDoubleOrNull()
                                            val temp = qTempText.replace(',', '.').toDoubleOrNull() ?: qZone.defaultTempCelsius
                                            when {
                                                qty == null || qty <= 0.0 -> qErrorMsg = "Informe o peso da balança ou quantidade para adicionar."
                                                else -> {
                                                    viewModel.addQuickEntryItem(
                                                        supplier = qSupplier.trim(),
                                                        invoiceNum = qInvoice.trim(),
                                                        responsible = qResponsible.trim(),
                                                        meatType = qMeatType,
                                                        cutName = qCutName.trim(),
                                                        zone = qZone,
                                                        tempCelsius = temp,
                                                        quantity = qty,
                                                        unit = qUnit,
                                                        batch = qBatch.trim(),
                                                        expiryEpochDay = qExpiryEpochDay
                                                    )
                                                    qQtyText = ""
                                                    qBatch = ""
                                                    qCutName = ""
                                                    Toast.makeText(context, "Corte adicionado! Informe o próximo da entrega.", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("quick_add_another_button")
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Adicionar Outro Corte da Entrega")
                                    }

                                    Button(
                                        onClick = {
                                            val qty = qQtyText.replace(',', '.').toDoubleOrNull()
                                            val hasExistingItems = formState.items.isNotEmpty()
                                            val resolvedNf = qInvoice.ifBlank { formState.invoiceNumber }
                                            when {
                                                resolvedNf.isBlank() -> qErrorMsg = "Informe o número da nota (NF)."
                                                qResponsible.isBlank() && formState.responsibleName.isBlank() -> qErrorMsg = "Informe o responsável pelo recebimento."
                                                !hasExistingItems && (qty == null || qty <= 0.0) -> qErrorMsg = "Informe o peso da balança ou quantidade."
                                                else -> {
                                                    commitPendingQuickItemIfEntered()
                                                    Toast.makeText(context, "Entregador liberado! Complete o lançamento da nota.", Toast.LENGTH_LONG).show()
                                                    quickStep = 1
                                                }
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .testTag("quick_release_driver_button")
                                    ) {
                                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Liberar Entregador & Ir p/ Lançamento")
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val qty = qQtyText.replace(',', '.').toDoubleOrNull()
                                                val hasItems = formState.items.isNotEmpty() || (qty != null && qty > 0.0)
                                                val resolvedNf = qInvoice.ifBlank { formState.invoiceNumber }
                                                if (!hasItems && resolvedNf.isBlank()) {
                                                    qErrorMsg = "Preencha ao menos NF ou produto antes de colocar na fila."
                                                    return@OutlinedButton
                                                }
                                                commitPendingQuickItemIfEntered()
                                                if (viewModel.parkCurrentEntryInQueue()) {
                                                    clearQuickFieldsAfterQueueOrSave()
                                                    Toast.makeText(context, "Entrada guardada na fila! Pronto para a próxima entrega.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("quick_park_queue_button")
                                        ) {
                                            Icon(Icons.Rounded.HourglassTop, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Guardar na Fila", maxLines = 1)
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                val qty = qQtyText.replace(',', '.').toDoubleOrNull()
                                                val hasExisting = formState.items.isNotEmpty()
                                                when {
                                                    qResponsible.isBlank() && formState.responsibleName.isBlank() -> qErrorMsg = "Informe o responsável."
                                                    !hasExisting && (qty == null || qty <= 0.0) -> qErrorMsg = "Informe o peso ou quantidade."
                                                    else -> {
                                                        commitPendingQuickItemIfEntered()
                                                        viewModel.confirmAndSaveSession()
                                                    }
                                                }
                                            },
                                            enabled = !formState.isSaving,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("quick_direct_save_button")
                                        ) {
                                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Salvar Direto", maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (formState.items.isNotEmpty()) {
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Entrada registrada (${formState.items.size} lote(s) • NF ${formState.invoiceNumber.ifBlank { "—" }}). Anexe a nota ou revise os lotes abaixo.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                        item {
                            ExtractionBanner(
                                state = extractionState,
                                onRetryCloud = { viewModel.retryLastMediaWithCloudReserve(context) },
                                onRetryFullVision = { viewModel.reanalyzeLastMediaWithFullVision(context) },
                                onDismiss = viewModel::clearExtractionBanner
                            )
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Leitura Automática da Nota Fiscal",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = useFastCloudAi,
                                        onClick = { useFastCloudAi = true },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                                        },
                                        label = { Text("IA Rápida (Nuvem)", fontWeight = FontWeight.SemiBold) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("mode_fast_cloud_ai")
                                    )
                                    FilterChip(
                                        selected = !useFastCloudAi,
                                        onClick = { useFastCloudAi = false },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.WifiOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                        },
                                        label = { Text("100% Offline", fontWeight = FontWeight.SemiBold) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("mode_offline_ocr")
                                    )
                                }

                                Text(
                                    text = if (useFastCloudAi) {
                                        "Modo IA Rápida: lê o texto no celular e estrutura tudo na nuvem em poucos segundos."
                                    } else {
                                        "Modo Offline: reconhece DANFE no aparelho sem gastar internet."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    InvoiceActionTile(
                                        icon = Icons.Rounded.CameraAlt,
                                        title = "Tirar Foto",
                                        subtitle = if (useFastCloudAi) "IA Rápida" else "OCR Local",
                                        onClick = ::launchCameraDirect,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("action_invoice_camera")
                                    )
                                    InvoiceActionTile(
                                        icon = Icons.Rounded.PhotoLibrary,
                                        title = "Galeria",
                                        subtitle = if (useFastCloudAi) "IA Rápida" else "OCR Local",
                                        onClick = {
                                            galleryLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("action_invoice_gallery")
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    InvoiceActionTile(
                                        icon = Icons.Rounded.PictureAsPdf,
                                        title = "PDF Nota",
                                        subtitle = if (useFastCloudAi) "IA Rápida" else "OCR Local",
                                        onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("action_invoice_pdf_ai")
                                    )
                                    InvoiceActionTile(
                                        icon = Icons.Rounded.Code,
                                        title = "XML NF-e",
                                        subtitle = "100% Exato",
                                        onClick = { xmlLauncher.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("action_invoice_xml")
                                    )
                                }
                                InvoiceActionTile(
                                    icon = Icons.Rounded.QrCodeScanner,
                                    title = "Ler QR / Código de Barras DANFE (44 dígitos)",
                                    subtitle = "Preenche nº da NF, série e CNPJ do fornecedor",
                                    onClick = onOpenBarcodeScanner,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("action_invoice_qr")
                                )
                            }
                        }

                        item {
                            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Dados da Sessão & Conferência Térmica",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )

                                    OutlinedTextField(
                                        value = formState.supplierName,
                                        onValueChange = viewModel::updateSupplierName,
                                        label = { Text("Fornecedor / Frigorífico") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("session_supplier_input")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = formState.invoiceNumber,
                                            onValueChange = viewModel::updateInvoiceNumber,
                                            label = { Text("Nº da NF") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("session_nf_input")
                                        )
                                        OutlinedTextField(
                                            value = formState.responsibleName,
                                            onValueChange = viewModel::updateResponsibleName,
                                            label = { Text("Conferente") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1.2f)
                                                .testTag("session_responsible_input")
                                        )
                                    }

                                    Text("Categoria & Temperatura Aferida", style = MaterialTheme.typography.labelLarge)
                                    val sessionZones = if (formState.targetZone == TemperatureZone.MATURACAO) {
                                        TemperatureZone.entries
                                    } else {
                                        TemperatureZone.primaryZones
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        sessionZones.forEach { z ->
                                            FilterChip(
                                                selected = formState.targetZone == z,
                                                onClick = { viewModel.updateTargetZone(z) },
                                                label = { Text("${z.displayName} (${z.rangeLabel})") },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("session_zone_${z.name.lowercase()}")
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = formState.measuredTempText,
                                            onValueChange = viewModel::updateMeasuredTemp,
                                            label = { Text("Temp. chegada (°C)") },
                                            trailingIcon = {
                                                TextButton(
                                                    onClick = {
                                                        viewModel.updateMeasuredTemp(
                                                            ReceivingViewModel.toggleTemperatureSign(formState.measuredTempText)
                                                        )
                                                    },
                                                    modifier = Modifier.testTag("session_temp_sign_button")
                                                ) {
                                                    Text("±", fontWeight = FontWeight.Bold)
                                                }
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                            singleLine = true,
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("session_temp_input")
                                        )
                                        formState.thermalCompliance?.let { compliance ->
                                            ThermalComplianceBadge(
                                                compliance = compliance,
                                                tempCelsius = formState.parsedTempCelsius,
                                                showTempValue = false
                                            )
                                        }
                                    }

                                    val sessionTemp = formState.parsedTempCelsius
                                    val divergentTemp = if (sessionTemp != null && formState.items.any { item ->
                                            item.temperatureZone == formState.targetZone &&
                                                abs(item.measuredTempCelsius - sessionTemp) >= 0.1
                                        }
                                    ) {
                                        sessionTemp
                                    } else {
                                        null
                                    }
                                    if (divergentTemp != null) {
                                        TextButton(
                                            onClick = viewModel::syncAllItemsToSessionTemp,
                                            modifier = Modifier.align(Alignment.End)
                                        ) {
                                            Text(
                                                text = "Aplicar ${DateUtils.formatTemp(divergentTemp)} nos itens ${formState.targetZone.displayName}"
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = formState.notes,
                                        onValueChange = viewModel::updateNotes,
                                        label = { Text("Observações da nota / sanitárias (opcional)") },
                                        minLines = 1,
                                        maxLines = 3,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("session_notes_input")
                                    )
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Produtos da Sessão (${formState.items.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedButton(
                                    onClick = {
                                        editingItem = null
                                        showManualItemSheet = true
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("add_manual_item_button")
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.cd_add_manual))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Produto Manual")
                                }
                            }
                        }

                        if (formState.items.isEmpty()) {
                            item {
                                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Nenhum produto adicionado ainda",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Tire foto, importe XML/PDF acima ou toque em Produto Manual.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            items(formState.items, key = { it.localId }) { draftItem ->
                                DraftItemCard(
                                    item = draftItem,
                                    sessionTempCelsius = formState.parsedTempCelsius,
                                    sessionZone = formState.targetZone,
                                    onEdit = {
                                        editingItem = draftItem
                                        showManualItemSheet = true
                                    },
                                    onRemove = { viewModel.removeDraftItem(draftItem.localId) }
                                )
                            }

                            item {
                                Spacer(Modifier.height(4.dp))
                                Button(
                                    onClick = viewModel::confirmAndSaveSession,
                                    enabled = !formState.isSaving,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("confirm_receiving_session_button")
                                ) {
                                    if (formState.isSaving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    } else {
                                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Confirmar Recebimento (${formState.items.size} lotes)",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (pastSessions.isEmpty()) {
                        item {
                            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Nenhuma sessão de recebimento registrada ainda.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(pastSessions, key = { it.id }) { session ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = session.supplierName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (sessionState.activeRole.canDeleteRecords) {
                                            IconButton(onClick = { pendingDeleteSession = session }) {
                                                Icon(
                                                    Icons.Rounded.Delete,
                                                    contentDescription = "Excluir sessão",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    val ptBr = Locale.forLanguageTag("pt-BR")
                                    Text(
                                        text = buildString {
                                            if (session.invoiceNumber.isNotBlank()) append("NF ${session.invoiceNumber} • ")
                                            append("${session.itemCount} itens")
                                            if (session.totalKg > 0) {
                                                append(" (${String.format(ptBr, "%.1f", session.totalKg)} kg)")
                                            }
                                            append(" • ${DateUtils.formatDateTime(session.receivedAtMillis)}")
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (session.responsibleName.isNotBlank()) {
                                        Text(
                                            text = "Conferente: ${session.responsibleName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (session.notes.isNotBlank()) {
                                        Text(
                                            text = session.notes,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    ThermalComplianceBadge(
                                        compliance = ThermalCompliance.entries.firstOrNull { it.name == session.thermalStatus }
                                            ?: ThermalCompliance.CONFORME,
                                        tempCelsius = session.measuredTempCelsius
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSession = null },
            title = { Text("Excluir sessão de recebimento?") },
            text = {
                Text("Remover o histórico de ${session.supplierName}" +
                    if (session.invoiceNumber.isNotBlank()) " (NF ${session.invoiceNumber})?" else "?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSession(session.id)
                        pendingDeleteSession = null
                    }
                ) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSession = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (qShowDateDialog) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.epochDayToUtcPickerMillis(qExpiryEpochDay)
        )
        DatePickerDialog(
            onDismissRequest = { qShowDateDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            qExpiryEpochDay = DateUtils.millisToEpochDay(millis)
                        }
                        qShowDateDialog = false
                    }
                ) {
                    Text("Confirmar")
                }
            },
            dismissButton = {
                TextButton(onClick = { qShowDateDialog = false }) {
                    Text("Cancelar")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showManualItemSheet) {
        ManualItemSheet(
            initialItem = editingItem,
            defaultZone = formState.targetZone,
            sessionTempCelsius = if (editingItem == null) formState.parsedTempCelsius else null,
            onDismiss = { showManualItemSheet = false },
            onSave = { item ->
                viewModel.addOrUpdateDraftItem(item)
                showManualItemSheet = false
            }
        )
    }
}

@Composable
private fun InvoiceActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DraftItemCard(
    item: ParsedInvoiceItemDraft,
    sessionTempCelsius: Double?,
    sessionZone: TemperatureZone,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val hasDistinctTemp = item.temperatureZone != sessionZone ||
        sessionTempCelsius == null ||
        abs(item.measuredTempCelsius - sessionTempCelsius) >= 0.1

    Card(
        onClick = onEdit,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("draft_item_card_${item.localId}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MeatTypePill(meatType = item.meatType)
                        TemperatureZonePill(zone = item.temperatureZone)
                    }
                    Text(
                        text = item.cutName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append(item.chamberName)
                            if (hasDistinctTemp) append(" • ${DateUtils.formatTemp(item.measuredTempCelsius)}")
                            if (item.batchCode.isNotBlank()) append(" • Lote ${item.batchCode}")
                            if (item.sifCode.isNotBlank()) append(" • SIF ${item.sifCode}")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (item.notes.isNotBlank()) {
                        Text(
                            text = "Obs.: ${item.notes}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = DateUtils.formatWeightOrQty(item.quantity, item.unit),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row {
                        IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Editar item", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remover item",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            ExpiryUrgencyBadge(expiryEpochDay = item.expiryEpochDay)
        }
    }
}

@Composable
private fun PendingQueueSection(
    queue: List<ParkedReceivingDraft>,
    onResume: (ParkedReceivingDraft) -> Unit,
    onRemove: (ParkedReceivingDraft) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Aguardando Lançamento (${queue.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            queue.forEach { parked ->
                val nfLabel = parked.form.invoiceNumber.ifBlank { "S/ NF" }
                val supLabel = parked.form.supplierName.ifBlank { "Fornecedor pendente" }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "NF $nfLabel • $supLabel",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${parked.form.items.size} lote(s) • ${DateUtils.formatDateTime(parked.parkedAtMillis)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { onResume(parked) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("resume_parked_${parked.id}")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Lançar")
                        }
                        IconButton(
                            onClick = { onRemove(parked) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remover da fila",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtractionBanner(
    state: ExtractionUiState,
    onRetryCloud: () -> Unit,
    onRetryFullVision: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showRawOcr by remember(state) { mutableStateOf(false) }

    when (state) {
        ExtractionUiState.Idle -> Unit
        is ExtractionUiState.Loading -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(state.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is ExtractionUiState.Success -> {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { copyTextToClipboard(context, state.message) }) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "Copiar mensagem",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close))
                        }
                    }
                    if (state.rawOcrText.isNotBlank() || state.canRetryWithCloud || state.canRetryFullVision) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (state.rawOcrText.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { showRawOcr = !showRawOcr },
                                    modifier = Modifier.testTag("toggle_raw_ocr_button")
                                ) {
                                    Icon(
                                        imageVector = if (showRawOcr) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (showRawOcr) "Ocultar OCR" else "Ver texto OCR")
                                }
                            }
                            if (state.canRetryWithCloud) {
                                OutlinedButton(
                                    onClick = onRetryCloud,
                                    modifier = Modifier.testTag("retry_cloud_reserve_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CloudQueue,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Estruturar c/ IA")
                                }
                            }
                            if (state.canRetryFullVision) {
                                OutlinedButton(
                                    onClick = onRetryFullVision,
                                    modifier = Modifier.testTag("retry_full_vision_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ImageSearch,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Reanalisar Imagem")
                                }
                            }
                        }
                    }
                    if (showRawOcr && state.rawOcrText.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Texto reconhecido localmente",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TextButton(onClick = { copyTextToClipboard(context, state.rawOcrText) }) {
                                        Text("Copiar OCR")
                                    }
                                }
                                Text(
                                    text = state.rawOcrText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily)
                                )
                            }
                        }
                    }
                }
            }
        }
        is ExtractionUiState.Error -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { copyTextToClipboard(context, state.message) },
                            modifier = Modifier.testTag("copy_extraction_error_button")
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "Copiar mensagem de erro",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.cd_close),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    if (state.canRetryWithCloud || state.canRetryFullVision) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (state.canRetryWithCloud) {
                                OutlinedButton(
                                    onClick = onRetryCloud,
                                    modifier = Modifier.testTag("retry_cloud_reserve_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CloudQueue,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Tentar IA Rápida")
                                }
                            }
                            if (state.canRetryFullVision) {
                                OutlinedButton(
                                    onClick = onRetryFullVision,
                                    modifier = Modifier.testTag("retry_full_vision_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ImageSearch,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Reanalisar Imagem")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Amazonas Center", text))
    Toast.makeText(context, "Mensagem copiada", Toast.LENGTH_SHORT).show()
}

private fun createInvoiceImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "invoices").apply { mkdirs() }
    val file = File(dir, "nota_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
