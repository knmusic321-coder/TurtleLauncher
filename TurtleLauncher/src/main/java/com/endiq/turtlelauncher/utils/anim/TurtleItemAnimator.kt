package com.endiq.turtlelauncher.utils.anim

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * RecyclerView item animator used by every list in the launcher.
 *
 * A list change happens dozens of times a session, so this is deliberately the quietest
 * motion in the app: an added row rises a few dp into place, a removed row fades out with a
 * short sideways drift, a move glides between slots, and a content change cross-fades the new
 * row under the old one. Travel is small, there is no rotation and no overshoot - a bounce on
 * something seen this often reads as the list fighting the finger.
 *
 * Everything animates alpha/translation/scale only, so it stays on the compositor, and every
 * duration comes from [MotionSpec] so the motion budget and [MotionGovernor] apply.
 *
 * When motion is off, [TurtleTransitions.installMotion] simply removes the animator.
 */
class TurtleItemAnimator : SimpleItemAnimator() {

    private data class MoveInfo(
        val holder: RecyclerView.ViewHolder
    )

    private data class ChangeInfo(
        val oldHolder: RecyclerView.ViewHolder,
        val newHolder: RecyclerView.ViewHolder
    )

    private val pendingAdds = mutableListOf<RecyclerView.ViewHolder>()
    private val pendingRemoves = mutableListOf<RecyclerView.ViewHolder>()
    private val pendingMoves = mutableListOf<MoveInfo>()
    private val pendingChanges = mutableListOf<ChangeInfo>()

    /** Holder -> the dispatch call that must run when its animation ends/cancels. */
    private val running = LinkedHashMap<RecyclerView.ViewHolder, () -> Unit>()

    init {
        addDuration = MotionSpec.ITEM_ADD_MS
        removeDuration = MotionSpec.ITEM_REMOVE_MS
        moveDuration = MotionSpec.ITEM_MOVE_MS
        changeDuration = MotionSpec.ITEM_CHANGE_MS
        setSupportsChangeAnimations(true)
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        reset(holder)
        val view = holder.itemView
        view.alpha = 0f
        view.translationY = -dp(view, 16f)
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        pendingAdds.add(holder)
        return true
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        reset(holder)
        pendingRemoves.add(holder)
        return true
    }

    override fun animateMove(
        holder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        reset(holder)
        val view = holder.itemView
        // Pre-translate back to where the view was, then glide to zero.
        view.translationX = (fromX - toX + view.translationX).toFloat()
        view.translationY = (fromY - toY + view.translationY).toFloat()
        pendingMoves.add(MoveInfo(holder))
        return true
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int
    ): Boolean {
        if (oldHolder === newHolder) {
            return animateMove(oldHolder, fromX, fromY, toX, toY)
        }
        reset(oldHolder)
        reset(newHolder)
        oldHolder.itemView.alpha = 1f
        oldHolder.itemView.translationX = (fromX - toX).toFloat()
        oldHolder.itemView.translationY = (fromY - toY).toFloat()
        newHolder.itemView.alpha = 0f
        pendingChanges.add(ChangeInfo(oldHolder, newHolder))
        return true
    }

    override fun runPendingAnimations() {
        if (pendingAdds.isEmpty() && pendingRemoves.isEmpty() &&
            pendingMoves.isEmpty() && pendingChanges.isEmpty()
        ) return

        // Re-read the budget here rather than in init: the animator outlives a settings
        // change, and a reduced or adaptive budget has to shorten this run, not the next one.
        addDuration = MotionSpec.ms(MotionSpec.ITEM_ADD_MS)
        removeDuration = MotionSpec.ms(MotionSpec.ITEM_REMOVE_MS)
        moveDuration = MotionSpec.ms(MotionSpec.ITEM_MOVE_MS)
        changeDuration = MotionSpec.ms(MotionSpec.ITEM_CHANGE_MS)

        val moves = pendingMoves.toList().also { pendingMoves.clear() }
        val removes = pendingRemoves.toList().also { pendingRemoves.clear() }
        val adds = pendingAdds.toList().also { pendingAdds.clear() }
        val changes = pendingChanges.toList().also { pendingChanges.clear() }

        moves.forEachIndexed { index, info -> startMove(info, delayFor(index, 10L)) }
        removes.forEachIndexed { index, holder -> startRemove(holder, delayFor(index, 8L)) }
        adds.forEachIndexed { index, holder -> startAdd(holder, delayFor(index, 18L)) }
        changes.forEachIndexed { index, info -> startChange(info, delayFor(index, 14L)) }

        if (running.isEmpty()) dispatchAnimationsFinished()
    }

    /**
     * Stagger that stops growing after [MotionSpec.MAX_STAGGERED_ROWS]. An unbounded
     * `index * step` turns a 20-row refresh into a half-second queue where the last rows
     * animate long after the user has looked away.
     */
    private fun delayFor(index: Int, stepMs: Long): Long =
        MotionSpec.ms(index.coerceAtMost(MotionSpec.MAX_STAGGERED_ROWS) * stepMs)

    private fun startAdd(holder: RecyclerView.ViewHolder, delay: Long) {
        val view = holder.itemView
        track(holder) { dispatchAddFinished(holder) }
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delay)
            .setDuration(addDuration)
            .setInterpolator(MotionSpec.EASE_OUT)
            .withEndAction { finish(holder) { dispatchAddFinished(holder) } }
            .start()
    }

    private fun startRemove(holder: RecyclerView.ViewHolder, delay: Long) {
        val view = holder.itemView
        val direction = if (holder.bindingAdapterPosition % 2 == 0) 1f else -1f
        track(holder) { dispatchRemoveFinished(holder) }
        view.animate()
            .alpha(0f)
            .translationX(direction * dp(view, 20f))
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setStartDelay(delay)
            .setDuration(removeDuration)
            .setInterpolator(MotionSpec.EASE_OUT)
            .withEndAction { finish(holder) { dispatchRemoveFinished(holder) } }
            .start()
    }

    private fun startMove(info: MoveInfo, delay: Long) {
        val holder = info.holder
        val view = holder.itemView
        track(holder) { dispatchMoveFinished(holder) }
        view.animate()
            .translationX(0f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(moveDuration)
            .setInterpolator(MotionSpec.EASE_IN_OUT)
            .withEndAction { finish(holder) { dispatchMoveFinished(holder) } }
            .start()
    }

    private fun startChange(info: ChangeInfo, delay: Long) {
        val oldHolder = info.oldHolder
        val newHolder = info.newHolder
        val oldView = oldHolder.itemView
        val newView = newHolder.itemView
        track(oldHolder) { dispatchChangeFinished(oldHolder, true) }
        track(newHolder) { dispatchChangeFinished(newHolder, false) }
        oldView.animate()
            .alpha(0f)
            .translationX(oldView.translationX + dp(oldView, 8f))
            .setStartDelay(delay)
            .setDuration(changeDuration)
            .setInterpolator(MotionSpec.EASE_OUT)
            .withEndAction { finish(oldHolder) { dispatchChangeFinished(oldHolder, true) } }
            .start()
        newView.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(changeDuration)
            .setInterpolator(MotionSpec.EASE_OUT)
            .withEndAction { finish(newHolder) { dispatchChangeFinished(newHolder, false) } }
            .start()
    }

    private fun track(holder: RecyclerView.ViewHolder, dispatch: () -> Unit) {
        running[holder] = dispatch
    }

    private fun finish(holder: RecyclerView.ViewHolder, dispatch: () -> Unit) {
        if (running.remove(holder) != null) {
            reset(holder)
            dispatch()
            if (running.isEmpty()) dispatchAnimationsFinished()
        }
    }

    override fun endAnimation(holder: RecyclerView.ViewHolder) {
        holder.itemView.animate().cancel()
        val dispatch = running.remove(holder)
        reset(holder)
        if (dispatch != null) dispatch()
        if (running.isEmpty()) dispatchAnimationsFinished()
    }

    override fun endAnimations() {
        val pendingAddHolders = pendingAdds.toList()
        val pendingRemoveHolders = pendingRemoves.toList()
        val pendingMoveHolders = pendingMoves.map { it.holder }
        val pendingChangesSnapshot = pendingChanges.toList()
        pendingAdds.clear()
        pendingRemoves.clear()
        pendingMoves.clear()
        pendingChanges.clear()

        for (holder in pendingAddHolders) {
            reset(holder)
            dispatchAddFinished(holder)
        }
        for (holder in pendingRemoveHolders) {
            reset(holder)
            dispatchRemoveFinished(holder)
        }
        for (holder in pendingMoveHolders) {
            reset(holder)
            dispatchMoveFinished(holder)
        }
        for (info in pendingChangesSnapshot) {
            reset(info.oldHolder)
            dispatchChangeFinished(info.oldHolder, true)
            reset(info.newHolder)
            dispatchChangeFinished(info.newHolder, false)
        }

        // Snapshot: dispatch callbacks can re-enter.
        val active = running.entries.toList()
        running.clear()
        for ((holder, dispatch) in active) {
            holder.itemView.animate().cancel()
            reset(holder)
            dispatch()
        }
        dispatchAnimationsFinished()
    }

    override fun isRunning(): Boolean = running.isNotEmpty() ||
        pendingAdds.isNotEmpty() || pendingRemoves.isNotEmpty() ||
        pendingMoves.isNotEmpty() || pendingChanges.isNotEmpty()

    private fun reset(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun dp(view: View, value: Float): Float =
        value * view.resources.displayMetrics.density
}
