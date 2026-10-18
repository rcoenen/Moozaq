package com.moozaq

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The phone player as a sheet over Browse. [offset] is how far the player sits below the top:
 * 0 is open, [height] is closed. Dragging the mini player up pulls it open; dragging the player
 * down pushes it closed. On release a flick, or a pull past [commitDistance], finishes the move;
 * anything shorter springs back.
 */
@Stable
class PlayerSheet(
    private val scope: CoroutineScope,
    private val commitDistance: Float,
    private val reducedMotion: () -> Boolean,
) {
    var height by mutableFloatStateOf(0f)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    val visible get() = height > 0f && offset < height
    private var animation: Job? = null
    private var dragStartedOpen = false
    private var dragging = false

    fun resize(newHeight: Float) {
        if (newHeight <= 0f || newHeight == height) return
        val closed = height == 0f || offset >= height
        height = newHeight
        offset = if (closed) newHeight else offset.coerceAtMost(newHeight)
    }

    fun open() = settle(0f)
    fun close() = settle(height)

    /** Moves the sheet by [delta] pixels (positive is down) and returns how much was used. */
    fun drag(delta: Float): Float {
        if (!dragging) { dragging = true; dragStartedOpen = offset < height / 2; animation?.cancel() }
        val next = (offset + delta).coerceIn(0f, height)
        val used = next - offset
        offset = next
        return used
    }

    fun release(velocity: Float) {
        if (!dragging) return
        dragging = false
        when {
            velocity > 1500f -> close()
            velocity < -1500f -> open()
            dragStartedOpen -> if (offset > commitDistance) close() else open()
            else -> if (height - offset > commitDistance) open() else close()
        }
    }

    private fun settle(target: Float) {
        animation?.cancel()
        if (reducedMotion()) { offset = target; return }
        // The same motion as Material's bottom sheet (the Cast panel): 300 ms, fast-out-slow-in.
        animation = scope.launch { animate(offset, target, animationSpec = tween(300, easing = FastOutSlowInEasing)) { value, _ -> offset = value } }
    }

    /** For a handle that does not move with the sheet, such as the mini player or a fixed layer
     * under the player. A handle inside the moving sheet would see half the finger's travel. */
    val dragState = DraggableState { drag(it) }

    /** For the player's body when it overflows and scrolls: it scrolls first, and the sheet takes
     * over at its top. These deltas come from inside the moving sheet, so this path is a fallback. */
    val connection = object : NestedScrollConnection {
        // Dragging back up first undoes a pull before the content scrolls.
        override fun onPreScroll(available: Offset, source: NestedScrollSource) =
            if (available.y < 0 && offset > 0f) Offset(0f, drag(available.y)) else Offset.Zero

        // A downward drag the content could not use (it is at its top) pulls the player down.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
            if (source == NestedScrollSource.UserInput && available.y > 0) Offset(0f, drag(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!dragging) return Velocity.Zero
            release(available.y)
            return available
        }
    }
}

@Composable
fun rememberPlayerSheet(height: Float, reducedMotion: Boolean): PlayerSheet {
    val scope = rememberCoroutineScope()
    val commit = with(LocalDensity.current) { 120.dp.toPx() }
    val reduced by rememberUpdatedState(reducedMotion)
    val sheet = remember { PlayerSheet(scope, commit) { reduced } }
    SideEffect { sheet.resize(height) }
    return sheet
}
