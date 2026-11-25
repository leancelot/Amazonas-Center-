package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ColdStorageRepository
import com.example.data.ShopMember
import com.example.data.ShopSessionState
import com.example.data.StaffRole
import com.example.data.TeamInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TeamUiState(
    val sessionState: ShopSessionState = ShopSessionState(),
    val members: List<ShopMember> = emptyList(),
    val invites: List<TeamInvite> = emptyList(),
    val isSubmitting: Boolean = false,
    val feedbackMessage: String? = null
)

class TeamViewModel(
    private val repository: ColdStorageRepository
) : ViewModel() {

    private val _isSubmitting = MutableStateFlow(false)
    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TeamUiState> = combine(
        repository.sessionState,
        repository.teamMembers,
        repository.teamInvites,
        _isSubmitting,
        _feedback
    ) { session, members, invites, submitting, feedback ->
        TeamUiState(
            sessionState = session,
            members = members,
            invites = invites,
            isSubmitting = submitting,
            feedbackMessage = feedback
        )
    }.catch { error ->
        val rootError = unwrapFirestoreStreamError(error)
        Log.w("TeamVM", "Error observing team", rootError)
        emit(
            TeamUiState(
                sessionState = ShopSessionState(
                    isLoading = false,
                    errorMessage = friendlyFirestoreMessage(rootError, "Falha ao carregar equipe.")
                )
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TeamUiState()
    )

    fun retrySession() {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.ensureSessionReady().fold(
                onSuccess = { _feedback.value = "Sessão sincronizada com a nuvem." },
                onFailure = { err -> _feedback.value = friendlyFirestoreMessage(err, "Falha ao reconectar.") }
            )
            _isSubmitting.value = false
        }
    }

    fun inviteCollaborator(email: String, role: StaffRole) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.inviteCollaborator(email, role).fold(
                onSuccess = {
                    _feedback.value = "Acesso liberado para ${email.trim().lowercase()} como ${role.displayName}."
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Não foi possível autorizar o colaborador.")
                }
            )
            _isSubmitting.value = false
        }
    }

    fun updateMemberRole(memberUid: String, newRole: StaffRole, active: Boolean) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.updateMemberRole(memberUid, newRole, active).fold(
                onSuccess = {
                    _feedback.value = "Cargo atualizado para ${newRole.displayName}."
                },
                onFailure = { err ->
                    _feedback.value = friendlyFirestoreMessage(err, "Falha ao atualizar cargo.")
                }
            )
            _isSubmitting.value = false
        }
    }

    fun revokeInvite(inviteId: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            repository.revokeInvite(inviteId).fold(
                onSuccess = { _feedback.value = "Convite revogado." },
                onFailure = { err -> _feedback.value = friendlyFirestoreMessage(err, "Falha ao revogar convite.") }
            )
            _isSubmitting.value = false
        }
    }

    fun clearFeedback() {
        _feedback.value = null
    }
}
