package com.endiq.turtlelauncher.feature.theme

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * Advanced appearance: the knobs that sit *under* a theme pack.
 *
 * A pack answers "which palette"; this answers "how is that palette shaped, spaced, typed and
 * moved". Every value here is a multiplier or a small enum rather than an absolute, so a pack
 * stays the single source of truth for colour and these only modulate it - which is what keeps
 * nine packs and eight sliders from producing an unusable interface.
 *
 * The whole object is a pure read of [AllSettings], so a screen can ask it per view without
 * worrying about staleness; the settings file is refreshed on every activity attach.
 */
object Appearance {

    /** Spacing rhythm. A denser rhythm fits more rows on a phone; a looser one reads calmer. */
    enum class Density(
        val key: String,
        @StringRes val labelRes: Int,
        /** Multiplier applied to every spacing token. */
        val scale: Float
    ) {
        COMPACT("compact", R.string.appearance_density_compact, 0.82f),
        COMFORTABLE("comfortable", R.string.appearance_density_comfortable, 1f),
        SPACIOUS("spacious", R.string.appearance_density_spacious, 1.22f);

        companion object {
            @JvmStatic
            fun fromKey(key: String?): Density =
                values().firstOrNull { it.key == key } ?: COMFORTABLE
        }
    }

    /**
     * How much depth a surface carries.
     *
     * `FLAT` removes the second elevation step (everything sits on one plane), `SOFT` is the
     * shipped look (a card lifts a little off the page), `ELEVATED` pushes cards further with a
     * brighter top edge - the "material thicker than the page" reading.
     */
    enum class SurfaceStyle(
        val key: String,
        @StringRes val labelRes: Int
    ) {
        FLAT("flat", R.string.appearance_surfaces_flat),
        SOFT("soft", R.string.appearance_surfaces_soft),
        ELEVATED("elevated", R.string.appearance_surfaces_elevated);

        companion object {
            @JvmStatic
            fun fromKey(key: String?): SurfaceStyle =
                values().firstOrNull { it.key == key } ?: SOFT
        }
    }

    /** `HIGH` thickens ink and dividers for direct sunlight and low-quality panels. */
    enum class ContrastStyle(
        val key: String,
        @StringRes val labelRes: Int
    ) {
        NORMAL("normal", R.string.appearance_contrast_normal),
        HIGH("high", R.string.appearance_contrast_high);

        companion object {
            @JvmStatic
            fun fromKey(key: String?): ContrastStyle =
                values().firstOrNull { it.key == key } ?: NORMAL
        }
    }

    /**
     * Global motion budget.
     *
     * `REDUCED` keeps every transition but shortens it and drops the decorative stagger, which
     * is what reduced motion should be: fewer and gentler, not none. `OFF` is the escape hatch
     * for a device that cannot hold 60fps.
     */
    enum class MotionStyle(
        val key: String,
        @StringRes val labelRes: Int,
        val scale: Float
    ) {
        FULL("full", R.string.appearance_motion_full, 1f),
        REDUCED("reduced", R.string.appearance_motion_reduced, 0.55f),
        OFF("off", R.string.appearance_motion_off, 0f);

        companion object {
            @JvmStatic
            fun fromKey(key: String?): MotionStyle =
                values().firstOrNull { it.key == key } ?: FULL
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @JvmStatic
    fun density(): Density = Density.fromKey(
        runCatching { AllSettings.appearanceDensity.getValue() }.getOrDefault("")
    )

    @JvmStatic
    fun surfaces(): SurfaceStyle = SurfaceStyle.fromKey(
        runCatching { AllSettings.appearanceSurfaces.getValue() }.getOrDefault("")
    )

    @JvmStatic
    fun contrast(): ContrastStyle = ContrastStyle.fromKey(
        runCatching { AllSettings.appearanceContrast.getValue() }.getOrDefault("")
    )

    @JvmStatic
    fun motion(): MotionStyle = MotionStyle.fromKey(
        runCatching { AllSettings.appearanceMotion.getValue() }.getOrDefault("")
    )

    /** Corner multiplier, 0.4x - 1.8x. Clamped so no setting can square off or balloon a card. */
    @JvmStatic
    fun cornerScale(): Float = percent(AllSettings.appearanceCornerScale, 100, 40, 180)

    /** Outline/dividider strength, 0 - 150% of the palette's own hairline. */
    @JvmStatic
    fun outlineScale(): Float = percent(AllSettings.appearanceOutline, 100, 0, 150)

    /** Type multiplier, 85 - 130% on top of the device's own font scale. */
    @JvmStatic
    fun textScale(): Float = percent(AllSettings.appearanceTextScale, 100, 85, 130)

    /** A soft accent halo behind primary buttons instead of a flat fill. */
    @JvmStatic
    fun accentGlow(): Boolean = runCatching {
        AllSettings.appearanceAccentGlow.getValue()
    }.getOrDefault(false)

    /** Whether transitions should run at all. */
    @JvmStatic
    fun motionEnabled(): Boolean = motion() != MotionStyle.OFF

    /** Multiplier for any duration the launcher computes itself. */
    @JvmStatic
    fun motionScale(): Float = motion().scale

    /**
     * Applies the motion budget to a duration.
     *
     * Kept here rather than in each animation call site so "reduced motion" is one decision,
     * not a hundred independent ones that can drift apart. The adaptive factor from
     * [com.endiq.turtlelauncher.utils.anim.MotionGovernor] multiplies on top: it is measured
     * jank, not a preference, so it has to reach every duration without a second code path.
     */
    @JvmStatic
    fun duration(baseMs: Long): Long {
        val scale = motionScale() * com.endiq.turtlelauncher.utils.anim.MotionGovernor.factor()
        if (scale <= 0f) return 0L
        return (baseMs * scale).toLong().coerceAtLeast(1L)
    }

    /** Spacing token scaled by the chosen density rhythm. */
    @JvmStatic
    fun spacing(context: Context, baseDp: Float): Int =
        (baseDp * density().scale * context.resources.displayMetrics.density).toInt()

    /**
     * Applies the type multiplier on top of whatever the user already chose in Android's
     * settings (Dynamic Type), instead of replacing it: a device set to 1.15 and a launcher set
     * to 110% ends up at 1.265, which is what the user asked for in both places.
     */
    @JvmStatic
    fun wrapTextScale(context: Context): Context {
        val scale = textScale()
        if (scale == 1f) return context
        return runCatching {
            val configuration = Configuration(context.resources.configuration)
            configuration.fontScale = (configuration.fontScale * scale).coerceIn(0.7f, 2.0f)
            context.createConfigurationContext(configuration)
        }.getOrDefault(context)
    }

    private fun percent(
        unit: com.endiq.turtlelauncher.setting.unit.IntSettingUnit,
        fallback: Int,
        min: Int,
        max: Int
    ): Float {
        val raw = runCatching { unit.getValue() }.getOrDefault(fallback)
        return (raw.coerceIn(min, max) / 100f)
    }
}
