package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.InvoiceSourceType
import com.example.data.ReceivingSessionSummary
import com.example.data.StaffRole
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivingSessionDetailSheet(
    session: ReceivingSessionSummary,
    activeRole: StaffRole,
    sessionItemsFlow: Flow<List<ColdStorageItemEntity>>,
    onDismiss: () -> Unit,
    onItemClick: (ColdStorageItemEntity) -> Unit = {},
    onDeleteSession: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items by sessionItemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val zone = TemperatureZone.fromRaw(session.targetZone)
    val compliance = ThermalCompliance.entries.firstOrNull { it.name == session.thermalStatus }
        ?: ThermalCompliance.CONFORME
    val source = InvoiceSourceType.fromRaw(session.sourceType)
    val ptBr = Locale.forLanguageTag("pt-BR")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TemperatureZonePill(zone = zone)
                    ThermalComplianceBadge(
                        compliance = compliance,
                        tempCelsius = session.measuredTempCelsius
                    )
                }

                Text(
                    text = session.supplierName.ifBlank { "Fornecedor não informado" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = buildString {
                        if (session.invoiceNumber.isNotBlank()) append("NF ${session.invoiceNumber} • ")
                        append("${session.itemCount} corte(s)")
                        if (session.totalKg > 0.0) {
                            append(" • ${String.format(ptBr, "%.2f", session.totalKg)} kg")
                        }
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SessionDetailLine("Data e hora", DateUtils.formatDateTime(session.receivedAtMillis))
                        SessionDetailLine("Origem", source.displayName)
                        if (session.invoiceNumber.isNotBlank()) {
                            SessionDetailLine("Número da NF", session.invoiceNumber)
                        }
                        if (session.supplierCnpj.isNotBlank()) {
                            SessionDetailLine("CNPJ", session.supplierCnpj)
                        }
                        if (session.responsibleName.isNotBlank()) {
                            SessionDetailLine("Responsável", session.responsibleName)
                        }
                        SessionDetailLine("Temperatura chegada", DateUtils.formatTemp(session.measuredTempCelsius))
                        if (session.accessKey.isNotBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Chave de Acesso DANFE",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = session.accessKey,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (session.notes.isNotBlank()) {
                            SessionDetailLine("Observações", session.notes)
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Cortes e Lotes Lançados (${items.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (items.isEmpty()) {
                    Text(
                        text = "Nenhum lote ativo vinculado a este lançamento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    items.forEach { lot ->
                        Card(
                            onClick = { onItemClick(lot) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("session_detail_lot_${lot.id}")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = lot.cutName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = DateUtils.formatWeightOrQty(lot.currentQuantity, lot.unitEnum),
                                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = buildString {
                                            append("${lot.chamberName} • ${DateUtils.formatTemp(lot.measuredTempCelsius)}")
                                            if (lot.batchCode.isNotBlank()) append(" • Lote ${lot.batchCode}")
                                            if (lot.sifCode.isNotBlank()) append(" • SIF ${lot.sifCode}")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ExpiryUrgencyBadge(expiryEpochDay = lot.expiryDateEpochDay)
                                    Text(
                                        text = "Val: ${DateUtils.formatEpochDay(lot.expiryDateEpochDay)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                if (activeRole.canDeleteRecords && onDeleteSession != null) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Excluir lançamento", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && onDeleteSession != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir sessão de recebimento?") },
            text = {
                Text(
                    "Remover o registro de ${session.supplierName}" +
                        if (session.invoiceNumber.isNotBlank()) " (NF ${session.invoiceNumber})?" else "?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteSession()
                        onDismiss()
                    }
                ) {
                    Text("Excluir", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun SessionDetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            fontWeight = FontWeight.SemiBold
        )
    }
}
