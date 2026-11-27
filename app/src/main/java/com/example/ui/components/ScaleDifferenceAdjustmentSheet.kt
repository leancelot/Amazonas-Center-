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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import com.example.data.DateUtils
import com.example.data.MeasurementUnit
import com.example.data.MovementType
import com.example.data.ReconciliationCadenceStatus
import com.example.data.ReconciliationIntervalPreset
import com.example.data.ReconciliationProfile
import com.example.data.ReconciliationScopeType
import com.example.data.ScaleDifferencePlan
import com.example.data.ScaleReconciliationEngine
import com.example.data.ScaleReconciliationRecord
import com.example.data.StockMovementEntity
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScaleDifferenceAdjustmentSheet(
    activeItems: List<ColdStorageItemEntity>,
    profiles: List<ReconciliationProfile>,
    records: List<ScaleReconciliationRecord>,
    allMovements: List<StockMovementEntity>,
    onDismiss: () -> Unit,
    onApplyDifference: (ScaleDifferencePlan) -> Unit,
    onSaveProfile: (
        id: String?,
        name: String,
        scopeType: ReconciliationScopeType,
        scopeFilter: String,
        intervalDays: Int,
        preferredMovementType: MovementType
    ) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Balança, 1 = Rotinas, 2 = Histórico

    val initialSelectedProfileId = remember(profiles, activeItems, allMovements) {
        val now = System.currentTimeMillis()
        profiles.firstOrNull { profile ->
            val hasLots = ScaleReconciliationEngine.filterMatchingLots(
                activeItems, profile.scopeTypeEnum, profile.scopeFilter
            ).isNotEmpty()
            val status = profile.evaluateStatus(allMovements, now).status
            hasLots && (status == ReconciliationCadenceStatus.DUE || status == ReconciliationCadenceStatus.NEVER_CHECKED)
        }?.id ?: profiles.firstOrNull { profile ->
            ScaleReconciliationEngine.filterMatchingLots(
                activeItems, profile.scopeTypeEnum, profile.scopeFilter
            ).isNotEmpty()
        }?.id ?: profiles.firstOrNull()?.id
    }

    var selectedProfileId by remember { mutableStateOf(initialSelectedProfileId) }
    var useCustomScope by remember { mutableStateOf(false) }

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
                    imageVector = Icons.Rounded.Scale,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ajuste por Diferença (Balança)",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Informe o peso atual da balança para apurar quebra e ajustar em FEFO.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Balança", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Rounded.Scale, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("scale_tab_adjust")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Rotinas (${profiles.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("scale_tab_routines")
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Histórico (${records.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.testTag("scale_tab_history")
                )
            }

            when (selectedTab) {
                0 -> ScaleCheckTab(
                    activeItems = activeItems,
                    profiles = profiles,
                    allMovements = allMovements,
                    selectedProfileId = selectedProfileId,
                    useCustomScope = useCustomScope,
                    onSelectProfile = { id ->
                        selectedProfileId = id
                        useCustomScope = false
                    },
                    onSelectCustomScope = { useCustomScope = true },
                    onOpenRoutinesTab = { selectedTab = 1 },
                    onApply = onApplyDifference
                )
                1 -> ScaleRoutinesTab(
                    activeItems = activeItems,
                    profiles = profiles,
                    allMovements = allMovements,
                    onUseProfile = { profileId ->
                        selectedProfileId = profileId
                        useCustomScope = false
                        selectedTab = 0
                    },
                    onSaveProfile = onSaveProfile,
                    onDeleteProfile = onDeleteProfile
                )
                else -> ScaleHistoryTab(records = records)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleCheckTab(
    activeItems: List<ColdStorageItemEntity>,
    profiles: List<ReconciliationProfile>,
    allMovements: List<StockMovementEntity>,
    selectedProfileId: String?,
    useCustomScope: Boolean,
    onSelectProfile: (String) -> Unit,
    onSelectCustomScope: () -> Unit,
    onOpenRoutinesTab: () -> Unit,
    onApply: (ScaleDifferencePlan) -> Unit
) {
    val ptBr = Locale.forLanguageTag("pt-BR")
    val activeProfile = remember(profiles, selectedProfileId, useCustomScope) {
        if (useCustomScope) null else profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
    }

    var customScopeType by remember { mutableStateOf(ReconciliationScopeType.ALL_KG) }
    var customFilter by remember { mutableStateOf("") }

    val effectiveScopeType = activeProfile?.scopeTypeEnum ?: customScopeType
    val effectiveScopeFilter = activeProfile?.scopeFilter ?: customFilter
    val effectiveProfileId = activeProfile?.id ?: "avulso"
    val effectiveProfileName = activeProfile?.name ?: when (customScopeType) {
        ReconciliationScopeType.ALL_KG -> "Avulso (Todo Estoque)"
        else -> "${customScopeType.label}: ${customFilter.ifBlank { "Todos" }}"
    }

    var movementType by remember(effectiveProfileId) {
        mutableStateOf(activeProfile?.preferredMovementEnum ?: MovementType.PERDA_APARA)
    }
    var scaleWeightText by remember(effectiveProfileId, effectiveScopeType, effectiveScopeFilter) {
        mutableStateOf("")
    }
    var noteText by remember(effectiveProfileId) { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val activeKgItems = remember(activeItems) {
        activeItems.filter { it.unitEnum == MeasurementUnit.KG && it.currentQuantity > 0.001 }
    }
    val availableSuppliers = remember(activeKgItems) {
        activeKgItems.map { it.supplierName.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val availableChambers = remember(activeKgItems) {
        activeKgItems.map { it.chamberName.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val matchingLots = remember(activeItems, effectiveScopeType, effectiveScopeFilter) {
        ScaleReconciliationEngine.filterMatchingLots(activeItems, effectiveScopeType, effectiveScopeFilter)
    }
    val expectedKg = remember(matchingLots) { matchingLots.sumOf { it.currentQuantity } }

    val parsedScaleKg = scaleWeightText.replace(',', '.').toDoubleOrNull()
    val previewPlan = remember(
        activeItems,
        effectiveProfileId,
        effectiveProfileName,
        effectiveScopeType,
        effectiveScopeFilter,
        parsedScaleKg,
        movementType,
        noteText
    ) {
        if (parsedScaleKg != null && parsedScaleKg >= 0.0 && matchingLots.isNotEmpty()) {
            ScaleReconciliationEngine.planScaleDifference(
                items = activeItems,
                profileId = effectiveProfileId,
                profileName = effectiveProfileName,
                scopeType = effectiveScopeType,
                scopeFilter = effectiveScopeFilter,
                scaleWeightKg = parsedScaleKg,
                movementType = movementType,
                note = noteText
            )
        } else {
            null
        }
    }

    val now = remember(allMovements, profiles) { System.currentTimeMillis() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Rotina ou escopo da pesagem",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onOpenRoutinesTab) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Configurar")
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            profiles.forEach { profile ->
                val status = profile.evaluateStatus(allMovements, now)
                val suffix = when (status.status) {
                    ReconciliationCadenceStatus.DUE -> " • Pendente"
                    ReconciliationCadenceStatus.NEVER_CHECKED -> " • Novo"
                    ReconciliationCadenceStatus.ON_TRACK -> " • OK"
                }
                FilterChip(
                    selected = !useCustomScope && activeProfile?.id == profile.id,
                    onClick = { onSelectProfile(profile.id) },
                    label = { Text("${profile.name}$suffix") },
                    modifier = Modifier.testTag("scale_profile_chip_${profile.id}")
                )
            }
            FilterChip(
                selected = useCustomScope,
                onClick = onSelectCustomScope,
                label = { Text("Avulso") },
                modifier = Modifier.testTag("scale_profile_chip_custom")
            )
        }

        if (activeProfile != null && !useCustomScope) {
            val statusSummary = activeProfile.evaluateStatus(allMovements, now)
            val badgeBg = when (statusSummary.status) {
                ReconciliationCadenceStatus.DUE -> MaterialTheme.colorScheme.errorContainer
                ReconciliationCadenceStatus.NEVER_CHECKED -> MaterialTheme.colorScheme.secondaryContainer
                ReconciliationCadenceStatus.ON_TRACK -> MaterialTheme.colorScheme.primaryContainer
            }
            val badgeFg = when (statusSummary.status) {
                ReconciliationCadenceStatus.DUE -> MaterialTheme.colorScheme.onErrorContainer
                ReconciliationCadenceStatus.NEVER_CHECKED -> MaterialTheme.colorScheme.onSecondaryContainer
                ReconciliationCadenceStatus.ON_TRACK -> MaterialTheme.colorScheme.onPrimaryContainer
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeProfile.scopeDescription(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Intervalo flexível: ~${activeProfile.intervalDays} dia(s) (sem dia fixo)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(color = badgeBg, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            text = statusSummary.badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (useCustomScope) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ReconciliationScopeType.entries.forEach { st ->
                    FilterChip(
                        selected = customScopeType == st,
                        onClick = {
                            customScopeType = st
                            if (st == ReconciliationScopeType.ALL_KG) customFilter = ""
                        },
                        label = { Text(st.label) }
                    )
                }
            }
            if (customScopeType != ReconciliationScopeType.ALL_KG) {
                OutlinedTextField(
                    value = customFilter,
                    onValueChange = { customFilter = it },
                    label = { Text(customScopeType.label) },
                    placeholder = { Text(customScopeType.filterHint) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scale_custom_filter_input")
                )
                val suggestions = when (customScopeType) {
                    ReconciliationScopeType.SUPPLIER -> availableSuppliers
                    ReconciliationScopeType.CHAMBER -> availableChambers
                    ReconciliationScopeType.CUT -> activeKgItems.map { it.cutName }.distinct().sorted().take(8)
                    ReconciliationScopeType.ALL_KG -> emptyList()
                }
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.forEach { s ->
                            FilterChip(
                                selected = customFilter.equals(s, ignoreCase = true),
                                onClick = { customFilter = s },
                                label = { Text(s) }
                            )
                        }
                    }
                }
            }
        }

        // System Expectation Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Expectativa no Sistema",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${matchingLots.size} lote(s) kg",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
                Text(
                    text = "${String.format(ptBr, "%.2f", expectedKg)} kg",
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = JetBrainsMonoFamily),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("scale_expected_kg_text")
                )
            }
        }

        if (matchingLots.isEmpty()) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Nenhum lote ativo em kg corresponde a '${effectiveScopeFilter.ifBlank { effectiveProfileName }}' no momento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (activeKgItems.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                onSelectCustomScope()
                                customScopeType = ReconciliationScopeType.ALL_KG
                                customFilter = ""
                            },
                            modifier = Modifier.height(44.dp)
                        ) {
                            Text("Conferir todo estoque (${String.format(ptBr, "%.1f", activeKgItems.sumOf { it.currentQuantity })} kg)")
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = scaleWeightText,
                onValueChange = {
                    scaleWeightText = it
                    errorMsg = null
                },
                label = { Text("Peso total atual na balança (kg) *") },
                placeholder = { Text("Ex.: ${String.format(Locale.US, "%.1f", (expectedKg * 0.96).coerceAtLeast(0.0))}") },
                trailingIcon = {
                    if (scaleWeightText.isNotEmpty()) {
                        IconButton(onClick = { scaleWeightText = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Limpar peso")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("scale_weight_input")
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = scaleWeightText == "0",
                    onClick = { scaleWeightText = "0"; errorMsg = null },
                    label = { Text("Zerou (0 kg)") }
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        scaleWeightText = String.format(Locale.US, "%.2f", expectedKg)
                        errorMsg = null
                    },
                    label = { Text("Bateu 100% (${String.format(ptBr, "%.1f", expectedKg)})") }
                )
            }

            if (previewPlan != null) {
                ScaleDifferencePreviewCard(
                    plan = previewPlan,
                    selectedMovementType = movementType,
                    onSelectMovementType = { movementType = it }
                )
            }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Observação (opcional)") },
                placeholder = { Text("Ex.: Pedido semana Friesul, desossa terça, gotejamento...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            errorMsg?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            val buttonLabel = when {
                previewPlan == null -> "Confirmar Ajuste"
                previewPlan.isLossOrOutflow -> {
                    "Aplicar Quebra (-${String.format(ptBr, "%.2f", previewPlan.differenceKg)} kg • ${String.format(ptBr, "%.1f", previewPlan.shrinkagePercent)}%)"
                }
                previewPlan.isSurplus -> {
                    "Aplicar Sobra (+${String.format(ptBr, "%.2f", previewPlan.surplusAddKg)} kg)"
                }
                else -> "Registrar Conferência (0% quebra)"
            }

            Button(
                onClick = {
                    val weight = parsedScaleKg
                    if (weight == null || weight < 0.0) {
                        errorMsg = "Informe o peso atual medido na balança (kg)."
                    } else if (previewPlan != null) {
                        onApply(previewPlan)
                    }
                },
                colors = if (previewPlan?.isLossOrOutflow == true && movementType.isLoss) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_scale_difference_button")
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleDifferencePreviewCard(
    plan: ScaleDifferencePlan,
    selectedMovementType: MovementType,
    onSelectMovementType: (MovementType) -> Unit
) {
    val ptBr = Locale.forLanguageTag("pt-BR")
    val classificationTypes = remember {
        listOf(
            MovementType.PERDA_APARA to "Quebra / Perda",
            MovementType.SAIDA_BALCAO to "Saída não lançada",
            MovementType.AJUSTE to "Ajuste simples"
        )
    }

    val containerColor = when {
        plan.isExactMatch -> MaterialTheme.colorScheme.primaryContainer
        plan.isSurplus -> MaterialTheme.colorScheme.secondaryContainer
        selectedMovementType.isLoss -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when {
        plan.isExactMatch -> MaterialTheme.colorScheme.onPrimaryContainer
        plan.isSurplus -> MaterialTheme.colorScheme.onSecondaryContainer
        selectedMovementType.isLoss -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("scale_difference_preview_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                plan.isLossOrOutflow -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Diferença apurada (Quebra)",
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor
                            )
                            Text(
                                text = "-${String.format(ptBr, "%.2f", plan.differenceKg)} kg",
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${String.format(ptBr, "%.1f", plan.shrinkagePercent)}% quebra",
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                            if (plan.estimatedLossBrl > 0.0) {
                                Text(
                                    text = "Custo: ${DateUtils.formatCurrencyBrl(plan.estimatedLossBrl)}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    color = contentColor
                                )
                            }
                        }
                    }

                    Text(
                        text = "Classificar diferença como:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        classificationTypes.forEach { (type, label) ->
                            FilterChip(
                                selected = selectedMovementType == type,
                                onClick = { onSelectMovementType(type) },
                                label = { Text(label) }
                            )
                        }
                    }

                    if (plan.allocations.isNotEmpty()) {
                        HorizontalDivider(color = contentColor.copy(alpha = 0.2f))
                        Text(
                            text = "Rateio automático FEFO (${plan.allocations.size} lote(s)):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                        plan.allocations.take(4).forEach { alloc ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${alloc.item.cutName} (Val ${DateUtils.formatEpochDay(alloc.item.expiryDateEpochDay)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentColor,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "-${String.format(ptBr, "%.2f", alloc.deductQuantity)} kg",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    fontWeight = FontWeight.Bold,
                                    color = contentColor
                                )
                            }
                        }
                        if (plan.allocations.size > 4) {
                            Text(
                                text = "+ ${plan.allocations.size - 4} outro(s) lote(s)...",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                plan.isSurplus -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = contentColor)
                        Column {
                            Text(
                                text = "Sobra na balança: +${String.format(ptBr, "%.2f", plan.surplusAddKg)} kg",
                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.Bold,
                                color = contentColor
                            )
                            Text(
                                text = "Será somado ao lote mais recente (${plan.surplusTargetLot?.cutName.orEmpty()}) para igualar o saldo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor
                            )
                        }
                    }
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = contentColor)
                        Text(
                            text = "Saldo 100% compatível com a balança (0,0% quebra).",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScaleRoutinesTab(
    activeItems: List<ColdStorageItemEntity>,
    profiles: List<ReconciliationProfile>,
    allMovements: List<StockMovementEntity>,
    onUseProfile: (String) -> Unit,
    onSaveProfile: (
        id: String?,
        name: String,
        scopeType: ReconciliationScopeType,
        scopeFilter: String,
        intervalDays: Int,
        preferredMovementType: MovementType
    ) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf("") }
    var scopeType by remember { mutableStateOf(ReconciliationScopeType.SUPPLIER) }
    var scopeFilter by remember { mutableStateOf("") }
    var intervalDaysText by remember { mutableStateOf("7") }
    var formError by remember { mutableStateOf<String?>(null) }

    val availableSuppliers = remember(activeItems) {
        activeItems.filter { it.unitEnum == MeasurementUnit.KG }.map { it.supplierName.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val availableChambers = remember(activeItems) {
        activeItems.filter { it.unitEnum == MeasurementUnit.KG }.map { it.chamberName.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    }

    val now = remember(allMovements, profiles) { System.currentTimeMillis() }
    val ptBr = Locale.forLanguageTag("pt-BR")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Defina intervalos flexíveis (sem dia fixo da semana). O app acompanha há quantos dias foi a última pesagem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (showForm) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (editingId != null) "Editar Rotina" else "Nova Rotina de Balança",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = nameText,
                        onValueChange = { nameText = it; formError = null },
                        label = { Text("Nome da rotina *") },
                        placeholder = { Text("Ex.: Pedidos Friesul, Unidade Bairro de Fátima") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("routine_name_input")
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReconciliationScopeType.entries.forEach { st ->
                            FilterChip(
                                selected = scopeType == st,
                                onClick = {
                                    scopeType = st
                                    if (st == ReconciliationScopeType.ALL_KG) scopeFilter = ""
                                },
                                label = { Text(st.label) }
                            )
                        }
                    }
                    if (scopeType != ReconciliationScopeType.ALL_KG) {
                        OutlinedTextField(
                            value = scopeFilter,
                            onValueChange = { scopeFilter = it },
                            label = { Text(scopeType.label) },
                            placeholder = { Text(scopeType.filterHint) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("routine_filter_input")
                        )
                        val suggestions = when (scopeType) {
                            ReconciliationScopeType.SUPPLIER -> availableSuppliers
                            ReconciliationScopeType.CHAMBER -> availableChambers
                            else -> emptyList()
                        }
                        if (suggestions.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                suggestions.forEach { s ->
                                    FilterChip(
                                        selected = scopeFilter.equals(s, ignoreCase = true),
                                        onClick = { scopeFilter = s },
                                        label = { Text(s) }
                                    )
                                }
                            }
                        }
                    }

                    Text("Intervalo de conferência (em dias)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ReconciliationIntervalPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = intervalDaysText == preset.days.toString(),
                                onClick = { intervalDaysText = preset.days.toString() },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = intervalDaysText,
                        onValueChange = { intervalDaysText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Intervalo personalizado (dias)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    formError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showForm = false; editingId = null },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancelar")
                        }
                        Button(
                            onClick = {
                                val days = intervalDaysText.toIntOrNull()
                                if (nameText.isBlank()) {
                                    formError = "Informe um nome para a rotina."
                                } else if (days == null || days < 1) {
                                    formError = "Informe um intervalo válido (mínimo 1 dia)."
                                } else {
                                    onSaveProfile(
                                        editingId,
                                        nameText.trim(),
                                        scopeType,
                                        scopeFilter.trim(),
                                        days,
                                        MovementType.PERDA_APARA
                                    )
                                    showForm = false
                                    editingId = null
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_routine_button")
                        ) {
                            Text("Salvar Rotina")
                        }
                    }
                }
            }
        } else {
            FilledTonalButton(
                onClick = {
                    editingId = null
                    nameText = ""
                    scopeType = ReconciliationScopeType.SUPPLIER
                    scopeFilter = ""
                    intervalDaysText = "7"
                    formError = null
                    showForm = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("add_routine_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Criar Nova Rotina / Intervalo")
            }
        }

        profiles.forEach { profile ->
            val status = profile.evaluateStatus(allMovements, now)
            val currentExpected = ScaleReconciliationEngine
                .filterMatchingLots(activeItems, profile.scopeTypeEnum, profile.scopeFilter)
                .sumOf { it.currentQuantity }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${profile.scopeDescription()} • A cada ${profile.intervalDays} dia(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${String.format(ptBr, "%.1f", currentExpected)} kg",
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
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
                                append(status.badgeText)
                                if (profile.lastCheckedAtMillis > 0L) {
                                    append(" • Últ. quebra: ${String.format(ptBr, "%.1f", profile.lastShrinkagePercent)}%")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.status == ReconciliationCadenceStatus.DUE) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onUseProfile(profile.id) }) {
                                Text("Conferir")
                            }
                            IconButton(
                                onClick = {
                                    editingId = profile.id
                                    nameText = profile.name
                                    scopeType = profile.scopeTypeEnum
                                    scopeFilter = profile.scopeFilter
                                    intervalDaysText = profile.intervalDays.toString()
                                    formError = null
                                    showForm = true
                                }
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Editar rotina", modifier = Modifier.size(18.dp))
                            }
                            if (profiles.size > 1) {
                                IconButton(onClick = { onDeleteProfile(profile.id) }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Remover rotina",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
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

@Composable
private fun ScaleHistoryTab(records: List<ScaleReconciliationRecord>) {
    val ptBr = Locale.forLanguageTag("pt-BR")
    if (records.isEmpty()) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Text("Nenhuma conferência registrada ainda", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = "Ao informar o peso da balança na aba Balança, cada diferença apurada fica salva aqui com o % de quebra.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records, key = { it.id }) { rec ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = rec.profileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = DateUtils.formatDateTime(rec.recordedAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Sistema: ${String.format(ptBr, "%.2f", rec.expectedKg)} kg  •  Balança: ${String.format(ptBr, "%.2f", rec.scaleWeightKg)} kg",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val diffLabel = when {
                                rec.differenceKg > 0.001 -> "-${String.format(ptBr, "%.2f", rec.differenceKg)} kg (${String.format(ptBr, "%.1f", rec.shrinkagePercent)}% quebra)"
                                rec.differenceKg < -0.001 -> "+${String.format(ptBr, "%.2f", abs(rec.differenceKg))} kg (sobra)"
                                else -> "0,00 kg (exato)"
                            }
                            Text(
                                text = diffLabel,
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                fontWeight = FontWeight.Bold,
                                color = if (rec.differenceKg > 0.001) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            if (rec.estimatedLossBrl > 0.0) {
                                Text(
                                    text = DateUtils.formatCurrencyBrl(rec.estimatedLossBrl),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
