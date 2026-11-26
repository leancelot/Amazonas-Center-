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
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.StaffRole
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.viewmodel.TeamViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TeamAccessSheet(
    viewModel: TeamViewModel,
    userEmail: String,
    userDisplayName: String,
    onDismiss: () -> Unit,
    onSignOutClick: () -> Unit,
    isLocalMode: Boolean = false,
    onSwitchToCloudSignIn: () -> Unit = {},
    onClearLocalData: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val session = state.sessionState
    val activeRole = session.activeRole
    val shopName = session.shopInfo?.name?.ifBlank { "Amazonas Center" } ?: "Amazonas Center"
    val ownerId = session.shopInfo?.ownerId

    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf(StaffRole.CONFERENTE) }
    var showClearLocalConfirm by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = shopName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isLocalMode) {
                            "Modo local neste aparelho (sem sincronização na nuvem)"
                        } else {
                            "Conta conectada e permissões"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.feedbackMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = state.feedbackMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::clearFeedback, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Fechar aviso", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            val sessionError = session.errorMessage
            if (sessionError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = sessionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = session.currentMember?.displayName?.ifBlank { userDisplayName } ?: userDisplayName.ifBlank { "Colaborador" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = userEmail.ifBlank { session.currentMember?.email ?: "" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Cargo: ${activeRole.displayName}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    Text(
                        text = activeRole.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLocalMode) {
                            Button(
                                onClick = onSwitchToCloudSignIn,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("connect_google_sheet_button")
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Entrar com Google")
                            }
                            if (onClearLocalData != null) {
                                OutlinedButton(
                                    onClick = { showClearLocalConfirm = true },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("clear_local_data_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Limpar dados locais",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Limpar", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = viewModel::retrySession,
                                enabled = !state.isSubmitting,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sincronizar")
                            }
                            OutlinedButton(
                                onClick = onSignOutClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("sign_out_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Logout,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Sair", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (isLocalMode) {
                HorizontalDivider()
                Text(
                    text = "Simular Cargo no Modo Local",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Teste as permissões de cada função antes de conectar a equipe na nuvem:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StaffRole.entries.forEach { role ->
                                FilterChip(
                                    selected = activeRole == role,
                                    onClick = {
                                        viewModel.updateMemberRole(
                                            memberUid = session.currentMember?.userId ?: "local_tester",
                                            newRole = role,
                                            active = true
                                        )
                                    },
                                    label = { Text(role.displayName) },
                                    modifier = Modifier.testTag("local_role_${role.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            } else if (activeRole.canManageTeam) {
                HorizontalDivider()

                Text(
                    text = "Autorizar Colaborador por E-mail Google",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = inviteEmail,
                            onValueChange = { inviteEmail = it },
                            label = { Text("E-mail Google do colaborador") },
                            placeholder = { Text("exemplo@gmail.com") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("invite_email_input")
                        )
                        Text("Cargo a liberar:", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StaffRole.entries.forEach { role ->
                                FilterChip(
                                    selected = inviteRole == role,
                                    onClick = { inviteRole = role },
                                    label = { Text(role.displayName) },
                                    modifier = Modifier.testTag("invite_role_${role.name.lowercase()}")
                                )
                            }
                        }
                        Button(
                            onClick = {
                                viewModel.inviteCollaborator(inviteEmail, inviteRole)
                                inviteEmail = ""
                            },
                            enabled = !state.isSubmitting && inviteEmail.contains("@"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("invite_submit_button")
                        ) {
                            Icon(Icons.Rounded.GroupAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Autorizar Colaborador")
                        }
                    }
                }

                if (state.invites.isNotEmpty()) {
                    Text(
                        text = "Convites Pendentes (${state.invites.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    state.invites.forEach { inv ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = inv.emailLower,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Cargo: ${inv.roleEnum.displayName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { viewModel.revokeInvite(inv.id) }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Revogar convite",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                if (state.members.isNotEmpty()) {
                    Text(
                        text = "Equipe da Loja (${state.members.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    state.members.forEach { member ->
                        val isOwner = ownerId != null && member.userId == ownerId
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = member.displayName + if (isOwner) " (Proprietário)" else "",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = member.email,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!isOwner) {
                                        Switch(
                                            checked = member.active,
                                            onCheckedChange = { checked ->
                                                viewModel.updateMemberRole(member.userId, member.roleEnum, checked)
                                            }
                                        )
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    StaffRole.entries.forEach { role ->
                                        FilterChip(
                                            selected = member.roleEnum == role,
                                            enabled = !isOwner || role == StaffRole.GERENTE,
                                            onClick = {
                                                if (!isOwner && member.roleEnum != role) {
                                                    viewModel.updateMemberRole(member.userId, role, member.active)
                                                }
                                            },
                                            label = { Text(role.displayName) }
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

    if (showClearLocalConfirm && onClearLocalData != null) {
        AlertDialog(
            onDismissRequest = { showClearLocalConfirm = false },
            title = { Text("Limpar dados locais?") },
            text = {
                Text("Todos os recebimentos, lotes, baixas e rondas térmicas salvos neste aparelho serão apagados. Exporte um backup JSON antes se quiser guardar uma cópia.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearLocalConfirm = false
                        onClearLocalData()
                    },
                    modifier = Modifier.testTag("confirm_clear_local_data_button")
                ) {
                    Text("Apagar Tudo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearLocalConfirm = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}
