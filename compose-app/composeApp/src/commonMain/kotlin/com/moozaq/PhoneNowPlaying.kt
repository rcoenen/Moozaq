package com.moozaq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.moozaq.resources.*

/**
 * Now playing: the Cast expanded controller on surfaceContainerLowest (handoff "Now playing").
 * Seek, volume and Stop casting live here; the receiver's own status ends every pending state.
 */
@Composable
fun PhoneNowPlaying(
    store: PlayerStore,
    ui: PlayerUiState,
    playback: PhonePlayback,
    sheet: PlayerSheet,
    onCollapse: () -> Unit,
    onOpenCast: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val s = ui.snapshot
    val receiver = presentedReceiver(ui)
    val awaiting = playback.awaiting
    val seekRequest = ui.pendingSeek
    val volumeRequest = ui.mainVolume
    val duration = (receiver.duration ?: 0.0).coerceAtLeast(0.0)
    var seekDrag by remember(s.target) { mutableStateOf<Float?>(null) }
    var volumeDrag by remember(s.target) { mutableStateOf<Float?>(null) }
    val seekValue = seekDrag ?: seekRequest?.position?.toFloat() ?: (receiver.position ?: 0.0).toFloat()
    val volumeValue = volumeDrag ?: volumeRequest?.value?.toFloat() ?: ((receiver.volume ?: 0) / 100f)
    val stopping = awaiting == "Stopping…"

    // The drag is measured on this fixed layer; only the player inside it slides. Measuring inside
    // the sliding player would see half the finger's travel, because it moves under the finger.
    Box(Modifier.fillMaxSize().draggable(sheet.dragState, Orientation.Vertical, onDragStopped = { sheet.release(it) })) {
        // Browse stays visible above the panel, dimmed as the panel rises; tapping it closes the panel.
        val openness = if (sheet.height > 0f) (1f - sheet.offset / sheet.height).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = openness }.background(Color.Black.copy(alpha = .32f))
                .clickable(interactionSource = null, indication = null, onClickLabel = "Close player") { onCollapse() },
        )
        Column(
            Modifier.fillMaxSize()
                .padding(top = Dimens.PlayerPanelTopGap)
                .graphicsLayer { translationY = sheet.offset }
                .nestedScroll(sheet.connection)
                .clip(RoundedCornerShape(topStart = Dimens.SheetCorner, topEnd = Dimens.SheetCorner))
                .background(colors.surfaceContainerLow),
        ) {
            // The same handle as the Cast panel.
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(Dimens.SheetHandleWidth, Dimens.SheetHandleHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurfaceVariant.copy(alpha = .4f)),
                )
            }
            Row(
                Modifier.fillMaxWidth().height(Dimens.TopAppBarHeight).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onCollapse,
                    modifier = Modifier.size(Dimens.TouchTarget).semantics { contentDescription = "Close player" },
                ) {
                    UiIcon(Res.drawable.ui_expand_more, modifier = Modifier.size(28.dp), tint = colors.onSurface)
                }
                // Tapping where it is casting opens the speaker choice.
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .clickable(onClickLabel = "Choose speakers") { onOpenCast() }
                        .semantics(mergeDescendants = true) { contentDescription = "Casting to ${playback.device}. Choose speakers" }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Casting to", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(
                        playback.device,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Balances the close arrow so the title stays centred.
                Spacer(Modifier.size(Dimens.TouchTarget))
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                val scroll = rememberScrollState()
                Column(
                    // Scrolls only when it does not fit, so on most phones every vertical swipe reaches the sheet.
                    Modifier.fillMaxWidth().verticalScroll(scroll, enabled = scroll.maxValue > 0).heightIn(min = viewport)
                        .padding(horizontal = Dimens.PlayerMargin),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(Dimens.PlayerCoverAspect)
                        .clip(RoundedCornerShape(Dimens.PlayerCoverCorner)).background(colors.surfaceContainerHighest)) {
                        playback.mix?.let { MixCover(it, Modifier.fillMaxSize()) }
                            ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                UiIcon(Res.drawable.ui_music_note, "Artwork unavailable", Modifier.size(36.dp), tint = colors.onSurfaceVariant)
                            }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        playback.title ?: "Nothing playing",
                        style = playerTitleStyle(),
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        phoneNowStatus(playback),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(24.dp))
                    MzSlider(
                        value = seekValue.coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = { seekDrag = it },
                        onValueChangeFinished = {
                            seekDrag?.let { store.seek(it.toDouble()) }
                            seekDrag = null
                        },
                        enabled = receiver.connected && duration > 0 && !s.busy && !s.connecting &&
                            (seekRequest == null || seekRequest.failed),
                        pending = seekRequest != null && !seekRequest.failed,
                        failed = seekRequest?.failed == true,
                        range = 0f..duration.toFloat().coerceAtLeast(1f),
                        trackHeight = Dimens.SeekTrack,
                        thumbHeight = Dimens.SliderHitHeight,
                        contentDescription = "Playback position",
                        stateDescription = when {
                            seekRequest?.failed == true -> "Not confirmed; adjust to retry"
                            seekRequest != null -> "Pending confirmation"
                            else -> null
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clockTime(seekValue.toDouble()), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Text(clockTime(receiver.duration), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        val toggleEnabled = awaiting == null && (canPause(s) || canResume(s))
                        Box(
                            Modifier.size(Dimens.PlayPauseSize)
                                .clip(RoundedCornerShape(Dimens.PlayPauseCorner))
                                .background(
                                    when {
                                        awaiting != null -> colors.onSurfaceVariant
                                        toggleEnabled -> colors.primary
                                        else -> colors.surfaceContainerHighest
                                    }
                                )
                                .clickable(
                                    enabled = toggleEnabled,
                                    onClickLabel = if (canPause(s)) "Pause" else "Resume",
                                ) { store.command(if (canPause(s)) "pause" else "resume") },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (awaiting != null) {
                                MzSpinner(size = 36.dp, color = colors.onPrimary)
                            } else {
                                UiIcon(
                                    if (canPause(s)) Res.drawable.ui_pause else Res.drawable.ui_play_arrow,
                                    modifier = Modifier.size(44.dp),
                                    tint = if (toggleEnabled) colors.onPrimary else colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        UiIcon(Res.drawable.ui_volume_down, modifier = Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                        MzSlider(
                            value = volumeValue.coerceIn(0f, 1f),
                            onValueChange = { volumeDrag = it },
                            onValueChangeFinished = {
                                volumeDrag?.let { store.mainVolume(it.toDouble()) }
                                volumeDrag = null
                            },
                            enabled = canAdjustSessionVolume(ui),
                            pending = volumeRequest != null && !volumeRequest.failed,
                            failed = volumeRequest?.failed == true,
                            trackHeight = Dimens.VolumeTrack,
                            thumbHeight = Dimens.VolumeThumbHeight,
                            contentDescription = "Volume",
                            stateDescription = when {
                                volumeRequest?.failed == true -> "Not confirmed; adjust to retry"
                                volumeRequest != null -> "Pending confirmation"
                                else -> null
                            },
                            modifier = Modifier.weight(1f),
                        )
                        UiIcon(Res.drawable.ui_volume_up, modifier = Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().padding(start = Dimens.PlayerMargin, end = Dimens.PlayerMargin, top = 16.dp, bottom = 32.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(Dimens.StopCastingHeight)
                        .clip(RoundedCornerShape(Dimens.StopCastingCorner))
                        .border(1.dp, colors.outline, RoundedCornerShape(Dimens.StopCastingCorner))
                        .clickable(
                            enabled = !stopping && (s.target != null || s.busy),
                            onClickLabel = "Stop casting",
                        ) { store.command("stop") },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UiIcon(Res.drawable.ui_stop, modifier = Modifier.size(18.dp), tint = colors.primary)
                        Text(
                            if (stopping) "Stopping…" else "Stop casting",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                }
            }
        }
    }
}

fun canAdjustSessionVolume(ui: PlayerUiState): Boolean {
    val s = ui.snapshot
    val request = ui.mainVolume
    return s.receiver.connected && s.receiver.volume != null && !s.busy && !s.connecting &&
        !membershipPending(ui) && (request == null || request.failed)
}
