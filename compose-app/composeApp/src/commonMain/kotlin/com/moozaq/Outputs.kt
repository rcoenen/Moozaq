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

fun isLocalOutput(snapshot: Snapshot, id: String?) = id != null && snapshot.local_output == id
fun localPlaybackActive(snapshot: Snapshot) = isLocalOutput(snapshot, snapshot.target) &&
    snapshot.receiver.state in listOf("PLAYING", "PAUSED", "BUFFERING")

fun canSelectDestination(ui: PlayerUiState, device: Device): Boolean {
    val snapshot = ui.snapshot
    if (!device.available || snapshot.busy || membershipPending(ui) ||
        ui.destination?.let { !it.failed } == true ||
        (snapshot.target == device.id && snapshot.receiver.connected && !snapshot.connecting)) return false
    if (deviceKind(device) == DeviceKind.LOCAL && snapshot.target != null &&
        !isLocalOutput(snapshot, snapshot.target) && snapshot.receiver.state != "IDLE") return false
    if (deviceKind(device) != DeviceKind.LOCAL && localPlaybackActive(snapshot)) return false
    return true
}

/** With no current playback, speaker checkboxes choose where the next Play goes. */
fun usesDestinationCheckbox(ui: PlayerUiState, device: Device): Boolean =
    deviceKind(device) == DeviceKind.GROUP ||
        (deviceKind(device) == DeviceKind.SPEAKER && !membershipPending(ui) &&
            (ui.destination != null || ui.snapshot.receiver.state !in listOf("PLAYING", "PAUSED", "BUFFERING")))

data class SpeakerDraft(
    val members: Set<String>,
    val playbackRevision: Long = 0,
    val source: String? = null,
    val playbackConfirmed: Boolean = false,
    val error: String? = null,
)

fun speakerDraftMembers(ui: PlayerUiState): Set<String> = ui.speakerDraft?.members ?: run {
    val selected = ui.snapshot.devices.find { it.id == ui.snapshot.target && it.available && deviceKind(it) == DeviceKind.SPEAKER }
    if (selected == null) emptySet() else setOf(selected.id)
}

fun canToggleSpeakerDraft(ui: PlayerUiState, device: Device): Boolean {
    val draft = ui.speakerDraft
    if (!device.available || deviceKind(device) != DeviceKind.SPEAKER || ui.snapshot.busy || membershipPending(ui)) return false
    if (ui.destination?.let { !it.failed } == true || draft?.playbackConfirmed == true) return false
    if (draft != null && draft.source != null && draft.error == null) return false
    return draft != null || ui.snapshot.receiver.state !in listOf("PLAYING", "PAUSED", "BUFFERING")
}

fun canRequestDraftPlay(ui: PlayerUiState, mix: Mix): Boolean {
    val draft = ui.speakerDraft ?: return false
    return draft.members.isNotEmpty() && !draft.playbackConfirmed &&
        (draft.source == null || draft.error != null) && !ui.snapshot.busy &&
        !(ui.snapshot.receiver.source == mix.url && ui.snapshot.receiver.state in listOf("PLAYING", "BUFFERING"))
}

fun draftPlayTarget(snapshot: Snapshot, draft: SpeakerDraft): String? {
    val devices = snapshot.devices.filter { it.id in draft.members && it.available && deviceKind(it) == DeviceKind.SPEAKER }
    if (devices.size != draft.members.size) return null
    return devices.firstOrNull { it.id == snapshot.target }?.id ?: devices.firstOrNull()?.id
}
