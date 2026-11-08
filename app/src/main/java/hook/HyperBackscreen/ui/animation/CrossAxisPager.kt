// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hook.HyperBackscreen.ui.animation

// Adapted from Miuix v0.9.4, commit 39c40f99 (Apache-2.0).
// Acquire pager motion only after horizontal intent is confirmed. Child taps/vertical
// drags must not cancel an in-flight page transition (upstream follow-up 2afdbb39).

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.pageLeft
import androidx.compose.ui.semantics.pageRight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.Duration.Companion.milliseconds

/**
 * Spring spec shared by pager tab navigation and snap fling.
 */
val CrossAxisPagerSpringSpec: SpringSpec<Float> = spring(
    stiffness = 322.2f,
    dampingRatio = 32.31f / (2f * kotlin.math.sqrt(322.2f)),
    visibilityThreshold = 0.5f,
)

/**
 * Animates to [target] using [CrossAxisPagerSpringSpec] after the first layout.
 * Uses [MutatePriority.UserInput] so focus scrolling cannot interrupt the animation.
 */
suspend fun PagerState.springToPage(target: Int) {
    if (target !in 0 until pageCount) return
    scroll(MutatePriority.UserInput) {
        animateToPage(this, target)
    }
}

private suspend fun PagerState.animateToPage(
    scrollScope: ScrollScope,
    target: Int,
    initialVelocity: Float = 0f,
) = with(scrollScope) {
    if (pageCount == 0) return@with
    val destination = target.coerceIn(0, pageCount - 1)
    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
    updateTargetPage(destination)
    if (pageSize > 0) {
        val distance = (destination - currentPage - currentPageOffsetFraction) * pageSize
        var previousValue = 0f
        animate(
            initialValue = 0f,
            targetValue = distance,
            initialVelocity = initialVelocity,
            animationSpec = CrossAxisPagerSpringSpec,
        ) { currentValue, _ ->
            previousValue += scrollBy(currentValue - previousValue)
        }
    }
    // Complete within this mutation so cancellation cannot snap a newer gesture to an old target.
    if (pageCount > 0) updateCurrentPage(destination.coerceAtMost(pageCount - 1))
}

/**
 * Leaves vertical scroll and velocity to page content, including between pages.
 *
 * Use as [HorizontalPager]'s pageNestedScrollConnection with [crossAxisPagerGestures].
 */
object CrossAxisPagerNestedScrollConnection : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(available.x, 0f)
}

/**
 * Drives horizontal drag and settling in one [PagerState.scroll] mutation.
 * New touches take over at the displayed position; vertical gestures stay with children.
 *
 * Set [HorizontalPager]'s userScrollEnabled to false and its pageNestedScrollConnection to
 * [CrossAxisPagerNestedScrollConnection] while enabled to prevent competing gesture recognition.
 */
fun Modifier.crossAxisPagerGestures(
    pagerState: PagerState,
    enabled: Boolean = true,
    onIntercepted: (() -> Unit)? = null,
): Modifier = if (!enabled) this else then(PagerSwipeElement(pagerState, onIntercepted))

private data class PagerSwipeElement(
    val pagerState: PagerState,
    val onIntercepted: (() -> Unit)?,
) : ModifierNodeElement<PagerSwipeNode>() {
    override fun create(): PagerSwipeNode = PagerSwipeNode(pagerState, onIntercepted)

    override fun update(node: PagerSwipeNode) {
        node.update(pagerState, onIntercepted)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "crossAxisPagerGestures"
        properties["pagerState"] = pagerState
    }
}

private sealed interface PagerDragEvent {
    data class Delta(val value: Float) : PagerDragEvent
    data class End(val velocity: Float) : PagerDragEvent
}

private class PagerSwipeNode(
    var pagerState: PagerState,
    var onIntercepted: (() -> Unit)?,
) : DelegatingNode(),
    CompositionLocalConsumerModifierNode,
    SemanticsModifierNode,
    PointerInputModifierNode {
    private var motionJob: Job? = null
    private var nonTouchEvents: Channel<PagerDragEvent>? = null
    private var wheelEndJob: Job? = null
    private val pointerNode = delegate(
        SuspendingPointerInputModifierNode {
            val velocityTracker = VelocityTracker()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val state = pagerState
                val scrollSign = scrollSign()
                finishNonTouchInput()
                var events: Channel<PagerDragEvent>? = null

                var pointerId = down.id
                var accumulated = Offset.Zero
                var dragging = false
                var ended = false
                velocityTracker.resetTracking()
                velocityTracker.addPointerInputChange(down)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.fastFirstOrNull { it.id == pointerId } ?: break
                        if (change.isConsumed) break
                        if (!change.pressed) {
                            val replacement = event.changes.fastFirstOrNull { it.pressed }
                            if (replacement != null) {
                                pointerId = replacement.id
                                velocityTracker.resetTracking()
                                velocityTracker.addPointerInputChange(replacement)
                                continue
                            }
                            velocityTracker.addPointerInputChange(change)
                            val velocity = if (dragging) {
                                change.consume()
                                velocityTracker.calculateVelocity(
                                    Velocity(viewConfiguration.maximumFlingVelocity, viewConfiguration.maximumFlingVelocity),
                                ).x * scrollSign
                            } else {
                                0f
                            }
                            events?.trySend(PagerDragEvent.End(velocity))
                            ended = true
                            break
                        }

                        velocityTracker.addPointerInputChange(change)
                        val delta = change.position - change.previousPosition
                        accumulated += delta
                        if (!dragging) {
                            val x = abs(accumulated.x)
                            val y = abs(accumulated.y)
                            if (x > viewConfiguration.touchSlop && x > y) {
                                dragging = true
                                events = startMotion(state)
                                onIntercepted?.invoke()
                                change.consume()
                                // Keep the first drag frame's movement beyond slop.
                                val overSlop = accumulated.x - sign(accumulated.x) * viewConfiguration.touchSlop
                                events.trySend(PagerDragEvent.Delta(overSlop * scrollSign))
                            } else if (y > viewConfiguration.touchSlop) {
                                // Yield without touching any existing pager settling animation.
                                break
                            }
                        } else {
                            change.consume()
                            events?.trySend(PagerDragEvent.Delta(delta.x * scrollSign))
                        }
                    }
                } finally {
                    if (!ended) events?.trySend(PagerDragEvent.End(0f))
                    events?.close()
                }
            }
        },
    )

    private fun scrollSign(): Float = if (
        (currentValueOf(LocalLayoutDirection) == LayoutDirection.Rtl) xor pagerState.layoutInfo.reverseLayout
    ) {
        1f
    } else {
        -1f
    }

    private fun startMotion(state: PagerState = pagerState): Channel<PagerDragEvent> {
        val minimumVelocity = with(currentValueOf(LocalDensity)) { 400.dp.toPx() }
        val events = Channel<PagerDragEvent>(Channel.UNLIMITED)
        motionJob?.cancel()
        motionJob = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                state.scroll(MutatePriority.UserInput) {
                    // Keep drag and settling under one mutation so new input cancels them together.
                    for (event in events) {
                        when (event) {
                            is PagerDragEvent.Delta -> scrollBy(event.value)

                            is PagerDragEvent.End -> {
                                val position = state.currentPage + state.currentPageOffsetFraction
                                val target = when {
                                    event.velocity > minimumVelocity -> ceil(position).toInt()
                                    event.velocity < -minimumVelocity -> floor(position).toInt()
                                    else -> position.roundToInt()
                                }
                                state.animateToPage(this, target, event.velocity)
                                break
                            }
                        }
                    }
                }
            } finally {
                events.cancel()
            }
        }
        return events
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        pointerNode.onPointerEvent(pointerEvent, pass, bounds)
        if (pass != PointerEventPass.Main) return
        // Handle wheel/trackpad input after children, since native pager input is disabled.
        val isWheel = pointerEvent.type == PointerEventType.Scroll
        val isPan = pointerEvent.type == PointerEventType.PanMove
        if (pointerEvent.type == PointerEventType.PanEnd) {
            finishNonTouchInput()
            return
        }
        if (!isWheel && !isPan) return
        var delta = Offset.Zero
        for (change in pointerEvent.changes) {
            if (change.isConsumed) return
            delta += if (isWheel) change.scrollDelta else change.panOffset
        }
        if (delta.x == 0f || abs(delta.x) <= abs(delta.y)) return
        val scale = if (isWheel) with(currentValueOf(LocalDensity)) { 48.dp.toPx() } else 1f
        val events = if (motionJob?.isActive == true) nonTouchEvents else null
        val activeEvents = events ?: startMotion().also { nonTouchEvents = it }
        activeEvents.trySend(PagerDragEvent.Delta(-delta.x * scale * scrollSign()))
        pointerEvent.changes.forEach { it.consume() }
        if (isWheel) {
            wheelEndJob?.cancel()
            wheelEndJob = coroutineScope.launch {
                delay(120.milliseconds)
                finishNonTouchInput()
            }
        }
    }

    override fun onCancelPointerInput() {
        pointerNode.onCancelPointerInput()
        finishNonTouchInput()
    }

    private fun finishNonTouchInput() {
        wheelEndJob?.cancel()
        wheelEndJob = null
        nonTouchEvents?.let {
            it.trySend(PagerDragEvent.End(0f))
            it.close()
        }
        nonTouchEvents = null
    }

    override fun onDetach() {
        finishNonTouchInput()
        motionJob?.cancel()
        motionJob = null
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        pageLeft { navigateBy(scrollSign().toInt()) }
        pageRight { navigateBy(-scrollSign().toInt()) }
    }

    private fun navigateBy(pages: Int): Boolean {
        val target = pagerState.currentPage + pages
        if (target !in 0 until pagerState.pageCount) return false
        finishNonTouchInput()
        motionJob?.cancel()
        motionJob = coroutineScope.launch { pagerState.springToPage(target) }
        return true
    }

    fun update(state: PagerState, onIntercepted: (() -> Unit)?) {
        this.onIntercepted = onIntercepted
        if (pagerState != state) {
            finishNonTouchInput()
            motionJob?.cancel()
            pagerState = state
            pointerNode.resetPointerInputHandler()
        }
    }
}
