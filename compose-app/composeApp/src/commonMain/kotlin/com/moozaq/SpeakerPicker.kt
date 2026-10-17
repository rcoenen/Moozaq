package com.moozaq

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun SpeakerPicker(store: PlayerStore, ui: PlayerUiState, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    val s = ui.snapshot
    val r = s.receiver
    val colors = MaterialTheme.colorScheme
    val name = s.devices.find { it.id == s.target }?.name ?: r.device ?: "Choose speakers"
    val status = if(s.connecting) "Connecting…" else if(r.connected) "Connected" else "Disconnected"
    val tint = if(s.connecting) colors.primary else if(r.connected) Color(0xFF6FD5A0) else colors.error
    Box {
        TextButton(onClick={onOpenChange(true)}, modifier=Modifier.semantics { contentDescription="$name · $status" }) {
            Text("●", color=tint)
            Spacer(Modifier.width(8.dp))
            Text(name, color=colors.onSurface)
            Text("  ▾", color=colors.secondary)
        }
        if(open) Dialog(onDismissRequest={onOpenChange(false)}, properties=DialogProperties(usePlatformDefaultWidth=false)) {
            Surface(Modifier.width(600.dp).height(540.dp), shape=RoundedCornerShape(24.dp), color=colors.surface) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Text("Speakers", style=MaterialTheme.typography.titleLarge, modifier=Modifier.weight(1f))
                    TextButton(onClick={onOpenChange(false)}) { Text("Done") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val requested = requestedMembers(ui)
            val membershipBusy = membershipPending(ui)
            val selectionError = membershipError(ui)
            val members = s.group?.members.orEmpty()
            val devices = s.devices + members.filter { member -> s.devices.none { it.id == member.id } }.map { Device(it.id, it.name) }
            devices.forEach { device ->
                key(s.target, device.id) {
                    val output = s.outputs.find { it.id == device.id }
                    val selected = s.target == device.id
                    val groupDestination = deviceKind(device) == DeviceKind.GROUP
                    val destinationCheckbox = usesDestinationCheckbox(ui, device)
                    val destinationRequest = ui.destination?.takeIf { it.id == device.id }
                    val destinationTint = if(destinationRequest?.failed == true) colors.error else if(destinationRequest != null || (selected && s.connecting)) colors.secondary else colors.primary
                    val pending = if(selected) ui.mainVolume else ui.outputVolumes[device.id]
                    val level = if(selected) r.volume?.div(100.0) else output?.volume
                    var adjustment by remember { mutableStateOf<Float?>(null) }
                    val volumeTint = if(pending?.failed == true) colors.error else if(pending != null) colors.secondary else colors.primary
                    Row(Modifier.fillMaxWidth().heightIn(min=68.dp).padding(horizontal=4.dp, vertical=4.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        val physicalSpeaker = deviceKind(device) == DeviceKind.SPEAKER
                        val checked = device.id in requested
                        val membershipChanged = membershipBusy && (checked != (device.id in s.membership.members))
                        val membershipTint = if(selectionError != null && membershipChanged) colors.error else if(membershipChanged) colors.secondary else colors.primary
                        Box(Modifier.width(32.dp), contentAlignment=Alignment.Center) {
                            if(destinationCheckbox) Checkbox(
                                checked=destinationChecked(ui, device.id),
                                onCheckedChange={store.selectDevice(device.id)},
                                enabled=canSelectDestination(ui, device),
                                colors=CheckboxDefaults.colors(checkedColor=destinationTint, disabledCheckedColor=destinationTint, disabledUncheckedColor=colors.outline),
                                modifier=Modifier.semantics { contentDescription="${device.name} playback destination" },
                            )
                            else if(physicalSpeaker) Checkbox(
                                checked=checked,
                                onCheckedChange={store.togglePlaybackDevice(device)},
                                enabled=canToggleMembership(ui, device),
                                colors=CheckboxDefaults.colors(checkedColor=membershipTint, disabledCheckedColor=membershipTint, disabledUncheckedColor=colors.outline),
                                modifier=Modifier.semantics { contentDescription="${device.name} playback" },
                            )
                        }
                        DeviceIcon(device)
                        Column(Modifier.weight(1f)) {
                            Text(device.name)
                            val detail = when {
                                !device.available -> "Unavailable · looking for speaker…"
                                destinationRequest?.failed == true -> "Connection not confirmed · select to retry"
                                destinationRequest != null -> "Connecting…"
                                membershipChanged && selectionError != null -> "Not confirmed"
                                membershipChanged -> if(checked) "Adding…" else "Removing…"
                                selected && s.connecting -> "Connecting…"
                                selected && !r.connected -> "Disconnected"
                                pending?.failed == true -> "Volume not confirmed"
                                pending != null -> "Syncing volume…"
                                destinationCheckbox && selected && r.connected -> "Selected destination · ${outputActivity(output, s)}"
                                groupDestination -> "Speaker group · ${outputActivity(output, s)}"
                                else -> outputActivity(output, s)
                            }
                            Text(detail, color=if(destinationRequest != null) destinationTint else if(membershipChanged) membershipTint else if(output?.state == "PLAYING" && output.connected) Color(0xFF6FD5A0) else colors.secondary, style=MaterialTheme.typography.labelSmall)
                            if(output?.title != null && output.session_id != s.target && device.id !in s.membership.members) Text(output.title, maxLines=1, overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis, color=colors.secondary, style=MaterialTheme.typography.labelSmall)
                        }
                        if(selected || output?.volume != null) {
                            Column(Modifier.weight(1f)) {
                                Slider(value=adjustment ?: (pending?.value ?: level ?: 0.0).toFloat().coerceIn(0f,1f),
                                    onValueChange={adjustment=it},
                                    onValueChangeFinished={adjustment?.let {if(selected) store.mainVolume(it.toDouble()) else store.outputVolume(device.id,it.toDouble())}; adjustment=null},
                                    colors=SliderDefaults.colors(thumbColor=volumeTint, activeTrackColor=volumeTint, disabledThumbColor=volumeTint, disabledActiveTrackColor=volumeTint),
                                    enabled=canAdjustOutputVolume(s, output, selected, pending),
                                    modifier=Modifier.fillMaxWidth().semantics { contentDescription="${device.name} volume" })
                                if(pending?.failed == true) TextButton(onClick={if(selected) store.dismissMainVolume() else store.dismissOutputVolume(device.id)}) { Text("Read speaker") }
                            }
                        } else Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick={store.selectDevice(output?.control_id ?: device.id)},
                            enabled=canSelectDestination(ui, device),
                            modifier=Modifier.width(72.dp).semantics { contentDescription="Control ${device.name} remote" },
                        ) {
                            if(device.available && selected && s.connecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth=2.dp)
                            else Text(if(!device.available) "Offline" else if(selected) "Active" else if(destinationCheckbox) "Select" else "Control", style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if(s.devices.isEmpty()) Text("Looking for speakers…", Modifier.padding(16.dp), color=colors.secondary)
                }
            val membershipFailure = membershipError(ui)
            if(membershipFailure != null) {
                Text(membershipFailure, Modifier.padding(horizontal=12.dp, vertical=4.dp), style=MaterialTheme.typography.labelSmall, color=colors.error)
                TextButton(onClick={store.dismissMembership()}) { Text("Read speakers") }
            } else if(membershipPending(ui)) {
                Text("Updating speakers…", Modifier.padding(horizontal=12.dp, vertical=8.dp), style=MaterialTheme.typography.labelSmall, color=colors.secondary)
            } else {
                Text(
                    if(previousOutputUnavailable(s)) "The previous output is no longer available. Choose a speaker with Control."
                    else if(s.membership.available) "Checked speakers play the current mix. Other music is shown separately."
                    else "Choose a speaker or group, then press Play on a mix.",
                    Modifier.padding(horizontal=12.dp, vertical=4.dp), style=MaterialTheme.typography.labelSmall, color=colors.secondary,
                )
            }
            Text(if(s.receiver.state in listOf("PLAYING", "PAUSED", "BUFFERING"))
                "Individual checks change this mix’s speakers. Group selection chooses the destination for Play."
                else "Selecting another destination replaces the previous selection. Music starts only when you press Play.", Modifier.padding(horizontal=12.dp, vertical=4.dp), style=MaterialTheme.typography.labelSmall, color=colors.secondary)
            }
            }
        }
    }
}
