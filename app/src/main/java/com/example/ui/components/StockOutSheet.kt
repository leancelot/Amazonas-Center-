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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.MovementType
import com.example.data.PackSpecHelper
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StockOutSheet(
    item: ColdStorageItemEntity,
    onDismiss: () -> Unit,
    onConfirm: (MovementType, Double, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedType by remember { mutableStateOf(MovementType.SAIDA_BALCAO) }
    var quantityText by remember {
        mutableStateOf(String.format(Locale.US, "%.2f", item.currentQuantity))
    }
    var reason by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SelectionContainer {
            Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Dar Baixa — ${item.cutName}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Saldo atual: ${DateUtils.formatWeightOrQty(item.currentQuantity, item.unitEnum)} • ${item.chamberName}",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Motivo da movimentação",
                style = MaterialTheme.typography.titleSmall
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MovementType.standardOutgoingTypes.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(type.displayName) },
                        modifier = Modifier.testTag("movement_chip_${type.name.lowercase()}")
                    )
                }
            }

            OutlinedTextField(
                value = quantityText,
                onValueChange = {
                    quantityText = it
                    errorMsg = null
                },
                label = { Text("Quantidade (${item.unitEnum.shortLabel})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = errorMsg != null,
                supportingText = errorMsg?.let { { Text(it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stock_out_quantity_input")
            )

            val packSpec = remember(item.notes) { PackSpecHelper.parse(item.notes) }
            if (packSpec != null && packSpec.remainingCount > 0 && packSpec.avgWeightPerPack > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val packCounts = listOf(1, 2).filter { it <= packSpec.remainingCount }
                    packCounts.forEach { packs ->
                        val qtyForPacks = if (packs >= packSpec.remainingCount) {
                            item.currentQuantity
                        } else {
                            (packs * packSpec.avgWeightPerPack).coerceAtMost(item.currentQuantity)
                        }
                        val label = if (packs == 1) {
                            packSpec.formatSinglePackAction()
                        } else {
                            "-$packs ${packSpec.packType.shortLabel}"
                        }
                        androidx.compose.material3.FilledTonalButton(
                            onClick = {
                                quantityText = String.format(Locale.US, "%.2f", qtyForPacks)
                                errorMsg = null
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("stock_out_pack_$packs")
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0.25 to "25%", 0.5 to "50%", 1.0 to "Total (100%)").forEach { (ratio, label) ->
                    OutlinedButton(
                        onClick = {
                            val qty = (item.currentQuantity * ratio).coerceAtLeast(0.01)
                            quantityText = String.format(Locale.US, "%.2f", qty)
                            errorMsg = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stock_out_ratio_${(ratio * 100).toInt()}")
                    ) {
                        Text(label, maxLines = 1)
                    }
                }
            }

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Observação (opcional)") },
                placeholder = { Text("Ex.: Reposição balcão manhã, apara de gordura...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stock_out_reason_input")
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    val parsed = quantityText.replace(',', '.').toDoubleOrNull()
                    when {
                        parsed == null || parsed <= 0.0 -> {
                            errorMsg = "Informe uma quantidade maior que zero."
                        }
                        parsed > item.currentQuantity + 0.001 -> {
                            errorMsg = "Quantidade excede o saldo atual."
                        }
                        else -> {
                            onConfirm(selectedType, parsed.coerceAtMost(item.currentQuantity), reason.trim())
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_stock_out_button")
            ) {
                Text(
                    text = "Confirmar Baixa",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
        }
    }
}
