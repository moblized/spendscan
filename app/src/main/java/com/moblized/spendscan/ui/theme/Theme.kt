package com.moblized.spendscan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.moblized.spendscan.R

/**
 * The look: a shop counter with till slips on it. Blue is the counter, paper is where the numbers are printed,
 * yellow is a highlighter, and red is a rubber stamp saved for going over budget.
 */
@Immutable
data class SlipColors(
    val counter: Color,
    val counterDeep: Color,
    val onCounter: Color,
    val onCounterMuted: Color,
    val paper: Color,
    val paperShade: Color,
    val ink: Color,
    val inkFaded: Color,
    val highlighter: Color,
    val onHighlighter: Color,
    val stamp: Color,
)

val LightSlip = SlipColors(
    counter = Color(0xFF2A46C8),
    counterDeep = Color(0xFF1F36A3),
    onCounter = Color(0xFFFFFFFF),
    onCounterMuted = Color(0xFFD3DAFB),
    paper = Color(0xFFFAFBF7),
    paperShade = Color(0xFFECEEE7),
    ink = Color(0xFF23233A),
    inkFaded = Color(0xFF62627E),
    highlighter = Color(0xFFFFE500),
    onHighlighter = Color(0xFF23233A),
    stamp = Color(0xFFC81E28),
)

val DarkSlip = SlipColors(
    counter = Color(0xFF0C1030),
    counterDeep = Color(0xFF070A20),
    onCounter = Color(0xFFEEF0FF),
    onCounterMuted = Color(0xFFA9B0DC),
    paper = Color(0xFF1B2049),
    paperShade = Color(0xFF2A3170),
    ink = Color(0xFFEEF0FF),
    inkFaded = Color(0xFFA9B0DC),
    highlighter = Color(0xFFFFE500),
    onHighlighter = Color(0xFF23233A),
    stamp = Color(0xFFFF8A87),
)

val LocalSlip = staticCompositionLocalOf { LightSlip }

val SometypeMono = FontFamily(
    Font(R.font.sometype_mono_regular, FontWeight.Normal),
    Font(R.font.sometype_mono_medium, FontWeight.Medium),
    Font(R.font.sometype_mono_bold, FontWeight.Bold),
)

val Doto = FontFamily(Font(R.font.doto_black, FontWeight.Black))

object Type {
    val small = TextStyle(fontFamily = SometypeMono, fontSize = 12.5.sp, lineHeight = 18.sp)
    val body = TextStyle(fontFamily = SometypeMono, fontSize = 15.sp, lineHeight = 22.sp)
    val strong = body.copy(fontWeight = FontWeight.Bold)
    val lead = TextStyle(fontFamily = SometypeMono, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontFamily = SometypeMono, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
    val figure = TextStyle(fontFamily = Doto, fontSize = 56.sp, lineHeight = 62.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
    val figureSmall = TextStyle(fontFamily = Doto, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
}

private val AppTypography = Typography().run {
    fun TextStyle.mono() = copy(fontFamily = SometypeMono)
    copy(
        displayLarge = displayLarge.mono(), displayMedium = displayMedium.mono(), displaySmall = displaySmall.mono(),
        headlineLarge = headlineLarge.mono(), headlineMedium = headlineMedium.mono(), headlineSmall = headlineSmall.mono(),
        titleLarge = titleLarge.mono(), titleMedium = titleMedium.mono(), titleSmall = titleSmall.mono(),
        bodyLarge = bodyLarge.mono(), bodyMedium = bodyMedium.mono(), bodySmall = bodySmall.mono(),
        labelLarge = labelLarge.mono(), labelMedium = labelMedium.mono(), labelSmall = labelSmall.mono(),
    )
}

@Composable
fun SpendTheme(dark: Boolean, content: @Composable () -> Unit) {
    val slip = if (dark) DarkSlip else LightSlip
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFFB9C4FF), onPrimary = Color(0xFF0C1030),
            primaryContainer = slip.paperShade, onPrimaryContainer = slip.ink,
            secondary = slip.highlighter, onSecondary = slip.onHighlighter,
            secondaryContainer = slip.paperShade, onSecondaryContainer = slip.ink,
            background = slip.counter, onBackground = slip.onCounter,
            surface = slip.paper, onSurface = slip.ink, onSurfaceVariant = slip.inkFaded,
            surfaceVariant = slip.paperShade, surfaceContainerHigh = slip.paper, surfaceContainerHighest = slip.paperShade,
            surfaceContainer = slip.paper, surfaceContainerLow = slip.paper,
            outline = slip.inkFaded, outlineVariant = slip.paperShade, error = slip.stamp,
        )
    } else {
        lightColorScheme(
            primary = slip.counter, onPrimary = slip.onCounter,
            primaryContainer = slip.paperShade, onPrimaryContainer = slip.ink,
            secondary = slip.highlighter, onSecondary = slip.onHighlighter,
            secondaryContainer = slip.paperShade, onSecondaryContainer = slip.ink,
            background = slip.counter, onBackground = slip.onCounter,
            surface = slip.paper, onSurface = slip.ink, onSurfaceVariant = slip.inkFaded,
            surfaceVariant = slip.paperShade, surfaceContainerHigh = slip.paper, surfaceContainerHighest = slip.paperShade,
            surfaceContainer = slip.paper, surfaceContainerLow = slip.paper,
            outline = slip.inkFaded, outlineVariant = slip.paperShade, error = slip.stamp,
        )
    }
    CompositionLocalProvider(LocalSlip provides slip) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
