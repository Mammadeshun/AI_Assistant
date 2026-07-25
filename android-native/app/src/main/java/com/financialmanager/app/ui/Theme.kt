package com.financialmanager.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's look.
 *
 * A deliberate palette rather than the wallpaper-derived one. Material You is a
 * lovely default, but a money app is read at a glance and in a hurry, and the
 * two colours that matter most — money in, money out — cannot be left to
 * whatever someone's wallpaper happens to be. Green and coral are fixed; the
 * accent is a deep indigo that stays out of their way.
 */

/* --- money ---------------------------------------------------------- */

private val MintLight = Color(0xFF0E7C5A)
private val MintDark = Color(0xFF4ADE9B)
private val CoralLight = Color(0xFFC8452F)
private val CoralDark = Color(0xFFFF7A66)

/* --- surfaces ------------------------------------------------------- */

private val InkDark = Color(0xFF0A0C11)          // page
private val SlateDark = Color(0xFF13171F)        // card
private val SlateDarkRaised = Color(0xFF1B2029)  // input, chip
private val IndigoDark = Color(0xFF8FA8FF)

private val PaperLight = Color(0xFFF6F7FA)
private val CardLight = Color(0xFFFFFFFF)
private val RaisedLight = Color(0xFFEDEFF5)
private val IndigoLight = Color(0xFF3A55C7)

@Composable fun positiveColour(): Color = if (isSystemInDarkTheme()) MintDark else MintLight

@Composable fun negativeColour(): Color = if (isSystemInDarkTheme()) CoralDark else CoralLight

@Composable
fun amountColour(minor: Long): Color = when {
    minor > 0 -> positiveColour()
    minor < 0 -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The headline gradient.
 *
 * Money out is not a failure state, so the everyday card is calm indigo; the
 * red is saved for actually being short, where it should be alarming because
 * the situation is.
 */
@Composable
fun heroBrush(short: Boolean): Brush = if (short) {
    Brush.linearGradient(
        if (isSystemInDarkTheme()) listOf(Color(0xFF52161B), Color(0xFF7A2230))
        else listOf(Color(0xFFFF8A75), Color(0xFFE05252))
    )
} else {
    Brush.linearGradient(
        if (isSystemInDarkTheme()) listOf(Color(0xFF1E2A5E), Color(0xFF2E1F5B))
        else listOf(Color(0xFF4A63D8), Color(0xFF7B5BD6))
    )
}

@Composable
fun onHeroColour(): Color = Color.White

private val DarkColours = darkColorScheme(
    primary = IndigoDark,
    onPrimary = Color(0xFF0A0C11),
    primaryContainer = Color(0xFF25305A),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = MintDark,
    tertiary = Color(0xFFFFC46B),
    background = InkDark,
    onBackground = Color(0xFFEDEFF5),
    surface = SlateDark,
    onSurface = Color(0xFFEDEFF5),
    surfaceVariant = SlateDarkRaised,
    onSurfaceVariant = Color(0xFF98A1B4),
    outline = Color(0xFF2A303C),
    outlineVariant = Color(0xFF1E232C),
    error = CoralDark,
    errorContainer = Color(0xFF3D1418),
    onErrorContainer = Color(0xFFFFD9D2),
)

private val LightColours = lightColorScheme(
    primary = IndigoLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF),
    onPrimaryContainer = Color(0xFF0A1745),
    secondary = MintLight,
    tertiary = Color(0xFF9A6100),
    background = PaperLight,
    onBackground = Color(0xFF11141B),
    surface = CardLight,
    onSurface = Color(0xFF11141B),
    surfaceVariant = RaisedLight,
    onSurfaceVariant = Color(0xFF5B6272),
    outline = Color(0xFFDCE0EA),
    outlineVariant = Color(0xFFE9ECF3),
    error = CoralLight,
    errorContainer = Color(0xFFFFE1DB),
    onErrorContainer = Color(0xFF44100A),
)

/**
 * Numbers are set tighter and heavier than text.
 *
 * Money is the content here, so it gets its own styles rather than borrowing
 * the body ones — and always tabular figures, or a column of amounts wanders
 * as the digits change width.
 */
private val Numeric = androidx.compose.ui.text.font.FontFamily.Default

val MoneyHero = TextStyle(
    fontFamily = Numeric,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-1.4).sp,
    fontFeatureSettings = "tnum",
)

val MoneyLarge = TextStyle(
    fontFamily = Numeric,
    fontSize = 24.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-0.6).sp,
    fontFeatureSettings = "tnum",
)

val MoneyMedium = TextStyle(
    fontFamily = Numeric,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.2).sp,
    fontFeatureSettings = "tnum",
)

/** Small all-caps labels above figures. */
val Eyebrow = TextStyle(
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 1.1.sp,
)

private val AppTypography = Typography().run {
    val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )
    copy(
        headlineSmall = headlineSmall.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeightStyle = trim,
        ),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(lineHeightStyle = trim),
        bodyMedium = bodyMedium.copy(lineHeightStyle = trim),
        bodySmall = bodySmall.copy(lineHeight = 18.sp, lineHeightStyle = trim),
        labelMedium = labelMedium.copy(letterSpacing = 0.8.sp),
    )
}

/** One radius scale, so nothing looks borrowed from another app. */
object Shape {
    val card = 22.dp
    val hero = 28.dp
    val control = 14.dp
    val pill = 999.dp
}

@Composable
fun FinancialManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours,
        typography = AppTypography,
    ) {
        // Wrapped in a Surface so text always inherits a content colour that
        // suits the background. Without it, anything not inside a Surface of its
        // own falls back to black — which is invisible in dark mode, and the
        // sort of thing only looking at a render catches.
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
