package com.endiq.turtlelauncher.ui.dialog

import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogAiKeyBinding
import com.endiq.turtlelauncher.feature.ai.TurtleAiGemini
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * The assistant's API key, editable from the chat itself (top-right key button).
 *
 * The key is the one thing the assistant cannot work without, so it belongs on the screen where
 * a missing key is actually felt - not only in Settings -> Experimental. It writes the same two
 * settings that screen does: `aiGeminiApiKey` and `aiAssistantCloudBrain`.
 *
 * Nothing is written until Save: the dialog reads both settings when it opens, and Cancel
 * leaves them untouched. The checkbox is deliberately explicit - typing a key does not turn the
 * cloud brain on behind the user's back, because that is the switch that starts sending
 * questions off the device.
 */
object AiKeyDialog {

    fun show(activity: FragmentActivity) {
        val binding = DialogAiKeyBinding.inflate(LayoutInflater.from(activity))
        binding.aiKeyInput.setText(
            runCatching { AllSettings.aiGeminiApiKey.getValue() }.getOrDefault("")
        )
        binding.aiBrainToggle.isChecked =
            runCatching { AllSettings.aiAssistantCloudBrain.getValue() }.getOrDefault(false)
        binding.aiKeyInput.setSelection(binding.aiKeyInput.text?.length ?: 0)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.ai_key_title)
            .setView(binding.root)
            .setPositiveButton(R.string.ai_key_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            // Overriding the button here (instead of in the builder) keeps the dialog open when
            // the field is empty, so the message can be shown instead of a silent dismissal.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = binding.aiKeyInput.text?.toString()?.trim().orEmpty()
                if (key.isEmpty() && !TurtleAiGemini.hasBuildKey()) {
                    binding.aiKeyStatus.visibility = View.VISIBLE
                    binding.aiKeyStatus.setText(R.string.ai_key_empty)
                    return@setOnClickListener
                }
                AllSettings.aiGeminiApiKey.put(key).save()
                AllSettings.aiAssistantCloudBrain.put(binding.aiBrainToggle.isChecked).save()
                dialog.dismiss()
                Toast.makeText(activity, R.string.ai_key_saved, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }
}
