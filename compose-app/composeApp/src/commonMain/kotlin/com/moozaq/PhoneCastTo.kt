package com.moozaq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Cast to: a modal sheet with one card per device holding its checkbox, icon, name and
 * volume (handoff "Cast to"). Behaviour is unchanged from the confirmed-route rules:
 * checking joins that device, unchecking the last device stops playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCastToSheet(store: PlayerStore, ui: PlayerUiState, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val devices = pickerDevices(ui.snapshot)
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
        Column(Modifier.fillMaxWidth().heightIn(max = Dimens.SheetMaxHeight)) {
            Text(
                "Cast to",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = Dimens.ScreenMargin),
                verticalArrangement = Arrangement.spacedBy(Dimens.DeviceCardGap),
            ) {
                items(devices, key = { it.id }) { device -> CastToRow(store, ui, device) }
                item(key = "status") { CastToStatus(store, ui) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CastToRow(store: PlayerStore, ui: PlayerUiState, device: Device) {
    val colors = MaterialTheme.colorScheme
    val s = ui.snapshot
    val output = s.outputs.find { it.id == device.id }
    val selected = s.target == device.id
    val destination = usesDestinationCheckbox(ui, device) ||
        deviceKind(device) in listOf(DeviceKind.TV, DeviceKind.DISPLAY, DeviceKind.CAST)
    val request = ui.destination?.takeIf { it.id == device.id }
    val checked = if (destination) destinationChecked(ui, device.id) else device.id in requestedMembers(ui)
    val changingMember = membershipPending(ui) && checked != (device.id in s.membership.members)
    val stopping = checked && ui.transport?.expected == "IDLE"
    val pending = request != null || (selected && s.connecting) || changingMember || stopping
    val failed = request?.failed == true || (changingMember && membershipError(ui) != null) ||
        (stopping && ui.transport?.failed == true)
    val foreground = if (checked) colors.onSurface else colors.onSurfaceVariant
    val groupPlaying = deviceKind(device) == DeviceKind.GROUP && selected &&
        s.receiver.state in listOf("PLAYING", "PAUSED", "BUFFERING")
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
        pending -> "Pending confirmation"
        volume?.failed == true -> "Volume not confirmed"
        volume != null -> "Syncing volume…"
        destination && checked -> "Selected destination"
        else -> outputActivity(output, s)
    }
    var drag by remember(device.id) { mutableStateOf<Float?>(null) }
    val current = drag ?: volume?.value?.toFloat() ?: (level ?: 0.0).toFloat()
    val shape = RoundedCornerShape(Dimens.DeviceCardCorner)
    Row(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(if (checked) colors.primary.copy(alpha = .10f) else colors.surfaceContainer)
            .border(
                1.dp,
                when {
                    pending -> colors.outline
                    checked -> colors.primary.copy(alpha = .45f)
                    else -> Color.Transparent
                },
                shape,
            )
            .padding(start = 4.dp, end = Dimens.CardPadding, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MzCheckbox(
            checked = checked,
            onCheckedChange = {
                when {
                    groupPlaying -> store.command("stop")
                    destination -> store.selectDevice(device.id)
                    else -> store.togglePlaybackDevice(device)
                }
            },
            enabled = enabled,
            pending = pending,
            contentDescription = "Cast to ${device.name}",
            stateDescription = detail,
        )
        Column(Modifier.weight(1f)) {
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceIcon(device, tint = foreground)
                Spacer(Modifier.width(12.dp))
                Text(
                    device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MzSlider(
                value = current.coerceIn(0f, 1f),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    drag?.let {
                        if (selected) store.mainVolume(it.toDouble()) else store.outputVolume(device.id, it.toDouble())
                    }
                    drag = null
                },
                enabled = canAdjustOutputVolume(s, output, selected, volume),
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
private fun CastToStatus(store: PlayerStore, ui: PlayerUiState) {
    val colors = MaterialTheme.colorScheme
    val failure = membershipError(ui)
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
