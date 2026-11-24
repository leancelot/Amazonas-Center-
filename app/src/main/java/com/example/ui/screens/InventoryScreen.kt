package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.MeatType
import com.example.data.TemperatureZone
import com.example.ui.components.ExpiryUrgencyBadge
import com.example.ui.components.MeatTypePill
import com.example.ui.components.StockOutSheet
import com.example.ui.components.TemperatureZonePill
import com.example.ui.components.ThermalComplianceBadge
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.InventoryViewModel
import com.example.ui.viewmodel.UrgencyFilter
import java.util.Locale

@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedItem by remember { mutableStateOf<ColdStorageItemEntity?>(null) }
    var stockOutItem by remember { mutableStateOf<ColdStorageItemEntity?>(null) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Estoque na Câmara (FEFO)",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${state.filteredItems.size} lotes • ${String.format(Locale("pt", "BR"), "%.1f", state.totalFilteredKg)} kg",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    placeholder = { Text("Buscar corte, lote, NF, SIF ou fornecedor...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.cd_search)) },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_clear_search))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inventory_search_input")
                )

                // FEFO Urgency & Temperature Zone Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UrgencyFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.urgencyFilter == filter,
                            onClick = { viewModel.setUrgencyFilter(filter) },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("filter_urgency_${filter.name.lowercase()}")
                        )
                    }
                    TemperatureZone.entries.forEach { zone ->
                        FilterChip(
                            selected = state.selectedZone == zone,
                            onClick = { viewModel.toggleZoneFilter(zone) },
                            label = { Text(zone.displayName) },
                            modifier = Modifier.testTag("filter_zone_${zone.name.lowercase()}")
                        )
                    }
                }

                // Meat Type Filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.selectedMeatType == null,
                        onClick = { viewModel.toggleMeatFilter(null) },
                        label = { Text("Todas as carnes") }
                    )
                    MeatType.entries.forEach { meat ->
                        FilterChip(
                            selected = state.selectedMeatType == meat,
                            onClick = { viewModel.toggleMeatFilter(meat) },
                            label = { Text(meat.displayName) },
                            modifier = Modifier.testTag("filter_meat_${meat.name.lowercase()}")
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.filteredItems.isEmpty()) {
                    item {
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Nenhum lote encontrado",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Ajuste os filtros ou registre uma nova entrada em Receber.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(state.filteredItems, key = { it.id }) { item ->
                        InventoryLotCard(
                            item = item,
                            onClick = { selectedItem = item },
                            onQuickStockOut = { stockOutItem = item }
                        )
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        ItemDetailSheet(
            item = item,
            viewModel = viewModel,
            onDismiss = { selectedItem = null },
            onRequestStockOut = {
                selectedItem = null
                stockOutItem = item
            }
        )
    }

    stockOutItem?.let { item ->
        StockOutSheet(
            item = item,
            onDismiss = { stockOutItem = null },
            onConfirm = { type, qty, reason ->
                viewModel.recordStockOut(item.id, type, qty, reason)
                stockOutItem = null
            }
        )
    }
}

@Composable
private fun InventoryLotCard(
    item: ColdStorageItemEntity,
    onClick: () -> Unit,
    onQuickStockOut: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("inventory_item_card_${item.id}")
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
                            append(item.chamberName)
                            append(" • ${DateUtils.formatTemp(item.measuredTempCelsius)}")
                            if (item.supplierName.isNotBlank()) append("\n${item.supplierName}")
                            if (item.invoiceNumber.isNotBlank()) append(" • NF ${item.invoiceNumber}")
                        },
                        style = MaterialTheme.typography.bodySmall,
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
                OutlinedButton(
                    onClick = onQuickStockOut,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("inventory_stock_out_${item.id}")
                ) {
                    Icon(Icons.Rounded.ArrowOutward, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Baixa")
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemDetailSheet(
    item: ColdStorageItemEntity,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit,
    onRequestStockOut: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val movements by viewModel.observeMovementsForItem(item.id).collectAsStateWithLifecycle(initialValue = emptyList())
    var showTransferMode by remember { mutableStateOf(false) }
    var newZone by remember { mutableStateOf(item.zoneEnum) }
    var newChamber by remember { mutableStateOf(item.chamberName) }
    var newTempText by remember { mutableStateOf(String.format(Locale.US, "%.1f", item.measuredTempCelsius)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
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

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DetailLine("Câmara atual", item.chamberName)
                    DetailLine("Validade", DateUtils.formatEpochDay(item.expiryDateEpochDay))
                    DetailLine("Recebido em", DateUtils.formatDateTime(item.receivedAtMillis))
                    if (item.supplierName.isNotBlank()) DetailLine("Fornecedor", item.supplierName)
                    if (item.invoiceNumber.isNotBlank()) DetailLine("Nota Fiscal", item.invoiceNumber)
                    if (item.batchCode.isNotBlank()) DetailLine("Lote", item.batchCode)
                    if (item.sifCode.isNotBlank()) DetailLine("SIF", item.sifCode)
                    if (item.unitCostBrl > 0.0) {
                        DetailLine("Custo unitário", DateUtils.formatCurrencyBrl(item.unitCostBrl))
                    }
                }
            }

            if (showTransferMode) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Transferir Câmara / Atualizar °C", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TemperatureZone.entries.forEach { z ->
                                FilterChip(
                                    selected = newZone == z,
                                    onClick = {
                                        newZone = z
                                        newChamber = z.defaultChamber
                                        newTempText = String.format(Locale.US, "%.1f", z.defaultTempCelsius)
                                    },
                                    label = { Text(z.displayName) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = newChamber,
                            onValueChange = { newChamber = it },
                            label = { Text("Câmara de destino") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newTempText,
                            onValueChange = { newTempText = it },
                            label = { Text("Temperatura aferida (°C)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val t = newTempText.replace(',', '.').toDoubleOrNull() ?: newZone.defaultTempCelsius
                                viewModel.transferChamber(item.id, newChamber.trim().ifBlank { newZone.defaultChamber }, newZone, t)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Salvar Transferência")
                        }
                    }
                }
            }

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

            TextButton(
                onClick = {
                    viewModel.deleteItem(item.id)
                    onDismiss()
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text("Excluir lote", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            fontWeight = FontWeight.SemiBold
        )
    }
}
