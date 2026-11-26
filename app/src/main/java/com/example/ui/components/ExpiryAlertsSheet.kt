package com.example.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.DateUtils
import com.example.notifications.ExpiryAlertDigest
import com.example.notifications.ExpiryAlertSettings
import com.example.notifications.ExpiryNotificationManager
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpiryAlertsSheet(
    digest: ExpiryAlertDigest,
    initialSettings: ExpiryAlertSettings,
    onSettingsUpdated: (ExpiryAlertSettings) -> Unit,
    onFeedback: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var settings by remember { mutableStateOf(initialSettings) }
    var hasPermission by remember {
        mutableStateOf(ExpiryNotificationManager.hasNotificationPermission(context))
    }
    var permissionDeniedNote by remember { mutableStateOf(false) }
    var pendingTestAfterGrant by remember { mutableStateOf(false) }

    val presetHours = listOf(
        7 to 0,
        8 to 0,
        9 to 0,
        14 to 0
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            permissionDeniedNote = false
            val updated = ExpiryNotificationManager.setAlertsEnabled(
                context = context,
                enabled = true,
                hour = settings.hour,
                minute = settings.minute
            )
            settings = updated
            onSettingsUpdated(updated)
            if (pendingTestAfterGrant) {
                pendingTestAfterGrant = false
                val msg = ExpiryNotificationManager.triggerAlertNow(context, forceShowEvenIfClean = true)
                settings = ExpiryNotificationManager.readSettings(context)
                onSettingsUpdated(settings)
                onFeedback(msg)
            } else {
                onFeedback(
                    "Alertas diários ativados para ${
                        String.format(Locale.US, "%02d:%02d", updated.hour, updated.minute)
                    }."
                )
            }
        } else {
            pendingTestAfterGrant = false
            permissionDeniedNote = true
            val updated = ExpiryNotificationManager.setAlertsEnabled(context, enabled = false)
            settings = updated
            onSettingsUpdated(updated)
        }
    }

    fun requestEnableAlerts() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ExpiryNotificationManager.hasNotificationPermission(context)
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val updated = ExpiryNotificationManager.setAlertsEnabled(
                context = context,
                enabled = true,
                hour = settings.hour,
                minute = settings.minute
            )
            settings = updated
            onSettingsUpdated(updated)
            onFeedback(
                "Alertas diários ativados para ${
                    String.format(Locale.US, "%02d:%02d", updated.hour, updated.minute)
                }."
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
                    imageVector = if (settings.enabled) {
                        Icons.Rounded.NotificationsActive
                    } else {
                        Icons.Rounded.NotificationsOff
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Alertas de Vencimento (7d / 15d)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Lembrete diário no aparelho para cortes vencidos, críticos (até 7 dias) e atenção (8 a 15 dias).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (permissionDeniedNote || !hasPermission) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (permissionDeniedNote) {
                            "A permissão de notificações foi negada. Libere notificações nas configurações do Android para receber os alertas FEFO."
                        } else {
                            "Ao ativar os alertas ou testar agora, o Android solicitará permissão para exibir notificações."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lembrete Diário Automático",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (settings.enabled) {
                                "Agendado todos os dias por volta das ${
                                    String.format(Locale.US, "%02d:%02d", settings.hour, settings.minute)
                                }"
                            } else {
                                "Desativado no momento"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                requestEnableAlerts()
                            } else {
                                val updated = ExpiryNotificationManager.setAlertsEnabled(
                                    context = context,
                                    enabled = false,
                                    hour = settings.hour,
                                    minute = settings.minute
                                )
                                settings = updated
                                onSettingsUpdated(updated)
                                onFeedback("Alertas diários desativados.")
                            }
                        },
                        modifier = Modifier.testTag("expiry_alerts_switch")
                    )
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Horário Preferido da Verificação",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetHours.forEach { (h, m) ->
                            val selected = settings.hour == h && settings.minute == m
                            val label = String.format(Locale.US, "%02d:%02d", h, m)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val updated = ExpiryNotificationManager.setAlertsEnabled(
                                        context = context,
                                        enabled = settings.enabled,
                                        hour = h,
                                        minute = m
                                    )
                                    settings = updated
                                    onSettingsUpdated(updated)
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("expiry_hour_chip_$h")
                            )
                        }
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Resumo Atual do Estoque",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Vencidos: ${digest.expiredCount} • Até 7d: ${digest.due7DaysCount} • 8 a 15d: ${digest.due15DaysCount}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = digest.buildNotificationBody(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (settings.lastNotifiedAtMillis > 0L) {
                        Text(
                            text = "Último alerta: ${DateUtils.formatDateTime(settings.lastNotifiedAtMillis)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !ExpiryNotificationManager.hasNotificationPermission(context)
                    ) {
                        pendingTestAfterGrant = true
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val msg = ExpiryNotificationManager.triggerAlertNow(
                            context = context,
                            forceShowEvenIfClean = true
                        )
                        settings = ExpiryNotificationManager.readSettings(context)
                        onSettingsUpdated(settings)
                        onFeedback(msg)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("test_expiry_notification_button")
            ) {
                Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Testar Notificação Agora")
            }
        }
    }
}
