package com.moozaq

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import com.moozaq.resources.*

/**
 * Cast: a modal sheet in the style of Android's output switcher. The current mix sits on top,
 * saved speaker groups are chips, and each device is a pill. A selected device's pill is its
 * volume slider (drag sideways) with the level as a percentage; an unselected pill is a plain
 * row with an empty circle. Checking a member joins the running group; choosing a group while a
 * mix plays moves that mix there after one explicit confirmation. Individual speakers cannot be
 * added live when the session is not on a group route, and that locked state explains itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCastToSheet(store: PlayerStore, ui: PlayerUiState, playback: PhonePlayback, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var locked by remember { mutableStateOf<Device?>(null) }
    var moving by remember { mutableStateOf<Device?>(null) }
    val s = ui.snapshot
    val stopping = ui.transport?.expected == "IDLE"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        scrimColor = Color.Black.copy(alpha = .32f),
        shape = RoundedCornerShape(topStart = Dimens.SheetCorner, topEnd = Dimens.SheetCorner),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(Dimens.SheetHandleWidth, Dimens.SheetHandleHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurfaceVariant.copy(alpha = .4f))
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(Dimens.TouchTarget).semantics { contentDescription = "Close" }) {
                    UiIcon(Res.drawable.ui_expand_more, modifier = Modifier.size(28.dp), tint = colors.onSurface)
                }
                Spacer(Modifier.width(12.dp))
                Text(if (isLocalOutput(s, s.target)) "Output" else "Cast", style = MaterialTheme.typography.headlineSmall)
            }
            CastToDeviceList(
                store, ui, playback,
                onLocked = { locked = it },
                onMove = { moving = it },
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenMargin, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (playback.session || stopping) {
                    TextButton(
                        onClick = { store.command("stop") },
                        enabled = !stopping && (s.target != null || s.busy),
                    ) { Text(if (stopping) "Stopping…" else if (isLocalOutput(s, s.target)) "Stop on this Mac" else "Stop casting", style = MaterialTheme.typography.labelLarge) }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) {
                    Text("Done", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    locked?.let { device ->
        AlertDialog(
            onDismissRequest = { locked = null },
            containerColor = colors.surfaceContainerHigh,
            title = { Text("${device.name} cannot join live", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "This session does not allow adding a speaker live. Choose a saved speaker group to play on several speakers.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { locked = null }) { Text("OK") } },
        )
    }
    moving?.let { device ->
        val mix = playback.mix
        AlertDialog(
            onDismissRequest = { moving = null },
            containerColor = colors.surfaceContainerHigh,
            title = { Text("Move to ${device.name}?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    if (mix != null) "“${mix.title}” moves to ${device.name} and ${playback.device} stops."
                    else "Playback moves to ${device.name} and ${playback.device} stops.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = device
                    moving = null
                    if (mix != null) store.movePlaybackTo(target.id, mix) else store.selectDevice(target.id)
                }) { Text("Move and play") }
            },
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
}

/** Everything a device row needs to know, shared by the group chips and the device pills. */
private class CastRow(
    val device: Device,
    val checked: Boolean,
    val selected: Boolean,
    val destination: Boolean,
    val groupPlaying: Boolean,
    val liveLocked: Boolean,
    val enabled: Boolean,
    val pending: Boolean,
    val failed: Boolean,
    val disconnecting: Boolean,
    val output: OutputState?,
    val volume: PendingVolume?,
    val level: Double?,
    val detail: String,
)

private fun castRow(ui: PlayerUiState, playback: PhonePlayback, device: Device): CastRow {
    val s = ui.snapshot
    val output = s.outputs.find { it.id == device.id }
    val selected = s.target == device.id
    val destination = usesDestinationCheckbox(ui, device) || deviceKind(device) == DeviceKind.LOCAL ||
        deviceKind(device) in listOf(DeviceKind.TV, DeviceKind.DISPLAY, DeviceKind.CAST)
    val request = ui.destination?.takeIf { it.id == device.id }
    val requested = requestedMembers(ui)
    val checked = if (destination) destinationChecked(ui, device.id) else device.id in requested
    val changingMember = membershipPending(ui) && checked != (device.id in s.membership.members)
    val leaving = !destination && leavingMember(membershipPending(ui), device.id in s.membership.members, device.id in requested)
    val stopping = checked && ui.transport?.expected == "IDLE"
    val pending = request != null || (selected && s.connecting) || changingMember || stopping
    val failed = request?.failed == true || (changingMember && membershipError(ui) != null) ||
        (stopping && ui.transport?.failed == true)
    val groupPlaying = deviceKind(device) == DeviceKind.GROUP && selected &&
        s.receiver.state in listOf("PLAYING", "PAUSED", "BUFFERING")
    val liveLocked = playback.session && !s.membership.available &&
        !destination && deviceKind(device) == DeviceKind.SPEAKER
    val enabled = when {
        !device.available -> false
        groupPlaying -> s.receiver.connected && !s.busy && !s.connecting && !membershipPending(ui)
        destination -> canSelectDestination(ui, device)
        else -> canToggleMembership(ui, device)
    }
    val volume = if (selected) ui.mainVolume else ui.outputVolumes[device.id]
    val level = if (selected) s.receiver.volume?.div(100.0) else output?.volume
    val detail = when {
        !device.available -> "Unavailable"
        failed -> "Not confirmed"
        stopping -> "Stopping…"
        leaving -> "Disconnecting…"
        pending -> if (checked) "Connecting…" else "Pending"
        volume?.failed == true -> "Volume not confirmed"
        deviceKind(device) == DeviceKind.LOCAL && s.target != null && !isLocalOutput(s, s.target) && s.receiver.state != "IDLE" -> "Stop casting first"
        deviceKind(device) != DeviceKind.LOCAL && localPlaybackActive(s) -> "Stop on this Mac first"
        deviceKind(device) == DeviceKind.LOCAL && selected && localPlaybackActive(s) -> "Playing on this Mac"
        destination && checked -> "Selected destination"
        liveLocked && checked -> "Playing here · use a group for several speakers"
        liveLocked -> "Cannot join live · use a saved group"
        else -> outputActivity(output, s)
    }
    return CastRow(device, checked, selected, destination, groupPlaying, liveLocked, enabled, pending, failed,
        leaving || stopping, output, volume, level, detail)
}

private fun toggle(store: PlayerStore, row: CastRow, playback: PhonePlayback, onLocked: () -> Unit, onMove: () -> Unit) {
    when {
        row.groupPlaying -> store.command("stop")
        row.liveLocked -> onLocked()
        row.destination && deviceKind(row.device) == DeviceKind.LOCAL -> store.selectDevice(row.device.id)
        row.destination && playback.session && playback.mix != null -> onMove()
        row.destination -> store.selectDevice(row.device.id)
        else -> store.togglePlaybackDevice(row.device)
    }
}

/** Groups as chips, then the devices as pills, chosen ones first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CastToDeviceList(
    store: PlayerStore,
    ui: PlayerUiState,
    playback: PhonePlayback,
    onLocked: (Device) -> Unit,
    onMove: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveLocked = playback.session && !ui.snapshot.membership.available
    val rows = pickerDevices(ui.snapshot).map { castRow(ui, playback, it) }
    val groups = rows.filter { deviceKind(it.device) == DeviceKind.GROUP }
    // Devices keep their order; choosing one changes its look, never its place.
    val devices = rows.filter { deviceKind(it.device) != DeviceKind.GROUP }
    val motion = LocalExpressiveMotion.current
    LazyColumn(modifier.padding(horizontal = Dimens.ScreenMargin)) {
        item(key = "now") { CastNowPlaying(playback) }
        if (groups.isNotEmpty()) item(key = "groups") {
            FlowRow(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { row ->
                    FilterChip(
                        selected = row.checked,
                        onClick = { toggle(store, row, playback, { onLocked(row.device) }, { onMove(row.device) }) },
                        enabled = row.enabled || row.liveLocked,
                        label = { Text(row.device.name, style = MaterialTheme.typography.labelLarge) },
                        leadingIcon = { DeviceIcon(row.device, tint = LocalContentColor.current) },
                        modifier = Modifier.height(40.dp).semantics { stateDescription = row.detail },
                    )
                }
            }
        }
        // Every device is its own pill with the same gap; chosen ones fill with their volume.
        items(devices, key = { it.device.id }) { row ->
            CastPill(
                store, ui, playback, row,
                onLocked = { onLocked(row.device) }, onMove = { onMove(row.device) },
                modifier = Modifier.animateItem(placementSpec = motion.defaultSpatial()),
            )
        }
        item(key = "status") { CastToStatus(store, ui, liveLocked) }
    }
}

@Composable
private fun CastNowPlaying(playback: PhonePlayback) {
    val title = playback.title ?: return
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHighest)) {
            playback.mix?.let { MixCover(it, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            title,
            Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun CastPill(
    store: PlayerStore,
    ui: PlayerUiState,
    playback: PhonePlayback,
    row: CastRow,
    onLocked: () -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val s = ui.snapshot
    val device = row.device
    val active = row.checked || row.pending || row.disconnecting
    val reduced = LocalReducedMotion.current
    val pulse by rememberInfiniteTransition().animateFloat(
        .45f, .75f, infiniteRepeatable(tween(Motion.PendingPulseMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    val alpha = when {
        !device.available -> .5f
        !row.disconnecting -> 1f
        reduced -> .5f
        else -> pulse
    }
    var drag by remember(device.id) { mutableStateOf<Float?>(null) }
    val adjustable = active && !row.disconnecting && !(row.selected && membershipPending(ui)) &&
        canAdjustOutputVolume(s, row.output, row.selected, row.volume)
    val motion = LocalExpressiveMotion.current
    val target = (row.volume?.value?.toFloat() ?: row.level?.toFloat())?.coerceIn(0f, 1f)
    // While dragging the fill tracks the finger exactly; otherwise it eases to the confirmed level.
    val eased by animateFloatAsState(target ?: 0f, motion.fastSpatial())
    val fraction = drag ?: target?.let { eased }
    val latestFraction by rememberUpdatedState(drag ?: target)
    val onTap by rememberUpdatedState {
        if (!row.checked && (row.enabled || row.liveLocked)) toggle(store, row, playback, onLocked, onMove)
    }
    fun commit(value: Float) {
        if (row.selected) store.mainVolume(value.toDouble()) else store.outputVolume(device.id, value.toDouble())
    }
    // Shape morph: a chosen device rounds into a full pill.
    val corner by animateDpAsState(if (active) 38.dp else 28.dp, motion.defaultSpatial())
    val container by animateColorAsState(
        if (active) colors.primaryContainer.copy(alpha = .5f) else colors.surfaceContainerLowest, motion.defaultEffects(),
    )
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.alpha(alpha).fillMaxWidth().padding(bottom = 8.dp)
            .height(if (active) 76.dp else 72.dp).clip(shape)
            .background(container)
            .semantics(mergeDescendants = true) {
                contentDescription = device.name
                stateDescription = if (active && fraction != null) "${(fraction * 100).toInt()}% · ${row.detail}" else row.detail
                if (adjustable) setProgress { target -> commit(target.coerceIn(0f, 1f)); true }
            }
            // A tap chooses an unselected device; a sideways drag on a chosen one sets its volume.
            // Vertical swipes are left to the list.
            .pointerInput(device.id, adjustable) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { moved, _ -> if (adjustable) moved.consume() }
                    if (start == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) { up.consume(); onTap() }
                        return@awaitEachGesture
                    }
                    if (!adjustable) return@awaitEachGesture
                    val width = size.width.coerceAtLeast(1).toFloat()
                    // Drag relative to the current level, so touching the pill does not jump it.
                    val origin = latestFraction ?: 0f
                    var value = origin
                    drag = value
                    horizontalDrag(start.id) { moved ->
                        moved.consume()
                        value = (value + (moved.position.x - moved.previousPosition.x) / width).coerceIn(0f, 1f)
                        drag = value
                    }
                    commit(value)
                    drag = null
                }
            },
    ) {
        if (active && fraction != null) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .background(
                        when {
                            row.volume?.failed == true -> colors.error.copy(alpha = .45f)
                            row.volume != null || drag != null -> colors.primaryContainer.copy(alpha = .8f)
                            else -> colors.primaryContainer
                        }
                    ),
            )
        }
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) UiIcon(Res.drawable.ui_volume_up, modifier = Modifier.size(28.dp), tint = colors.onPrimaryContainer)
            else DeviceIcon(device, tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                // Emphasized type for chosen devices guides the eye to what is playing.
                Text(
                    device.name,
                    style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                    color = if (active) colors.onPrimaryContainer else colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val line = when {
                    active && !row.pending && !row.failed && row.volume?.failed != true && fraction != null ->
                        "${(fraction * 100).toInt()}%" + if (row.volume != null || drag != null) " · syncing" else ""
                    active || row.detail !in listOf("Not playing", "Playback unconfirmed", "Reading speaker…") -> row.detail
                    else -> null
                }
                line?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            row.failed || row.volume?.failed == true -> colors.error
                            active -> colors.onPrimaryContainer.copy(alpha = .8f)
                            else -> colors.onSurfaceVariant
                        },
                    )
                }
            }
            SelectionCircle(
                checked = row.checked,
                pending = row.pending,
                enabled = row.enabled || row.liveLocked,
                description = "Cast to ${device.name}",
                onClick = { toggle(store, row, playback, onLocked, onMove) },
            )
        }
    }
}

/** The round check on the right of a device pill (Material check_circle / radio_button_unchecked). */
@Composable
private fun SelectionCircle(checked: Boolean, pending: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(Dimens.TouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Checkbox, onClickLabel = description) { onClick() }
            .semantics { this.contentDescription = description; toggleableState = androidx.compose.ui.state.ToggleableState(checked) },
        contentAlignment = Alignment.Center,
    ) {
        UiIcon(
            if (checked) Res.drawable.ui_check_circle else Res.drawable.ui_radio_unchecked,
            modifier = Modifier.size(28.dp),
            tint = when {
                !enabled -> colors.outline
                pending -> colors.onSurfaceVariant
                checked -> colors.onSurface
                else -> colors.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun CastToStatus(store: PlayerStore, ui: PlayerUiState, liveLocked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val failure = membershipError(ui)
    if (liveLocked) {
        Text(
            "Adding a speaker live is not available on this session. Choose a saved speaker group and the mix moves there.",
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
    if (ui.destination?.failed == true) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Connection not confirmed. Select the speaker to retry.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = colors.error,
            )
        }
    }
    if (failure != null) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(failure, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.error)
            TextButton(onClick = { store.dismissMembership() }) { Text("Read speakers") }
        }
    }
    if (ui.snapshot.devices.isEmpty() && ui.snapshot.group?.members.isNullOrEmpty()) {
        Text(
            "Looking for speakers…",
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}
