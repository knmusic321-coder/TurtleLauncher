package com.endiq.turtlelauncher.ui.fragment

import androidx.fragment.app.Fragment
import com.endiq.turtlelauncher.feature.theme.ThemeManager
import com.endiq.turtlelauncher.utils.anim.TurtleTransitions
import net.endiq.launcher.progresskeeper.ProgressKeeper
import net.endiq.launcher.progresskeeper.TaskCountListener

abstract class BaseFragment : Fragment, TaskCountListener {
    private var mIsTaskRunning: Boolean = false

    constructor() : super()

    constructor(contentLayoutId: Int) : super(contentLayoutId)

    open fun onBackPressed(): Boolean = true

    fun isTaskRunning() = mIsTaskRunning

    fun forceBack() {
        requireActivity().supportFragmentManager.popBackStackImmediate()
    }

    override fun onStart() {
        super.onStart()
        ProgressKeeper.addTaskCountListener(this)
        // Every screen gets the shared list motion (item animator + staggered first
        // population) without each fragment having to remember to wire its own lists.
        view?.let { TurtleTransitions.installMotion(it) }
        // Theme packs: layouts still reference fixed resource colours, so a light pack swaps the
        // brand ink on the views that used the old near-white values. A no-op in dark mode.
        view?.let { root -> ThemeManager.reconcileLegacyColors(requireContext(), root) }
    }

    override fun onStop() {
        super.onStop()
        ProgressKeeper.removeTaskCountListener(this)
    }

    override fun onUpdateTaskCount(taskCount: Int) {
        this.mIsTaskRunning = taskCount != 0
    }
}