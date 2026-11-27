package com.endiq.turtlelauncher.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.theme.Appearance
import com.endiq.turtlelauncher.feature.theme.ThemeManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.setting.Settings
import com.endiq.turtlelauncher.setting.unit.IntSettingUnit
import com.endiq.turtlelauncher.ui.miuix.MiuixSlider
import com.endiq.turtlelauncher.ui.miuix.MiuixTheme

/**
 * Advanced appearance: the layer under the theme packs.
 *
 * The studio answers "which palette"; this answers "how is it shaped, spaced, typed and moved".
 * Everything here is a multiplier over the pack, so the two screens cannot contradict each other:
 * pick Sakura for the colours, then round it further, tighten the rhythm, lift the cards and cut
 * the motion - all without leaving the launcher.
 *
 * Built in code for the same reason as the studio: every swatch, chip and preview surface has to
 * be painted from the *draft* palette, which static XML colours cannot express. Nothing is written
 * until Apply, and Apply recreates the activity so the whole app picks the new tokens up at once.
 */
class AppearanceDialog(private val activity: FragmentActivity) :
    Dialog(activity, R.style.CustomAlertDialogTheme) {

    // Live palette of the saved theme, used for the dialog's own chrome.
    private val base = MiuixTheme.colors(activity)
    private val pack = ThemeManager.pack()
    private val dark = ThemeManager.isDark(activity)
    private val accent = ThemeManager.accent()

    // ── Draft state (nothing is saved until Apply) ────────────────────────────
    private var cornerPercent = intSetting(AllSettings.appearanceCornerScale, 100)
    private var density = Appearance.density()
    private var textPercent = intSetting(AllSettings.appearanceTextScale, 100)
    private var surfaces = Appearance.surfaces()
    private var outlinePercent = intSetting(AllSettings.appearanceOutline, 100)
    private var contrast = Appearance.contrast()
    private var motion = Appearance.motion()
    private var glow = Appearance.accentGlow()

    private lateinit var previewShell: LinearLayout
    private lateinit var previewIsland: LinearLayout
    private lateinit var previewDot: View
    private lateinit var previewIslandTitle: TextView
    private lateinit var previewCard: LinearLayout
    private lateinit var previewCardTitle: TextView
    private lateinit var previewCardBody: TextView
    private lateinit var previewPlay: TextView

    private val densityChips = mutableListOf<Pair<Appearance.Density, TextView>>()
    private val surfaceChips = mutableListOf<Pair<Appearance.SurfaceStyle, TextView>>()
    private val contrastChips = mutableListOf<Pair<Appearance.ContrastStyle, TextView>>()
    private val motionChips = mutableListOf<Pair<Appearance.MotionStyle, TextView>>()
    private lateinit var glowChip: TextView
    private lateinit var applyButton: TextView

    init {
        setContentView(buildContent())
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        refresh()
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private fun buildContent(): View {
        // A rebuild (Reset/Surprise) must not keep styling the previous sheet's chips.
        densityChips.clear()
        surfaceChips.clear()
        contrastChips.clear()
        motionChips.clear()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
        }

        root.addView(TextView(activity).apply {
            setText(R.string.appearance_title)
            setTextColor(base.onSurface)
            textSize = MiuixTheme.TypeScale.TITLE.sizeSp
            letterSpacing = MiuixTheme.TypeScale.TITLE.tracking
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(activity).apply {
            setText(R.string.appearance_subtitle)
            setTextColor(base.onSurfaceVariantSummary)
            textSize = MiuixTheme.TypeScale.CAPTION.sizeSp
            letterSpacing = MiuixTheme.TypeScale.CAPTION.tracking
            setPadding(0, dp(4), 0, dp(14))
        })

        root.addView(buildPreview(), matchWrap(bottom = dp(6)))

        // Shape: the radius ladder and the spacing rhythm.
        root.addView(sectionHeader(R.string.appearance_section_shape))
        root.addView(
            sliderRow(
                R.string.appearance_corner,
                CORNER_MIN,
                CORNER_MAX,
                cornerPercent
            ) { value ->
                cornerPercent = value
                refresh()
            },
            matchWrap()
        )
        root.addView(TextView(activity).apply {
            setText(R.string.appearance_density)
            setTextColor(base.onSurfaceSecondary)
            textSize = MiuixTheme.TypeScale.CAPTION.sizeSp
            setPadding(0, dp(10), 0, dp(6))
        })
        root.addView(chipRow(
            Appearance.Density.values().map { it to activity.getString(it.labelRes) },
            densityChips
        ) { index ->
            density = Appearance.Density.values()[index]
            refresh()
        })

        // Type.
        root.addView(sectionHeader(R.string.appearance_section_type))
        root.addView(
            sliderRow(
                R.string.appearance_text_scale,
                TEXT_MIN,
                TEXT_MAX,
                textPercent
            ) { value ->
                textPercent = value
                refresh()
            },
            matchWrap()
        )

        // Surfaces: depth, outline weight, contrast.
        root.addView(sectionHeader(R.string.appearance_section_surfaces))
        root.addView(chipRow(
            Appearance.SurfaceStyle.values().map { it to activity.getString(it.labelRes) },
            surfaceChips
        ) { index ->
            surfaces = Appearance.SurfaceStyle.values()[index]
            refresh()
        })
        root.addView(
            sliderRow(
                R.string.appearance_outline,
                OUTLINE_MIN,
                OUTLINE_MAX,
                outlinePercent
            ) { value ->
                outlinePercent = value
                refresh()
            },
            matchWrap(top = dp(10))
        )
        root.addView(chipRow(
            Appearance.ContrastStyle.values().map { it to activity.getString(it.labelRes) },
            contrastChips
        ) { index ->
            contrast = Appearance.ContrastStyle.values()[index]
            refresh()
        })

        // Motion: the global budget, plus the accent halo.
        root.addView(sectionHeader(R.string.appearance_section_motion))
        root.addView(chipRow(
            Appearance.MotionStyle.values().map { it to activity.getString(it.labelRes) },
            motionChips
        ) { index ->
            motion = Appearance.MotionStyle.values()[index]
            refresh()
        })

        glowChip = chip(activity.getString(R.string.appearance_accent_glow)) {
            glow = !glow
            refresh()
        }
        root.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(glowChip)
            addView(TextView(activity).apply {
                setText(R.string.appearance_accent_glow_summary)
                setTextColor(base.onSurfaceVariantSummary)
                textSize = MiuixTheme.TypeScale.CAPTION.sizeSp
                setPadding(dp(8), 0, 0, 0)
            })
        }, matchWrap(top = dp(4)))

        root.addView(buildActions(), matchWrap(top = dp(18)))

        return ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        }
    }

    /** A miniature of the home screen, so every slider has something visible to move. */
    private fun buildPreview(): View {
        previewShell = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        previewIsland = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewDot = View(activity)
        previewIslandTitle = TextView(activity).apply {
            setText(R.string.appearance_preview_title)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        previewIsland.addView(previewDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
            marginEnd = dp(7)
        })
        previewIsland.addView(previewIslandTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        previewIsland.addView(TextView(activity).apply {
            setText("⌄")
            setTextColor(base.onSurfaceVariantSummary)
        })

        previewCard = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        previewCardTitle = TextView(activity).apply {
            setText(R.string.appearance_preview_card_title)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        previewCardBody = TextView(activity).apply {
            setText(R.string.appearance_preview_body)
        }
        previewCard.addView(previewCardTitle)
        previewCard.addView(previewCardBody)

        previewPlay = TextView(activity).apply {
            setText(R.string.appearance_preview_button)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
        }

        previewShell.addView(previewIsland)
        previewShell.addView(previewCard, matchWrap(top = dp(8)))
        previewShell.addView(previewPlay, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(8)
        })
        return previewShell
    }

    private fun buildActions(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(toolButton(R.string.appearance_reset) { resetDraft() })
        row.addView(toolButton(R.string.appearance_surprise) { randomize() })
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(toolButton(R.string.cancel) { dismiss() })
        applyButton = TextView(activity).apply {
            setText(R.string.appearance_apply)
            textSize = MiuixTheme.TypeScale.BODY.sizeSp
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(11), dp(20), dp(11))
            setOnClickListener { applyAndRestart() }
            pressFeedback(this)
        }
        row.addView(applyButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        return row
    }

    private fun sectionHeader(textRes: Int): TextView = TextView(activity).apply {
        setText(textRes)
        setTextColor(base.onBackgroundVariant)
        textSize = MiuixTheme.TypeScale.OVERLINE.sizeSp
        letterSpacing = MiuixTheme.TypeScale.OVERLINE.tracking
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), dp(18), 0, dp(8))
    }

    /**
     * A labelled slider over a value range.
     *
     * The track itself always runs 0-100 so every row shares one visual weight regardless of the
     * real range; the label shows the mapped value, which is the number the user cares about.
     */
    private fun sliderRow(
        labelRes: Int,
        min: Int,
        max: Int,
        initial: Int,
        onChange: (Int) -> Unit
    ): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(activity).apply {
            setText(labelRes)
            setTextColor(base.onSurface)
            textSize = MiuixTheme.TypeScale.BODY.sizeSp
        }
        val value = TextView(activity).apply {
            setTextColor(base.primary)
            textSize = MiuixTheme.TypeScale.CAPTION.sizeSp
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.END
        }
        header.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(value)

        val slider = MiuixSlider(activity).apply {
            this.max = 100
            progress = toFraction(initial, min, max)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mapped = fromFraction(progress, min, max)
                value.text = "$mapped%"
                onChange(mapped)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        value.text = "$initial%"

        row.addView(header)
        row.addView(slider, matchWrap())
        return row
    }

    /** A horizontally scrollable row of single-choice chips. */
    private fun <T> chipRow(
        items: List<Pair<T, String>>,
        into: MutableList<Pair<T, TextView>>,
        onPick: (Int) -> Unit
    ): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = android.widget.HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        items.forEachIndexed { index, (value, label) ->
            val view = chip(label) { onPick(index) }
            into += value to view
            row.addView(view, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) })
        }
        return scroll
    }

    private fun chip(label: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = MiuixTheme.TypeScale.CAPTION.sizeSp
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
            pressFeedback(this)
        }

    private fun toolButton(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            setText(labelRes)
            textSize = MiuixTheme.TypeScale.CAPTION.sizeSp
            gravity = Gravity.CENTER
            setTextColor(base.onSurface)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
            pressFeedback(this)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
        }

    /**
     * Press feedback on a plain view: 0.97 scale on touch-down, back on release.
     *
     * The listener consumes the event and calls [View.performClick] itself so the view's own
     * click listener (and its accessibility action) still runs exactly once.
     */
    private fun pressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.97f).scaleY(0.97f)
                    .setDuration(MiuixTheme.MotionMs.PRESS)
                    .setInterpolator(MiuixTheme.EASE_OUT)
                    .start()

                MotionEvent.ACTION_UP -> {
                    releasePress(v)
                    v.performClick()
                }

                MotionEvent.ACTION_CANCEL -> releasePress(v)
            }
            true
        }
    }

    private fun releasePress(view: View) {
        view.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(MiuixTheme.MotionMs.PRESS)
            .setInterpolator(MiuixTheme.EASE_OUT)
            .start()
    }

    // ── State ────────────────────────────────────────────────────────────────

    private fun applyAndRestart() {
        // One write, not eight: every appearance key is part of the same decision, and a partial
        // write would leave the palette half-updated if the process died mid-save.
        Settings.Manager.put(AllSettings.appearanceCornerScale.key, cornerPercent)
            .put(AllSettings.appearanceDensity.key, density.key)
            .put(AllSettings.appearanceTextScale.key, textPercent)
            .put(AllSettings.appearanceSurfaces.key, surfaces.key)
            .put(AllSettings.appearanceOutline.key, outlinePercent)
            .put(AllSettings.appearanceContrast.key, contrast.key)
            .put(AllSettings.appearanceMotion.key, motion.key)
            .put(AllSettings.appearanceAccentGlow.key, glow)
            .save()

        ThemeManager.applyNightMode()
        dismiss()
        // Recreating re-reads every token: the palette, the corner ladder, the type scale and the
        // motion budget all resolve per screen, so this is the whole "restart" the user needs.
        activity.recreate()
    }

    private fun resetDraft() {
        cornerPercent = 100
        density = Appearance.Density.COMFORTABLE
        textPercent = 100
        surfaces = Appearance.SurfaceStyle.SOFT
        outlinePercent = 100
        contrast = Appearance.ContrastStyle.NORMAL
        motion = Appearance.MotionStyle.FULL
        glow = false
        rebuildSliders()
        refresh()
    }

    private fun randomize() {
        cornerPercent = (CORNER_MIN..CORNER_MAX).random()
        density = Appearance.Density.values().random()
        textPercent = (TEXT_MIN..TEXT_MAX).random()
        surfaces = Appearance.SurfaceStyle.values().random()
        outlinePercent = (OUTLINE_MIN..OUTLINE_MAX).random()
        contrast = Appearance.ContrastStyle.values().random()
        motion = Appearance.MotionStyle.values().random()
        glow = (0..1).random() == 1
        rebuildSliders()
        refresh()
    }

    /**
     * After Reset/Surprise the sliders have to show the new draft, not the values they were built
     * with. Rather than hunting them down, the sheet is rebuilt once - it is a dialog, not a
     * long-lived screen, and this keeps the two sliders and the chips in sync by construction.
     */
    private fun rebuildSliders() {
        setContentView(buildContent())
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    private fun refresh() {
        if (!::previewShell.isInitialized || !::glowChip.isInitialized) return

        val corner = cornerPercent / 100f
        val type = textPercent / 100f
        val scheme = ThemeManager.previewScheme(
            accent = accent,
            accentAlt = pack.accentAlt,
            background = if (dark) pack.darkBackground else pack.lightBackground,
            dark = dark,
            surfaces = surfaces,
            outlineScale = outlinePercent / 100f,
            highContrast = contrast == Appearance.ContrastStyle.HIGH,
            accentGlow = glow
        )

        // Page, island and card come straight out of the shared palette builder, so the preview
        // cannot drift from what the real screens will render.
        previewShell.background = rounded(scheme.background, 22f * corner)
        previewShell.setPadding(dp(10), dp(10), dp(10), dp(10))

        previewIsland.background = rounded(scheme.surfaceContainerHigh, 18f * corner, scheme.outline)
        previewIsland.setPadding(dp(10), dp(8), dp(10), dp(8))
        previewDot.background = rounded(scheme.primary, 999f)
        previewIslandTitle.setTextColor(scheme.onSurface)
        previewIslandTitle.textSize = 13f * type
        previewIslandTitle.letterSpacing = MiuixTheme.TypeScale.HEADLINE.tracking

        previewCard.background = rounded(scheme.surfaceContainer, 14f * corner, scheme.outline)
        previewCard.setPadding(dp(10), dp(9), dp(10), dp(9))
        previewCardTitle.setTextColor(scheme.onSurface)
        previewCardTitle.textSize = 12f * type
        previewCardBody.setTextColor(scheme.onSurfaceVariantSummary)
        previewCardBody.textSize = 10f * type
        previewCardBody.setPadding(0, dp(2), 0, 0)

        previewPlay.setTextColor(scheme.onPrimary)
        previewPlay.textSize = 12f * type
        previewPlay.setPadding(dp(18), dp(8), dp(18), dp(8))
        // The halo is the point of "accent glow": a soft ring instead of a flat fill, which reads
        // as light coming off the button rather than a second border.
        previewPlay.background = rounded(scheme.primary, 999f, if (glow) scheme.accentGlow else null)

        styleChips(densityChips, Appearance.Density.values().indexOf(density), scheme)
        styleChips(surfaceChips, Appearance.SurfaceStyle.values().indexOf(surfaces), scheme)
        styleChips(contrastChips, Appearance.ContrastStyle.values().indexOf(contrast), scheme)
        styleChips(motionChips, Appearance.MotionStyle.values().indexOf(motion), scheme)
        styleChip(glowChip, glow, scheme)

        if (::applyButton.isInitialized) applyButton.apply {
            setTextColor(scheme.onPrimary)
            background = rounded(scheme.primary, 18f * corner, if (glow) scheme.accentGlow else null)
        }
    }

    private fun <T> styleChips(chips: List<Pair<T, TextView>>, selectedIndex: Int, scheme: com.endiq.turtlelauncher.ui.miuix.MiuixColorScheme) {
        chips.forEachIndexed { index, (_, view) -> styleChip(view, index == selectedIndex, scheme) }
    }

    private fun styleChip(chip: TextView, selected: Boolean, scheme: com.endiq.turtlelauncher.ui.miuix.MiuixColorScheme) {
        chip.setTextColor(if (selected) scheme.onPrimary else scheme.onSurfaceVariantSummary)
        chip.background = rounded(
            if (selected) scheme.primary else scheme.surfaceContainer,
            14f * (cornerPercent / 100f),
            if (selected) null else scheme.outline
        )
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun toFraction(value: Int, min: Int, max: Int): Int =
        (((value - min).toFloat() / (max - min).toFloat()) * 100f).toInt().coerceIn(0, 100)

    private fun fromFraction(fraction: Int, min: Int, max: Int): Int =
        (min + (max - min) * (fraction / 100f)).toInt().coerceIn(min, max)

    private fun intSetting(unit: IntSettingUnit, fallback: Int): Int =
        runCatching { unit.getValue() }.getOrDefault(fallback)

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()

    /** Full-width row with wrap height: sliders and chip rails must span the sheet. */
    private fun matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            WRAP
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }

    companion object {
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val CORNER_MIN = 40
        private const val CORNER_MAX = 180
        private const val TEXT_MIN = 85
        private const val TEXT_MAX = 130
        private const val OUTLINE_MIN = 0
        private const val OUTLINE_MAX = 150

        @JvmStatic
        fun show(activity: FragmentActivity) {
            AppearanceDialog(activity).show()
        }

        /** Short value line for the settings row, so the section is not a mystery door. */
        @JvmStatic
        fun summary(context: Context): String = runCatching {
            val densityLabel = context.getString(Appearance.density().labelRes)
            val corner = runCatching { AllSettings.appearanceCornerScale.getValue() }.getOrDefault(100)
            val type = runCatching { AllSettings.appearanceTextScale.getValue() }.getOrDefault(100)
            "$densityLabel · $corner% · $type%"
        }.getOrDefault("")
    }
}
