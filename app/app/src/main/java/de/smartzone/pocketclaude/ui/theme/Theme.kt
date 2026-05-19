package de.smartzone.pocketclaude.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import de.smartzone.pocketclaude.data.ThemeMode

@Immutable
data class PocketColors(
    val bubbleUser: Color,
    val bubbleAssistant: Color,
    val onBubbleUser: Color,
    val onBubbleAssistant: Color,
    val accent: Color,
    val success: Color,
)

val LocalPocketColors = staticCompositionLocalOf {
    PocketColors(
        bubbleUser = DarkBubbleUser,
        bubbleAssistant = DarkBubbleAssistant,
        onBubbleUser = Color.White,
        onBubbleAssistant = DarkOnSurface,
        accent = SmartzoneCyan,
        success = SuccessGreen,
    )
}

private val DarkColors = darkColorScheme(
    primary = SmartzoneBlue,
    onPrimary = Color.White,
    primaryContainer = SmartzoneBlueDark,
    onPrimaryContainer = Color.White,
    secondary = SmartzoneCyan,
    onSecondary = Color(0xFF052731),
    tertiary = SmartzoneBlueLight,
    onTertiary = Color(0xFF052731),
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceElevated,
    surfaceContainerHigh = DarkSurfaceElevated,
    surfaceContainerHighest = DarkSurfaceElevated,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = ErrorRed,
    onError = Color.White,
)

private val LightColors = lightColorScheme(
    primary = SmartzoneBlue,
    onPrimary = Color.White,
    primaryContainer = SmartzoneBlueLight,
    onPrimaryContainer = Color(0xFF052731),
    secondary = SmartzoneBlueDark,
    onSecondary = Color.White,
    tertiary = SmartzoneCyan,
    onTertiary = Color(0xFF052731),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceElevated,
    surfaceContainerHigh = LightSurfaceElevated,
    surfaceContainerHighest = LightSurfaceElevated,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = ErrorRed,
    onError = Color.White,
)

@Composable
fun PocketClaudeTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val scheme = if (dark) DarkColors else LightColors

    val pocket = if (dark) {
        PocketColors(
            bubbleUser = DarkBubbleUser,
            bubbleAssistant = DarkBubbleAssistant,
            onBubbleUser = Color.White,
            onBubbleAssistant = DarkOnSurface,
            accent = SmartzoneCyan,
            success = SuccessGreen,
        )
    } else {
        PocketColors(
            bubbleUser = LightBubbleUser,
            bubbleAssistant = LightBubbleAssistant,
            onBubbleUser = Color.White,
            onBubbleAssistant = LightOnSurface,
            accent = SmartzoneBlueDark,
            success = SuccessGreen,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalPocketColors provides pocket,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PocketTypography,
            content = content,
        )
    }
}

// Convenience accessor
object PocketTheme {
    val colors: PocketColors
        @Composable get() = LocalPocketColors.current
}
