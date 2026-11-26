package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.BackupRestoreSummary
import com.example.data.ChamberTempLog
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.ReceivingSessionSummary
import com.example.data.StaffRole
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.notifications.ExpiryAlertCalculator
import com.example.notifications.ExpiryAlertSettings
import com.example.notifications.ExpiryNotificationManager
import com.example.ui.components.ChamberTemperatureSheet
import com.example.ui.components.ExpiryAlertsSheet
import com.example.ui.components.ExpiryUrgencyBadge
import com.example.ui.components.ItemDetailSheet
import com.example.ui.components.LocalBackupSheet
import com.example.ui.components.MeatTypePill
import com.example.ui.components.ReceivingSessionDetailSheet
import com.example.ui.components.StockOutSheet
import com.example.ui.components.TemperatureZonePill
import com.example.ui.components.ThermalComplianceBadge
import com.example.ui.theme.ColdNavyLight
import com.example.ui.theme.ColdNavyPrimary
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusDangerRedBg
import com.example.ui.theme.StatusDangerRedDarkBg
import com.example.ui.theme.StatusOkGreen
import com.example.ui.theme.StatusOkGreenBg
import com.example.ui.theme.StatusOkGreenDarkBg
import com.example.ui.theme.StatusWarningAmber
import com.example.ui.theme.StatusWarningAmberBg
import com.example.ui.theme.StatusWarningAmberDarkBg
import com.example.ui.viewmodel.ChamberSummary
import com.example.ui.viewmodel.DashboardViewModel
import com.example.ui.viewmodel.UrgencyFilter
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToReceiving: () -> Unit,
    onNavigateToInventory: (UrgencyFilter, TemperatureZone?) -> Unit,
    onOpenTeamAccess: () -> Unit,
    modifier: Modifier = Modifier,
    isLocalMode: Boolean = false,
    onSwitchToCloudSignIn: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var stockOutTarget by remember { mutableStateOf<ColdStorageItemEntity?>(null) }
    var selectedItem by remember { mutableStateOf<ColdStorageItemEntity?>(null) }
    var selectedSession by remember { mutableStateOf<ReceivingSessionSummary?>(null) }
    var showChamberTempSheet by remember { mutableStateOf(false) }
    var showExpiryAlertsSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var alertSettings by remember(context) {
        mutableStateOf(ExpiryNotificationManager.readSettings(context))
    }

    LaunchedEffect(state.feedbackMessage) {
        state.feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeroColdHeader(
                    shopName = state.sessionState.shopInfo?.name?.ifBlank { "AMAZONAS CENTER" } ?: "AMAZONAS CENTER",
                    memberName = state.sessionState.currentMember?.displayName.orEmpty(),
                    activeRole = state.activeRole,
                    totalKg = state.totalKg,
                    totalOtherUnits = state.totalOtherUnits,
                    activeLotCount = state.activeLotCount,
                    onReceiveClick = onNavigateToReceiving,
                    onOpenTeamAccess = onOpenTeamAccess
                )
            }

            if (isLocalMode) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("local_mode_dashboard_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Modo Local de Teste",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "Dados salvos apenas no aparelho.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            TextButton(
                                onClick = onSwitchToCloudSignIn,
                                modifier = Modifier.testTag("connect_google_banner_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Usar Google")
                            }
                        }
                    }
                }
            }

            if (state.streamError != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.streamError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            item {
                FefoSummaryRow(
                    expiredCount = state.expiredCount,
                    due7DaysCount = state.due7DaysCount,
                    due15DaysCount = state.due15DaysCount,
                    onTrackCount = state.onTrackCount,
                    onCardClick = { filter -> onNavigateToInventory(filter, null) }
                )
            }

            item {
                DailyOperationalToolsSection(
                    todayTempLogs = state.todayTempLogs,
                    latestTempLog = state.latestTempLog,
                    alertSettings = alertSettings,
                    urgentItemsCount = state.expiredCount + state.due7DaysCount + state.due15DaysCount,
                    isLocalMode = isLocalMode,
                    backupSummary = state.backupSummary,
                    onOpenChamberTemp = { showChamberTempSheet = true },
                    onOpenExpiryAlerts = { showExpiryAlertsSheet = true },
                    onOpenLocalBackup = { showBackupSheet = true }
                )
            }

            if (state.chamberSummaries.isNotEmpty()) {
                item {
                    SectionTitle(
                        title = "Zonas & Câmaras Ativas",
                        actionLabel = "Ver tudo",
                        onAction = { onNavigateToInventory(UrgencyFilter.ALL, null) }
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.chamberSummaries, key = { it.chamberName }) { chamber ->
                            ChamberSummaryCard(
                                chamber = chamber,
                                onClick = { onNavigateToInventory(UrgencyFilter.ALL, chamber.zone) }
                            )
                        }
                    }
                }
            }

            item {
                SectionTitle(
                    title = "Prioridade FEFO (Próximos a Vencer)",
                    actionLabel = if (state.activeItems.isNotEmpty()) "Ver estoque" else null,
                    onAction = { onNavigateToInventory(UrgencyFilter.ALL, null) }
                )
            }

            if (state.priorityFefoItems.isEmpty()) {
                item {
                    EmptyColdStorageCard(onReceiveClick = onNavigateToReceiving)
                }
            } else {
                items(state.priorityFefoItems, key = { it.id }) { item ->
                    FefoPriorityItemCard(
                        item = item,
                        canEditStock = state.activeRole.canEditReceivingOrStock,
                        onClick = { selectedItem = item },
                        onStockOutClick = { stockOutTarget = item }
                    )
                }
            }

            item {
                SectionTitle(
                    title = "Recebimentos de Hoje (${state.todaySessions.size})",
                    actionLabel = "Nova sessão",
                    onAction = onNavigateToReceiving
                )
            }

            if (state.todaySessions.isEmpty()) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Nenhum recebimento registrado hoje",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Somente os lançamentos feitos na data de hoje aparecem neste painel. Toque em Nova sessão ou consulte o Histórico completo em Receber.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val ptBr = Locale.forLanguageTag("pt-BR")
                items(state.todaySessions, key = { it.id }) { session ->
                    Card(
                        onClick = { selectedSession = session },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dashboard_receiving_card_${session.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = session.supplierName.ifBlank { "Fornecedor não informado" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        if (session.invoiceNumber.isNotBlank()) append("NF ${session.invoiceNumber} • ")
                                        append("${session.itemCount} corte(s)")
                                        if (session.totalKg > 0.0) {
                                            append(" (${String.format(ptBr, "%.1f", session.totalKg)} kg)")
                                        }
                                        append(" • ${DateUtils.formatShortDate(session.receivedAtMillis)}")
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (session.responsibleName.isNotBlank()) {
                                    Text(
                                        text = "Resp.: ${session.responsibleName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
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

    selectedItem?.let { item ->
        ItemDetailSheet(
            item = item,
            activeRole = state.activeRole,
            movementsFlow = viewModel.observeMovementsForItem(item.id),
            onDismiss = { selectedItem = null },
            onRequestStockOut = {
                selectedItem = null
                stockOutTarget = item
            },
            onTransferChamber = { chamberName, zone, tempCelsius ->
                viewModel.transferChamber(item.id, chamberName, zone, tempCelsius)
            },
            onUpdateExpiry = { epochDay ->
                viewModel.updateExpiry(item.id, epochDay)
            },
            onDeleteItem = {
                viewModel.deleteItem(item.id)
            }
        )
    }

    selectedSession?.let { session ->
        ReceivingSessionDetailSheet(
            session = session,
            activeRole = state.activeRole,
            sessionItemsFlow = viewModel.observeItemsForSession(session.id),
            onDismiss = { selectedSession = null },
            onItemClick = { lot ->
                selectedSession = null
                selectedItem = lot
            },
            onDeleteSession = {
                viewModel.deleteSession(session.id)
            }
        )
    }

    stockOutTarget?.let { target ->
        StockOutSheet(
            item = target,
            onDismiss = { stockOutTarget = null },
            onConfirm = { type, qty, reason ->
                viewModel.recordStockOut(target.id, type, qty, reason)
                stockOutTarget = null
            }
        )
    }

    if (showChamberTempSheet) {
        ChamberTemperatureSheet(
            logs = state.chamberTempLogs,
            knownChambers = state.chamberSummaries.map { it.chamberName },
            defaultResponsibleName = state.sessionState.currentMember?.displayName.orEmpty(),
            activeRole = state.activeRole,
            onDismiss = { showChamberTempSheet = false },
            onRecordRound = { chamber, zone, temp, responsible, notes ->
                viewModel.recordChamberTemperature(chamber, zone, temp, responsible, notes)
            },
            onDeleteRound = { logId ->
                viewModel.deleteChamberTemperature(logId)
            }
        )
    }

    if (showExpiryAlertsSheet) {
        val digest = remember(state.activeItems) {
            ExpiryAlertCalculator.fromItems(state.activeItems)
        }
        ExpiryAlertsSheet(
            digest = digest,
            initialSettings = alertSettings,
            onSettingsUpdated = { updated -> alertSettings = updated },
            onFeedback = { msg -> viewModel.setFeedback(msg) },
            onDismiss = { showExpiryAlertsSheet = false }
        )
    }

    if (showBackupSheet && isLocalMode) {
        LocalBackupSheet(
            summary = state.backupSummary,
            onExportJson = { viewModel.exportLocalBackupJson() },
            onParseBackup = { raw -> viewModel.parseBackupEnvelope(raw) },
            onConfirmRestore = { envelope -> viewModel.restoreBackupEnvelope(envelope) },
            onFeedback = { msg -> viewModel.setFeedback(msg) },
            onDismiss = { showBackupSheet = false }
        )
    }
}

@Composable
private fun DailyOperationalToolsSection(
    todayTempLogs: List<ChamberTempLog>,
    latestTempLog: ChamberTempLog?,
    alertSettings: ExpiryAlertSettings,
    urgentItemsCount: Int,
    isLocalMode: Boolean,
    backupSummary: BackupRestoreSummary?,
    onOpenChamberTemp: () -> Unit,
    onOpenExpiryAlerts: () -> Unit,
    onOpenLocalBackup: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedCard(
            onClick = onOpenChamberTemp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dashboard_chamber_temp_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Thermostat,
                            contentDescription = "Ronda Térmica das Câmaras",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Ronda Térmica Hoje",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                text = "${todayTempLogs.size} leitura(s)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (latestTempLog != null) {
                        Text(
                            text = "Última: ${latestTempLog.chamberName} (${DateUtils.formatShortDate(latestTempLog.recordedAtMillis)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        ThermalComplianceBadge(
                            compliance = latestTempLog.complianceEnum,
                            tempCelsius = latestTempLog.measuredTempCelsius
                        )
                    } else {
                        Text(
                            text = "Toque para registrar a temperatura das câmaras e gerar histórico diário.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "Registrar",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedCard(
                onClick = onOpenExpiryAlerts,
                modifier = Modifier
                    .weight(1f)
                    .testTag("dashboard_expiry_alerts_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (alertSettings.enabled) {
                            Icons.Rounded.NotificationsActive
                        } else {
                            Icons.Rounded.NotificationsOff
                        },
                        contentDescription = "Configurar alertas de vencimento",
                        tint = if (alertSettings.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Alertas 7d/15d",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (alertSettings.enabled) {
                                "Diário ${String.format(Locale.US, "%02d:%02d", alertSettings.hour, alertSettings.minute)}"
                            } else if (urgentItemsCount > 0) {
                                "$urgentItemsCount lote(s) em atenção"
                            } else {
                                "Ativar avisos"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (isLocalMode) {
                OutlinedCard(
                    onClick = onOpenLocalBackup,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dashboard_local_backup_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = "Backup e restauração JSON local",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Backup JSON",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (backupSummary != null && backupSummary.exportedAtMillis > 0L) {
                                    "Salvo ${DateUtils.formatShortDate(backupSummary.exportedAtMillis)}"
                                } else {
                                    "Salvar / Restaurar"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroColdHeader(
    shopName: String,
    memberName: String,
    activeRole: StaffRole,
    totalKg: Double,
    totalOtherUnits: Int,
    activeLotCount: Int,
    onReceiveClick: () -> Unit,
    onOpenTeamAccess: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(ColdNavyPrimary, ColdNavyLight)
                )
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AcUnit,
                        contentDescription = null,
                        tint = Color(0xFF7DD3FC),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "${shopName.uppercase()} • CÂMARA FRIA",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = Color(0xFFBAE6FD),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = Color.White.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onOpenTeamAccess)
                        .testTag("open_team_access_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Badge,
                            contentDescription = "Equipe e Cargo",
                            tint = Color(0xFF7DD3FC),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = activeRole.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (memberName.isNotBlank()) {
                Text(
                    text = "Colaborador: $memberName • $activeLotCount lotes ativos",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = Color.White.copy(alpha = 0.85f)
                )
            } else {
                Text(
                    text = "$activeLotCount lotes ativos",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            Column {
                Text(
                    text = String.format(Locale.forLanguageTag("pt-BR"), "%.2f kg", totalKg),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                if (totalOtherUnits > 0) {
                    Text(
                        text = "+ $totalOtherUnits peças/caixas em estoque",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFBAE6FD)
                    )
                } else {
                    Text(
                        text = "Saldo total refrigerado e congelado",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
            }

            Button(
                onClick = onReceiveClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF38BDF8),
                    contentColor = ColdNavyPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("dashboard_receive_cta")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Nova Sessão / Ler Nota Fiscal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun FefoSummaryRow(
    expiredCount: Int,
    due7DaysCount: Int,
    due15DaysCount: Int,
    onTrackCount: Int,
    onCardClick: (UrgencyFilter) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatUrgencyCard(
            count = expiredCount,
            label = "Vencidos",
            bgColor = if (isDark) StatusDangerRedDarkBg else StatusDangerRedBg,
            accentColor = if (isDark) Color(0xFFFCA5A5) else StatusDangerRed,
            onClick = { onCardClick(UrgencyFilter.EXPIRED) },
            modifier = Modifier
                .weight(1f)
                .testTag("dashboard_expired_card")
        )
        StatUrgencyCard(
            count = due7DaysCount,
            label = "Até 7d",
            bgColor = if (isDark) StatusWarningAmberDarkBg else StatusWarningAmberBg,
            accentColor = if (isDark) Color(0xFFFCD34D) else StatusWarningAmber,
            onClick = { onCardClick(UrgencyFilter.DUE_7_DAYS) },
            modifier = Modifier
                .weight(1f)
                .testTag("dashboard_due_7d_card")
        )
        StatUrgencyCard(
            count = due15DaysCount,
            label = "Até 15d",
            bgColor = if (isDark) Color(0xFF422006) else Color(0xFFFEF3C7),
            accentColor = if (isDark) Color(0xFFFDE68A) else Color(0xFFD97706),
            onClick = { onCardClick(UrgencyFilter.DUE_15_DAYS) },
            modifier = Modifier
                .weight(1f)
                .testTag("dashboard_due_15d_card")
        )
        StatUrgencyCard(
            count = onTrackCount,
            label = "Em dia",
            bgColor = if (isDark) StatusOkGreenDarkBg else StatusOkGreenBg,
            accentColor = if (isDark) Color(0xFF86EFAC) else StatusOkGreen,
            onClick = { onCardClick(UrgencyFilter.ON_TRACK) },
            modifier = Modifier
                .weight(1f)
                .testTag("dashboard_ok_card")
        )
    }
}

@Composable
private fun StatUrgencyCard(
    count: Int,
    label: String,
    bgColor: Color,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = accentColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor
        )
    }
}

@Composable
private fun ChamberSummaryCard(
    chamber: ChamberSummary,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.width(210.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TemperatureZonePill(zone = chamber.zone)
                if (chamber.hasAlert) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = "Alerta de validade",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = chamber.chamberName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val ptBr = Locale.forLanguageTag("pt-BR")
            val stockText = buildString {
                if (chamber.totalKg > 0.0 || chamber.totalOtherUnits == 0) {
                    append("${String.format(ptBr, "%.1f", chamber.totalKg)} kg")
                }
                if (chamber.totalOtherUnits > 0) {
                    if (isNotEmpty()) append(" + ")
                    append("${chamber.totalOtherUnits} pç/cx")
                }
                append(" • ${chamber.lotCount} lotes")
            }
            Text(
                text = stockText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Thermostat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Média ${DateUtils.formatTemp(chamber.avgTempCelsius)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun FefoPriorityItemCard(
    item: ColdStorageItemEntity,
    canEditStock: Boolean,
    onClick: () -> Unit,
    onStockOutClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("fefo_item_card_${item.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MeatTypePill(meatType = item.meatTypeEnum)
                        TemperatureZonePill(zone = item.zoneEnum)
                    }
                    Text(
                        text = item.cutName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append("${item.chamberName} • ${DateUtils.formatTemp(item.measuredTempCelsius)}")
                            if (item.batchCode.isNotBlank()) append(" • Lote ${item.batchCode}")
                            if (item.supplierName.isNotBlank()) append("\n${item.supplierName}")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = DateUtils.formatWeightOrQty(item.currentQuantity, item.unitEnum),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Val: ${DateUtils.formatEpochDay(item.expiryDateEpochDay)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExpiryUrgencyBadge(expiryEpochDay = item.expiryDateEpochDay)
                if (canEditStock) {
                    FilledTonalButton(
                        onClick = onStockOutClick,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("fefo_stock_out_button_${item.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowOutward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Dar Baixa")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyColdStorageCard(onReceiveClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = "Câmara fria sem lotes ativos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Inicie uma sessão de recebimento importando um XML NF-e, fotografando ou enviando o PDF da nota (leitura local offline) ou lançando manualmente seus cortes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onReceiveClick,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("empty_receive_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.ReceiptLong, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Receber Mercadoria")
            }
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}
