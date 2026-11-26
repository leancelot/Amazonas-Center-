package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.DateUtils
import com.example.data.ExpiryUrgency
import com.example.data.MeatType
import com.example.data.TemperatureZone
import com.example.data.ThermalCompliance
import com.example.ui.theme.ButcherCrimson
import com.example.ui.theme.ButcherCrimsonSoft
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusDangerRed
import com.example.ui.theme.StatusDangerRedBg
import com.example.ui.theme.StatusDangerRedDarkBg
import com.example.ui.theme.StatusOkGreen
import com.example.ui.theme.StatusOkGreenBg
import com.example.ui.theme.StatusOkGreenDarkBg
import com.example.ui.theme.StatusWarningAmber
import com.example.ui.theme.StatusWarningAmberBg
import com.example.ui.theme.StatusWarningAmberDarkBg
import com.example.ui.theme.ZoneChilledBg
import com.example.ui.theme.ZoneChilledBlue
import com.example.ui.theme.ZoneFrozenBg
import com.example.ui.theme.ZoneFrozenIndigo
import com.example.ui.theme.ZoneMaturationBg
import com.example.ui.theme.ZoneMaturationBurgundy

@Composable
fun ExpiryUrgencyBadge(
    expiryEpochDay: Long,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val days = DateUtils.daysUntil(expiryEpochDay)
    val urgency = ExpiryUrgency.fromEpochDay(expiryEpochDay)

    val (bg, fg, icon, text) = when (urgency) {
        ExpiryUrgency.VENCIDO -> Quad(
            if (isDark) StatusDangerRedDarkBg else StatusDangerRedBg,
            if (isDark) Color(0xFFFCA5A5) else StatusDangerRed,
            Icons.Rounded.Warning,
            "Vencido há ${-days}d"
        )
        ExpiryUrgency.VENCE_HOJE -> Quad(
            if (isDark) StatusDangerRedDarkBg else StatusDangerRedBg,
            if (isDark) Color(0xFFFCA5A5) else StatusDangerRed,
            Icons.Rounded.Warning,
            "Vence HOJE"
        )
        ExpiryUrgency.CRITICO_7_DIAS -> Quad(
            if (isDark) StatusWarningAmberDarkBg else StatusWarningAmberBg,
            if (isDark) Color(0xFFFCD34D) else StatusWarningAmber,
            Icons.Rounded.Schedule,
            "Vence em ${days}d"
        )
        ExpiryUrgency.ATENCAO_15_DIAS -> Quad(
            if (isDark) Color(0xFF422006) else Color(0xFFFEF3C7),
            if (isDark) Color(0xFFFDE68A) else Color(0xFFB45309),
            Icons.Rounded.Schedule,
            "Vence em ${days}d"
        )
        ExpiryUrgency.EM_DIA -> Quad(
            if (isDark) StatusOkGreenDarkBg else StatusOkGreenBg,
            if (isDark) Color(0xFF86EFAC) else StatusOkGreen,
            Icons.Rounded.CheckCircle,
            "${days}d restantes"
        )
    }

    BadgePill(text = text, bg = bg, fg = fg, icon = icon, modifier = modifier)
}

@Composable
fun ThermalComplianceBadge(
    compliance: ThermalCompliance,
    tempCelsius: Double? = null,
    showTempValue: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val (bg, fg, icon) = when (compliance) {
        ThermalCompliance.CONFORME -> Triple(
            if (isDark) StatusOkGreenDarkBg else StatusOkGreenBg,
            if (isDark) Color(0xFF86EFAC) else StatusOkGreen,
            Icons.Rounded.CheckCircle
        )
        ThermalCompliance.ATENCAO -> Triple(
            if (isDark) StatusWarningAmberDarkBg else StatusWarningAmberBg,
            if (isDark) Color(0xFFFCD34D) else StatusWarningAmber,
            Icons.Rounded.Thermostat
        )
        ThermalCompliance.CRITICO -> Triple(
            if (isDark) StatusDangerRedDarkBg else StatusDangerRedBg,
            if (isDark) Color(0xFFFCA5A5) else StatusDangerRed,
            Icons.Rounded.Warning
        )
    }
    val label = if (tempCelsius != null && showTempValue) {
        "${DateUtils.formatTemp(tempCelsius)} • ${compliance.displayName}"
    } else {
        compliance.displayName
    }
    BadgePill(text = label, bg = bg, fg = fg, icon = icon, modifier = modifier)
}

@Composable
fun TemperatureZonePill(
    zone: TemperatureZone,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val (bg, fg, icon) = when (zone) {
        TemperatureZone.RESFRIADO -> Triple(
            if (isDark) Color(0xFF0C3652) else ZoneChilledBg,
            if (isDark) Color(0xFF7DD3FC) else ZoneChilledBlue,
            Icons.Rounded.Thermostat
        )
        TemperatureZone.CONGELADO -> Triple(
            if (isDark) Color(0xFF1E295E) else ZoneFrozenBg,
            if (isDark) Color(0xFFA5B4FC) else ZoneFrozenIndigo,
            Icons.Rounded.AcUnit
        )
        TemperatureZone.MATURACAO -> Triple(
            if (isDark) Color(0xFF4C1427) else ZoneMaturationBg,
            if (isDark) Color(0xFFFDA4AF) else ZoneMaturationBurgundy,
            Icons.Rounded.Schedule
        )
    }
    BadgePill(text = zone.displayName, bg = bg, fg = fg, icon = icon, modifier = modifier)
}

@Composable
fun MeatTypePill(
    meatType: MeatType,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bg = if (isDark) Color(0xFF4A181D) else ButcherCrimsonSoft
    val fg = if (isDark) Color(0xFFFCA5A5) else ButcherCrimson
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = meatType.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}

@Composable
private fun BadgePill(
    text: String,
    bg: Color,
    fg: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
            fontWeight = FontWeight.SemiBold,
            color = fg
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
