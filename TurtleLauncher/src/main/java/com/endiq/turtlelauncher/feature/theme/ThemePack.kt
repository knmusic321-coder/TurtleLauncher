package com.endiq.turtlelauncher.feature.theme

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.endiq.turtlelauncher.R

/**
 * The launcher's built-in theme packs.
 *
 * A pack is a *seed*, not a full palette: it names the accent, the background family and a few
 * personality flags (glow, drifting stars, frosted glass) and [ThemeManager] expands that seed
 * into the ~50 Miuix colour tokens the UI draws with. That is what lets a custom accent colour
 * work without anybody hand-writing fifty tokens per theme.
 */
enum class ThemePack(
    val key: String,
    @StringRes val labelRes: Int,
    /** Interactive/brand colour of the pack. */
    @ColorInt val accent: Int,
    /** Secondary hue used for containers, chips and gradients. */
    @ColorInt val accentAlt: Int,
    /** Background the pack uses in dark mode. */
    @ColorInt val darkBackground: Int,
    /** Background the pack uses in light mode (cream/white families). */
    @ColorInt val lightBackground: Int,
    /** Multiplier applied to the standard corner radii - soft packs round more. */
    val cornerScale: Float,
    /** Buttons keep a warm halo, like lava under a Nether button. */
    val glow: Boolean = false,
    /** Slow drifting star particles behind the content (End Void). */
    val starfield: Boolean = false,
    /** Frosted-glass panels instead of flat surfaces (Amethyst). */
    val glass: Boolean = false
) {
    OVERWORLD(
        key = "overworld",
        labelRes = R.string.theme_pack_overworld,
        // Pastel green rather than a saturated one: it is the launcher's existing brand accent,
        // and it stays readable as a surface over both the cream and the forest background.
        accent = 0xFF7FD79E.toInt(),
        accentAlt = 0xFF4C9A5A.toInt(),
        darkBackground = 0xFF1B241D.toInt(),
        lightBackground = 0xFFF4F1E1.toInt(),
        cornerScale = 1.0f
    ),
    NETHER_DARK(
        key = "nether",
        labelRes = R.string.theme_pack_nether,
        accent = 0xFFFF6A2B.toInt(),
        accentAlt = 0xFFFFC04D.toInt(),
        darkBackground = 0xFF1C0F0B.toInt(),
        lightBackground = 0xFF2A1712.toInt(),
        cornerScale = 1.0f,
        glow = true
    ),
    END_VOID(
        key = "end",
        labelRes = R.string.theme_pack_end,
        accent = 0xFF9B7CF0.toInt(),
        accentAlt = 0xFF6C4FD1.toInt(),
        darkBackground = 0xFF08060F.toInt(),
        lightBackground = 0xFF191430.toInt(),
        cornerScale = 1.0f,
        starfield = true
    ),
    AMETHYST(
        key = "amethyst",
        labelRes = R.string.theme_pack_amethyst,
        accent = 0xFFC99BE8.toInt(),
        accentAlt = 0xFFF0B6D8.toInt(),
        darkBackground = 0xFF221A2B.toInt(),
        lightBackground = 0xFFF6EEFB.toInt(),
        cornerScale = 1.3f,
        glass = true
    ),
    REDSTONE(
        key = "redstone",
        labelRes = R.string.theme_pack_redstone,
        accent = 0xFFE03B2F.toInt(),
        accentAlt = 0xFF9AA0A6.toInt(),
        darkBackground = 0xFF191C1F.toInt(),
        lightBackground = 0xFFE9EBED.toInt(),
        cornerScale = 0.75f
    ),
    VANILLA_LIGHT(
        key = "vanilla_light",
        labelRes = R.string.theme_pack_vanilla,
        accent = 0xFF3F7D4F.toInt(),
        accentAlt = 0xFFB9A98A.toInt(),
        darkBackground = 0xFF3A3A36.toInt(),
        lightBackground = 0xFFFBF8F1.toInt(),
        cornerScale = 1.0f
    );

    companion object {
        @JvmStatic
        fun fromKey(key: String?): ThemePack =
            values().firstOrNull { it.key == key } ?: OVERWORLD

        /** Packs that read well on a light background even in dark mode's absence. */
        @JvmStatic
        fun labels(): Array<String> = values().map { it.key }.toTypedArray()
    }
}

/**
 * Icon pack: the shape language applied to the launcher's icon-bearing surfaces.
 *
 * Each pack scales the standard corner radius of cards, buttons and icon tiles, which is what
 * the pack previews show: Classic keeps Turtle's soft squircles, Minimal rounds further into
 * pills, Pixel squares everything off. The drawable set itself is not swapped - a full
 * alternate icon set would be a separate asset pack.
 */
enum class IconPack(
    val key: String,
    @StringRes val labelRes: Int,
    val cornerScale: Float
) {
    CLASSIC("classic", R.string.icon_pack_classic, 1.0f),
    MINIMAL("minimal", R.string.icon_pack_minimal, 1.7f),
    PIXEL("pixel", R.string.icon_pack_pixel, 0.15f);

    companion object {
        @JvmStatic
        fun fromKey(key: String?): IconPack =
            values().firstOrNull { it.key == key } ?: CLASSIC
    }
}

/**
 * How the launcher resolves dark/light for the current pack.
 * `SYSTEM` follows the device, the other two force a variant of whatever pack is selected.
 */
enum class ThemeMode(val key: String, @StringRes val labelRes: Int) {
    SYSTEM("system", R.string.setting_launcher_theme_system),
    LIGHT("light", R.string.setting_launcher_theme_light),
    DARK("dark", R.string.setting_launcher_theme_dark);

    companion object {
        @JvmStatic
        fun fromKey(key: String?): ThemeMode =
            values().firstOrNull { it.key == key } ?: SYSTEM
    }
}
