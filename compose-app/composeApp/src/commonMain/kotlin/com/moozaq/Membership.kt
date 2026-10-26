package com.moozaq

import kotlinx.serialization.Serializable
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Membership is receiver evidence, independent of which remote is selected. */
@Serializable data class PlaybackMembership(
    val members: List<String> = emptyList(),
    val available: Boolean = false,
    val pending: List<String>? = null,
    val error: String? = null,
    val revision: Long = 0,
    val token: String? = null,
)

data class PendingMembership(
    val members: Set<String>,
    val revision: Long,
    val started: TimeMark = TimeSource.Monotonic.markNow(),
    val error: String? = null,
)

fun reconcileMembership(request: PendingMembership?, snapshot: Snapshot): PendingMembership? {
    request ?: return null
    val membership = snapshot.membership
    return when {
        // A finished failure keeps the message and shows the last confirmed speakers.
        // The 2026-10-17 Kitchen add left the new speaker checked after the phone had lost the session.
        membership.error != null && membership.pending == null -> null
        membership.error != null -> request.copy(error = membership.error)
        membership.revision > request.revision && membership.pending == null &&
            membership.members.toSet() == request.members -> null
        request.started.elapsedNow().inWholeSeconds >= 45 ->
            request.copy(error = request.error ?: "Speaker selection was not confirmed.")
        else -> request
    }
}

fun requestedMembers(ui: PlayerUiState): Set<String> = ui.membership?.members
    ?: ui.snapshot.membership.pending?.toSet()
    ?: ui.snapshot.membership.members.toSet()

fun membershipError(ui: PlayerUiState): String? = ui.membership?.error ?: ui.speakerDraft?.error ?: ui.snapshot.membership.error
fun membershipPending(ui: PlayerUiState) = ui.membership != null || ui.snapshot.membership.pending != null

/** A confirmed member that the pending request excludes is being removed, not merely unchecked. */
fun leavingMember(pending: Boolean, confirmed: Boolean, requested: Boolean) = pending && confirmed && !requested

fun canToggleMembership(ui: PlayerUiState, device: Device): Boolean {
    val s = ui.snapshot
    return ui.destination == null && device.available && deviceKind(device) == DeviceKind.SPEAKER && s.membership.available &&
        s.membership.token != null && s.receiver.connected && !s.connecting && !s.busy &&
        !membershipPending(ui)
}
