package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.ui.auth.AuthFailureCategory
import com.example.ui.auth.AuthFailureReport
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.onGoogleSignInClicked
import com.example.ui.theme.ColdNavyLight
import com.example.ui.theme.ColdNavyPrimary
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    onContinueLocalMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var isAutoChecking by remember { mutableStateOf(true) }
    var isSigningIn by remember { mutableStateOf(false) }
    var failureReport by remember { mutableStateOf<AuthFailureReport?>(null) }
    var showTechnicalLog by remember { mutableStateOf(false) }
    var showGuestFallbackDialog by remember { mutableStateOf(false) }
    var copiedError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
                isAutoChecking = false
                onAuthSuccess()
            },
            onUnauthenticated = {
                isAutoChecking = false
            },
            scope = scope
        )
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(listOf(ColdNavyPrimary, ColdNavyLight))
                        )
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AcUnit,
                                contentDescription = null,
                                tint = Color(0xFF7DD3FC),
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                text = "AMAZONAS CENTER",
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                                color = Color(0xFFBAE6FD)
                            )
                        }
                        Text(
                            text = "Controle de Câmara Fria & Recebimento",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Entre com sua conta Google para sincronizar o estoque na nuvem com controle de acesso por cargo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Permissões por Cargo na Equipe",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        RoleHighlightRow(
                            icon = Icons.Rounded.VerifiedUser,
                            title = "Gerente",
                            detail = "Acesso total: estoque, recebimentos, exclusões e equipe."
                        )
                        RoleHighlightRow(
                            icon = Icons.Rounded.Badge,
                            title = "Conferente & Resp. Câmara Fria",
                            detail = "Registra recebimentos e edita estoque, temperatura e baixas."
                        )
                        RoleHighlightRow(
                            icon = Icons.Rounded.CloudDone,
                            title = "Operador / Portaria",
                            detail = "Consulta o estoque e dá entrada inicial nas entregas."
                        )
                    }
                }

                failureReport?.let { report ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_error_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = report.headline,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Text(
                                text = report.userSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Causa provável: ${report.probableCause}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            if (report.suggestGuestMode) {
                                Button(
                                    onClick = onContinueLocalMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("fallback_guest_mode_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PhoneAndroid,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Continuar no Modo Convidado (Local)")
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { showTechnicalLog = !showTechnicalLog },
                                    modifier = Modifier.testTag("toggle_auth_log_button")
                                ) {
                                    Icon(
                                        imageVector = if (showTechnicalLog) {
                                            Icons.Rounded.ExpandLess
                                        } else {
                                            Icons.Rounded.ExpandMore
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (showTechnicalLog) "Ocultar log técnico" else "Ver log técnico",
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText("Diagnóstico Login Google", report.technicalLog)
                                        )
                                        copiedError = true
                                    },
                                    modifier = Modifier.testTag("copy_auth_error_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (copiedError) "Copiado" else "Copiar log",
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }

                            if (showTechnicalLog) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = report.technicalLog,
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        failureReport = null
                        showTechnicalLog = false
                        copiedError = false
                        isSigningIn = true
                        onGoogleSignInClicked(
                            context = context,
                            credentialManager = credentialManager,
                            onAuthSuccess = {
                                isSigningIn = false
                                onAuthSuccess()
                            },
                            onAuthFailure = { report ->
                                isSigningIn = false
                                failureReport = report
                                if (report.category != AuthFailureCategory.USER_CANCELLED && report.suggestGuestMode) {
                                    showGuestFallbackDialog = true
                                }
                            },
                            scope = scope
                        )
                    },
                    enabled = !isSigningIn && !isAutoChecking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ColdNavyPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("google_sign_in_button")
                ) {
                    if (isSigningIn || isAutoChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(if (isAutoChecking) "Verificando sessão..." else "Conectando com Google...")
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Entrar com Google",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OutlinedButton(
                    onClick = onContinueLocalMode,
                    enabled = !isSigningIn && !isAutoChecking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("local_test_mode_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Modo Convidado (Salvar Localmente)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "No Modo Convidado, seus recebimentos, rondas térmicas, estoque e backups JSON funcionam normalmente neste aparelho sem depender do login Google.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showGuestFallbackDialog && failureReport != null) {
        val report = failureReport!!
        AlertDialog(
            onDismissRequest = { showGuestFallbackDialog = false },
            title = { Text(report.headline) },
            text = {
                Text(
                    "${report.userSummary}\n\n${report.probableCause}\n\nVocê pode entrar agora mesmo no Modo Convidado com salvamento local e backup JSON."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showGuestFallbackDialog = false
                        onContinueLocalMode()
                    },
                    modifier = Modifier.testTag("dialog_guest_mode_button")
                ) {
                    Text("Entrar no Modo Convidado")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showGuestFallbackDialog = false
                        showTechnicalLog = true
                    },
                    modifier = Modifier.testTag("dialog_view_log_button")
                ) {
                    Text("Ver Log Detalhado")
                }
            }
        )
    }
}

@Composable
private fun RoleHighlightRow(
    icon: ImageVector,
    title: String,
    detail: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
