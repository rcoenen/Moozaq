package com.moozaq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Cast to: a modal sheet listing devices in plain rows (groups, speakers, TVs), separated by
 * thin dividers. A row shows a volume slider only while that device is part of playback or is
 * the selected destination; other rows stay compact. Checking a member joins the running group; checking a group
 * while a mix plays moves that mix there after one explicit confirmation. Individual
 * speakers cannot be added live when the session is not on a group route, and that locked
 * state now explains itself instead of doing nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCastToSheet(store: PlayerStore, ui: PlayerUiState, playback: PhonePlayback, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var locked by remember { mutableStateOf<Device?>(null) }
    var moving by remember { mutableStateOf<Device?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
        scrimColor = Color.Black.copy(alpha = .32f),
        shape = RoundedCornerShape(topStart = Dimens.SheetCorner, topEnd = Dimens.SheetCorner),
        dragHandle = {
            Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(Dimens.SheetHandleWidth, Dimens.SheetHandleHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurfaceVariant.copy(alpha = .4f))
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Text(
                "Cast to",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            )
            CastToDeviceList(
                store, ui, playback,
                onLocked = { locked = it },
                onMove = { moving = it },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.height(24.dp))
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

/** The device rows of the Cast to sheet, grouped by kind with dividers between rows. */
@Composable
fun CastToDeviceList(
    store: PlayerStore,
    ui: PlayerUiState,
    playback: PhonePlayback,
    onLocked: (Device) -> Unit,
    onMove: (Device) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val liveLocked = playback.session && !ui.snapshot.membership.available
    val devices = pickerDevices(ui.snapshot)
    val sections = listOf(
        "Speaker groups" to devices.filter { deviceKind(it) == DeviceKind.GROUP },
        "Speakers" to devices.filter { deviceKind(it) == DeviceKind.SPEAKER },
        "TVs and displays" to devices.filter { deviceKind(it) !in listOf(DeviceKind.GROUP, DeviceKind.SPEAKER) },
    ).filter { it.second.isNotEmpty() }
    LazyColumn(modifier.padding(horizontal = Dimens.ScreenMargin)) {
        sections.forEach { (title, members) ->
            item(key = "section-$title") {
                Text(
                    title,
                    Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            itemsIndexed(members, key = { _, device -> device.id }) { index, device ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = Dimens.TouchTarget), color = colors.outlineVariant.copy(alpha = .6f))
                CastToRow(store, ui, playback, device, onLocked = { onLocked(device) }, onMove = { onMove(device) })
            }
        }
        item(key = "status") { CastToStatus(store, ui, liveLocked) }
    }
}

@Composable
private fun CastToRow(
    store: PlayerStore,
    ui: PlayerUiState,
    playback: PhonePlayback,
    device: Device,
    onLocked: () -> Unit,
    onMove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val s = ui.snapshot
    val output = s.outputs.find { it.id == device.id }
    val selected = s.target == device.id
    val destination = usesDestinationCheckbox(ui, device) ||
        deviceKind(device) in listOf(DeviceKind.TV, DeviceKind.DISPLAY, DeviceKind.CAST)
    val request = ui.destination?.takeIf { it.id == device.id }
    val requested = requestedMembers(ui)
    val checked = if (destination) destinationChecked(ui, device.id) else device.id in requested
    val changingMember = membershipPending(ui) && checked != (device.id in s.membership.members)
    val leaving = !destination && leavingMember(membershipPending(ui), device.id in s.membership.members, device.id in requested)
    val stopping = checked && ui.transport?.expected == "IDLE"
    val disconnecting = leaving || stopping
    val pending = request != null || (selected && s.connecting) || changingMember || stopping
    val failed = request?.failed == true || (changingMember && membershipError(ui) != null) ||
        (stopping && ui.transport?.failed == true)
    val foreground = if (checked) colors.onSurface else colors.onSurfaceVariant
    val reduced = LocalReducedMotion.current
    val fadeTransition = rememberInfiniteTransition()
    val fadeAlpha by fadeTransition.animateFloat(
        initialValue = .45f,
        targetValue = .75f,
        animationSpec = infiniteRepeatable(
            tween(Motion.PendingPulseMs, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
    )
    val rowAlpha = when {
        !disconnecting -> 1f
        reduced -> .5f
        else -> fadeAlpha
    }
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
        pending -> "Pending confirmation"
        volume?.failed == true -> "Volume not confirmed"
        volume != null -> "Syncing volume…"
        destination && checked -> if (playback.session) "Playing here" else "Next mix plays here"
        liveLocked && checked -> "Playing here · use a group for several speakers"
        liveLocked -> "Cannot join live · use a saved group"
        else -> outputActivity(output, s)
    }
    var drag by remember(device.id) { mutableStateOf<Float?>(null) }
    val current = drag ?: volume?.value?.toFloat() ?: (level ?: 0.0).toFloat()
    // Volume belongs to devices that are playing or chosen; elsewhere it is noise.
    val showVolume = checked || pending || disconnecting
    // Unchosen rows only mention what matters there, such as other music or being offline.
    val status = detail.takeUnless { !checked && !pending && it in listOf("Not playing", "Playback unconfirmed", "Reading speaker…") }
    Column(
        Modifier.alpha(rowAlpha * if (device.available) 1f else .5f)
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MzCheckbox(
                checked = checked,
                onCheckedChange = {
                    when {
                        groupPlaying -> store.command("stop")
                        liveLocked -> onLocked()
                        destination && playback.session && playback.mix != null -> onMove()
                        destination -> store.selectDevice(device.id)
                        else -> store.togglePlaybackDevice(device)
                    }
                },
                enabled = enabled || liveLocked,
                pending = pending,
                contentDescription = "Cast to ${device.name}",
                stateDescription = detail,
            )
            DeviceIcon(device, tint = if (checked) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed || volume?.failed == true) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showVolume) {
            MzSlider(
                value = current.coerceIn(0f, 1f),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let {
                        if (selected) store.mainVolume(it.toDouble()) else store.outputVolume(device.id, it.toDouble())
                    }
                    drag = null
                },
                // Lines up with the device name: checkbox target, icon and gap.
                modifier = Modifier.padding(start = Dimens.TouchTarget + 36.dp, end = 8.dp),
                enabled = !disconnecting && !(selected && membershipPending(ui)) &&
                    canAdjustOutputVolume(s, output, selected, volume),
                pending = volume != null && !volume.failed,
                failed = volume?.failed == true,
                trackHeight = Dimens.VolumeTrack,
                thumbHeight = Dimens.VolumeThumbHeight,
                contentDescription = "${device.name} volume",
                stateDescription = when {
                    volume?.failed == true -> "Not confirmed; adjust to retry"
                    volume != null -> "Pending confirmation"
                    level == null -> "Unavailable"
                    else -> "Confirmed"
                },
            )
        }
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
