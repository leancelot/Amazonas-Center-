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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.data.ChamberTempLog
import com.example.data.DateUtils
import com.example.data.StaffRole
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.ReceivingViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChamberTemperatureSheet(
    logs: List<ChamberTempLog>,
    knownChambers: List<String>,
    defaultResponsibleName: String,
    activeRole: StaffRole,
    onDismiss: () -> Unit,
    onRecordRound: (
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double,
        responsibleName: String,
        notes: String
    ) -> Unit,
    onDeleteRound: (logId: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val chamberSuggestions = remember(knownChambers) {
        (TemperatureZone.defaultChambers + knownChambers)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    var selectedZone by remember { mutableStateOf(TemperatureZone.RESFRIADO) }
    var chamberName by remember { mutableStateOf(selectedZone.defaultChamber) }
    var tempText by remember { mutableStateOf(String.format(Locale.US, "%.1f", selectedZone.defaultTempCelsius)) }
    var responsible by remember { mutableStateOf(defaultResponsibleName) }
    var notes by remember { mutableStateOf("") }

    val parsedTemp = tempText.replace(",", ".").toDoubleOrNull()
    val previewCompliance = parsedTemp?.let { ThermalCompliance.evaluate(selectedZone, it) }

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Thermostat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ronda Térmica das Câmaras",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Controle diário de temperatura para auditoria sanitária e prevenção de quebras.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Câmara / Equipamento",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        chamberSuggestions.forEach { preset ->
                            FilterChip(
                                selected = chamberName.equals(preset, ignoreCase = true),
                                onClick = {
                                    chamberName = preset
                                    val inferredZone = when {
                                        preset.contains("cong", ignoreCase = true) -> TemperatureZone.CONGELADO
                                        preset.contains("matur", ignoreCase = true) -> TemperatureZone.MATURACAO
                                        else -> TemperatureZone.RESFRIADO
                                    }
                                    if (inferredZone != selectedZone) {
                                        selectedZone = inferredZone
                                        tempText = String.format(Locale.US, "%.1f", inferredZone.defaultTempCelsius)
                                    }
                                },
                                label = { Text(preset) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = chamberName,
                        onValueChange = { chamberName = it },
                        label = { Text("Nome da câmara") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chamber_temp_name_input")
                    )

                    Text(
                        text = "Faixa Térmica Alvo",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TemperatureZone.entries.forEach { zone ->
                            FilterChip(
                                selected = selectedZone == zone,
                                onClick = {
                                    selectedZone = zone
                                    if (chamberSuggestions.contains(chamberName)) {
                                        chamberName = zone.defaultChamber
                                    }
                                    tempText = String.format(Locale.US, "%.1f", zone.defaultTempCelsius)
                                },
                                label = { Text("${zone.displayName} (${zone.rangeLabel})") },
                                modifier = Modifier.testTag("chamber_temp_zone_${zone.name.lowercase()}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = tempText,
                            onValueChange = { raw ->
                                tempText = ReceivingViewModel.sanitizeTemperatureInput(raw)
                            },
                            label = { Text("Temperatura medida (°C)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chamber_temp_value_input")
                        )
                        OutlinedButton(
                            onClick = { tempText = ReceivingViewModel.toggleTemperatureSign(tempText) },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("chamber_temp_sign_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Exposure,
                                contentDescription = "Alternar sinal positivo ou negativo",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("+/-")
                        }
                    }

                    if (parsedTemp != null && previewCompliance != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Avaliação:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ThermalComplianceBadge(
                                compliance = previewCompliance,
                                tempCelsius = parsedTemp
                            )
                        }
                    }

                    OutlinedTextField(
                        value = responsible,
                        onValueChange = { responsible = it },
                        label = { Text("Responsável pela leitura") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chamber_temp_responsible_input")
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Observação (opcional)") },
                        placeholder = { Text("Ex.: Degelo OK, vedação conferida") },
                        maxLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chamber_temp_notes_input")
                    )

                    Button(
                        onClick = {
                            val validTemp = parsedTemp ?: return@Button
                            onRecordRound(
                                chamberName.ifBlank { selectedZone.defaultChamber },
                                selectedZone,
                                validTemp,
                                responsible,
                                notes
                            )
                            notes = ""
                        },
                        enabled = parsedTemp != null && chamberName.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_chamber_temp_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Registrar Ronda")
                    }
                }
            }

            HorizontalDivider()

            val todayLogs = logs.filter { DateUtils.isToday(it.recordedAtMillis) }
            Text(
                text = "Histórico Recente (${todayLogs.size} hoje • ${logs.size} total)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (logs.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Nenhuma ronda térmica registrada ainda. Faça a primeira medição acima.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                logs.take(15).forEach { log ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("chamber_temp_log_card_${log.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = log.chamberName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    TemperatureZonePill(zone = log.zoneEnum)
                                }
                                ThermalComplianceBadge(
                                    compliance = log.complianceEnum,
                                    tempCelsius = log.measuredTempCelsius
                                )
                                Text(
                                    text = buildString {
                                        append(DateUtils.formatDateTime(log.recordedAtMillis))
                                        if (log.recordedByName.isNotBlank()) {
                                            append(" • ${log.recordedByName}")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (log.notes.isNotBlank()) {
                                    Text(
                                        text = log.notes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (activeRole.canDeleteRecords) {
                                IconButton(
                                    onClick = { onDeleteRound(log.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("delete_chamber_temp_button_${log.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Remover registro térmico",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
