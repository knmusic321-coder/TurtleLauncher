package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogGameNoticeBinding
import com.endiq.turtlelauncher.ui.dialog.DraggableDialog.DialogInitializationListener

/**
 * The notice shown when the game opens: what was fixed/changed in this launcher version,
 * and where each new feature lives. The list is built here instead of in the layout so a
 * new release only edits [fixedItems] / [newItems] plus two strings per entry.
 */
class GameChangeNoticeDialog(context: Context, private val version: String) :
    FullScreenDialog(context), DialogInitializationListener {

    private val binding = DialogGameNoticeBinding.inflate(LayoutInflater.from(context))

    private val fixedItems = listOf(
        R.string.game_notice_item_1_title to R.string.game_notice_item_1_desc,
        R.string.game_notice_item_2_title to R.string.game_notice_item_2_desc,
        R.string.game_notice_item_3_title to R.string.game_notice_item_3_desc,
        R.string.game_notice_item_4_title to R.string.game_notice_item_4_desc
    )

    private val newItems = listOf(
        R.string.game_notice_item_5_title to R.string.game_notice_item_5_desc,
        R.string.game_notice_item_6_title to R.string.game_notice_item_6_desc,
        R.string.game_notice_item_7_title to R.string.game_notice_item_7_desc
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.closeButton.setOnClickListener { dismiss() }
        binding.noticeConfirm.setOnClickListener { dismiss() }
        binding.noticeVersion.text = version

        addSection(R.string.game_notice_fixed_header)
        fixedItems.forEach { (title, desc) -> addItem(title, desc) }
        addSection(R.string.game_notice_new_header)
        newItems.forEach { (title, desc) -> addItem(title, desc) }

        DraggableDialog.initDialog(this)
        checkHeight(binding.root, binding.noticeItems, binding.noticeScroll)
    }

    private fun addSection(titleRes: Int) {
        val title = TextView(context).apply {
            setText(titleRes)
            setTextColor(ContextCompat.getColor(context, R.color.turtle_success))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }
        binding.noticeItems.addView(title)
    }

    private fun addItem(titleRes: Int, descRes: Int) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(6), 0, 0)
        }
        container.addView(TextView(context).apply {
            setText(titleRes)
            setTextColor(ContextCompat.getColor(context, R.color.turtle_primary_text))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(context).apply {
            setText(descRes)
            setTextColor(ContextCompat.getColor(context, R.color.turtle_text_secondary))
            textSize = 12f
        })
        binding.noticeItems.addView(container)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onInit(): Window? = window
}
