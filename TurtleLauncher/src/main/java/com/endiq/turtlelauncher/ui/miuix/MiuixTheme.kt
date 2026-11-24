package com.endiq.turtlelauncher.ui.miuix

import android.content.Context
import androidx.annotation.ColorInt

/**
 * Miuix Design System theme tokens ported directly from
 * `top.yukonga.miuix.kmp.theme.Colors` and `top.yukonga.miuix.kmp.theme.TextStyles`
 * (https://github.com/compose-miuix-ui/miuix).
 */
data class MiuixColorScheme(
    @ColorInt val primary: Int,
    @ColorInt val onPrimary: Int,
    @ColorInt val primaryVariant: Int,
    @ColorInt val onPrimaryVariant: Int,
    @ColorInt val primaryContainer: Int,
    @ColorInt val onPrimaryContainer: Int,
    @ColorInt val disabledPrimary: Int,
    @ColorInt val disabledOnPrimary: Int,
    @ColorInt val disabledPrimaryButton: Int,
    @ColorInt val disabledOnPrimaryButton: Int,
    @ColorInt val disabledPrimarySlider: Int,
    @ColorInt val secondary: Int,
    @ColorInt val onSecondary: Int,
    @ColorInt val secondaryVariant: Int,
    @ColorInt val onSecondaryVariant: Int,
    @ColorInt val disabledSecondary: Int,
    @ColorInt val disabledOnSecondary: Int,
    @ColorInt val disabledSecondaryVariant: Int,
    @ColorInt val disabledOnSecondaryVariant: Int,
    @ColorInt val secondaryContainer: Int,
    @ColorInt val onSecondaryContainer: Int,
    @ColorInt val secondaryContainerVariant: Int,
    @ColorInt val onSecondaryContainerVariant: Int,
    @ColorInt val tertiaryContainer: Int,
    @ColorInt val onTertiaryContainer: Int,
    @ColorInt val tertiaryContainerVariant: Int,
    @ColorInt val background: Int,
    @ColorInt val onBackground: Int,
    @ColorInt val onBackgroundVariant: Int,
    @ColorInt val surface: Int,
    @ColorInt val onSurface: Int,
    @ColorInt val surfaceVariant: Int,
    @ColorInt val onSurfaceSecondary: Int,
    @ColorInt val onSurfaceVariantSummary: Int,
    @ColorInt val onSurfaceVariantActions: Int,
    @ColorInt val disabledOnSurface: Int,
    @ColorInt val surfaceContainer: Int,
    @ColorInt val onSurfaceContainer: Int,
    @ColorInt val surfaceContainerHigh: Int,
    @ColorInt val onSurfaceContainerHigh: Int,
    @ColorInt val surfaceContainerHighest: Int,
    @ColorInt val onSurfaceContainerHighest: Int,
    @ColorInt val outline: Int,
    @ColorInt val dividerLine: Int,
    @ColorInt val windowDimming: Int,
    @ColorInt val error: Int,
    @ColorInt val onError: Int,
    @ColorInt val errorContainer: Int,
    @ColorInt val onErrorContainer: Int,
    @ColorInt val sliderKeyPoint: Int,
    @ColorInt val sliderKeyPointForeground: Int,
    @ColorInt val sliderBackground: Int
)

object MiuixTheme {
    /**
     * Miuix `darkColorScheme()` (https://github.com/compose-miuix-ui/miuix), re-hued to
     * Turtle's pastel-green accent. `onPrimary` is the deep green ink rather than white:
     * pastel green is a light surface and white text on it drops below a readable contrast.
     */
    @JvmField
    val DarkColorScheme = MiuixColorScheme(
        primary = 0xFF8FD9A8.toInt(),
        onPrimary = 0xFF0B1F12.toInt(),
        primaryVariant = 0xFF74C793.toInt(),
        onPrimaryVariant = 0xFFD8F7E2.toInt(),
        primaryContainer = 0xFFA7E8BD.toInt(),
        onPrimaryContainer = 0xFF0B1F12.toInt(),
        disabledPrimary = 0xFF2D4A38.toInt(),
        disabledOnPrimary = 0xFF7A9482.toInt(),
        disabledPrimaryButton = 0xFF2D4A38.toInt(),
        disabledOnPrimaryButton = 0xFF7A9482.toInt(),
        disabledPrimarySlider = 0xFF3F5F4C.toInt(),
        secondary = 0xFF505050.toInt(),
        onSecondary = 0xFFFFFFFF.toInt(),
        secondaryVariant = 0xFF434343.toInt(),
        onSecondaryVariant = 0xFFD9D9D9.toInt(),
        disabledSecondary = 0xFF404040.toInt(),
        disabledOnSecondary = 0xFF707070.toInt(),
        disabledSecondaryVariant = 0xFF404040.toInt(),
        disabledOnSecondaryVariant = 0xFF717171.toInt(),
        secondaryContainer = 0xFF434343.toInt(),
        onSecondaryContainer = 0xFF7C7C7C.toInt(),
        secondaryContainerVariant = 0xFF4F4F4F.toInt(),
        onSecondaryContainerVariant = 0xFF959595.toInt(),
        tertiaryContainer = 0xFF1B3A28.toInt(),
        onTertiaryContainer = 0xFFB8EFCB.toInt(),
        tertiaryContainerVariant = 0xFF505050.toInt(),
        background = 0xFF1B241D.toInt(),
        onBackground = 0xE6FFFFFF.toInt(),
        onBackgroundVariant = 0xFF8A9C90.toInt(),
        surface = 0xFF000000.toInt(),
        onSurface = 0xFFF2F2F2.toInt(),
        surfaceVariant = 0xFF1A221C.toInt(),
        onSurfaceSecondary = 0xCCFFFFFF.toInt(),
        onSurfaceVariantSummary = 0x80FFFFFF.toInt(),
        onSurfaceVariantActions = 0x66FFFFFF.toInt(),
        disabledOnSurface = 0xFF666666.toInt(),
        surfaceContainer = 0xFF1A221C.toInt(),
        onSurfaceContainer = 0xE6FFFFFF.toInt(),
        surfaceContainerHigh = 0xFF1A221C.toInt(),
        onSurfaceContainerHigh = 0xFF666666.toInt(),
        surfaceContainerHighest = 0xFF243028.toInt(),
        onSurfaceContainerHighest = 0xFFE9E9E9.toInt(),
        outline = 0xFF404040.toInt(),
        dividerLine = 0xFF393939.toInt(),
        windowDimming = 0x99000000.toInt(),
        error = 0xFFF12522.toInt(),
        onError = 0xFF690005.toInt(),
        errorContainer = 0xFF2E0603.toInt(),
        onErrorContainer = 0xFFFFDAD6.toInt(),
        sliderKeyPoint = 0x4D7A9C86,
        sliderKeyPointForeground = 0xFFA7E8BD.toInt(),
        sliderBackground = 0x26FFFFFF
    )

    /**
     * Typography sizes in sp from `top.yukonga.miuix.kmp.theme.TextStyles`.
     */
    object TextSizesSp {
        const val MAIN = 17f
        const val PARAGRAPH = 17f
        const val BODY1 = 16f
        const val BODY2 = 14f
        const val BUTTON = 17f
        const val FOOTNOTE1 = 13f
        const val FOOTNOTE2 = 11f
        const val HEADLINE1 = 17f
        const val HEADLINE2 = 16f
        const val SUBTITLE = 14f
        const val TITLE1 = 32f
        const val TITLE2 = 24f
        const val TITLE3 = 20f
        const val TITLE4 = 18f
    }

    /**
     * Standard Miuix component corner radii in dp.
     */
    object CornerRadiiDp {
        const val CARD = 16f
        const val BUTTON = 16f
        const val TEXT_FIELD = 16f
        const val TAB_ROW = 12f
        const val TAB_INDICATOR = 8f
        const val DIALOG = 32f
        const val POPUP = 20f
    }

    /**
     * Standard Miuix indication overlay alphas from `MiuixIndication.kt`.
     */
    object IndicationAlpha {
        const val HOVER = 0.06f
        const val FOCUS = 0.08f
        const val PRESS = 0.10f
        const val HOLD_DOWN = 0.10f
    }

    /**
     * Whether the active theme pack renders light. This is no longer a constant: the theme
     * studio (Settings -> Launcher -> Theme studio) can switch packs and a dark/light mode,
     * so every Miuix surface asks [com.endiq.turtlelauncher.feature.theme.ThemeManager].
     */
    @JvmStatic
    fun isDarkMode(context: Context?): Boolean =
        if (context == null) true else com.endiq.turtlelauncher.feature.theme.ThemeManager.isDark(context)

    /**
     * The active colour scheme: [DarkColorScheme] expanded from the selected theme pack and
     * accent colour. Callers that cached a scheme per-view must re-read it when the theme
     * changes (the picker recreates the activity). A null context (no live theme) falls back
     * to the legacy dark palette so callers without an Activity still get a readable scheme.
     */
    @JvmStatic
    fun colors(context: Context?): MiuixColorScheme =
        if (context == null) DarkColorScheme else
            runCatching { com.endiq.turtlelauncher.feature.theme.ThemeManager.scheme(context) }
                .getOrDefault(DarkColorScheme)

    @JvmStatic
    fun dpToPx(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
