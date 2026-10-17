package com.moozaq

fun reconcileOutputVolumes(pending: Map<String, PendingVolume>, snapshot: Snapshot): Map<String, PendingVolume> = pending.mapNotNull { (id, request) ->
    val output = snapshot.outputs.find { it.id == id }
    when {
        output != null && output.connected && output.volume_revision > request.revision && output.volume != null && kotlin.math.abs(output.volume - request.value) < .01 -> null
        request.started.elapsedNow().inWholeSeconds >= 10 -> id to request.copy(failed=true)
        else -> id to request
    }
}.toMap()

fun outputActivity(output: OutputState?, snapshot: Snapshot): String {
    if(output == null) return "Reading speaker…"
    if(!output.connected) return "Unavailable"
    val sameSession = output.session_id == snapshot.target || output.id in snapshot.membership.members
    return when(output.state) {
        "PLAYING" -> if(output.muted || output.volume == 0.0) "Playing · silent" else if(sameSession) "Playing this music" else "Playing other music"
        "PAUSED" -> if(sameSession) "Paused" else "Other music paused"
        "BUFFERING" -> "Buffering…"
        "IDLE" -> "Not playing"
        else -> "Playback unconfirmed"
    }
}

fun previousOutputUnavailable(snapshot: Snapshot) = snapshot.target != null && snapshot.devices.isNotEmpty() &&
    snapshot.devices.none { it.id == snapshot.target && it.available }

fun canAdjustOutputVolume(snapshot: Snapshot, output: OutputState?, selected: Boolean, pending: PendingVolume?): Boolean {
    val ready = if(selected) snapshot.receiver.connected && snapshot.receiver.volume != null
        else output?.connected == true && output.volume != null
    return ready && !snapshot.busy && (!selected || !snapshot.connecting) && (pending == null || pending.failed)
}

/** Selecting a destination joins its remote; it never loads or transfers media. */
data class PendingDestination(
    val id: String,
    val started: kotlin.time.TimeMark = kotlin.time.TimeSource.Monotonic.markNow(),
    val failed: Boolean = false,
)

fun reconcileDestination(request: PendingDestination?, snapshot: Snapshot): PendingDestination? {
    request ?: return null
    return when {
        snapshot.target == request.id && snapshot.receiver.connected && !snapshot.connecting -> null
        snapshot.target == request.id && snapshot.connection_error != null -> request.copy(failed=true)
        request.started.elapsedNow().inWholeSeconds >= 20 -> request.copy(failed=true)
        else -> request
    }
}

fun destinationChecked(ui: PlayerUiState, id: String) =
    (ui.destination?.id ?: ui.snapshot.target) == id

fun canSelectDestination(ui: PlayerUiState, device: Device) = device.available &&
    !ui.snapshot.busy && !membershipPending(ui) && ui.destination?.let { !it.failed } != true &&
    !(ui.snapshot.target == device.id && ui.snapshot.receiver.connected && !ui.snapshot.connecting)

/** With no current playback, speaker checkboxes choose where the next Play goes. */
fun usesDestinationCheckbox(ui: PlayerUiState, device: Device): Boolean =
    deviceKind(device) == DeviceKind.GROUP ||
        (deviceKind(device) == DeviceKind.SPEAKER && !membershipPending(ui) &&
            (ui.destination != null || ui.snapshot.receiver.state !in listOf("PLAYING", "PAUSED", "BUFFERING")))
