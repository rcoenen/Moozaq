package com.moozaq

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Handoff slider: a flat 16 dp (seek) or 12 dp (volume) track with a 4 dp thumb inside a
 * 44 dp hit area. Pending and failed requests tint the control instead of rolling back.
 */
@Composable
fun MzSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false,
    failed: Boolean = false,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    trackHeight: Dp = Dimens.SeekTrack,
    thumbHeight: Dp = Dimens.SliderHitHeight,
    contentDescription: String? = null,
    stateDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        failed -> colors.error
        pending -> colors.onSurfaceVariant
        !enabled -> colors.outline
        else -> colors.primary
    }
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    Box(modifier.height(Dimens.SliderHitHeight).semantics {
        if (contentDescription != null) this.contentDescription = contentDescription
        progressBarRangeInfo = ProgressBarRangeInfo(value, range)
        if (stateDescription != null) this.stateDescription = stateDescription
        if (!enabled) disabled()
        if (enabled) setProgress { target ->
            change(target.coerceIn(range.start, range.endInclusive))
            finished()
            true
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val thumbWidth = Dimens.SliderThumbWidth.toPx()
            val thumbSize = thumbHeight.toPx().coerceAtMost(size.height)
            val usable = (size.width - thumbWidth).coerceAtLeast(1f)
            val track = trackHeight.toPx()
            val trackTop = (size.height - track) / 2f
            val x = usable * fraction
            val activeWidth = (x + thumbWidth / 2f - track / 2f).coerceAtLeast(0f)
            if (activeWidth > 0f) {
                drawRoundRect(tint, Offset(0f, trackTop), Size(activeWidth, track), CornerRadius(track / 2f))
            }
            val inactiveX = (x + thumbWidth + 6f).coerceAtMost(size.width)
            val inactiveWidth = (size.width - inactiveX).coerceAtLeast(0f)
            if (inactiveWidth > 0f) {
                drawRoundRect(colors.secondaryContainer, Offset(inactiveX, trackTop), Size(inactiveWidth, track), CornerRadius(track / 2f))
            }
            drawRoundRect(
                tint,
                Offset(x, (size.height - thumbSize) / 2f),
                Size(thumbWidth, thumbSize),
                CornerRadius(thumbWidth / 2f),
            )
        }
        if (enabled) {
            Box(Modifier.fillMaxSize().pointerInput(range.start, range.endInclusive) {
                awaitEachGesture {
                    val width = size.width.coerceAtLeast(1)
                    fun report(x: Float) = change(range.start + (x / width).coerceIn(0f, 1f) * span)
                    val down = awaitFirstDown()
                    down.consume()
                    report(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val moved = event.changes.firstOrNull { it.id == down.id } ?: break
                        moved.consume()
                        if (!moved.pressed) break
                        report(moved.position.x)
                    }
                    finished()
                }
            })
        }
    }
}

/** Three equalizer bars, 900 ms loop with a 300 ms offset per bar. Frozen when animations are off. */
@Composable
fun MzEqualizer(
    animated: Boolean,
    modifier: Modifier = Modifier.size(12.dp),
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    val statics = listOf(.55f, .9f, .4f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.Bottom) {
        repeat(3) { index ->
            val scale = if (animated) animatedBar(index) else statics[index]
            Box(Modifier.width(3.dp).fillMaxHeight().graphicsLayer {
                scaleY = scale
                transformOrigin = TransformOrigin(.5f, 1f)
            }.background(tint, RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun animatedBar(index: Int): Float {
    val transition = rememberInfiniteTransition()
    val value by transition.animateFloat(
        initialValue = .35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(Motion.EqualizerMs, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
            initialStartOffset = StartOffset(index * Motion.EqualizerStaggerMs),
        ),
    )
    return value
}

/** Handoff spinner: one 1 s linear loop. */
@Composable
fun MzSpinner(size: Dp = 24.dp, color: Color = MaterialTheme.colorScheme.primary, stroke: Dp = 2.dp) {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(Motion.SpinnerMs, easing = LinearEasing)))
    Canvas(Modifier.size(size)) {
        val strokePx = stroke.toPx()
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(strokePx / 2f, strokePx / 2f),
            size = Size(this.size.width - strokePx, this.size.height - strokePx),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/** Handoff checkbox: 18 dp box inside a 48 dp target, grey and locked while a change is pending. */
@Composable
fun MzCheckbox(
    checked: Boolean,
    onCheckedChange: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false,
    contentDescription: String? = null,
    stateDescription: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        pending -> colors.onSurfaceVariant
        !enabled -> colors.outline
        else -> colors.primary
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(Dimens.TouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interaction,
                indication = null,
                onValueChange = { onCheckedChange() },
            )
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                stateDescription?.let { this.stateDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 2.dp.toPx()
            if (checked) {
                drawRoundRect(tint, cornerRadius = CornerRadius(3.dp.toPx()))
                val check = Path().apply {
                    moveTo(size.width * .22f, size.height * .51f)
                    lineTo(size.width * .4f, size.height * .69f)
                    lineTo(size.width * .78f, size.height * .32f)
                }
                drawPath(check, colors.onPrimary, style = Stroke(width = stroke, cap = StrokeCap.Round))
            } else {
                drawRoundRect(
                    color = if (pending) colors.outline else colors.onSurfaceVariant,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(2.5.dp.toPx()),
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}
