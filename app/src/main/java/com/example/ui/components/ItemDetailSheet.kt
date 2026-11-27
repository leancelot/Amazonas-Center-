package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.PackSpecHelper
import com.example.data.StaffRole
import com.example.data.StockMovementEntity
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.ReceivingViewModel
import kotlinx.coroutines.flow.Flow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItemDetailSheet(
    item: ColdStorageItemEntity,
    activeRole: StaffRole,
    movementsFlow: Flow<List<StockMovementEntity>>,
    onDismiss: () -> Unit,
    onRequestStockOut: () -> Unit,
    onTransferChamber: (chamberName: String, zone: TemperatureZone, tempCelsius: Double) -> Unit,
    onUpdateExpiry: (expiryEpochDay: Long) -> Unit,
    onDeleteItem: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val movements by movementsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var showTransferMode by remember { mutableStateOf(false) }
    var showExpiryPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var newZone by remember(item.id) { mutableStateOf(item.zoneEnum) }
    var newChamber by remember(item.id) { mutableStateOf(item.chamberName) }
    var newTempText by remember(item.id) {
        mutableStateOf(String.format(Locale.US, "%.1f", item.measuredTempCelsius))
    }
    val canEditStock = activeRole.canEditReceivingOrStock
    val canDelete = activeRole.canDeleteRecords

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
                    MeatTypePill(meatType = item.meatTypeEnum)
                    TemperatureZonePill(zone = item.zoneEnum)
                    ThermalComplianceBadge(
                        compliance = item.thermalCompliance,
                        tempCelsius = item.measuredTempCelsius
                    )
                }

                Text(
                    text = item.cutName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Saldo: ${DateUtils.formatWeightOrQty(item.currentQuantity, item.unitEnum)} " +
                        "(de ${DateUtils.formatWeightOrQty(item.initialQuantity, item.unitEnum)})",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.primary
                )

                ExpiryUrgencyBadge(expiryEpochDay = item.expiryDateEpochDay)

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LotDetailLine("Câmara atual", item.chamberName)
                        LotDetailLine("Validade", DateUtils.formatEpochDay(item.expiryDateEpochDay))
                        LotDetailLine("Recebido em", DateUtils.formatDateTime(item.receivedAtMillis))
                        val packSpec = remember(item.notes) { PackSpecHelper.parse(item.notes) }
                        if (packSpec != null && packSpec.remainingCount > 0) {
                            LotDetailLine("Volumes restantes", packSpec.formatSummary(item.unitEnum))
                        }
                        if (item.supplierName.isNotBlank()) LotDetailLine("Fornecedor", item.supplierName)
                        if (item.invoiceNumber.isNotBlank()) LotDetailLine("Nota Fiscal", item.invoiceNumber)
                        if (item.batchCode.isNotBlank()) LotDetailLine("Lote", item.batchCode)
                        if (item.sifCode.isNotBlank()) LotDetailLine("SIF", item.sifCode)
                        if (item.unitCostBrl > 0.0) {
                            LotDetailLine("Custo unitário", DateUtils.formatCurrencyBrl(item.unitCostBrl))
                        }
                        val cleanNotes = remember(item.notes) { PackSpecHelper.stripMetadata(item.notes) }
                        if (cleanNotes.isNotBlank()) LotDetailLine("Observações", cleanNotes)
                    }
                }

                if (showTransferMode) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Categoria da Câmara / Atualizar °C", style = MaterialTheme.typography.titleSmall)
                            val transferZones = if (item.zoneEnum == TemperatureZone.MATURACAO) {
                                TemperatureZone.entries
                            } else {
                                TemperatureZone.primaryZones
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                transferZones.forEach { z ->
                                    FilterChip(
                                        selected = newZone == z,
                                        onClick = {
                                            newZone = z
                                            val isGenericChamber = TemperatureZone.entries.any {
                                                it.defaultChamber.equals(newChamber.trim(), ignoreCase = true)
                                            }
                                            if (newChamber.isBlank() || isGenericChamber) {
                                                newChamber = z.defaultChamber
                                            }
                                            newTempText = String.format(Locale.US, "%.1f", z.defaultTempCelsius)
                                        },
                                        label = { Text(z.displayName) }
                                    )
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TemperatureZone.defaultChambers.forEach { preset ->
                                    FilterChip(
                                        selected = newChamber.trim().equals(preset, ignoreCase = true),
                                        onClick = { newChamber = preset },
                                        label = { Text(preset) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = newChamber,
                                onValueChange = { newChamber = it },
                                label = { Text("Câmara na loja") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newTempText,
                                onValueChange = { newTempText = ReceivingViewModel.sanitizeTemperatureInput(it) },
                                label = { Text("Temperatura aferida (°C)") },
                                trailingIcon = {
                                    TextButton(onClick = { newTempText = ReceivingViewModel.toggleTemperatureSign(newTempText) }) {
                                        Text("±", fontWeight = FontWeight.Bold)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            newTempText.replace(',', '.').toDoubleOrNull()?.let { parsedTemp ->
                                ThermalComplianceBadge(
                                    compliance = ThermalCompliance.evaluate(newZone, parsedTemp),
                                    tempCelsius = parsedTemp,
                                    showTempValue = false
                                )
                            }
                            Button(
                                onClick = {
                                    val t = newTempText.replace(',', '.').toDoubleOrNull() ?: newZone.defaultTempCelsius
                                    onTransferChamber(newChamber.trim().ifBlank { newZone.defaultChamber }, newZone, t)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text("Salvar Transferência")
                            }
                        }
                    }
                }

                if (canEditStock) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onRequestStockOut,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("detail_stock_out_button")
                        ) {
                            Icon(Icons.Rounded.ArrowOutward, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Dar Baixa")
                        }
                        OutlinedButton(
                            onClick = { showTransferMode = !showTransferMode },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("detail_transfer_button")
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Transferir")
                        }
                    }
                    OutlinedButton(
                        onClick = { showExpiryPicker = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("detail_edit_expiry_button")
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Alterar Validade (${DateUtils.formatEpochDay(item.expiryDateEpochDay)})")
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Cargo ${activeRole.displayName}: somente Gerente, Conferente ou Resp. Câmara Fria alteram estoque e baixas.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (movements.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Histórico deste Lote", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    movements.forEach { mov ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mov.movementTypeEnum.displayName, style = MaterialTheme.typography.bodyMedium)
                                if (mov.reason.isNotBlank()) {
                                    Text(
                                        mov.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = "${DateUtils.formatWeightOrQty(mov.quantity, mov.unitEnum)} • ${DateUtils.formatShortDate(mov.createdAtMillis)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (canDelete) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Excluir lote", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showExpiryPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.epochDayToUtcPickerMillis(item.expiryDateEpochDay)
        )
        DatePickerDialog(
            onDismissRequest = { showExpiryPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            onUpdateExpiry(DateUtils.millisToEpochDay(millis))
                        }
                        showExpiryPicker = false
                        onDismiss()
                    }
                ) {
                    Text("Confirmar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExpiryPicker = false }) {
                    Text("Cancelar")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Excluir lote do estoque?") },
            text = { Text("Esta ação removerá permanentemente ${item.cutName} da câmara fria.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteItem()
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
private fun LotDetailLine(label: String, value: String) {
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
