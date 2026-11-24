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
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.ReceiptLong
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.ThermalCompliance
import com.example.ui.components.ExpiryUrgencyBadge
import com.example.ui.components.MeatTypePill
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
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToReceiving: () -> Unit,
    onNavigateToInventory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var stockOutTarget by remember { mutableStateOf<ColdStorageItemEntity?>(null) }

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
                    totalKg = state.totalKg,
                    totalOtherUnits = state.totalOtherUnits,
                    activeLotCount = state.activeLotCount,
                    onReceiveClick = onNavigateToReceiving
                )
            }

            item {
                FefoSummaryRow(
                    expiredCount = state.expiredCount,
                    dueSoonCount = state.dueSoonCount,
                    onTrackCount = state.onTrackCount,
                    onCardClick = onNavigateToInventory
                )
            }

            if (state.chamberSummaries.isNotEmpty()) {
                item {
                    SectionTitle(title = "Zonas & Câmaras Ativas", actionLabel = "Ver tudo", onAction = onNavigateToInventory)
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.chamberSummaries, key = { it.chamberName }) { chamber ->
                            ChamberSummaryCard(chamber = chamber, onClick = onNavigateToInventory)
                        }
                    }
                }
            }

            item {
                SectionTitle(
                    title = "Prioridade FEFO (Próximos a Vencer)",
                    actionLabel = if (state.activeItems.isNotEmpty()) "Ver estoque" else null,
                    onAction = onNavigateToInventory
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
                        onStockOutClick = { stockOutTarget = item }
                    )
                }
            }

            if (state.recentSessions.isNotEmpty()) {
                item {
                    SectionTitle(
                        title = "Últimas Sessões de Recebimento",
                        actionLabel = "Nova sessão",
                        onAction = onNavigateToReceiving
                    )
                }
                items(state.recentSessions, key = { it.id }) { session ->
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth()
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
                                    text = session.supplierName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        if (session.invoiceNumber.isNotBlank()) append("NF ${session.invoiceNumber} • ")
                                        append("${session.itemCount} itens • ${DateUtils.formatShortDate(session.receivedAtMillis)}")
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
}

@Composable
private fun HeroColdHeader(
    totalKg: Double,
    totalOtherUnits: Int,
    activeLotCount: Int,
    onReceiveClick: () -> Unit
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AcUnit,
                        contentDescription = null,
                        tint = Color(0xFF7DD3FC),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "AMAZONAS CENTER • CÂMARA FRIA",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = Color(0xFFBAE6FD)
                    )
                }
                Text(
                    text = "$activeLotCount lotes ativos",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            Column {
                Text(
                    text = String.format(Locale("pt", "BR"), "%.2f kg", totalKg),
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
    dueSoonCount: Int,
    onTrackCount: Int,
    onCardClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatUrgencyCard(
            count = expiredCount,
            label = "Vencidos",
            bgColor = if (isDark) StatusDangerRedDarkBg else StatusDangerRedBg,
            accentColor = if (isDark) Color(0xFFFCA5A5) else StatusDangerRed,
            onClick = onCardClick,
            modifier = Modifier
                .weight(1f)
                .testTag("dashboard_expired_card")
        )
        StatUrgencyCard(
            count = dueSoonCount,
            label = "Até 3 dias",
            bgColor = if (isDark) StatusWarningAmberDarkBg else StatusWarningAmberBg,
            accentColor = if (isDark) Color(0xFFFCD34D) else StatusWarningAmber,
            onClick = onCardClick,
            modifier = Modifier
                .weight(1f)
                .testTag("dashboard_due_soon_card")
        )
        StatUrgencyCard(
            count = onTrackCount,
            label = "Em dia",
            bgColor = if (isDark) StatusOkGreenDarkBg else StatusOkGreenBg,
            accentColor = if (isDark) Color(0xFF86EFAC) else StatusOkGreen,
            onClick = onCardClick,
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
            Text(
                text = "${String.format(Locale("pt", "BR"), "%.1f", chamber.totalKg)} kg • ${chamber.lotCount} lotes",
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
    onStockOutClick: () -> Unit
) {
    Card(
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
                        text = "${item.chamberName} • ${DateUtils.formatTemp(item.measuredTempCelsius)}" +
                            if (item.batchCode.isNotBlank()) " • Lote ${item.batchCode}" else "",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = DateUtils.formatWeightOrQty(item.currentQuantity, item.unitEnum),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExpiryUrgencyBadge(expiryEpochDay = item.expiryDateEpochDay)
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
                text = "Inicie uma sessão de recebimento importando um XML NF-e, fotografando a nota fiscal com IA ou lançando manualmente seus cortes.",
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
                Icon(Icons.Rounded.ReceiptLong, contentDescription = null)
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
