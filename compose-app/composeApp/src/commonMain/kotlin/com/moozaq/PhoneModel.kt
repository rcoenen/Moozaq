package com.moozaq

/** Card states from the handoff: default, starting, now playing (this mix), paused (this mix). */
enum class PhoneCardState { Idle, Starting, NowPlaying, Paused }

/**
 * Everything the phone screen shows, derived only from confirmed receiver state plus an
 * explicit pending request. Pending states never invent playback; they only relabel the
 * control that was touched.
 */
data class PhonePlayback(
    val session: Boolean,
    val title: String?,
    val creator: String?,
    val mix: Mix?,
    val paused: Boolean,
    val starting: Boolean,
    val awaiting: String?,
    val status: String,
    val device: String,
)

/** "Casting to" / "Cast to {device}" and the app-bar Cast button wording. */
fun phoneDeviceName(ui: PlayerUiState): String {
    val s = ui.snapshot
    val target = s.devices.find { it.id == s.target }
    if (target?.temporary == true) return "${requestedMembers(ui).size.coerceAtLeast(2)} speakers"
    return target?.name ?: s.receiver.device ?: ui.lastPlayed?.device ?: "your speaker"
}

fun phonePlayback(ui: PlayerUiState, library: List<Mix>): PhonePlayback {
    val s = ui.snapshot
    val r = presentedReceiver(ui)
    val request = ui.transport?.takeIf { !it.failed }
    val starting = request != null && request.expected == "PLAYING" && request.source != null
    val awaiting = when {
        request == null -> null
        request.expected == "PAUSED" -> "Pausing…"
        request.expected == "IDLE" -> "Stopping…"
        request.source != null -> "Starting…"
        else -> "Resuming…"
    }
    val session = r.state in listOf("PLAYING", "PAUSED", "BUFFERING") || request != null ||
        (r.recovery != null && !r.title.isNullOrBlank())
    val pendingMix = pendingTransportMix(ui.transport, library)
    val shown = if (session || s.connecting) r else ui.lastPlayed
    val mix = pendingMix ?: library.find { it.url == shown?.source }
    val paused = r.state == "PAUSED"
    val status = when {
        starting -> "Starting…"
        awaiting != null -> awaiting
        ui.pendingSeek != null -> "Seeking…"
        paused -> "Paused"
        r.state == "BUFFERING" -> "Buffering…"
        r.state == "PLAYING" -> "Playing"
        s.connecting -> "Connecting…"
        r.recovery != null -> r.recovery
        else -> "Unconfirmed"
    }
    return PhonePlayback(
        session = session,
        title = when {
            pendingMix != null -> pendingMix.title
            session -> shown?.title ?: if (s.connecting) "Connecting…" else null
            else -> null
        },
        creator = mix?.creator,
        mix = mix,
        paused = paused,
        starting = starting,
        awaiting = awaiting,
        status = status,
        device = phoneDeviceName(ui),
    )
}

/** Mini player status: "Casting · Bedroom speaker", "Paused · …", "Pausing… · …". */
fun phoneMiniStatus(p: PhonePlayback): String {
    val prefix = when {
        p.starting -> "Starting…"
        p.awaiting != null -> p.awaiting
        p.paused -> "Paused"
        else -> "Casting"
    }
    return "$prefix · ${p.device}"
}

/** Stopped mini player hint: the last track, or the first-launch instruction. */
fun phoneIdleHint(ui: PlayerUiState, device: String): String =
    ui.lastPlayed?.title?.let { "Last played: $it" } ?: "Pick a mix to cast to $device"

/** Now playing status line: "{creator} · Playing". */
fun phoneNowStatus(p: PhonePlayback): String =
    p.creator?.let { "$it · ${p.status}" } ?: p.status

fun phoneCardState(ui: PlayerUiState, p: PhonePlayback, mix: Mix): PhoneCardState {
    val request = ui.transport?.takeIf { !it.failed }
    if (request?.expected == "PLAYING" && request.source == mix.url) return PhoneCardState.Starting
    val r = presentedReceiver(ui)
    val confirmed = r.connected && r.state in listOf("PLAYING", "PAUSED", "BUFFERING") && r.source == mix.url
    return when {
        confirmed && r.state == "PAUSED" -> PhoneCardState.Paused
        confirmed -> PhoneCardState.NowPlaying
        else -> PhoneCardState.Idle
    }
}

/** TalkBack label for a mix card. */
fun phoneCardLabel(state: PhoneCardState, mix: Mix): String = when (state) {
    PhoneCardState.Starting -> "Starting ${mix.title}"
    PhoneCardState.NowPlaying, PhoneCardState.Paused -> "Open player: ${mix.title}"
    PhoneCardState.Idle -> "Play ${mix.title}"
}

/** The app-bar Cast button, tinted connected (tertiary) while casting. */
fun phoneCasting(p: PhonePlayback): Boolean = p.session && !p.starting
