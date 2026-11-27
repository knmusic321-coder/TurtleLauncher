package com.endiq.turtlelauncher.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.theme.IconPack
import com.endiq.turtlelauncher.feature.theme.ThemeManager
import com.endiq.turtlelauncher.feature.theme.ThemeMode
import com.endiq.turtlelauncher.feature.theme.ThemePack
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.miuix.MiuixTheme

/**
 * The theme studio: pick one of the built-in packs, override the accent colour, choose how
 * light/dark is resolved and pick an icon treatment.
 *
 * Built in code rather than a layout file on purpose - every swatch, chip and card has to be
 * painted from the *live* [ThemeManager] scheme, which is exactly what static XML colours cannot
 * do. Applying recreates the activity, so every screen picks the new palette up at once.
 */
class ThemeStudioDialog(private val activity: FragmentActivity) :
    Dialog(activity, R.style.CustomAlertDialogTheme) {

    private val scheme = MiuixTheme.colors(activity)
    private val baseColors = scheme

    private var selectedPack: ThemePack = ThemeManager.pack()
    private var selectedMode: ThemeMode = ThemeManager.mode()
    private var selectedIconPack: IconPack = ThemeManager.iconPack()
    private var accentValue: String = currentAccentRaw()

    private lateinit var previewContainer: LinearLayout
    private lateinit var previewCard: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewButton: TextView
    private lateinit var previewBody: TextView
    private lateinit var packGrid: GridLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var iconRow: LinearLayout
    private lateinit var accentRow: LinearLayout
    private lateinit var accentInput: EditText
    private val swatches = mutableListOf<Pair<Int, View>>()

    init {
        setTitle(R.string.theme_studio_title)
        setContentView(buildContent())
        refresh()
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private fun buildContent(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }

        root.addView(buildPreview(), matchWrap(bottom = dp(16)))
        root.addView(sectionHeader(R.string.theme_studio_presets))

        packGrid = GridLayout(activity).apply { columnCount = 2 }
        ThemePack.values().forEach { pack -> packGrid.addView(buildPackCard(pack), gridParams()) }
        root.addView(packGrid, matchWrap())

        root.addView(sectionHeader(R.string.theme_studio_accent))
        accentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(accentRow)
        }, matchWrap())
        ThemeManager.ACCENT_SWATCHES.forEach { color ->
            val swatch = View(activity).apply {
                setOnClickListener {
                    accentValue = hex(color)
                    refresh()
                }
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
            }
            swatches += color to swatch
            accentRow.addView(swatch)
        }

        root.addView(buildAccentInput(), matchWrap(top = dp(10)))

        root.addView(sectionHeader(R.string.theme_studio_mode))
        modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        ThemeMode.values().forEach { mode ->
            modeRow.addView(buildChip(activity.getString(mode.labelRes)) {
                selectedMode = mode
                refresh()
            })
        }
        root.addView(modeRow, matchWrap())

        root.addView(sectionHeader(R.string.theme_studio_icon_pack))
        iconRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        IconPack.values().forEach { pack -> iconRow.addView(buildIconChip(pack)) }
        root.addView(iconRow, matchWrap())

        root.addView(buildToolsRow(), matchWrap(top = dp(14)))

        root.addView(buildActions(), matchWrap(top = dp(20)))

        return ScrollView(activity).apply { addView(root) }
    }

    /** A miniature of the launcher chrome: page background, a card with body text, a button. */
    private fun buildPreview(): View {
        previewContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        previewTitle = TextView(activity).apply {
            text = activity.getString(R.string.theme_studio_preview)
            textSize = 11f
        }
        previewCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        previewCard.addView(TextView(activity).apply {
            text = activity.getString(R.string.theme_studio_preview_title)
            textSize = 14f
        })
        previewBody = TextView(activity).apply {
            text = activity.getString(R.string.theme_studio_preview_body)
            textSize = 11f
        }
        previewCard.addView(previewBody)
        previewButton = TextView(activity).apply {
            text = activity.getString(R.string.theme_studio_preview_button)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }

        previewContainer.addView(previewTitle, matchWrap(bottom = dp(8)))
        previewContainer.addView(previewCard, matchWrap(bottom = dp(10)))
        previewContainer.addView(previewButton, LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT))
        return previewContainer
    }

    private fun buildPackCard(pack: ThemePack): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            tag = pack
            setOnClickListener {
                selectedPack = pack
                refresh()
            }
        }
        val swatch = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(pack.darkBackground)
                setStroke(dp(2), pack.accent)
            }
        }
        card.addView(swatch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
        card.addView(TextView(activity).apply {
            text = activity.getString(pack.labelRes)
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })
        return card
    }

    private fun buildAccentInput(): View {
        accentInput = EditText(activity).apply {
            hint = activity.getString(R.string.theme_studio_accent_hint)
            setSingleLine(true)
            textSize = 13f
            setTextColor(baseColors.onSurface)
            setHintTextColor(baseColors.onSurfaceVariantSummary)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        accentInput.setText(accentValue)
        accentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                accentValue = s?.toString().orEmpty()
                refresh()
            }
        })
        return accentInput
    }

    private fun buildChip(label: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

    /**
     * Surprise, reset, and the door to Advanced appearance.
     *
     * The studio owns colour; the advanced sheet owns shape, spacing, type, surfaces and motion.
     * Keeping the two one tap apart is what lets a pack stay a palette instead of growing fifty
     * sliders of its own.
     */
    private fun buildToolsRow(): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(buildToolButton(R.string.theme_studio_advanced) { openAdvanced() })
        row.addView(buildToolButton(R.string.theme_studio_random) { randomize() })
        row.addView(buildToolButton(R.string.theme_studio_reset) { resetToDefaults() })
        return row
    }

    /** Hands over to the advanced sheet; the studio closes so only one sheet is ever open. */
    private fun openAdvanced() {
        dismiss()
        AppearanceDialog.show(activity)
    }

    private fun buildToolButton(labelRes: Int, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            setText(labelRes)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(baseColors.onSurface)
            background = rounded(baseColors.surfaceContainer, 14f, baseColors.outline)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

    /** An icon-pack chip carrying a small tile drawn at that pack's own corner radius. */
    private fun buildIconChip(pack: IconPack): TextView =
        buildChip(activity.getString(pack.labelRes)) {
            selectedIconPack = pack
            refresh()
        }.apply {
            val tile = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9f * pack.cornerScale).toFloat()
                setColor(baseColors.primary)
            }
            tile.setBounds(0, 0, dp(16), dp(16))
            setCompoundDrawablesRelative(tile, null, null, null)
            compoundDrawablePadding = dp(6)
        }

    private fun randomize() {
        selectedPack = ThemePack.values().random()
        selectedIconPack = IconPack.values().random()
        accentInput.setText(hex(ThemeManager.ACCENT_SWATCHES.random()))
    }

    private fun resetToDefaults() {
        selectedPack = ThemePack.OVERWORLD
        selectedMode = ThemeMode.DARK
        selectedIconPack = IconPack.CLASSIC
        accentInput.setText("")
    }

    private fun buildActions(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = TextView(activity).apply {
            text = activity.getString(R.string.cancel)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(baseColors.onSurfaceVariantSummary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { dismiss() }
        }
        val apply = TextView(activity).apply {
            text = activity.getString(R.string.theme_studio_apply)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(baseColors.onPrimary)
            background = rounded(baseColors.primary, 12f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { applyAndRestart() }
        }
        row.addView(cancel)
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(apply)
        return row
    }

    // ── State ────────────────────────────────────────────────────────────────

    private fun applyAndRestart() {
        AllSettings.launcherThemePack.put(selectedPack.key).save()
        AllSettings.launcherIconPack.put(selectedIconPack.key).save()
        AllSettings.launcherTheme.put(selectedMode.key).save()
        AllSettings.launcherAccentColor.put(normalizeAccent(accentValue)).save()

        ThemeManager.applyNightMode()
        dismiss()
        // Recreating re-reads every colour: MiuixTheme.colors() resolves per view tree, so this
        // is what shows the new palette without a full app restart.
        activity.recreate()
    }

    private fun refresh() {
        // The accent field's text watcher fires while the dialog is still being built (mode/icon
        // rows do not exist yet); repaint only once every part is in place.
        if (!::previewContainer.isInitialized || !::previewButton.isInitialized ||
            !::packGrid.isInitialized || !::modeRow.isInitialized ||
            !::iconRow.isInitialized || !::accentInput.isInitialized
        ) {
            return
        }
        val accent = resolvedAccent()
        previewContainer.background = rounded(
            effectiveBackground(),
            MiuixTheme.CornerRadiiDp.PANEL,
            baseColors.outline
        )
        previewTitle.setTextColor(baseColors.onBackgroundVariant)
        previewCard.background = rounded(
            previewCardColor(),
            MiuixTheme.CornerRadiiDp.CARD,
            baseColors.outline
        )
        (previewCard.getChildAt(0) as? TextView)?.setTextColor(previewInk())
        previewBody.setTextColor(previewInkSecondary())
        previewButton.setTextColor(onAccent())
        // Nether Dark keeps a warm halo around the button; every other pack uses a flat fill.
        previewButton.background = rounded(
            accent,
            MiuixTheme.CornerRadiiDp.BUTTON,
            if (selectedPack.glow) accent else null
        )

        packGrid.children().forEach { card ->
            val pack = card.tag as? ThemePack ?: return@forEach
            card.background = rounded(
                if (pack == selectedPack) baseColors.surfaceContainerHigh else baseColors.surfaceContainer,
                MiuixTheme.CornerRadiiDp.CARD,
                if (pack == selectedPack) accent else baseColors.outline
            )
            ((card as? ViewGroup)?.getChildAt(1) as? TextView)?.setTextColor(
                if (pack == selectedPack) baseColors.onSurface else baseColors.onSurfaceVariantSummary
            )
        }

        modeRow.children().forEachIndexed { index, chip ->
            styleChip(chip as TextView, ThemeMode.values()[index] == selectedMode, accent)
        }
        iconRow.children().forEachIndexed { index, chip ->
            styleChip(chip as TextView, IconPack.values()[index] == selectedIconPack, accent)
        }

        val normalized = normalizeAccent(accentValue)
        swatches.forEach { (color, swatch) ->
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(2), if (hex(color) == normalized) baseColors.onSurface else Color.TRANSPARENT)
            }
        }
        accentInput.background = rounded(baseColors.surfaceContainer, 12f, baseColors.outline)
    }

    private fun styleChip(chip: TextView, selected: Boolean, accent: Int) {
        chip.setTextColor(if (selected) onAccent() else baseColors.onSurfaceVariantSummary)
        chip.background = rounded(
            if (selected) accent else baseColors.surfaceContainer,
            MiuixTheme.CornerRadiiDp.TAB_ROW,
            if (selected) accent else baseColors.outline
        )
    }

    // ── Colour resolution for the live preview ───────────────────────────────

    private fun resolvedAccent(): Int =
        runCatching { Color.parseColor(normalizeAccent(accentValue)) }.getOrDefault(selectedPack.accent)

    private fun effectiveBackground(): Int =
        if (previewIsLight()) selectedPack.lightBackground else selectedPack.darkBackground

    private fun previewIsLight(): Boolean = when (selectedMode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
        ThemeMode.SYSTEM -> false
    }

    private fun previewCardColor(): Int = blend(effectiveBackground(), if (previewIsLight()) Color.WHITE else Color.WHITE, 0.12f)
    private fun previewInk(): Int = if (previewIsLight()) 0xFF14181A.toInt() else 0xFFF2F2F2.toInt()
    private fun previewInkSecondary(): Int = if (previewIsLight()) 0xFF4A5350.toInt() else 0xFFB3BFB7.toInt()
    private fun onAccent(): Int =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(resolvedAccent()) > 0.55f) {
            0xFF10140F.toInt()
        } else Color.WHITE

    private fun blend(base: Int, over: Int, ratio: Float): Int =
        androidx.core.graphics.ColorUtils.blendARGB(base, over, ratio)

    private fun normalizeAccent(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withHash = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return runCatching { hex(Color.parseColor(withHash)) }.getOrDefault("")
    }

    private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun currentAccentRaw(): String = runCatching {
        AllSettings.launcherAccentColor.getValue()
    }.getOrDefault("")

    // ── small view helpers ───────────────────────────────────────────────────

    private fun sectionHeader(textRes: Int): TextView = TextView(activity).apply {
        setText(textRes)
        textSize = 11f
        setTextColor(baseColors.onBackgroundVariant)
        setPadding(dp(2), dp(14), 0, dp(6))
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun gridParams(): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = 0
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun ViewGroup.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    companion object {
        @JvmStatic
        fun show(activity: FragmentActivity) {
            ThemeStudioDialog(activity).show()
        }

        /** Human-readable name of the current pack, for the settings row's value text. */
        @JvmStatic
        fun currentPackLabel(context: Context): String =
            context.getString(ThemeManager.pack().labelRes)
    }
}
