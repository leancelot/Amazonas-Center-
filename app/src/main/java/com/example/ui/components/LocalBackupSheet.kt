package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.BackupRestoreSummary
import com.example.data.DateUtils
import com.example.data.LocalColdStorageBackupEnvelope
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBackupSheet(
    summary: BackupRestoreSummary?,
    onExportJson: suspend () -> Result<String>,
    onParseBackup: (String) -> Result<LocalColdStorageBackupEnvelope>,
    onConfirmRestore: (LocalColdStorageBackupEnvelope) -> Unit,
    onFeedback: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var pendingRestore by remember { mutableStateOf<LocalColdStorageBackupEnvelope?>(null) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            inlineError = null
            onExportJson().fold(
                onSuccess = { jsonText ->
                    val writeResult = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(jsonText.toByteArray(Charsets.UTF_8))
                            } ?: error("Não foi possível abrir o destino selecionado.")
                        }
                    }
                    writeResult.fold(
                        onSuccess = {
                            onFeedback("Backup JSON exportado com sucesso.")
                        },
                        onFailure = { err ->
                            inlineError = err.localizedMessage ?: "Falha ao gravar arquivo de backup."
                        }
                    )
                },
                onFailure = { err ->
                    inlineError = err.localizedMessage ?: "Falha ao gerar backup JSON."
                }
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            inlineError = null
            val rawResult = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: error("Não foi possível ler o arquivo selecionado.")
                }
            }
            rawResult.fold(
                onSuccess = { raw ->
                    onParseBackup(raw).fold(
                        onSuccess = { envelope ->
                            pendingRestore = envelope
                        },
                        onFailure = { err ->
                            inlineError = err.localizedMessage ?: "Arquivo JSON inválido para restauração."
                        }
                    )
                },
                onFailure = { err ->
                    inlineError = err.localizedMessage ?: "Erro ao abrir arquivo selecionado."
                }
            )
        }
    }

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
                    imageVector = Icons.Rounded.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Backup e Restauração Local",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Salve uma cópia completa em JSON (estoque, recebimentos, baixas e rondas térmicas) para proteger seus dados ou restaurar após reinstalar o app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (inlineError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = inlineError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Registros Neste Aparelho",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    val countsText = if (summary != null) {
                        "${summary.sessionCount} recebimento(s) • ${summary.itemCount} corte(s)\n" +
                            "${summary.movementCount} movimentação(ões) • ${summary.temperatureLogCount} ronda(s) térmica(s)"
                    } else {
                        "Carregando resumo local..."
                    }
                    Text(
                        text = countsText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                    val lastBackupText = if (summary != null && summary.exportedAtMillis > 0L) {
                        "Último backup/restauração: ${DateUtils.formatDateTime(summary.exportedAtMillis)}"
                    } else {
                        "Nenhum backup exportado neste aparelho ainda."
                    }
                    Text(
                        text = lastBackupText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    exportLauncher.launch("amazonas_center_backup_$stamp.json")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("export_backup_json_button")
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Exportar Backup (.json)")
            }

            OutlinedButton(
                onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("import_backup_json_button")
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Restaurar de Arquivo (.json)")
            }
        }
    }

    pendingRestore?.let { envelope ->
        val exportedLabel = if (envelope.exportedAtMillis > 0L) {
            DateUtils.formatDateTime(envelope.exportedAtMillis)
        } else {
            "Data não informada"
        }
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Confirmar Restauração?") },
            text = {
                Text(
                    "Os dados atuais do Modo Local serão substituídos pelo arquivo selecionado:\n\n" +
                        "• ${envelope.sessions.size} sessão(ões) de recebimento\n" +
                        "• ${envelope.items.size} lote(s)/corte(s)\n" +
                        "• ${envelope.movements.size} movimentação(ões)\n" +
                        "• ${envelope.temperatureLogs.size} ronda(s) térmica(s)\n\n" +
                        "Exportado em: $exportedLabel"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirmRestore(envelope)
                        pendingRestore = null
                        onDismiss()
                    },
                    modifier = Modifier.testTag("confirm_restore_backup_button")
                ) {
                    Text("Restaurar Agora")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
