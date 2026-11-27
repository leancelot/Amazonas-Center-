package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ColdStorageItemEntity
import com.example.data.CutFefoGroup
import com.example.data.DateUtils
import com.example.data.FastClosureCutEntry
import com.example.data.FastClosurePlan
import com.example.data.FefoStockAllocationEngine
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.TemperatureZone
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FastStockClosureSheet(
    activeItems: List<ColdStorageItemEntity>,
    onDismiss: () -> Unit,
    onApplyClosure: (FastClosurePlan) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allGroups = remember(activeItems) {
        FefoStockAllocationEngine.groupActiveItemsByCut(activeItems)
    }

    var selectedZone by remember { mutableStateOf<TemperatureZone?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var movementType by remember { mutableStateOf(MovementType.SAIDA_BALCAO) }
    var closureNote by remember { mutableStateOf("") }
    val countedTextByKey = remember { mutableStateMapOf<String, String>() }

    val filteredGroups = remember(allGroups, selectedZone, searchQuery) {
        val q = searchQuery.trim().lowercase()
        allGroups.filter { g ->
            val matchesZone = selectedZone == null || g.zones.contains(selectedZone)
            val matchesQ = q.isEmpty() ||
                g.cutName.lowercase().contains(q) ||
                g.chamberNames.any { it.lowercase().contains(q) }
            matchesZone && matchesQ
        }
    }

    val closurePlan = remember(allGroups, countedTextByKey.toMap(), movementType, closureNote) {
        val entries = allGroups.mapNotNull { group ->
            val rawText = countedTextByKey[group.key]?.trim().orEmpty()
            if (rawText.isEmpty()) return@mapNotNull null
            val parsed = rawText.replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            FastClosureCutEntry(group = group, countedRemaining = parsed)
        }
        val reason = if (closureNote.isNotBlank()) {
            "Fechamento rápido: ${closureNote.trim()}"
        } else {
            "Fechamento rápido (sobra na câmara)"
        }
        FefoStockAllocationEngine.planFastClosure(
            entries = entries,
            movementType = movementType,
            reasonPrefix = reason
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FactCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Fechamento Rápido (Sobra)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "Informe apenas o que sobrou de cada corte na câmara. O sistema calcula a saída do período e abate em FEFO automático.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(MovementType.SAIDA_BALCAO, MovementType.SAIDA_DESOSSA).forEach { type ->
                    FilterChip(
                        selected = movementType == type,
                        onClick = { movementType = type },
                        label = { Text(type.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("closure_type_${type.name.lowercase()}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filtrar corte...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Limpar")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("closure_search_input")
                )
                OutlinedTextField(
                    value = closureNote,
                    onValueChange = { closureNote = it },
                    placeholder = { Text("Turno / obs.") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selectedZone == null,
                    onClick = { selectedZone = null },
                    label = { Text("Todas câmaras") }
                )
                TemperatureZone.primaryZones.forEach { z ->
                    FilterChip(
                        selected = selectedZone == z,
                        onClick = { selectedZone = if (selectedZone == z) null else z },
                        label = { Text(z.displayName) }
                    )
                }
            }

            if (filteredGroups.isEmpty()) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Text("Nenhum corte ativo para conferência", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 330.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredGroups, key = { it.key }) { group ->
                        FastClosureCutCard(
                            group = group,
                            countedText = countedTextByKey[group.key].orEmpty(),
                            onValueChange = { newText ->
                                if (newText.isEmpty()) {
                                    countedTextByKey.remove(group.key)
                                } else {
                                    countedTextByKey[group.key] = newText
                                }
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            val ptBr = Locale.forLanguageTag("pt-BR")
            if (closurePlan.overCountedCutNames.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Atenção: sobra informada maior que o saldo do sistema em ${closurePlan.overCountedCutNames.joinToString(", ")} (esses itens foram ignorados na baixa).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (closurePlan.affectedCutCount > 0) {
                            "${closurePlan.affectedCutCount} corte(s) • ${closurePlan.affectedLotCount} lote(s) FEFO"
                        } else {
                            "Digite a sobra nos cortes que movimentaram"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (closurePlan.totalDeductedKg > 0.0 || closurePlan.totalDeductedOtherUnits > 0) {
                        val parts = buildList {
                            if (closurePlan.totalDeductedKg > 0.0) {
                                add("${String.format(ptBr, "%.2f", closurePlan.totalDeductedKg)} kg")
                            }
                            if (closurePlan.totalDeductedOtherUnits > 0) {
                                add("${closurePlan.totalDeductedOtherUnits} pç/cx")
                            }
                        }
                        Text(
                            text = "Baixa total calculada: ${parts.joinToString(" + ")}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Button(
                onClick = { onApplyClosure(closurePlan) },
                enabled = closurePlan.requests.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_fast_closure_button")
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (closurePlan.requests.isNotEmpty()) {
                        "Aplicar Fechamento (${closurePlan.affectedCutCount} cortes)"
                    } else {
                        "Aplicar Fechamento"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FastClosureCutCard(
    group: CutFefoGroup,
    countedText: String,
    onValueChange: (String) -> Unit
) {
    val parsedCounted = countedText.replace(',', '.').toDoubleOrNull()
    val diff = parsedCounted?.let { group.totalQuantity - it }
    val ptBr = Locale.forLanguageTag("pt-BR")

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("closure_cut_card_${group.cutName.lowercase().replace(" ", "_")}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = group.cutName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append("Sistema: ${DateUtils.formatWeightOrQty(group.totalQuantity, group.unit)}")
                            if (group.totalRemainingPacks > 0 && group.primaryPackType != null) {
                                append(" (${group.totalRemainingPacks} ${group.primaryPackType.shortLabel})")
                            }
                            append(" • ${group.chamberNames.joinToString(", ")}")
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = countedText,
                    onValueChange = onValueChange,
                    label = { Text("Sobrou (${group.unit.shortLabel})") },
                    placeholder = { Text(String.format(Locale.US, "%.1f", group.totalQuantity)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .width(126.dp)
                        .testTag("closure_input_${group.cutName.lowercase().replace(" ", "_")}")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = parsedCounted != null && parsedCounted <= 0.001,
                        onClick = { onValueChange("0") },
                        label = { Text("Zerou (0)") }
                    )
                    if (group.totalRemainingPacks > 1 && group.primaryPackType != null && group.firstFefoPackAvgWeight > 0.0) {
                        val oneBoxLeft = (group.totalQuantity - group.firstFefoPackAvgWeight).coerceAtLeast(0.0)
                        FilterChip(
                            selected = false,
                            onClick = { onValueChange(String.format(Locale.US, "%.1f", oneBoxLeft)) },
                            label = { Text("-1 ${group.primaryPackType.shortLabel}") }
                        )
                    }
                    if (countedText.isNotEmpty()) {
                        FilterChip(
                            selected = false,
                            onClick = { onValueChange("") },
                            label = { Text("Limpar") }
                        )
                    }
                }

                if (diff != null) {
                    val (badgeColor, textColor, label) = when {
                        diff < -0.05 -> Triple(
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer,
                            "Maior que saldo"
                        )
                        diff > 0.001 -> Triple(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                            "Saída: ${DateUtils.formatWeightOrQty(diff, group.unit)}"
                        )
                        else -> Triple(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            "Sem saída"
                        )
                    }
                    Surface(color = badgeColor, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
