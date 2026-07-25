package com.financialmanager.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's look: quiet, grouped, mostly monochrome.
 *
 * The previous version leaned on gradients and colour to look designed, which is
 * the opposite of what a money app needs. Almost everything here is grey; the
 * only saturated things on screen are the amounts themselves and one accent.
 * Restraint is the style.
 */

/* --- amounts --------------------------------------------------------- */

private val GreenLight = Color(0xFF12894F)
private val GreenDark = Color(0xFF32D583)
private val RedLight = Color(0xFFC5333B)
private val RedDark = Color(0xFFFF5A5F)

/* --- greys ----------------------------------------------------------- */

private val GroupedLight = Color(0xFFF2F2F7)   // page behind the cards
private val CardLight = Color(0xFFFFFFFF)
private val FillLight = Color(0xFFE9E9EF)      // input, icon well
private val SeparatorLight = Color(0xFFE3E3E8)
private val LabelLight = Color(0xFF1C1C1E)
private val SecondaryLight = Color(0xFF8A8A8E)
private val TintLight = Color(0xFF1F6FEB)

private val GroupedDark = Color(0xFF000000)
private val CardDark = Color(0xFF1C1C1E)
private val FillDark = Color(0xFF2C2C2E)
private val SeparatorDark = Color(0xFF2F2F33)
private val LabelDark = Color(0xFFF5F5F7)
private val SecondaryDark = Color(0xFF98989E)
private val TintDark = Color(0xFF5A9BFF)

@Composable fun positiveColour(): Color = if (isSystemInDarkTheme()) GreenDark else GreenLight

@Composable fun negativeColour(): Color = if (isSystemInDarkTheme()) RedDark else RedLight

/**
 * Spending is shown in the ordinary text colour, not red.
 *
 * Nearly every row in a statement is money leaving. Colouring them all red
 * makes the list shout and stops red meaning anything; it is kept for the one
 * place it matters, which is being short. Income gets the green, because it is
 * the rarer, more interesting event.
 */
@Composable
fun amountColour(minor: Long): Color =
    if (minor > 0) positiveColour() else MaterialTheme.colorScheme.onSurface

private val DarkColours = darkColorScheme(
    primary = TintDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF16233A),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = GreenDark,
    tertiary = Color(0xFFFFB020),
    background = GroupedDark,
    onBackground = LabelDark,
    surface = CardDark,
    onSurface = LabelDark,
    surfaceVariant = FillDark,
    onSurfaceVariant = SecondaryDark,
    outline = SeparatorDark,
    outlineVariant = SeparatorDark,
    error = RedDark,
    errorContainer = Color(0xFF2A1215),
    onErrorContainer = Color(0xFFFFD7D9),
)

private val LightColours = lightColorScheme(
    primary = TintLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001A44),
    secondary = GreenLight,
    tertiary = Color(0xFF9A6100),
    background = GroupedLight,
    onBackground = LabelLight,
    surface = CardLight,
    onSurface = LabelLight,
    surfaceVariant = FillLight,
    onSurfaceVariant = SecondaryLight,
    outline = SeparatorLight,
    outlineVariant = SeparatorLight,
    error = RedLight,
    errorContainer = Color(0xFFFFE2E2),
    onErrorContainer = Color(0xFF410008),
)

/**
 * Numbers get their own styles: tighter, heavier, and always tabular figures,
 * or a column of amounts wanders as the digits change width.
 */
val MoneyHero = TextStyle(
    fontSize = 46.sp,
    lineHeight = 50.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = (-1.8).sp,
    fontFeatureSettings = "tnum",
)

val MoneyLarge = TextStyle(
    fontSize = 22.sp,
    lineHeight = 26.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.5).sp,
    fontFeatureSettings = "tnum",
)

val MoneyMedium = TextStyle(
    fontSize = 16.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = (-0.2).sp,
    fontFeatureSettings = "tnum",
)

/** Small grey headings above a group. */
val GroupHeader = TextStyle(
    fontSize = 13.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp,
)

private val AppTypography = Typography().run {
    copy(
        // The big screen title, set once at the top and left alone.
        headlineLarge = headlineLarge.copy(
            fontSize = 34.sp, lineHeight = 40.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
        ),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
        titleMedium = titleMedium.copy(
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
        ),
        titleSmall = titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = labelMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp),
    )
}

object Shape {
    val card = 16.dp
    val control = 12.dp
    val pill = 999.dp
}

@Composable
fun FinancialManagerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColours else LightColours,
        typography = AppTypography,
    ) {
        // A Surface at the root so text always inherits a content colour that
        // suits the background; without it, anything outside a Surface falls
        // back to black and disappears in dark mode.
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
