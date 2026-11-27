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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.FefoLotAllocation
import com.example.data.FefoStockAllocationEngine
import com.example.data.MeatType
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.PackUnitType
import com.example.data.TemperatureZone
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickCutStockOutSheet(
    activeItems: List<ColdStorageItemEntity>,
    onDismiss: () -> Unit,
    onConfirmAllocations: (
        group: CutFefoGroup,
        movementType: MovementType,
        allocations: List<FefoLotAllocation>,
        reason: String
    ) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allGroups = remember(activeItems) {
        FefoStockAllocationEngine.groupActiveItemsByCut(activeItems)
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedMeatType by remember { mutableStateOf<MeatType?>(null) }
    var selectedZone by remember { mutableStateOf<TemperatureZone?>(null) }
    var selectedGroupKey by remember { mutableStateOf<String?>(null) }

    val filteredGroups = remember(allGroups, searchQuery, selectedMeatType, selectedZone) {
        val q = searchQuery.trim().lowercase()
        allGroups.filter { g ->
            val matchesQ = q.isEmpty() ||
                g.cutName.lowercase().contains(q) ||
                g.meatType.displayName.lowercase().contains(q) ||
                g.chamberNames.any { it.lowercase().contains(q) }
            val matchesMeat = selectedMeatType == null || g.meatType == selectedMeatType
            val matchesZone = selectedZone == null || g.zones.contains(selectedZone)
            matchesQ && matchesMeat && matchesZone
        }
    }

    val activeGroup = remember(allGroups, selectedGroupKey) {
        selectedGroupKey?.let { key -> allGroups.firstOrNull { it.key == key } }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        if (activeGroup == null) {
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
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Saída Rápida (FEFO Automático)",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Escolha o corte que saiu da câmara para o balcão ou desossa. O app abate automaticamente dos lotes que vencem primeiro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar corte (ex.: Picanha, Alcatra, Peito)...") },
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
                        .fillMaxWidth()
                        .testTag("quick_cut_search_input")
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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
                    MeatType.entries.forEach { meat ->
                        if (allGroups.any { it.meatType == meat }) {
                            FilterChip(
                                selected = selectedMeatType == meat,
                                onClick = { selectedMeatType = if (selectedMeatType == meat) null else meat },
                                label = { Text(meat.displayName) }
                            )
                        }
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
                            Icon(
                                imageVector = Icons.Rounded.Inventory2,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Nenhum corte ativo disponível",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ajuste os filtros ou registre uma entrada em Receber.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredGroups, key = { it.key }) { group ->
                            QuickCutGroupCard(
                                group = group,
                                onClick = { selectedGroupKey = group.key }
                            )
                        }
                    }
                }
            }
        } else {
            QuickCutDeductionStep(
                group = activeGroup,
                onBack = { selectedGroupKey = null },
                onConfirm = { movementType, allocations, reason ->
                    onConfirmAllocations(activeGroup, movementType, allocations, reason)
                }
            )
        }
    }
}

@Composable
private fun QuickCutGroupCard(
    group: CutFefoGroup,
    onClick: () -> Unit
) {
    val ptBr = Locale.forLanguageTag("pt-BR")
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quick_cut_group_${group.cutName.lowercase().replace(" ", "_")}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MeatTypePill(meatType = group.meatType)
                    ExpiryUrgencyBadge(expiryEpochDay = group.nearestExpiryEpochDay)
                }
                Text(
                    text = group.cutName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("${group.lots.size} lote(s) • ${group.chamberNames.joinToString(", ")}")
                        if (group.totalRemainingPacks > 0 && group.primaryPackType != null) {
                            val unitShort = group.primaryPackType.shortLabel
                            append("\nVolumes: ${group.totalRemainingPacks} $unitShort")
                            if (group.firstFefoPackAvgWeight > 0.0) {
                                append(" (~${String.format(ptBr, "%.1f", group.firstFefoPackAvgWeight)} kg/$unitShort)")
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = DateUtils.formatWeightOrQty(group.totalQuantity, group.unit),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowOutward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Baixar",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickCutDeductionStep(
    group: CutFefoGroup,
    onBack: () -> Unit,
    onConfirm: (MovementType, List<FefoLotAllocation>, String) -> Unit
) {
    val ptBr = Locale.forLanguageTag("pt-BR")
    val quickMovementTypes = remember {
        listOf(
            MovementType.SAIDA_BALCAO,
            MovementType.SAIDA_DESOSSA,
            MovementType.PERDA_APARA,
            MovementType.AJUSTE
        )
    }

    var movementType by remember(group.key) { mutableStateOf(MovementType.SAIDA_BALCAO) }
    var selectedPackCount by remember(group.key) {
        mutableIntStateOf(if (group.totalRemainingPacks > 0) 1 else 0)
    }
    var customQtyText by remember(group.key) {
        mutableStateOf(
            if (group.totalRemainingPacks > 0) {
                ""
            } else {
                val defaultPreset = when {
                    group.unit != MeasurementUnit.KG -> minOf(1.0, group.totalQuantity)
                    group.totalQuantity >= 10.0 -> 10.0
                    else -> group.totalQuantity
                }
                String.format(Locale.US, "%.2f", defaultPreset)
            }
        )
    }
    var reason by remember(group.key) { mutableStateOf("") }
    var errorMsg by remember(group.key) { mutableStateOf<String?>(null) }

    val plannedAllocations = remember(group, selectedPackCount, customQtyText) {
        if (selectedPackCount > 0) {
            FefoStockAllocationEngine.planPackDeduction(group.lots, selectedPackCount)
        } else {
            val parsed = customQtyText.replace(',', '.').toDoubleOrNull() ?: 0.0
            FefoStockAllocationEngine.planQuantityDeduction(group.lots, parsed)
        }
    }
    val plannedTotal = remember(plannedAllocations) {
        plannedAllocations.sumOf { it.deductQuantity }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("quick_cut_back_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Trocar corte")
            }
            ExpiryUrgencyBadge(expiryEpochDay = group.nearestExpiryEpochDay)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = group.cutName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = buildString {
                    append("Disponível: ${DateUtils.formatWeightOrQty(group.totalQuantity, group.unit)} • ${group.lots.size} lote(s)")
                    if (group.totalRemainingPacks > 0 && group.primaryPackType != null) {
                        append(" • ${group.totalRemainingPacks} ${group.primaryPackType.shortLabel}")
                    }
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text("Motivo da saída", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickMovementTypes.forEach { type ->
                FilterChip(
                    selected = movementType == type,
                    onClick = { movementType = type },
                    label = { Text(type.displayName) },
                    modifier = Modifier.testTag("quick_cut_type_${type.name.lowercase()}")
                )
            }
        }

        if (group.totalRemainingPacks > 0 && group.primaryPackType != null) {
            val packType = group.primaryPackType
            val packOptions = listOf(1, 2, 3).filter { it <= group.totalRemainingPacks }
            Text(
                text = "Baixa em 1 toque por ${packType.singularLabel.lowercase()}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                packOptions.forEach { count ->
                    val approxAlloc = remember(group.lots, count) {
                        FefoStockAllocationEngine.planPackDeduction(group.lots, count).sumOf { it.deductQuantity }
                    }
                    val selected = selectedPackCount == count
                    val label = "-$count ${packType.shortLabel} (~${String.format(ptBr, "%.1f", approxAlloc)} kg)"
                    if (selected) {
                        Button(
                            onClick = {
                                selectedPackCount = count
                                customQtyText = ""
                                errorMsg = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_pack_button_$count")
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                selectedPackCount = count
                                customQtyText = ""
                                errorMsg = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("quick_pack_button_$count")
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Text(
            text = if (group.unit == MeasurementUnit.KG) "Atalhos de peso ou digite" else "Atalhos de quantidade",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val presets = if (group.unit == MeasurementUnit.KG) {
                listOf(5.0 to "-5 kg", 10.0 to "-10 kg", 15.0 to "-15 kg", 20.0 to "-20 kg")
            } else {
                listOf(1.0 to "-1 ${group.unit.shortLabel}", 2.0 to "-2 ${group.unit.shortLabel}", 5.0 to "-5 ${group.unit.shortLabel}")
            }
            presets.forEach { (amount, label) ->
                if (amount <= group.totalQuantity + 0.01) {
                    val isSelected = selectedPackCount == 0 &&
                        (customQtyText.replace(',', '.').toDoubleOrNull() ?: -1.0) == amount
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedPackCount = 0
                            customQtyText = String.format(Locale.US, "%.2f", amount)
                            errorMsg = null
                        },
                        label = { Text(label) }
                    )
                }
            }
            FilterChip(
                selected = selectedPackCount == 0 &&
                    kotlin.math.abs((customQtyText.replace(',', '.').toDoubleOrNull() ?: 0.0) - group.totalQuantity) < 0.01,
                onClick = {
                    selectedPackCount = 0
                    customQtyText = String.format(Locale.US, "%.2f", group.totalQuantity)
                    errorMsg = null
                },
                label = { Text("Zerar corte (100%)") },
                modifier = Modifier.testTag("quick_cut_all_chip")
            )
        }

        OutlinedTextField(
            value = customQtyText,
            onValueChange = {
                selectedPackCount = 0
                customQtyText = it
                errorMsg = null
            },
            label = { Text("Outro valor (${group.unit.shortLabel})") },
            placeholder = {
                Text(
                    if (selectedPackCount > 0) {
                        "Usando $selectedPackCount ${group.primaryPackType?.shortLabel ?: "vol"}"
                    } else {
                        "Ex.: 12.5"
                    }
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("quick_cut_custom_qty_input")
        )

        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text("Observação (opcional)") },
            placeholder = { Text("Ex.: Reposição balcão tarde, pedido açougue...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (plannedAllocations.isNotEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Plano FEFO automático (${plannedAllocations.size} lote(s)):",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    plannedAllocations.forEach { alloc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = buildString {
                                    append("Val ${DateUtils.formatEpochDay(alloc.item.expiryDateEpochDay)}")
                                    if (alloc.item.batchCode.isNotBlank()) append(" • Lote ${alloc.item.batchCode}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "-${DateUtils.formatWeightOrQty(alloc.deductQuantity, group.unit)} (resta ${DateUtils.formatWeightOrQty(alloc.remainingAfter, group.unit)})",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        errorMsg?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                val parsedCustom = customQtyText.replace(',', '.').toDoubleOrNull()
                when {
                    selectedPackCount <= 0 && (parsedCustom == null || parsedCustom <= 0.0) -> {
                        errorMsg = "Selecione um volume/atalho ou informe uma quantidade maior que zero."
                    }
                    selectedPackCount <= 0 && parsedCustom != null && parsedCustom > group.totalQuantity + 0.01 -> {
                        errorMsg = "Quantidade excede o saldo total disponível do corte."
                    }
                    plannedAllocations.isEmpty() -> {
                        errorMsg = "Não foi possível calcular a baixa FEFO para esse valor."
                    }
                    else -> {
                        val autoReason = buildString {
                            if (selectedPackCount > 0 && group.primaryPackType != null) {
                                append("Saída rápida: $selectedPackCount ${group.primaryPackType.shortLabel}")
                            } else {
                                append("Saída rápida por corte (FEFO)")
                            }
                            if (reason.isNotBlank()) {
                                append(" • ${reason.trim()}")
                            }
                        }.take(300)
                        onConfirm(movementType, plannedAllocations, autoReason)
                    }
                }
            },
            colors = if (movementType.isLoss) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("confirm_quick_cut_stock_out_button")
        ) {
            Text(
                text = if (plannedTotal > 0.001) {
                    "Confirmar Saída (-${DateUtils.formatWeightOrQty(plannedTotal, group.unit)})"
                } else {
                    "Confirmar Saída"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
