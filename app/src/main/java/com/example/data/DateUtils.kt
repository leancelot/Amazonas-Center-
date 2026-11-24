package com.example.data

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtils {
    private val ptBrLocale = Locale.forLanguageTag("pt-BR")
    private const val MILLIS_PER_DAY = 86_400_000L

    fun todayEpochDay(): Long {
        val cal = Calendar.getInstance()
        return calendarToEpochDay(cal)
    }

    fun addDaysToTodayEpochDay(days: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, days)
        return calendarToEpochDay(cal)
    }

    fun millisToEpochDay(utcMillis: Long): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCal.timeInMillis = utcMillis
        val localCal = Calendar.getInstance()
        localCal.set(
            utcCal.get(Calendar.YEAR),
            utcCal.get(Calendar.MONTH),
            utcCal.get(Calendar.DAY_OF_MONTH),
            0,
            0,
            0
        )
        localCal.set(Calendar.MILLISECOND, 0)
        return calendarToEpochDay(localCal)
    }

    fun epochDayToUtcPickerMillis(epochDay: Long): Long {
        val localCal = epochDayToCalendar(epochDay)
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCal.set(
            localCal.get(Calendar.YEAR),
            localCal.get(Calendar.MONTH),
            localCal.get(Calendar.DAY_OF_MONTH),
            0,
            0,
            0
        )
        utcCal.set(Calendar.MILLISECOND, 0)
        return utcCal.timeInMillis
    }

    fun parseIsoDateToEpochDay(isoDate: String?): Long? {
        if (isoDate.isNullOrBlank()) return null
        val cleaned = isoDate.trim().take(10)
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            val date = parser.parse(cleaned) ?: return null
            val cal = Calendar.getInstance().apply { time = date }
            calendarToEpochDay(cal)
        } catch (_: Exception) {
            try {
                val parserBr = SimpleDateFormat("dd/MM/yyyy", ptBrLocale).apply { isLenient = false }
                val date = parserBr.parse(cleaned) ?: return null
                val cal = Calendar.getInstance().apply { time = date }
                calendarToEpochDay(cal)
            } catch (_: Exception) {
                null
            }
        }
    }

    fun formatEpochDay(epochDay: Long): String {
        val cal = epochDayToCalendar(epochDay)
        val fmt = SimpleDateFormat("dd/MM/yyyy", ptBrLocale)
        return fmt.format(cal.time)
    }

    fun formatDateTime(millis: Long): String {
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", ptBrLocale)
        return fmt.format(Date(millis))
    }

    fun formatShortDate(millis: Long): String {
        val fmt = SimpleDateFormat("dd/MM HH:mm", ptBrLocale)
        return fmt.format(Date(millis))
    }

    fun daysUntil(expiryEpochDay: Long): Int {
        return (expiryEpochDay - todayEpochDay()).toInt()
    }

    fun formatWeightOrQty(value: Double, unit: MeasurementUnit): String {
        val nf = NumberFormat.getNumberInstance(ptBrLocale).apply {
            minimumFractionDigits = if (unit == MeasurementUnit.KG) 2 else 0
            maximumFractionDigits = if (unit == MeasurementUnit.KG) 2 else 1
        }
        return "${nf.format(value)} ${unit.shortLabel}"
    }

    fun formatCurrencyBrl(value: Double): String {
        val nf = NumberFormat.getCurrencyInstance(ptBrLocale)
        return nf.format(value)
    }

    fun formatTemp(celsius: Double): String {
        val nf = NumberFormat.getNumberInstance(ptBrLocale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return "${nf.format(celsius)} °C"
    }

    private fun calendarToEpochDay(cal: Calendar): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.set(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
            0,
            0,
            0
        )
        utc.set(Calendar.MILLISECOND, 0)
        return utc.timeInMillis / MILLIS_PER_DAY
    }

    private fun epochDayToCalendar(epochDay: Long): Calendar {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utc.timeInMillis = epochDay * MILLIS_PER_DAY
        return Calendar.getInstance().apply {
            set(
                utc.get(Calendar.YEAR),
                utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH),
                0,
                0,
                0
            )
            set(Calendar.MILLISECOND, 0)
        }
    }
}
