package com.endiq.turtlelauncher.ui.fragment

import com.endiq.anim.AnimPlayer
import com.endiq.turtlelauncher.utils.anim.SlideAnimation
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions

abstract class FragmentWithAnim : BaseFragment, SlideAnimation {
    private var animPlayer: AnimPlayer = AnimPlayer()

    constructor() : super()

    constructor(contentLayoutId: Int) : super(contentLayoutId)

    override fun onStart() {
        super.onStart()
        // Screen motion belongs to the fragment transaction (nav_push/nav_pop in ZHTools).
        // Animating this same view here as well composed two transforms over one screen,
        // which is what made a navigation read as busy instead of as an iOS push.
        // slideIn() stays public for in-place swaps (SettingsPageSwapEvent), it is just no
        // longer fired automatically on every start.
    }

    fun slideIn() {
        playAnimation { slideIn(it) }
    }

    fun slideOut() {
        playAnimation { slideOut(it) }
    }

    override fun slideIn(animPlayer: AnimPlayer) {
        view?.let { TurtleTransitions.applyEnter(animPlayer, it) }
    }

    override fun slideOut(animPlayer: AnimPlayer) {
        view?.let { TurtleTransitions.applyExit(animPlayer, it) }
    }

    private fun playAnimation(animationAction: (AnimPlayer) -> Unit) {
        if (TurtleTransitions.isEnabled()) {
            animPlayer.clearEntries()
            animPlayer.apply {
                animationAction(this)
                start()
            }
        }
    }
}
