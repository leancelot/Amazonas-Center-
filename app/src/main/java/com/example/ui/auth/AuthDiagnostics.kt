package com.example.ui.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.example.BuildConfig
import com.example.ai.REGISTERED_DEBUG_SHA1
import com.example.ai.readApkShaFingerprints
import com.google.firebase.auth.FirebaseAuthException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val AUTH_LOG_TAG = "FrigStockAuth"

enum class AuthFailureCategory {
    REAUTH_16,
    DEVELOPER_ERROR_10,
    NETWORK_ERROR,
    NO_CREDENTIAL,
    USER_CANCELLED,
    FIREBASE_AUTH,
    CREDENTIAL_PROVIDER,
    UNKNOWN
}

data class AuthDeviceContext(
    val buildType: String,
    val packageName: String,
    val androidSdk: Int,
    val deviceModel: String,
    val sha1: String,
    val sha256: String,
    val matchesDebugSha1: Boolean,
    val webClientIdMasked: String,
    val playServicesVersion: String,
    val playServicesEnabled: Boolean
)

data class AuthFailureReport(
    val category: AuthFailureCategory,
    val headline: String,
    val userSummary: String,
    val probableCause: String,
    val technicalLog: String,
    val suggestGuestMode: Boolean
)

class AuthTraceRecorder {
    private val entries = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun info(stage: String, message: String) {
        val line = "[${timeFormat.format(Date())}] INFO [$stage] $message"
        entries += line
        Log.i(AUTH_LOG_TAG, line)
    }

    fun warn(stage: String, message: String, error: Throwable? = null) {
        val suffix = error?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        val line = "[${timeFormat.format(Date())}] WARN [$stage] $message$suffix"
        entries += line
        if (error != null) Log.w(AUTH_LOG_TAG, line, error) else Log.w(AUTH_LOG_TAG, line)
    }

    fun error(stage: String, message: String, error: Throwable? = null) {
        val suffix = error?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        val line = "[${timeFormat.format(Date())}] ERROR [$stage] $message$suffix"
        entries += line
        if (error != null) Log.e(AUTH_LOG_TAG, line, error) else Log.e(AUTH_LOG_TAG, line)
    }

    fun snapshot(): List<String> = entries.toList()
}

object GoogleAuthExceptionHandler {

    fun captureDeviceContext(context: Context, clientId: String): AuthDeviceContext {
        val appContext = context.applicationContext
        val fingerprints = readApkShaFingerprints(appContext)
        val (gmsVersion, gmsEnabled) = readPlayServicesInfo(appContext)
        val maskedClientId = if (clientId.length > 18) {
            "${clientId.take(12)}...${clientId.takeLast(14)}"
        } else {
            clientId.ifBlank { "(vazio)" }
        }
        return AuthDeviceContext(
            buildType = if (BuildConfig.DEBUG) "Debug" else "Release",
            packageName = appContext.packageName,
            androidSdk = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            sha1 = fingerprints.sha1.ifBlank { "indisponível" },
            sha256 = fingerprints.sha256.ifBlank { "indisponível" },
            matchesDebugSha1 = fingerprints.matchesRegisteredDebugSha1,
            webClientIdMasked = maskedClientId,
            playServicesVersion = gmsVersion,
            playServicesEnabled = gmsEnabled
        )
    }

    fun collectCauseChain(throwable: Throwable): List<String> {
        val visited = HashSet<Throwable>()
        val chain = mutableListOf<String>()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && visited.add(current) && depth < 10) {
            val extra = when (current) {
                is GetCredentialException ->
                    "type=${current.type}, errorMessage=${current.errorMessage}"
                is FirebaseAuthException ->
                    "errorCode=${current.errorCode}"
                else -> null
            }
            val msg = current.message?.takeIf { it.isNotBlank() } ?: "(sem mensagem)"
            chain += if (extra != null) {
                "#$depth ${current.javaClass.name} [$extra]: $msg"
            } else {
                "#$depth ${current.javaClass.name}: $msg"
            }
            current = current.cause
            depth++
        }
        return chain
    }

    fun classify(throwable: Throwable): AuthFailureCategory {
        val combinedText = collectCauseChain(throwable).joinToString("\n")
        return when {
            isReauth16Text(combinedText) -> AuthFailureCategory.REAUTH_16
            combinedText.contains("[10]") ||
                combinedText.contains("10:") ||
                combinedText.contains("DEVELOPER_ERROR", ignoreCase = true) ->
                AuthFailureCategory.DEVELOPER_ERROR_10
            combinedText.contains("[7]") ||
                combinedText.contains("NETWORK_ERROR", ignoreCase = true) ||
                combinedText.contains("network error", ignoreCase = true) ||
                combinedText.contains("unable to resolve host", ignoreCase = true) ||
                combinedText.contains("timeout", ignoreCase = true) ->
                AuthFailureCategory.NETWORK_ERROR
            throwable is NoCredentialException -> AuthFailureCategory.NO_CREDENTIAL
            throwable is FirebaseAuthException -> AuthFailureCategory.FIREBASE_AUTH
            throwable is GetCredentialProviderConfigurationException ||
                throwable is GetCredentialUnsupportedException ||
                throwable is GetCredentialInterruptedException ||
                throwable is GetCredentialUnknownException ||
                throwable is GetCredentialCustomException -> AuthFailureCategory.CREDENTIAL_PROVIDER
            throwable is GetCredentialCancellationException -> AuthFailureCategory.USER_CANCELLED
            else -> AuthFailureCategory.UNKNOWN
        }
    }

    fun shouldAttemptFallback(throwable: Throwable): Boolean {
        return when (classify(throwable)) {
            AuthFailureCategory.REAUTH_16,
            AuthFailureCategory.NO_CREDENTIAL,
            AuthFailureCategory.CREDENTIAL_PROVIDER -> true
            else -> false
        }
    }

    fun buildReport(
        throwable: Throwable,
        deviceContext: AuthDeviceContext,
        traceLines: List<String>,
        phase: String
    ): AuthFailureReport {
        val category = classify(throwable)
        val causeChain = collectCauseChain(throwable)
        val rawDetail = when (throwable) {
            is GetCredentialException ->
                (throwable.errorMessage?.toString() ?: throwable.message).orEmpty().trim()
            else -> throwable.message.orEmpty().trim()
        }

        val shaStatus = if (deviceContext.matchesDebugSha1) {
            "SHA-1 coincide com o certificado de Debug cadastrado"
        } else {
            "SHA-1 DIFERENTE do debug cadastrado ($REGISTERED_DEBUG_SHA1)"
        }

        val (headline, userSummary, probableCause) = when (category) {
            AuthFailureCategory.REAUTH_16 -> Triple(
                "Erro [16] Account reauth failed",
                "O Google Play Services bloqueou a reautenticação da conta após você selecioná-la.",
                buildString {
                    if (!deviceContext.matchesDebugSha1) {
                        append("Este APK (${deviceContext.buildType}) está assinado com o SHA-1 ${deviceContext.sha1}, que não confere com o SHA-1 de debug cadastrado no Firebase/OAuth.")
                    } else if (!deviceContext.playServicesEnabled) {
                        append("O Google Play Services está desativado ou indisponível neste aparelho.")
                    } else {
                        append("Mesmo com SHA-1 compatível, o Google Identity/Play Services recusou emitir o ID Token (comum quando a conta no aparelho exige revalidação no sistema ou a tela de consentimento OAuth está em modo de teste restrito no Google Cloud).")
                    }
                }
            )
            AuthFailureCategory.DEVELOPER_ERROR_10 -> Triple(
                "Erro [10] Configuração OAuth / SHA-1 inválida",
                "O Google recusou o cliente OAuth configurado neste APK.",
                "Verifique se o pacote ${deviceContext.packageName} e o SHA-1 ${deviceContext.sha1} ($shaStatus) estão vinculados ao Web Client ID no Console Firebase."
            )
            AuthFailureCategory.NETWORK_ERROR -> Triple(
                "Falha de rede durante o login Google",
                "Não foi possível concluir a comunicação com os servidores do Google/Firebase.",
                "Verifique a conexão com a internet ou use o Modo Convidado offline para continuar trabalhando."
            )
            AuthFailureCategory.NO_CREDENTIAL -> Triple(
                "Nenhuma conta Google disponível",
                "O gerenciador de credenciais não encontrou uma conta Google elegível neste aparelho.",
                "Adicione uma conta Google nas configurações do Android ou entre no Modo Convidado (Local)."
            )
            AuthFailureCategory.FIREBASE_AUTH -> {
                val code = (throwable as? FirebaseAuthException)?.errorCode ?: "AUTH"
                Triple(
                    "Erro no Firebase Auth [$code]",
                    "O Google retornou a credencial, mas o Firebase recusou a criação da sessão.",
                    "Código Firebase [$code]: ${rawDetail.ifBlank { "verifique provedor Google e App Check no Firebase Console." }}"
                )
            }
            AuthFailureCategory.CREDENTIAL_PROVIDER -> Triple(
                "Provedor de credenciais Google indisponível",
                "O provedor Credential Manager falhou antes de concluir o login.",
                "Play Services (${deviceContext.playServicesVersion}, ativo=${deviceContext.playServicesEnabled}) • Detalhe: ${rawDetail.ifBlank { throwable.javaClass.simpleName }}"
            )
            AuthFailureCategory.USER_CANCELLED -> Triple(
                "Login com Google cancelado",
                "A janela de seleção de conta foi fechada ou interrompida antes de concluir.",
                "Se o seletor fechou sozinho logo após tocar na conta, use o Modo Convidado (Local) para continuar sem bloqueio."
            )
            AuthFailureCategory.UNKNOWN -> Triple(
                "Falha inesperada no login Google",
                "Não foi possível autenticar com a conta Google.",
                rawDetail.ifBlank { throwable.javaClass.simpleName }
            )
        }

        val technicalLog = buildString {
            appendLine("=== DIAGNÓSTICO DE AUTENTICAÇÃO GOOGLE ===")
            appendLine("Categoria: ${category.name} • Fase: $phase")
            appendLine("Build: ${deviceContext.buildType} • SDK: ${deviceContext.androidSdk} • Modelo: ${deviceContext.deviceModel}")
            appendLine("Pacote: ${deviceContext.packageName}")
            appendLine("Web Client ID: ${deviceContext.webClientIdMasked}")
            appendLine("Play Services: ${deviceContext.playServicesVersion} (ativo=${deviceContext.playServicesEnabled})")
            appendLine("SHA-1 APK: ${deviceContext.sha1} ($shaStatus)")
            appendLine("SHA-256 APK: ${deviceContext.sha256}")
            if (rawDetail.isNotBlank()) {
                appendLine("Mensagem original: $rawDetail")
            }
            appendLine("--- Cadeia de Exceções ---")
            causeChain.forEach { appendLine(it) }
            if (traceLines.isNotEmpty()) {
                appendLine("--- Trilha de Execução ---")
                traceLines.forEach { appendLine(it) }
            }
        }.trim()

        Log.e(AUTH_LOG_TAG, technicalLog)

        return AuthFailureReport(
            category = category,
            headline = headline,
            userSummary = userSummary,
            probableCause = probableCause,
            technicalLog = technicalLog,
            suggestGuestMode = true
        )
    }

    private fun isReauth16Text(text: String): Boolean {
        return text.contains("[16]") ||
            text.contains("16:") ||
            text.contains("reauth", ignoreCase = true) ||
            text.contains("SIGN_IN_FAILED", ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun readPlayServicesInfo(context: Context): Pair<String, Boolean> {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo("com.google.android.gms", 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            val appEnabled = info.applicationInfo?.enabled ?: true
            val versionLabel = "${info.versionName ?: "?"} ($versionCode)"
            versionLabel to appEnabled
        } catch (_: PackageManager.NameNotFoundException) {
            "Não instalado" to false
        } catch (e: Exception) {
            "Indisponível (${e.javaClass.simpleName})" to false
        }
    }
}
