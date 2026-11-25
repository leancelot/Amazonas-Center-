package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    val activityContext = context.findActivity()
    if (activityContext == null) {
        onUnauthenticated()
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onUnauthenticated()
        return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    scope.launch {
        try {
            val cred = credentialManager.getCredential(activityContext, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                if (token.isNotBlank()) {
                    val result = Firebase.auth
                        .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .await()
                    if (result.user != null) {
                        onAuthSuccess()
                    } else {
                        onUnauthenticated()
                    }
                } else {
                    onUnauthenticated()
                }
            } else {
                onUnauthenticated()
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Auto sign-in cancelled: ${e.errorMessage ?: e.message}")
            onUnauthenticated()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Auth", "Auto sign-in skipped (${e.javaClass.simpleName}): ${e.message}")
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: (String) -> Unit = {}
) {
    val activityContext = context.findActivity()
    if (activityContext == null) {
        onAuthError("Não foi possível identificar a tela ativa (Activity) para abrir o login do Google.")
        return
    }

    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onAuthError("Configuração do Google Sign-In ausente (default_web_client_id).")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()

    scope.launch {
        try {
            val cred = credentialManager.getCredential(activityContext, request).credential
            handleGoogleCredential(
                cred = cred,
                onAuthSuccess = onAuthSuccess,
                onAuthError = onAuthError
            )
        } catch (e: GetCredentialCancellationException) {
            val primaryDetail = (e.errorMessage?.toString() ?: e.message).orEmpty()
            Log.w("Auth", "Google Sign-In cancelled or dismissed: $primaryDetail", e)
            val isReauthOrAccountIssue = primaryDetail.contains("reauth", ignoreCase = true) ||
                primaryDetail.contains("[16]") ||
                primaryDetail.contains("16:")
            if (isReauthOrAccountIssue) {
                tryGoogleIdFallback(
                    activityContext = activityContext,
                    clientId = clientId,
                    credentialManager = credentialManager,
                    primaryDetail = primaryDetail,
                    onAuthSuccess = onAuthSuccess,
                    onAuthError = onAuthError,
                    onAuthCancelled = onAuthCancelled
                )
            } else {
                onAuthCancelled(formatCancellationDiagnostic(primaryDetail))
            }
        } catch (e: NoCredentialException) {
            Log.w("Auth", "No Google credentials found with SignInWithGoogleOption: ${e.errorMessage ?: e.message}", e)
            tryGoogleIdFallback(
                activityContext = activityContext,
                clientId = clientId,
                credentialManager = credentialManager,
                primaryDetail = (e.errorMessage?.toString() ?: e.message).orEmpty(),
                onAuthSuccess = onAuthSuccess,
                onAuthError = onAuthError,
                onAuthCancelled = onAuthCancelled
            )
        } catch (e: GetCredentialException) {
            Log.e("Auth", "Google Sign-In CredentialException [${e.type}]: ${e.errorMessage ?: e.message}", e)
            val detail = (e.errorMessage?.toString() ?: e.message)?.takeIf { it.isNotBlank() }
            onAuthError(
                buildString {
                    append("Erro do Google Sign-In (${e.type.substringAfterLast('.')}).")
                    if (detail != null) append("\nDetalhe: $detail")
                    append("\n\nVocê pode usar o modo local abaixo para testar agora ou tentar outra conta Google.")
                }
            )
        } catch (e: FirebaseAuthException) {
            Log.e("Auth", "FirebaseAuthException [${e.errorCode}]: ${e.message}", e)
            onAuthError("Erro do Firebase Auth [${e.errorCode}]: ${e.localizedMessage ?: e.message ?: "Falha ao autenticar."}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Auth", "Google Sign-In failed", e)
            onAuthError("Falha ao entrar com Google (${e.javaClass.simpleName}): ${e.localizedMessage ?: e.message ?: "Erro desconhecido."}")
        }
    }
}

private suspend fun tryGoogleIdFallback(
    activityContext: Activity,
    clientId: String,
    credentialManager: CredentialManager,
    primaryDetail: String,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    onAuthCancelled: (String) -> Unit
) {
    val fallbackOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(false)
        .build()
    val fallbackRequest = GetCredentialRequest.Builder()
        .addCredentialOption(fallbackOption)
        .build()
    try {
        val cred = credentialManager.getCredential(activityContext, fallbackRequest).credential
        handleGoogleCredential(
            cred = cred,
            onAuthSuccess = onAuthSuccess,
            onAuthError = onAuthError
        )
    } catch (fallbackCancel: GetCredentialCancellationException) {
        val fallbackDetail = (fallbackCancel.errorMessage?.toString() ?: fallbackCancel.message).orEmpty()
        Log.w("Auth", "Fallback Google Sign-In cancelled: $fallbackDetail", fallbackCancel)
        onAuthCancelled(formatCancellationDiagnostic(fallbackDetail.ifBlank { primaryDetail }))
    } catch (fallbackNoCred: NoCredentialException) {
        Log.w("Auth", "Fallback NoCredentialException: ${fallbackNoCred.errorMessage ?: fallbackNoCred.message}", fallbackNoCred)
        onAuthCancelled(formatCancellationDiagnostic(primaryDetail.ifBlank { fallbackNoCred.message.orEmpty() }))
    } catch (fallbackCred: GetCredentialException) {
        Log.e("Auth", "Fallback CredentialException [${fallbackCred.type}]: ${fallbackCred.errorMessage ?: fallbackCred.message}", fallbackCred)
        val detail = (fallbackCred.errorMessage?.toString() ?: fallbackCred.message).orEmpty().ifBlank { primaryDetail }
        onAuthError(
            buildString {
                append("Erro do Google Sign-In (${fallbackCred.type.substringAfterLast('.')}).")
                if (detail.isNotBlank()) append("\nDetalhe: $detail")
                append("\n\nVocê pode testar o app imediatamente usando o modo local abaixo.")
            }
        )
    } catch (e: FirebaseAuthException) {
        Log.e("Auth", "Fallback FirebaseAuthException [${e.errorCode}]: ${e.message}", e)
        onAuthError("Erro do Firebase Auth [${e.errorCode}]: ${e.localizedMessage ?: e.message ?: "Falha ao autenticar."}")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("Auth", "Fallback Google Sign-In failed", e)
        onAuthError("Falha ao entrar com Google (${e.javaClass.simpleName}): ${e.localizedMessage ?: e.message ?: "Erro desconhecido."}")
    }
}

private suspend fun handleGoogleCredential(
    cred: androidx.credentials.Credential,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit
) {
    if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        if (token.isBlank()) {
            onAuthError("O Google não retornou um token de identificação válido.")
            return
        }
        val result = Firebase.auth
            .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
            .await()
        if (result.user != null) {
            onAuthSuccess()
        } else {
            onAuthError("Conta Google autenticada, mas o Firebase não criou a sessão do usuário.")
        }
    } else {
        onAuthError("Tipo de credencial inesperado retornado pelo Google (${cred.type}).")
    }
}

private fun formatCancellationDiagnostic(detail: String): String {
    val cleanDetail = detail.trim()
    val isReauth16 = cleanDetail.contains("reauth", ignoreCase = true) || cleanDetail.contains("[16]")
    return buildString {
        append("Login com Google interrompido após selecionar a conta.")
        if (cleanDetail.isNotBlank()) {
            append("\nMotivo: $cleanDetail")
        }
        if (isReauth16) {
            append("\n\nEsse erro [16] ocorre no Google Play Services do aparelho quando a conta Google exige revalidação ou o login OAuth é bloqueado no dispositivo. Enquanto isso, você pode tocar em \"Pular login (Testar em modo local)\" logo abaixo para usar o app normalmente no celular.")
        } else {
            append("\n\nVocê também pode tocar em \"Pular login (Testar em modo local)\" logo abaixo para testar todo o app neste aparelho.")
        }
    }
}

fun signOutUser(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("Auth", "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}
