package com.endiq.turtlelauncher.feature.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.miuix.MiuixColorScheme

/**
 * Resolves the launcher's colours at runtime.
 *
 * Everything that used to be a single hard-coded dark palette (`MiuixTheme.DarkColorScheme`)
 * now flows through here, which is what makes a theme pack, a custom accent colour and a
 * dark/light switch possible without touching the hundred call sites that already ask
 * `MiuixTheme.colors(context)`.
 *
 * The palette is a pure function of (pack, accent override, dark/light), so it is cached and
 * rebuilt only when one of those actually changes.
 */
object ThemeManager {
    private const val TAG = "ThemeManager"

    /** Accent hues offered as one-tap swatches in the theme studio. */
    @JvmField
    val ACCENT_SWATCHES = intArrayOf(
        0xFF7FD79E.toInt(), // Overworld green
        0xFFFF6A2B.toInt(), // Lava orange
        0xFF9B7CF0.toInt(), // End violet
        0xFFC99BE8.toInt(), // Amethyst
        0xFFE03B2F.toInt(), // Redstone
        0xFF3AA6C9.toInt(), // Ocean cyan
        0xFFE8B44A.toInt(), // Gold
        0xFFE06A9E.toInt()  // Rose
    )

    @Volatile private var cacheKey: String = ""
    @Volatile private var cacheDark: Boolean = true
    @Volatile private var cacheScheme: MiuixColorScheme? = null

    /**
     * The pack selected in Settings -> Launcher -> Theme studio.
     *
     * `pack()` is asked for by every Miuix surface while it inflates, so the resolved enum is
     * cached against the raw stored key: a screen full of cards parses the setting once, not
     * once per view.
     */
    @Volatile private var cachedPackKey: String? = null
    @Volatile private var cachedPack: ThemePack = ThemePack.OVERWORLD

    @JvmStatic
    fun pack(): ThemePack {
        val raw = runCatching { AllSettings.launcherThemePack.getValue() }.getOrDefault("")
        if (raw == cachedPackKey) return cachedPack
        val resolved = ThemePack.fromKey(raw)
        cachedPackKey = raw
        cachedPack = resolved
        return resolved
    }

    @JvmStatic
    fun iconPack(): IconPack = runCatching {
        IconPack.fromKey(AllSettings.launcherIconPack.getValue())
    }.getOrDefault(IconPack.CLASSIC)

    @JvmStatic
    fun mode(): ThemeMode = runCatching {
        ThemeMode.fromKey(AllSettings.launcherTheme.getValue())
    }.getOrDefault(ThemeMode.DARK)

    /** Explicit accent chosen in the theme studio, or null to use the pack's own accent. */
    @ColorInt
    @JvmStatic
    fun accentOverride(): Int? = runCatching {
        val raw = AllSettings.launcherAccentColor.getValue().trim()
        if (raw.isEmpty()) null else Color.parseColor(raw)
    }.getOrNull()

    @ColorInt
    @JvmStatic
    fun accent(): Int = accentOverride() ?: pack().accent

    /**
     * Whether the current configuration renders light.
     *
     * Light packs (Vanilla Light, Overworld in daylight, Amethyst pastel) still read as light
     * when the device is in dark mode; a dark-only pack never turns light, so a user can pick
     * Nether Dark and get Nether Dark on a light-mode phone.
     */
    @JvmStatic
    fun isDark(context: Context?): Boolean {
        val pack = pack()
        return when (mode()) {
            ThemeMode.LIGHT -> pack.lightBackground.luminance() < 0.45f
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> {
                val night = context?.resources?.configuration?.uiMode
                    ?.and(Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                if (night) true else pack.lightBackground.luminance() < 0.45f
            }
        }
    }

    @JvmStatic
    fun scheme(context: Context?): MiuixColorScheme {
        val pack = pack()
        val explicitAccent = accentOverride()
        val accent = explicitAccent ?: pack.accent
        val dark = isDark(context)
        val surfaces = Appearance.surfaces()
        val contrast = Appearance.contrast()
        val outlineScale = Appearance.outlineScale()
        val glow = Appearance.accentGlow()
        val key = "${pack.key}|${Integer.toHexString(accent)}|$dark|${surfaces.key}|" +
            "${contrast.key}|$outlineScale|$glow"

        cacheScheme?.let { cached -> if (cacheKey == key && cacheDark == dark) return cached }

        val background = if (dark) pack.darkBackground else pack.lightBackground
        val scheme = PaletteBuilder.build(
            accent = accent,
            accentAlt = if (explicitAccent != null) accent.rotateHue(18f) else pack.accentAlt,
            background = background,
            dark = dark,
            surfaces = surfaces,
            outlineScale = outlineScale,
            highContrast = contrast == Appearance.ContrastStyle.HIGH,
            accentGlow = glow
        )
        cacheKey = key
        cacheDark = dark
        cacheScheme = scheme
        return scheme
    }

    /**
     * A scheme built from explicit inputs instead of the saved settings.
     *
     * Advanced appearance edits several settings at once, so its preview has to render the
     * *draft* rather than what is on disk. Sharing [PaletteBuilder] keeps the preview honest: a
     * chip cannot promise a colour the real screen will not produce.
     */
    @JvmStatic
    fun previewScheme(
        @ColorInt accent: Int,
        @ColorInt accentAlt: Int,
        @ColorInt background: Int,
        dark: Boolean,
        surfaces: Appearance.SurfaceStyle,
        outlineScale: Float,
        highContrast: Boolean,
        accentGlow: Boolean
    ): MiuixColorScheme = PaletteBuilder.build(
        accent = accent,
        accentAlt = accentAlt,
        background = background,
        dark = dark,
        surfaces = surfaces,
        outlineScale = outlineScale,
        highContrast = highContrast,
        accentGlow = accentGlow
    )

    /**
     * Corner radius in dp, honouring the theme pack's softness and the icon pack's shape language.
     * Clamped so no pack can turn a card into a circle or into a stray sliver.
     */
    @JvmStatic
    fun cornerDp(context: Context, baseDp: Float): Float =
        (baseDp * pack().cornerScale * iconPack().cornerScale * Appearance.cornerScale())
            .coerceIn(baseDp * 0.1f, baseDp * 2.4f)

    /**
     * Spacing token in pixels for code-built screens, honouring the density rhythm picked in
     * Advanced appearance. Static layouts keep their own dimens; this is what lets the studio,
     * the advanced sheet and the home chrome breathe together.
     */
    @JvmStatic
    fun spacingPx(context: Context, baseDp: Float): Int =
        Appearance.spacing(context, baseDp).coerceAtLeast(0)

    /** Keeps AppCompat's night mode in sync with the chosen theme mode. */
    @JvmStatic
    fun applyNightMode() {
        val delegateMode = when (mode()) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        runCatching { AppCompatDelegate.setDefaultNightMode(delegateMode) }
            .onFailure { Logging.e(TAG, "Failed to apply night mode", it) }
    }

    /**
     * Paints the window chrome (background, status/navigation bar icons) from the scheme, so a
     * light pack does not keep a black system bar and vice versa.
     */
    @JvmStatic
    fun applyWindowTheme(activity: Activity?) {
        activity ?: return
        runCatching {
            val scheme = scheme(activity)
            val dark = isDark(activity)
            activity.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(scheme.surface))
                statusBarColor = scheme.surface
                navigationBarColor = scheme.surface
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val flags = decorView.systemUiVisibility
                    decorView.systemUiVisibility = if (dark) {
                        flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    } else {
                        flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    }
                }
            }
        }.onFailure { Logging.e(TAG, "Failed to theme the window", it) }
    }

    /**
     * Recolours the legacy brand text/border colours that layouts still reference as raw
     * resources (`@color/turtle_primary_text` and friends). Resource colours cannot change at
     * runtime, so in light mode - where those near-white values would disappear - the view tree
     * is walked once per screen and matching colours are swapped for the scheme's ink.
     *
     * In dark mode this is a no-op: the bundled resources already are the dark palette.
     */
    @JvmStatic
    fun reconcileLegacyColors(context: Context?, root: View?) {
        context ?: return
        root ?: return
        if (isDark(context)) return
        val scheme = scheme(context)
        val replacements = LegacyColorMap(context, scheme)
        runCatching { walk(root, replacements) }
            .onFailure { Logging.e(TAG, "Legacy colour pass failed", it) }
    }

    private fun walk(view: View, map: LegacyColorMap) {
        when (view) {
            is TextView -> {
                map.map(view.currentTextColor)?.let { view.setTextColor(it) }
                view.compoundDrawables.forEach { drawable ->
                    map.map(drawable?.let { tintOf(it) } ?: 0)?.let { drawable?.setTint(it) }
                }
            }
            is ViewGroup -> for (index in 0 until view.childCount) walk(view.getChildAt(index), map)
        }
        map.map(backgroundTintOf(view))?.let { tint ->
            view.background?.mutate()?.setTint(tint)
        }
    }

    private fun tintOf(drawable: android.graphics.drawable.Drawable): Int = runCatching {
        (drawable as? android.graphics.drawable.GradientDrawable)?.color?.defaultColor ?: 0
    }.getOrDefault(0)

    private fun backgroundTintOf(view: View): Int = runCatching {
        view.background?.let { background ->
            if (background is android.graphics.drawable.GradientDrawable) {
                background.color?.defaultColor ?: 0
            } else 0
        } ?: 0
    }.getOrDefault(0)

    private class LegacyColorMap(context: Context, private val scheme: MiuixColorScheme) {
        private val pairs: List<Pair<Int, Int>> = buildList {
            fun addColor(name: String, target: Int) {
                val id = context.resources.getIdentifier(name, "color", context.packageName)
                if (id != 0) runCatching { add(context.getColor(id) to target) }
            }
            addColor("turtle_primary_text", scheme.onSurface)
            addColor("turtle_text_primary", scheme.onSurface)
            addColor("turtle_text_secondary", scheme.onSurfaceSecondary)
            addColor("miuix_on_surface", scheme.onSurface)
            addColor("miuix_on_background", scheme.onBackground)
            addColor("miuix_on_background_variant", scheme.onBackgroundVariant)
            addColor("miuix_on_surface_secondary", scheme.onSurfaceSecondary)
            addColor("miuix_on_surface_variant_summary", scheme.onSurfaceVariantSummary)
            addColor("miuix_divider_line", scheme.dividerLine)
        }

        fun map(color: Int): Int? = pairs.firstOrNull { it.first == color }?.second
    }

    // ── Colour maths ─────────────────────────────────────────────────────────

    private fun Int.luminance(): Float = ColorUtils.calculateLuminance(this).toFloat()

    private fun Int.rotateHue(degrees: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(this, hsl)
        hsl[0] = (hsl[0] + degrees) % 360f
        return ColorUtils.HSLToColor(hsl)
    }

    /**
     * Expands an (accent, background, dark) seed into the complete Miuix token set.
     *
     * Contrast follows the same rule the hand-written dark scheme used: an accent light enough
     * to read as a surface gets dark ink on it instead of white, which is what keeps pastel
     * greens and amethyst buttons legible.
     */
    private object PaletteBuilder {
        fun build(
            @ColorInt accent: Int,
            @ColorInt accentAlt: Int,
            @ColorInt background: Int,
            dark: Boolean,
            surfaces: Appearance.SurfaceStyle,
            outlineScale: Float,
            highContrast: Boolean,
            accentGlow: Boolean
        ): MiuixColorScheme {
            val onAccent = if (accent.luminanceSafe() > 0.55f) inkFor(background) else Color.WHITE
            // High contrast thickens the ink itself; the surface ladder is untouched so the
            // layout keeps its hierarchy instead of turning into a wall of white text.
            val ink = when {
                highContrast && dark -> Color.WHITE
                highContrast -> 0xFF070A08.toInt()
                dark -> INK_DARK
                else -> INK_LIGHT
            }
            val inkSecondary = when {
                highContrast && dark -> 0xFFDCE5DF.toInt()
                highContrast -> 0xFF2C3532.toInt()
                dark -> INK_DARK_SECONDARY
                else -> INK_LIGHT_SECONDARY
            }
            // Depth ladder: FLAT collapses the steps, ELEVATED opens them up.
            val lift = when (surfaces) {
                Appearance.SurfaceStyle.FLAT -> 0.4f
                Appearance.SurfaceStyle.SOFT -> 1f
                Appearance.SurfaceStyle.ELEVATED -> 1.7f
            }
            val surface = if (dark) background.mix(Color.BLACK, 0.32f) else background.mix(Color.WHITE, 0.55f)
            val surfaceVariant =
                if (dark) background.mix(Color.WHITE, 0.06f * lift) else background.mix(Color.BLACK, 0.04f * lift)
            val surfaceContainer =
                if (dark) background.mix(Color.WHITE, 0.10f * lift) else background.mix(Color.WHITE, 0.75f)
            val surfaceContainerHigh =
                if (dark) background.mix(Color.WHITE, 0.14f * lift) else background.mix(Color.WHITE, 0.9f)
            val highest =
                if (dark) background.mix(Color.WHITE, 0.20f * lift) else background.mix(Color.BLACK, 0.06f * lift)
            val outline = if (dark) background.mix(Color.WHITE, 0.18f * outlineScale)
            else background.mix(Color.BLACK, 0.16f * outlineScale)
            val divider = if (dark) background.mix(Color.WHITE, 0.14f * outlineScale)
            else background.mix(Color.BLACK, 0.10f * outlineScale)
            val error = if (dark) 0xFFF12522.toInt() else 0xFFB3261E.toInt()

            return MiuixColorScheme(
                primary = accent,
                onPrimary = onAccent,
                primaryVariant = accent.mix(if (dark) Color.BLACK else Color.WHITE, 0.18f),
                onPrimaryVariant = onAccent.mix(background, 0.15f),
                primaryContainer = accent.mix(if (dark) Color.BLACK else Color.WHITE, 0.45f),
                onPrimaryContainer = onAccent,
                disabledPrimary = accent.mix(background, 0.65f),
                disabledOnPrimary = onAccent.mix(background, 0.55f),
                disabledPrimaryButton = accent.mix(background, 0.72f),
                disabledOnPrimaryButton = onAccent.mix(background, 0.55f),
                disabledPrimarySlider = accent.mix(background, 0.58f),
                secondary = surfaceContainer,
                onSecondary = ink,
                secondaryVariant = surfaceVariant,
                onSecondaryVariant = ink,
                disabledSecondary = surfaceVariant.mix(background, 0.5f),
                disabledOnSecondary = inkSecondary.mix(background, 0.4f),
                disabledSecondaryVariant = surfaceVariant.mix(background, 0.5f),
                disabledOnSecondaryVariant = inkSecondary.mix(background, 0.4f),
                secondaryContainer = surfaceContainer,
                onSecondaryContainer = inkSecondary,
                secondaryContainerVariant = surfaceContainerHigh,
                onSecondaryContainerVariant = inkSecondary.mix(ink, 0.35f),
                tertiaryContainer = accentAlt.mix(background, if (dark) 0.68f else 0.78f),
                onTertiaryContainer = if (dark) accentAlt.mix(Color.WHITE, 0.5f) else accentAlt.mix(Color.BLACK, 0.45f),
                tertiaryContainerVariant = surfaceContainerHigh,
                background = background,
                onBackground = ink,
                onBackgroundVariant = inkSecondary,
                surface = surface,
                onSurface = ink,
                surfaceVariant = surfaceVariant,
                onSurfaceSecondary = ink.mix(background, 0.18f),
                onSurfaceVariantSummary = inkSecondary,
                onSurfaceVariantActions = inkSecondary.mix(background, 0.35f),
                disabledOnSurface = inkSecondary.mix(background, 0.5f),
                surfaceContainer = surfaceContainer,
                onSurfaceContainer = ink,
                surfaceContainerHigh = surfaceContainerHigh,
                onSurfaceContainerHigh = inkSecondary,
                surfaceContainerHighest = highest,
                onSurfaceContainerHighest = ink,
                outline = outline,
                dividerLine = divider,
                windowDimming = if (dark) 0x99000000.toInt() else 0x66000000,
                error = error,
                onError = if (dark) 0xFF690005.toInt() else Color.WHITE,
                errorContainer = error.mix(background, if (dark) 0.75f else 0.85f),
                onErrorContainer = if (dark) 0xFFFFDAD6.toInt() else 0xFF410002.toInt(),
                sliderKeyPoint = accent.withAlpha(0x4D),
                sliderKeyPointForeground = accent,
                sliderBackground = ink.withAlpha(0x26),
                accentGlow = if (accentGlow) accent.withAlpha(0x40) else 0
            )
        }

        // Plain vals: `const val` cannot hold a `Long.toInt()` call.
        private val INK_DARK = 0xFFF2F2F2.toInt()
        private val INK_DARK_SECONDARY = 0xFFB3BFB7.toInt()
        private val INK_LIGHT = 0xFF14181A.toInt()
        private val INK_LIGHT_SECONDARY = 0xFF4A5350.toInt()

        /** Ink that reads on [background] - dark ink on a bright surface, light ink on a dark one. */
        private fun inkFor(background: Int): Int =
            if (background.luminanceSafe() > 0.55f) 0xFF10140F.toInt() else Color.WHITE

        private fun Int.luminanceSafe(): Float = ColorUtils.calculateLuminance(this).toFloat()

        private fun Int.mix(other: Int, ratio: Float): Int =
            ColorUtils.blendARGB(this, other, ratio.coerceIn(0f, 1f))

        private fun Int.withAlpha(alpha: Int): Int =
            Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
    }
}
