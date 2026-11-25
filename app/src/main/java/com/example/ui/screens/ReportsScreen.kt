package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.DateUtils
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusWarningAmber
import com.example.ui.viewmodel.ReportPeriod
import com.example.ui.viewmodel.ReportStockGrouping
import com.example.ui.viewmodel.ReportsViewModel
import java.util.Locale

@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Relatórios de Estoque & Perdas",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportPeriod.entries.forEach { p ->
                            FilterChip(
                                selected = state.period == p,
                                onClick = { viewModel.setPeriod(p) },
                                label = { Text(p.label) },
                                modifier = Modifier.testTag("report_period_${p.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val ptBr = Locale.forLanguageTag("pt-BR")
                    val otherSuffix = if (state.totalStockOtherUnits > 0) " • +${state.totalStockOtherUnits} pç/cx" else ""
                    ReportKpiCard(
                        title = "Estoque Atual",
                        value = String.format(ptBr, "%.1f kg", state.totalStockKg),
                        subtitle = "${state.totalActiveLots} lotes ativos$otherSuffix",
                        isError = false,
                        modifier = Modifier.weight(1f)
                    )
                    ReportKpiCard(
                        title = "Perdas (${state.period.label})",
                        value = String.format(ptBr, "%.1f kg", state.totalLossKg),
                        subtitle = "${String.format(ptBr, "%.1f", state.lossRatePercent)}% quebra • " +
                            DateUtils.formatCurrencyBrl(state.totalLossBrl),
                        isError = state.totalLossKg > 0.0,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val text = viewModel.buildTextSummary()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Conferência Câmara Fria — Amazonas Center")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Compartilhar Relatório"))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("share_report_text_button")
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.cd_export_report))
                        Spacer(Modifier.width(6.dp))
                        Text("Resumo")
                    }
                    OutlinedButton(
                        onClick = {
                            val csvUri = viewModel.createConferenceCsvUri(context)
                            if (csvUri != null) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, csvUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Exportar CSV"))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_report_csv_button")
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Exportar CSV")
                    }
                }
            }

            if (state.zoneBreakdown.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val ptBr = Locale.forLanguageTag("pt-BR")
                        state.zoneBreakdown.forEach { zb ->
                            OutlinedCard(modifier = Modifier.weight(1f)) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = zb.zone.displayName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = String.format(ptBr, "%.1f kg", zb.totalKg),
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${zb.lotCount} lotes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Categorizar Estoque Por:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportStockGrouping.entries.forEach { grouping ->
                                FilterChip(
                                    selected = state.stockGrouping == grouping,
                                    onClick = { viewModel.setStockGrouping(grouping) },
                                    label = { Text(grouping.label) },
                                    modifier = Modifier.testTag("report_grouping_${grouping.name.lowercase()}")
                                )
                            }
                        }

                        val ptBr = Locale.forLanguageTag("pt-BR")
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant
                        val defaultBarColor = MaterialTheme.colorScheme.secondary

                        when (state.stockGrouping) {
                            ReportStockGrouping.MEAT_TYPE -> {
                                if (state.meatBreakdown.isEmpty()) {
                                    Text(
                                        text = "Sem itens ativos para exibir.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    state.meatBreakdown.forEach { row ->
                                        ReportBreakdownRow(
                                            label = "${row.meatType.displayName} (${row.lotCount} lotes)",
                                            value = String.format(ptBr, "%.1f kg", row.totalKg),
                                            share = row.sharePercent,
                                            barColor = defaultBarColor,
                                            trackColor = trackColor
                                        )
                                    }
                                }
                            }
                            ReportStockGrouping.SUPPLIER -> {
                                if (state.supplierBreakdown.isEmpty()) {
                                    Text(
                                        text = "Sem frigoríficos registrados no estoque ativo.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    state.supplierBreakdown.forEach { row ->
                                        ReportBreakdownRow(
                                            label = "${row.supplierName} (${row.lotCount} lotes)",
                                            value = String.format(ptBr, "%.1f kg", row.totalKg),
                                            share = row.sharePercent,
                                            barColor = MaterialTheme.colorScheme.primary,
                                            trackColor = trackColor
                                        )
                                    }
                                }
                            }
                            ReportStockGrouping.EXPIRY -> {
                                if (state.expiryBreakdown.isEmpty()) {
                                    Text(
                                        text = "Sem lotes ativos para análise de validade.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    state.expiryBreakdown.forEach { row ->
                                        val barColor = when {
                                            row.isUrgent -> MaterialTheme.colorScheme.error
                                            row.isWarning -> StatusWarningAmber
                                            else -> defaultBarColor
                                        }
                                        ReportBreakdownRow(
                                            label = "${row.label} (${row.lotCount} lotes)",
                                            value = String.format(ptBr, "%.1f kg", row.totalKg),
                                            share = row.sharePercent,
                                            barColor = barColor,
                                            trackColor = trackColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (state.lossReasons.isNotEmpty()) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Perdas por Motivo",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            state.lossReasons.forEach { loss ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${loss.movementType.displayName} (${loss.count}x)",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${String.format(Locale.forLanguageTag("pt-BR"), "%.2f", loss.totalKg)} kg",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Movimentações Recentes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (state.recentMovements.isEmpty()) {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Nenhuma movimentação registrada neste período.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                }
            } else {
                items(state.recentMovements, key = { it.id }) { mov ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "${mov.cutNameSnapshot} (${mov.meatTypeEnum.displayName})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = buildString {
                                        append(mov.movementTypeEnum.displayName)
                                        if (mov.reason.isNotBlank()) append(" • ${mov.reason}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (mov.movementTypeEnum.isLoss) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = (if (mov.movementTypeEnum.isOutgoing) "-" else "+") +
                                        DateUtils.formatWeightOrQty(mov.quantity, mov.unitEnum),
                                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.Bold,
                                    color = if (mov.movementTypeEnum.isLoss) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                                Text(
                                    text = DateUtils.formatShortDate(mov.createdAtMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportKpiCard(
    title: String,
    value: String,
    subtitle: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReportBreakdownRow(
    label: String,
    value: String,
    share: Float,
    barColor: androidx.compose.ui.graphics.Color,
    trackColor: androidx.compose.ui.graphics.Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily)
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = CornerRadius(8f, 8f)
            )
            val fillWidth = (size.width * share.coerceIn(0f, 1f))
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = barColor,
                    size = Size(fillWidth, size.height),
                    cornerRadius = CornerRadius(8f, 8f)
                )
            }
        }
    }
}
