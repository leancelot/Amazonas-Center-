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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.DateUtils
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.PackUnitType
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.domain.invoice.ParsedInvoiceItemDraft
import com.example.ui.viewmodel.ReceivingViewModel
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualItemSheet(
    initialItem: ParsedInvoiceItemDraft? = null,
    defaultZone: TemperatureZone = TemperatureZone.RESFRIADO,
    sessionTempCelsius: Double? = null,
    onDismiss: () -> Unit,
    onSave: (ParsedInvoiceItemDraft) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var meatType by remember { mutableStateOf(initialItem?.meatType ?: MeatType.BOVINA) }
    var cutName by remember { mutableStateOf(initialItem?.cutName ?: "") }
    var zone by remember { mutableStateOf(initialItem?.temperatureZone ?: defaultZone) }
    var tempText by remember {
        val initialTemp = initialItem?.measuredTempCelsius
            ?: sessionTempCelsius
            ?: defaultZone.defaultTempCelsius
        mutableStateOf(
            String.format(
                Locale.US,
                "%.1f",
                initialTemp
            )
        )
    }
    var chamberName by remember {
        mutableStateOf(initialItem?.chamberName ?: defaultZone.defaultChamber)
    }
    var quantityText by remember {
        mutableStateOf(
            if (initialItem != null && initialItem.quantity > 0) {
                String.format(Locale.US, "%.2f", initialItem.quantity)
            } else {
                ""
            }
        )
    }
    var unit by remember { mutableStateOf(initialItem?.unit ?: MeasurementUnit.KG) }
    var costText by remember {
        mutableStateOf(
            if (initialItem != null && initialItem.unitCostBrl > 0) {
                String.format(Locale.US, "%.2f", initialItem.unitCostBrl)
            } else {
                ""
            }
        )
    }
    var batchCode by remember { mutableStateOf(initialItem?.batchCode ?: "") }
    var sifCode by remember { mutableStateOf(initialItem?.sifCode ?: "") }
    var notes by remember { mutableStateOf(initialItem?.notes ?: "") }
    var packCountText by remember {
        mutableStateOf(if (initialItem != null && initialItem.packCount > 0) initialItem.packCount.toString() else "")
    }
    var packUnit by remember { mutableStateOf(initialItem?.packUnit ?: PackUnitType.CAIXA) }
    var expiryEpochDay by remember {
        mutableLongStateOf(
            initialItem?.expiryEpochDay ?: DateUtils.addDaysToTodayEpochDay(
                if (defaultZone == TemperatureZone.CONGELADO) meatType.defaultFrozenDays else meatType.defaultChilledDays
            )
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

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
            Text(
                text = if (initialItem == null) "Novo Produto Manual" else "Revisar Produto",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text("Tipo de Carne", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MeatType.entries.forEach { type ->
                    FilterChip(
                        selected = meatType == type,
                        onClick = {
                            meatType = type
                            if (initialItem == null) {
                                val days = if (zone == TemperatureZone.CONGELADO) {
                                    type.defaultFrozenDays
                                } else {
                                    type.defaultChilledDays
                                }
                                expiryEpochDay = DateUtils.addDaysToTodayEpochDay(days)
                            }
                        },
                        label = { Text(type.displayName) },
                        modifier = Modifier.testTag("manual_meat_chip_${type.name.lowercase()}")
                    )
                }
            }

            OutlinedTextField(
                value = cutName,
                onValueChange = {
                    cutName = it
                    errorMsg = null
                },
                label = { Text("Corte / Produto *") },
                placeholder = { Text("Ex.: Picanha, Alcatra, Peito de Frango...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_cut_input")
            )

            val matchingCutPresets = remember(cutName, meatType) {
                if (cutName.length >= 1) {
                    meatType.presetCuts.filter { it.contains(cutName, ignoreCase = true) && !it.equals(cutName, ignoreCase = true) }.take(5)
                } else {
                    emptyList()
                }
            }
            if (matchingCutPresets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sugestão na escrita:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        matchingCutPresets.forEach { preset ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    cutName = preset
                                    errorMsg = null
                                },
                                label = { Text(preset) }
                            )
                        }
                    }
                }
            }

            Text("Armazenamento (Câmara) & Temperatura", style = MaterialTheme.typography.titleSmall)
            val zoneOptions = if (zone == TemperatureZone.MATURACAO) {
                TemperatureZone.entries
            } else {
                TemperatureZone.primaryZones
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                zoneOptions.forEach { z ->
                    FilterChip(
                        selected = zone == z,
                        onClick = {
                            zone = z
                            val isGenericChamber = TemperatureZone.entries.any {
                                it.defaultChamber.equals(chamberName.trim(), ignoreCase = true)
                            }
                            if (chamberName.isBlank() || isGenericChamber) {
                                chamberName = z.defaultChamber
                            }
                            val nextTemp = if (initialItem == null && z == defaultZone && sessionTempCelsius != null) {
                                sessionTempCelsius
                            } else {
                                z.defaultTempCelsius
                            }
                            tempText = String.format(Locale.US, "%.1f", nextTemp)
                            if (initialItem == null) {
                                val days = if (z == TemperatureZone.CONGELADO) {
                                    meatType.defaultFrozenDays
                                } else {
                                    meatType.defaultChilledDays
                                }
                                expiryEpochDay = DateUtils.addDaysToTodayEpochDay(days)
                            }
                        },
                        label = { Text("${z.displayName} (${z.rangeLabel})") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_zone_chip_${z.name.lowercase()}")
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TemperatureZone.defaultChambers.forEach { preset ->
                    FilterChip(
                        selected = chamberName.trim().equals(preset, ignoreCase = true),
                        onClick = { chamberName = preset },
                        label = { Text(preset) }
                    )
                }
            }

            OutlinedTextField(
                value = chamberName,
                onValueChange = { chamberName = it },
                label = { Text("Câmara / Unidade") },
                placeholder = { Text("Ex.: Bairro de Fátima, Resfriado...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_chamber_input")
            )

            OutlinedTextField(
                value = tempText,
                onValueChange = { tempText = ReceivingViewModel.sanitizeTemperatureInput(it) },
                label = { Text("Temp. (°C)") },
                trailingIcon = {
                    TextButton(
                        onClick = { tempText = ReceivingViewModel.toggleTemperatureSign(tempText) },
                        modifier = Modifier.testTag("manual_temp_sign_button")
                    ) {
                        Text("±", fontWeight = FontWeight.Bold)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_temp_input")
            )

            tempText.replace(',', '.').toDoubleOrNull()?.let { parsedTemp ->
                ThermalComplianceBadge(
                    compliance = ThermalCompliance.evaluate(zone, parsedTemp),
                    tempCelsius = parsedTemp,
                    showTempValue = false
                )
            }

            Text("Quantidade, Unidade e Custo", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = {
                        quantityText = it
                        errorMsg = null
                    },
                    label = { Text("Qtd / Peso *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_quantity_input")
                )
                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    label = { Text("Custo/un R$") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_cost_input")
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MeasurementUnit.entries.forEach { u ->
                    FilterChip(
                        selected = unit == u,
                        onClick = { unit = u },
                        label = { Text(u.displayName) },
                        modifier = Modifier.testTag("manual_unit_chip_${u.name.lowercase()}")
                    )
                }
            }

            if (unit == MeasurementUnit.KG) {
                val parsedQty = quantityText.replace(',', '.').toDoubleOrNull() ?: 0.0
                val parsedPacks = packCountText.toIntOrNull() ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = packCountText,
                        onValueChange = { raw -> packCountText = raw.filter { it.isDigit() }.take(4) },
                        label = { Text("Qtd. volumes (opc.)") },
                        placeholder = { Text("Ex.: 3") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_pack_count_input")
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PackUnitType.entries.forEach { pType ->
                            FilterChip(
                                selected = packUnit == pType,
                                onClick = { packUnit = pType },
                                label = { Text(pType.pluralLabel) }
                            )
                        }
                    }
                }
                if (parsedQty > 0.0 && parsedPacks > 0) {
                    val avgKg = parsedQty / parsedPacks.toDouble()
                    Text(
                        text = "Peso médio calculado: ~${String.format(Locale.forLanguageTag("pt-BR"), "%.1f", avgKg)} kg/${packUnit.shortLabel} (habilita saída em 1 toque)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Text(
                text = "Validade: ${DateUtils.formatEpochDay(expiryEpochDay)} (${DateUtils.daysUntil(expiryEpochDay)}d)",
                style = MaterialTheme.typography.titleSmall
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(3 to "+3d", 7 to "+7d", 15 to "+15d", 30 to "+30d", 90 to "+90d").forEach { (days, label) ->
                    OutlinedButton(
                        onClick = { expiryEpochDay = DateUtils.addDaysToTodayEpochDay(days) },
                        modifier = Modifier.testTag("manual_expiry_quick_$days")
                    ) {
                        Text(label)
                    }
                }
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.testTag("manual_expiry_calendar_button")
                ) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.padding(start = 6.dp))
                    Text("Calendário")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = batchCode,
                    onValueChange = { batchCode = it },
                    label = { Text("Lote (opcional)") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_batch_input")
                )
                OutlinedTextField(
                    value = sifCode,
                    onValueChange = { sifCode = it },
                    label = { Text("SIF (opcional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manual_sif_input")
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Observações do lote (opcional)") },
                placeholder = { Text("Ex.: Embalagem a vácuo íntegra, sem exudato...") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_notes_input")
            )

            if (errorMsg != null) {
                Text(
                    text = errorMsg!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = {
                    val qty = quantityText.replace(',', '.').toDoubleOrNull()
                    when {
                        cutName.isBlank() -> errorMsg = "Informe o nome do corte ou produto."
                        qty == null || qty <= 0.0 -> errorMsg = "Informe uma quantidade ou peso válido."
                        else -> {
                            val parsedTemp = tempText.replace(',', '.').toDoubleOrNull() ?: zone.defaultTempCelsius
                            val parsedCost = costText.replace(',', '.').toDoubleOrNull() ?: 0.0
                            val parsedPacks = if (unit == MeasurementUnit.KG) {
                                packCountText.toIntOrNull()?.coerceAtLeast(0) ?: 0
                            } else {
                                0
                            }
                            onSave(
                                ParsedInvoiceItemDraft(
                                    localId = initialItem?.localId ?: UUID.randomUUID().toString(),
                                    rawDescription = initialItem?.rawDescription?.ifBlank { cutName.trim() } ?: cutName.trim(),
                                    meatType = meatType,
                                    cutName = cutName.trim(),
                                    temperatureZone = zone,
                                    measuredTempCelsius = parsedTemp,
                                    chamberName = chamberName.trim().ifBlank { zone.defaultChamber },
                                    quantity = qty,
                                    unit = unit,
                                    unitCostBrl = parsedCost,
                                    batchCode = batchCode.trim(),
                                    sifCode = sifCode.trim(),
                                    expiryEpochDay = expiryEpochDay,
                                    notes = notes.trim(),
                                    packCount = parsedPacks,
                                    packUnit = packUnit
                                )
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_manual_item_button")
            ) {
                Text(
                    text = if (initialItem == null) "Adicionar à Sessão" else "Salvar Alterações",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = DateUtils.epochDayToUtcPickerMillis(expiryEpochDay)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            expiryEpochDay = DateUtils.millisToEpochDay(millis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("Confirmar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancelar")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
