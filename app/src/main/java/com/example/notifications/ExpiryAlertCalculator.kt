package com.example.notifications

import com.example.data.ColdStorageItemEntity
import com.example.data.DateUtils
import com.example.data.ExpiryUrgency
import kotlinx.serialization.Serializable

@Serializable
data class CachedLotForAlert(
    val id: String,
    val cutName: String,
    val chamberName: String = "",
    val expiryDateEpochDay: Long,
    val currentQuantity: Double
)

data class ExpiryAlertDigest(
    val expiredCount: Int,
    val due7DaysCount: Int,
    val due15DaysCount: Int,
    val highlightCuts: List<String>
) {
    val totalAlertCount: Int get() = expiredCount + due7DaysCount + due15DaysCount
    val hasUrgentAlerts: Boolean get() = totalAlertCount > 0

    fun buildNotificationTitle(): String {
        val parts = mutableListOf<String>()
        if (expiredCount > 0) parts += "$expiredCount vencido(s)"
        if (due7DaysCount > 0) parts += "$due7DaysCount até 7d"
        if (due15DaysCount > 0) parts += "$due15DaysCount em 8-15d"
        return if (parts.isEmpty()) {
            "Câmara Fria em dia (FEFO)"
        } else {
            "Alerta FEFO: ${parts.joinToString(" • ")}"
        }
    }

    fun buildNotificationBody(): String {
        if (!hasUrgentAlerts) {
            return "Nenhum corte vencido ou com vencimento nos próximos 15 dias."
        }
        val cutsText = if (highlightCuts.isNotEmpty()) {
            "Prioridade: ${highlightCuts.joinToString(", ")}."
        } else {
            "Confira a fila FEFO para priorizar a saída."
        }
        return "$cutsText Toque para abrir o estoque."
    }
}

object ExpiryAlertCalculator {

    fun fromItems(items: List<ColdStorageItemEntity>): ExpiryAlertDigest {
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
        return fromCachedLots(cached)
    }

    fun fromCachedLots(lots: List<CachedLotForAlert>): ExpiryAlertDigest {
        val active = lots
            .filter { it.currentQuantity > 0.001 }
            .sortedBy { it.expiryDateEpochDay }

        var expired = 0
        var due7 = 0
        var due15 = 0
        val highlights = mutableListOf<String>()

        active.forEach { lot ->
            val days = DateUtils.daysUntil(lot.expiryDateEpochDay)
            val urgency = ExpiryUrgency.fromEpochDay(lot.expiryDateEpochDay)
            when (urgency) {
                ExpiryUrgency.VENCIDO -> {
                    expired++
                    if (highlights.size < 3) highlights += "${lot.cutName} (vencido)"
                }
                ExpiryUrgency.VENCE_HOJE -> {
                    due7++
                    if (highlights.size < 3) highlights += "${lot.cutName} (hoje)"
                }
                ExpiryUrgency.CRITICO_7_DIAS -> {
                    due7++
                    if (highlights.size < 3) highlights += "${lot.cutName} (${days}d)"
                }
                ExpiryUrgency.ATENCAO_15_DIAS -> {
                    due15++
                    if (highlights.size < 3) highlights += "${lot.cutName} (${days}d)"
                }
                ExpiryUrgency.EM_DIA -> Unit
            }
        }

        return ExpiryAlertDigest(
            expiredCount = expired,
            due7DaysCount = due7,
            due15DaysCount = due15,
            highlightCuts = highlights
        )
    }
}
