package com.example.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
internal const val REGISTERED_DEBUG_SHA1 = "50:17:72:5E:08:9C:EB:FC:B7:73:46:B3:C4:83:62:E9:17:BC:28:F0"

data class ApkFingerprintInfo(
    val sha1: String = "",
    val sha256: String = ""
) {
    val matchesRegisteredDebugSha1: Boolean
        get() = sha1.equals(REGISTERED_DEBUG_SHA1, ignoreCase = true)
}

object AppCheckRuntimeStatus {
    @Volatile
    var installedProviderName: String = "Não inicializado"
        private set

    @Volatile
    var initFailureDetail: String? = null
        private set

    @Volatile
    var hasDebugIntentExtra: Boolean = false
        private set

    @Volatile
    var recaptchaSiteKeyPresent: Boolean = false
        private set

    internal fun recordDebug(hasIntentToken: Boolean) {
        installedProviderName = "DebugAppCheckProviderFactory"
        hasDebugIntentExtra = hasIntentToken
        initFailureDetail = null
    }

    internal fun recordReleaseSuccess() {
        installedProviderName = "RecaptchaAppCheckProviderFactory"
        recaptchaSiteKeyPresent = true
        initFailureDetail = null
    }

    internal fun recordError(provider: String, detail: String) {
        installedProviderName = provider
        initFailureDetail = detail
    }
}

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            AppCheckRuntimeStatus.recordDebug(hasIntentToken = !debugToken.isNullOrEmpty())
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    AppCheckRuntimeStatus.recordReleaseSuccess()
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    AppCheckRuntimeStatus.recordError(
                        provider = "Nenhum (recaptcha_site_key vazia)",
                        detail = "Chave recaptcha_site_key está vazia."
                    )
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                AppCheckRuntimeStatus.recordError(
                    provider = "Nenhum (recaptcha_site_key ausente)",
                    detail = "Recurso recaptcha_site_key não encontrado."
                )
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        AppCheckRuntimeStatus.recordError(
            provider = "Falha na inicialização",
            detail = e.localizedMessage ?: e.javaClass.simpleName
        )
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

fun readApkShaFingerprints(context: Context): ApkFingerprintInfo {
    return runCatching {
        val pm = context.packageManager
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signingInfo = info.signingInfo
            if (signingInfo == null) {
                null
            } else if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }

        val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return ApkFingerprintInfo()
        ApkFingerprintInfo(
            sha1 = digestHex(certBytes, "SHA-1"),
            sha256 = digestHex(certBytes, "SHA-256")
        )
    }.getOrDefault(ApkFingerprintInfo())
}

suspend fun inspectAppCheckTokenDiagnostic(context: Context): String {
    val buildLabel = if (BuildConfig.DEBUG) "Debug" else "Release"
    val fingerprints = readApkShaFingerprints(context)
    val shaLine = if (fingerprints.sha1.isNotBlank()) {
        val matchNote = if (fingerprints.matchesRegisteredDebugSha1) {
            "igual ao SHA-1 de debug cadastrado"
        } else {
            "diferente do SHA-1 de debug cadastrado"
        }
        "SHA-1 deste APK: ${fingerprints.sha1} ($matchNote)"
    } else {
        "SHA-1 deste APK: indisponível"
    }

    val tokenCheck = withTimeoutOrNull(3_000L) {
        runCatching {
            val result = FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
            decodeAppCheckTokenState(result.token)
        }.getOrElse { err ->
            "erro ao gerar token (${err.javaClass.simpleName}: ${err.localizedMessage ?: err.message})"
        }
    } ?: "tempo esgotado ao consultar token App Check"

    val initErr = AppCheckRuntimeStatus.initFailureDetail?.let { " • Erro init: $it" }.orEmpty()
    return "Build: $buildLabel • Provedor: ${AppCheckRuntimeStatus.installedProviderName}$initErr • Token: $tokenCheck\n$shaLine"
}

private fun decodeAppCheckTokenState(token: String): String {
    if (token.isBlank()) return "token vazio"
    // Firebase App Check SDK encodes dummy error tokens as base64 JSON {"error":"..."} when provider fails
    val dummyError = runCatching {
        val decoded = String(Base64.decode(token, Base64.DEFAULT), Charsets.UTF_8)
        if (decoded.trimStart().startsWith("{") && decoded.contains("error")) {
            JSONObject(decoded).optString("error").takeIf { it.isNotBlank() }
        } else {
            null
        }
    }.getOrNull()
    if (dummyError != null) {
        return "provedor falhou localmente ($dummyError)"
    }
    return "token emitido (${token.length} chars), rejeitado pelo servidor Firebase App Check"
}

private fun digestHex(bytes: ByteArray, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm).digest(bytes)
    return digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}

