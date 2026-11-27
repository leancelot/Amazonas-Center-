package com.example.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
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
    val trace = AuthTraceRecorder()
    val existingUser = Firebase.auth.currentUser
    if (existingUser != null) {
        trace.info("AutoSignIn", "Sessão Firebase já ativa (uid=${existingUser.uid.take(8)}...).")
        onAuthSuccess()
        return
    }
    val activityContext = context.findActivity()
    if (activityContext == null) {
        trace.warn("AutoSignIn", "Activity não encontrada no contexto; exibindo AuthScreen.")
        onUnauthenticated()
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        trace.error("AutoSignIn", "default_web_client_id ausente nos recursos.", e)
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
            trace.info("AutoSignIn", "Consultando credencial autorizada via GetGoogleIdOption...")
            val cred = credentialManager.getCredential(activityContext, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
                val token = googleCred.idToken
                trace.info("AutoSignIn", "ID Token recebido (len=${token.length}); autenticando no Firebase...")
                if (token.isNotBlank()) {
                    val result = Firebase.auth
                        .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .await()
                    if (result.user != null) {
                        trace.info("AutoSignIn", "Login automático concluído com sucesso.")
                        onAuthSuccess()
                    } else {
                        trace.warn("AutoSignIn", "Firebase retornou usuário nulo após signInWithCredential.")
                        onUnauthenticated()
                    }
                } else {
                    trace.warn("AutoSignIn", "ID Token retornado estava vazio.")
                    onUnauthenticated()
                }
            } else {
                trace.warn("AutoSignIn", "Credencial não é Google ID Token (${cred.type}).")
                onUnauthenticated()
            }
        } catch (e: GetCredentialCancellationException) {
            trace.warn("AutoSignIn", "Auto sign-in cancelado/interrompido: ${e.errorMessage ?: e.message}", e)
            onUnauthenticated()
        } catch (e: GetCredentialException) {
            trace.warn("AutoSignIn", "Auto sign-in indisponível [${e.type}]: ${e.errorMessage ?: e.message}", e)
            onUnauthenticated()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace.warn("AutoSignIn", "Auto sign-in ignorado (${e.javaClass.simpleName}): ${e.message}", e)
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthFailure: (AuthFailureReport) -> Unit,
    scope: CoroutineScope
) {
    val trace = AuthTraceRecorder()
    val activityContext = context.findActivity()
    if (activityContext == null) {
        trace.error("Init", "Contexto sem Activity para iniciar CredentialManager.")
        val deviceCtx = GoogleAuthExceptionHandler.captureDeviceContext(context, "")
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = IllegalStateException("Activity context unavailable"),
                deviceContext = deviceCtx,
                traceLines = trace.snapshot(),
                phase = "Init"
            )
        )
        return
    }

    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (e: Exception) {
        trace.error("Init", "Falha ao ler default_web_client_id.", e)
        val deviceCtx = GoogleAuthExceptionHandler.captureDeviceContext(activityContext, "")
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = e,
                deviceContext = deviceCtx,
                traceLines = trace.snapshot(),
                phase = "Init"
            )
        )
        return
    }

    val deviceContext = GoogleAuthExceptionHandler.captureDeviceContext(activityContext, clientId)
    trace.info(
        "Init",
        "Iniciando Google Sign-In | build=${deviceContext.buildType} sha1=${deviceContext.sha1} gms=${deviceContext.playServicesVersion} (enabled=${deviceContext.playServicesEnabled})"
    )

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()

    scope.launch {
        try {
            trace.info("PrimaryGetSignInWithGoogle", "Abrindo seletor de contas Google (GetSignInWithGoogleOption)...")
            val cred = credentialManager.getCredential(activityContext, request).credential
            trace.info("PrimaryGetSignInWithGoogle", "Credencial obtida: ${cred.javaClass.simpleName} (type=${cred.type})")
            handleGoogleCredential(
                cred = cred,
                trace = trace,
                deviceContext = deviceContext,
                phase = "FirebaseExchange(Primary)",
                onAuthSuccess = onAuthSuccess,
                onAuthFailure = onAuthFailure
            )
        } catch (e: GetCredentialCancellationException) {
            trace.warn(
                "PrimaryGetSignInWithGoogle",
                "GetCredentialCancellationException [type=${e.type}]: ${e.errorMessage ?: e.message}",
                e
            )
            handlePrimaryCredentialException(
                throwable = e,
                activityContext = activityContext,
                clientId = clientId,
                credentialManager = credentialManager,
                deviceContext = deviceContext,
                trace = trace,
                onAuthSuccess = onAuthSuccess,
                onAuthFailure = onAuthFailure
            )
        } catch (e: NoCredentialException) {
            trace.warn("PrimaryGetSignInWithGoogle", "NoCredentialException: ${e.errorMessage ?: e.message}", e)
            handlePrimaryCredentialException(
                throwable = e,
                activityContext = activityContext,
                clientId = clientId,
                credentialManager = credentialManager,
                deviceContext = deviceContext,
                trace = trace,
                onAuthSuccess = onAuthSuccess,
                onAuthFailure = onAuthFailure
            )
        } catch (e: GetCredentialProviderConfigurationException) {
            trace.error("PrimaryGetSignInWithGoogle", "GetCredentialProviderConfigurationException: ${e.errorMessage ?: e.message}", e)
            onAuthFailure(
                GoogleAuthExceptionHandler.buildReport(
                    throwable = e,
                    deviceContext = deviceContext,
                    traceLines = trace.snapshot(),
                    phase = "PrimaryProviderConfig"
                )
            )
        } catch (e: GetCredentialUnsupportedException) {
            trace.error("PrimaryGetSignInWithGoogle", "GetCredentialUnsupportedException: ${e.errorMessage ?: e.message}", e)
            onAuthFailure(
                GoogleAuthExceptionHandler.buildReport(
                    throwable = e,
                    deviceContext = deviceContext,
                    traceLines = trace.snapshot(),
                    phase = "PrimaryUnsupported"
                )
            )
        } catch (e: GetCredentialInterruptedException) {
            trace.warn("PrimaryGetSignInWithGoogle", "GetCredentialInterruptedException: ${e.errorMessage ?: e.message}", e)
            handlePrimaryCredentialException(
                throwable = e,
                activityContext = activityContext,
                clientId = clientId,
                credentialManager = credentialManager,
                deviceContext = deviceContext,
                trace = trace,
                onAuthSuccess = onAuthSuccess,
                onAuthFailure = onAuthFailure
            )
        } catch (e: GetCredentialUnknownException) {
            trace.error("PrimaryGetSignInWithGoogle", "GetCredentialUnknownException: ${e.errorMessage ?: e.message}", e)
            handlePrimaryCredentialException(
                throwable = e,
                activityContext = activityContext,
                clientId = clientId,
                credentialManager = credentialManager,
                deviceContext = deviceContext,
                trace = trace,
                onAuthSuccess = onAuthSuccess,
                onAuthFailure = onAuthFailure
            )
        } catch (e: GetCredentialCustomException) {
            trace.error("PrimaryGetSignInWithGoogle", "GetCredentialCustomException [${e.type}]: ${e.errorMessage ?: e.message}", e)
            handlePrimaryCredentialException(
                throwable = e,
                activityContext = activityContext,
                clientId = clientId,
                credentialManager = credentialManager,
                deviceContext = deviceContext,
                trace = trace,
                onAuthSuccess = onAuthSuccess,
                onAuthFailure = onAuthFailure
            )
        } catch (e: GetCredentialException) {
            trace.error("PrimaryGetSignInWithGoogle", "GetCredentialException [${e.type}]: ${e.errorMessage ?: e.message}", e)
            onAuthFailure(
                GoogleAuthExceptionHandler.buildReport(
                    throwable = e,
                    deviceContext = deviceContext,
                    traceLines = trace.snapshot(),
                    phase = "PrimaryCredentialException"
                )
            )
        } catch (e: FirebaseAuthException) {
            trace.error("FirebaseExchange(Primary)", "FirebaseAuthException [${e.errorCode}]: ${e.message}", e)
            onAuthFailure(
                GoogleAuthExceptionHandler.buildReport(
                    throwable = e,
                    deviceContext = deviceContext,
                    traceLines = trace.snapshot(),
                    phase = "FirebaseExchange(Primary)"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace.error("PrimaryGetSignInWithGoogle", "Exceção não esperada", e)
            onAuthFailure(
                GoogleAuthExceptionHandler.buildReport(
                    throwable = e,
                    deviceContext = deviceContext,
                    traceLines = trace.snapshot(),
                    phase = "PrimaryUnexpected"
                )
            )
        }
    }
}

private suspend fun handlePrimaryCredentialException(
    throwable: Throwable,
    activityContext: Activity,
    clientId: String,
    credentialManager: CredentialManager,
    deviceContext: AuthDeviceContext,
    trace: AuthTraceRecorder,
    onAuthSuccess: () -> Unit,
    onAuthFailure: (AuthFailureReport) -> Unit
) {
    val category = GoogleAuthExceptionHandler.classify(throwable)
    trace.warn(
        "PrimaryClassifier",
        "Exceção classificada como ${category.name}; cadeia=${GoogleAuthExceptionHandler.collectCauseChain(throwable).joinToString(" -> ")}"
    )

    if (GoogleAuthExceptionHandler.shouldAttemptFallback(throwable)) {
        tryGoogleIdFallback(
            activityContext = activityContext,
            clientId = clientId,
            credentialManager = credentialManager,
            deviceContext = deviceContext,
            trace = trace,
            primaryThrowable = throwable,
            onAuthSuccess = onAuthSuccess,
            onAuthFailure = onAuthFailure
        )
    } else {
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = throwable,
                deviceContext = deviceContext,
                traceLines = trace.snapshot(),
                phase = "Primary(${category.name})"
            )
        )
    }
}

private suspend fun tryGoogleIdFallback(
    activityContext: Activity,
    clientId: String,
    credentialManager: CredentialManager,
    deviceContext: AuthDeviceContext,
    trace: AuthTraceRecorder,
    primaryThrowable: Throwable,
    onAuthSuccess: () -> Unit,
    onAuthFailure: (AuthFailureReport) -> Unit
) {
    // Limpa estado em cache do Credential Manager antes do fallback para evitar reutilizar sessão com reauth [16] travada
    try {
        trace.info("ClearCacheBeforeFallback", "Limpando cache local do CredentialManager...")
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
        trace.info("ClearCacheBeforeFallback", "Cache de credenciais limpo.")
    } catch (clearErr: Exception) {
        trace.warn("ClearCacheBeforeFallback", "Não foi possível limpar cache de credenciais", clearErr)
    }

    val fallbackOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(false)
        .build()
    val fallbackRequest = GetCredentialRequest.Builder()
        .addCredentialOption(fallbackOption)
        .build()

    try {
        trace.info("FallbackGetGoogleId", "Tentando GetGoogleIdOption(filterByAuthorizedAccounts=false)...")
        val cred = credentialManager.getCredential(activityContext, fallbackRequest).credential
        trace.info("FallbackGetGoogleId", "Credencial obtida no fallback: ${cred.javaClass.simpleName} (type=${cred.type})")
        handleGoogleCredential(
            cred = cred,
            trace = trace,
            deviceContext = deviceContext,
            phase = "FirebaseExchange(Fallback)",
            onAuthSuccess = onAuthSuccess,
            onAuthFailure = onAuthFailure
        )
    } catch (e: GetCredentialCancellationException) {
        trace.warn("FallbackGetGoogleId", "Fallback cancelado/reauth: ${e.errorMessage ?: e.message}", e)
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = chooseMoreInformativeThrowable(primaryThrowable, e),
                deviceContext = deviceContext,
                traceLines = trace.snapshot(),
                phase = "FallbackGetGoogleId"
            )
        )
    } catch (e: GetCredentialException) {
        trace.error("FallbackGetGoogleId", "Fallback GetCredentialException [${e.type}]: ${e.errorMessage ?: e.message}", e)
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = chooseMoreInformativeThrowable(primaryThrowable, e),
                deviceContext = deviceContext,
                traceLines = trace.snapshot(),
                phase = "FallbackGetGoogleId"
            )
        )
    } catch (e: FirebaseAuthException) {
        trace.error("FirebaseExchange(Fallback)", "Fallback FirebaseAuthException [${e.errorCode}]: ${e.message}", e)
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = e,
                deviceContext = deviceContext,
                traceLines = trace.snapshot(),
                phase = "FirebaseExchange(Fallback)"
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        trace.error("FallbackGetGoogleId", "Exceção não esperada no fallback", e)
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = chooseMoreInformativeThrowable(primaryThrowable, e),
                deviceContext = deviceContext,
                traceLines = trace.snapshot(),
                phase = "FallbackUnexpected"
            )
        )
    }
}

internal fun chooseMoreInformativeThrowable(primary: Throwable, fallback: Throwable): Throwable {
    val fallbackCat = GoogleAuthExceptionHandler.classify(fallback)
    val primaryCat = GoogleAuthExceptionHandler.classify(primary)
    val primaryIsSpecificRootCause = primaryCat == AuthFailureCategory.REAUTH_16 ||
        primaryCat == AuthFailureCategory.DEVELOPER_ERROR_10 ||
        primaryCat == AuthFailureCategory.FIREBASE_AUTH
    val fallbackIsGeneric = fallbackCat == AuthFailureCategory.USER_CANCELLED ||
        fallbackCat == AuthFailureCategory.NO_CREDENTIAL
    return if (primaryIsSpecificRootCause && fallbackIsGeneric) {
        primary
    } else {
        fallback
    }
}

private suspend fun handleGoogleCredential(
    cred: Credential,
    trace: AuthTraceRecorder,
    deviceContext: AuthDeviceContext,
    phase: String,
    onAuthSuccess: () -> Unit,
    onAuthFailure: (AuthFailureReport) -> Unit
) {
    if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val googleCred = GoogleIdTokenCredential.createFrom(cred.data)
        val token = googleCred.idToken
        if (token.isBlank()) {
            trace.error(phase, "GoogleIdTokenCredential veio com idToken vazio.")
            onAuthFailure(
                GoogleAuthExceptionHandler.buildReport(
                    throwable = IllegalStateException("Google ID Token vazio"),
                    deviceContext = deviceContext,
                    traceLines = trace.snapshot(),
                    phase = phase
                )
            )
            return
        }
        trace.info(phase, "ID Token válido recebido (len=${token.length}). Autenticando no Firebase Auth...")
        val result = Firebase.auth
            .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
            .await()
        val user = result.user
        if (user != null) {
            trace.info(phase, "Sessão Firebase criada (uid=${user.uid.take(8)}...).")
            onAuthSuccess()
        } else {
            trace.error(phase, "signInWithCredential concluiu com user == null.")
            onAuthFailure(
                GoogleAuthExceptionHandler.buildReport(
                    throwable = IllegalStateException("Firebase returned null user after Google credential sign-in"),
                    deviceContext = deviceContext,
                    traceLines = trace.snapshot(),
                    phase = phase
                )
            )
        }
    } else {
        trace.error(phase, "Tipo de credencial não suportado: ${cred.type}")
        onAuthFailure(
            GoogleAuthExceptionHandler.buildReport(
                throwable = IllegalStateException("Unexpected credential type: ${cred.type}"),
                deviceContext = deviceContext,
                traceLines = trace.snapshot(),
                phase = phase
            )
        )
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
                Log.i(AUTH_LOG_TAG, "CredentialManager state cleared on sign-out.")
            } catch (e: Exception) {
                Log.w(AUTH_LOG_TAG, "Failed to clear credential state: ${e.message}", e)
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}
