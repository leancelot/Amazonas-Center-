package com.example.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.ColdStorageItemEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Calendar

data class ExpiryAlertSettings(
    val enabled: Boolean = false,
    val hour: Int = 8,
    val minute: Int = 0,
    val lastNotifiedAtMillis: Long = 0L
)

object ExpiryNotificationManager {

    private const val PREFS_NAME = "frigstock_expiry_alerts"
    private const val KEY_ENABLED = "alerts_enabled"
    private const val KEY_HOUR = "alert_hour"
    private const val KEY_MINUTE = "alert_minute"
    private const val KEY_CACHED_LOTS_JSON = "cached_lots_json"
    private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"

    const val CHANNEL_ID = "fefo_expiry_alerts"
    private const val NOTIFICATION_ID = 7015
    private const val ALARM_REQUEST_CODE = 7015

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun readSettings(context: Context): ExpiryAlertSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ExpiryAlertSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            hour = prefs.getInt(KEY_HOUR, 8).coerceIn(0, 23),
            minute = prefs.getInt(KEY_MINUTE, 0).coerceIn(0, 59),
            lastNotifiedAtMillis = prefs.getLong(KEY_LAST_NOTIFIED_AT, 0L)
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun updateCachedLots(context: Context, items: List<ColdStorageItemEntity>) {
        val cached = items
            .filter { it.currentQuantity > 0.001 }
            .map {
                CachedLotForAlert(
                    id = it.id,
                    cutName = it.cutName,
                    chamberName = it.chamberName,
                    expiryDateEpochDay = it.expiryDateEpochDay,
                    currentQuantity = it.currentQuantity
                )
            }
        val encoded = json.encodeToString(ListSerializer(CachedLotForAlert.serializer()), cached)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_LOTS_JSON, encoded)
            .apply()
    }

    fun readCachedDigest(context: Context): ExpiryAlertDigest {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_LOTS_JSON, null)
            ?: return ExpiryAlertCalculator.fromCachedLots(emptyList())

        return try {
            val lots = json.decodeFromString(ListSerializer(CachedLotForAlert.serializer()), raw)
            ExpiryAlertCalculator.fromCachedLots(lots)
        } catch (e: Exception) {
            Log.w("ExpiryNotifMgr", "Failed to read cached lots for alerts", e)
            ExpiryAlertCalculator.fromCachedLots(emptyList())
        }
    }

    fun setAlertsEnabled(
        context: Context,
        enabled: Boolean,
        hour: Int = readSettings(context).hour,
        minute: Int = readSettings(context).minute
    ): ExpiryAlertSettings {
        val appContext = context.applicationContext
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_HOUR, safeHour)
            .putInt(KEY_MINUTE, safeMinute)
            .apply()

        if (enabled) {
            createNotificationChannel(appContext)
            scheduleDailyAlarm(appContext, safeHour, safeMinute)
        } else {
            cancelDailyAlarm(appContext)
        }
        return readSettings(appContext)
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alertas de Vencimento FEFO",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos diários para lotes vencidos, críticos (até 7 dias) e atenção (8 a 15 dias)."
            }
            val manager = context.applicationContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun scheduleDailyAlarm(context: Context, hour: Int, minute: Int) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(appContext)

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            target.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
    }

    fun cancelDailyAlarm(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(buildAlarmPendingIntent(appContext))
    }

    fun onAlarmTriggered(context: Context) {
        val settings = readSettings(context)
        if (!settings.enabled) return
        triggerAlertNow(context, forceShowEvenIfClean = false)
    }

    @SuppressLint("MissingPermission")
    fun triggerAlertNow(context: Context, forceShowEvenIfClean: Boolean): String {
        val appContext = context.applicationContext
        if (!hasNotificationPermission(appContext)) {
            return "Permissão de notificações não concedida no aparelho."
        }
        val digest = readCachedDigest(appContext)
        if (!digest.hasUrgentAlerts && !forceShowEvenIfClean) {
            return "Nenhum corte vencido ou com vencimento nos próximos 15 dias."
        }

        createNotificationChannel(appContext)

        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            ALARM_REQUEST_CODE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = digest.buildNotificationTitle()
        val body = digest.buildNotificationBody()

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        return try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_NOTIFIED_AT, System.currentTimeMillis())
                .apply()
            if (digest.hasUrgentAlerts) {
                "Alerta enviado: $title"
            } else {
                "Notificação de teste enviada (estoque em dia)."
            }
        } catch (se: SecurityException) {
            Log.w("ExpiryNotifMgr", "Missing POST_NOTIFICATIONS permission", se)
            "Permissão de notificações negada pelo sistema."
        }
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ExpiryAlertReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
