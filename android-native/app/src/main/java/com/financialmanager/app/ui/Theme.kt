package com.financialmanager.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Money that came in, and money that went out. Used everywhere amounts appear. */
val PositiveGreen = Color(0xFF12915F)
val PositiveGreenDark = Color(0xFF3DDC97)
val NegativeRed = Color(0xFFD93A3A)
val NegativeRedDark = Color(0xFFFF6B6B)

@Composable
fun positiveColour(): Color = if (isSystemInDarkTheme()) PositiveGreenDark else PositiveGreen

@Composable
fun negativeColour(): Color = if (isSystemInDarkTheme()) NegativeRedDark else NegativeRed

@Composable
fun amountColour(minor: Long): Color = when {
    minor > 0 -> positiveColour()
    minor < 0 -> negativeColour()
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val LightColours = lightColorScheme(
    primary = Color(0xFF2F6BED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE4FF),
    onPrimaryContainer = Color(0xFF00174B),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF4F5F8),
    surfaceVariant = Color(0xFFEAEDF3),
)

private val DarkColours = darkColorScheme(
    primary = Color(0xFF7AA2FF),
    onPrimary = Color(0xFF06101F),
    primaryContainer = Color(0xFF23375E),
    onPrimaryContainer = Color(0xFFDBE4FF),
    surface = Color(0xFF14171F),
    background = Color(0xFF0B0D12),
    surfaceVariant = Color(0xFF1C202A),
)

/**
 * Numbers are set with tabular figures so columns of money line up: with
 * proportional digits a 1 is narrower than an 8 and the decimal points wander.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    )
}

val MoneyLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold)
val MoneyMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

@Composable
fun FinancialManagerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current

    // Material You: on Android 12+ the palette follows the user's wallpaper,
    // which is what makes an app feel like it belongs on the phone.
    val colours = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColours
        else -> LightColours
    }

    MaterialTheme(colorScheme = colours, typography = AppTypography, content = content)
}
