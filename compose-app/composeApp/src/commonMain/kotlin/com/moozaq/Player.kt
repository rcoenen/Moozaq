package com.moozaq

import kotlinx.serialization.Serializable
import kotlin.time.TimeSource
import kotlin.time.TimeMark
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Serializable data class Device(val id: String, val name: String, val model: String = "", val cast_type: String = "", val available: Boolean = true)
@Serializable data class OutputState(val id: String, val connected: Boolean = false, val state: String = "UNKNOWN", val title: String? = null, val volume: Double? = null, val muted: Boolean = false, val volume_revision: Long = 0, val control_id: String? = null, val session_id: String? = null)
@Serializable data class GroupMember(val id: String, val name: String, val connected: Boolean = false, val state: String = "UNKNOWN", val muted: Boolean = false, val volume: Double? = null, val volume_revision: Long = 0)
@Serializable data class GroupInfo(val leader: String = "Unknown", val members: List<GroupMember> = emptyList(), val status: String = "Membership unverified")
@Serializable data class Receiver(
    val recovery: String? = null,
    val connected: Boolean = false, val device: String? = null, val title: String? = null,
    val state: String? = null, val position: Double? = null, val duration: Double? = null,
    val transport_revision: Long = 0, val seek_revision: Long = 0, val volume: Int? = null, val volume_revision: Long = 0, val source: String? = null, val mode: String? = null, val idle_reason: String? = null
)
@Serializable data class Snapshot(
    val devices: List<Device> = emptyList(), val receiver: Receiver = Receiver(),
    val target: String? = null, val busy: Boolean = false, val connecting: Boolean = false,
    val catalog_revision: Int = 0, val importing: Boolean = false, val catalog_message: String = "",
    val message: String = "Starting local engine…", val group: GroupInfo? = null,
    val membership: PlaybackMembership = PlaybackMembership(), val outputs: List<OutputState> = emptyList()
)
interface PlayerBackend {
    suspend fun snapshot(): Snapshot
    suspend fun selectDevice(id: String)
    suspend fun play(source: String)
    suspend fun command(name: String, value: Double? = null)
    suspend fun outputVolume(id: String, value: Double)
    suspend fun memberVolume(id: String, value: Double)
    suspend fun setPlaybackDevices(devices: List<String>, token: String)
    suspend fun dismissMembership()
    suspend fun catalog(): CatalogData
    suspend fun importChannel(url: String?)
    fun close()
}
data class PendingVolume(val value: Double, val revision: Long, val started: TimeMark = TimeSource.Monotonic.markNow(), val failed: Boolean = false)
fun reconcileVolumes(pending: Map<String, PendingVolume>, snapshot: Snapshot): Map<String, PendingVolume> = pending.mapNotNull { (id, request) ->
    val member = snapshot.group?.members?.find { it.id == id }
    when {
        member != null && member.volume_revision > request.revision && member.volume != null && kotlin.math.abs(member.volume - request.value) < 0.01 -> null
        request.started.elapsedNow().inWholeSeconds >= 10 -> id to request.copy(failed = true)
        else -> id to request
    }
}.toMap()
fun reconcileMainVolume(request: PendingVolume?, snapshot: Snapshot): PendingVolume? {
    request ?: return null
    val receiver = snapshot.receiver
    return when {
        receiver.volume_revision > request.revision && receiver.volume != null && kotlin.math.abs(receiver.volume / 100.0 - request.value) <= .015 -> null
        request.started.elapsedNow().inWholeSeconds >= 10 -> request.copy(failed=true)
        else -> request
    }
}
data class PendingSeek(val position: Double, val revision: Long, val started: TimeMark = TimeSource.Monotonic.markNow(), val failed: Boolean = false)
fun reconcileSeek(request: PendingSeek?, snapshot: Snapshot): PendingSeek? {
    request ?: return null
    return when {
        snapshot.receiver.seek_revision > request.revision -> null
        request.started.elapsedNow().inWholeSeconds >= 10 -> request.copy(failed=true)
        else -> request
    }
}
data class PendingTransport(val expected: String, val previous: String?, val revision: Long, val source: String? = null, val started: TimeMark = TimeSource.Monotonic.markNow(), val failed: Boolean = false)
fun reconcileTransport(request: PendingTransport?, snapshot: Snapshot): PendingTransport? {
    request ?: return null
    return when {
        snapshot.receiver.transport_revision > request.revision -> null
        !snapshot.busy && request.started.elapsedNow().inWholeSeconds >= 1 -> request.copy(failed=true)
        else -> request
    }
}
data class PlayerUiState(val library: CatalogData = CatalogData(), val snapshot: Snapshot = Snapshot(), val previewId: String? = null, val error: String? = null, val pendingVolumes: Map<String, PendingVolume> = emptyMap(), val mainVolume: PendingVolume? = null, val pendingSeek: PendingSeek? = null, val transport: PendingTransport? = null, val membership: PendingMembership? = null, val outputVolumes: Map<String, PendingVolume> = emptyMap(), val destination: PendingDestination? = null)
fun canResume(s: Snapshot) = s.receiver.connected && !s.connecting && !s.busy && s.receiver.state == "PAUSED"
fun playbackLabel(s: Snapshot) = s.receiver.recovery ?: if(s.connecting) "Synchronizing…" else s.receiver.state?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Choose a mix to begin"
fun canPause(s: Snapshot) = s.receiver.connected && !s.connecting && !s.busy && s.receiver.state == "PLAYING"
fun canStart(s: Snapshot, mix: Mix) = s.receiver.connected && !s.connecting && !s.busy && !(s.receiver.source == mix.url && s.receiver.state in listOf("PLAYING", "BUFFERING"))
fun clockTime(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite()) return "—"
    val n = seconds.toInt().coerceAtLeast(0)
    val sec = (n % 60).toString().padStart(2, '0')
    val min = (n / 60 % 60).toString().padStart(2, '0')
    return if (n >= 3600) "${n / 3600}:$min:$sec" else "${n / 60}:$sec"
}
class PlayerStore(private val backend: PlayerBackend, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = mutable.asStateFlow()
    private var catalogRevision = -1
    fun start() = scope.launch {
        while (isActive) {
            try { val s = backend.snapshot(); if(s.catalog_revision != catalogRevision) { val library = backend.catalog(); mutable.update { it.copy(library=library) }; catalogRevision=s.catalog_revision }; mutable.update { it.copy(snapshot = s, destination = reconcileDestination(it.destination, s), membership = reconcileMembership(it.membership, s), outputVolumes = reconcileOutputVolumes(it.outputVolumes, s), transport = if(it.snapshot.target == s.target) reconcileTransport(it.transport, s) else null, pendingSeek = if(it.snapshot.target == s.target) reconcileSeek(it.pendingSeek, s) else null, mainVolume = if(it.snapshot.target == s.target) reconcileMainVolume(it.mainVolume, s) else null, pendingVolumes = if(it.snapshot.target == s.target) reconcileVolumes(it.pendingVolumes, s) else emptyMap()) } }
            catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(snapshot = it.snapshot.copy(receiver = Receiver(), connecting = false), error = e.message) } }
            delay(700)
        }
    }
    fun importChannel(url: String? = null) {
        scope.launch {
            try { backend.importChannel(url); mutable.update { it.copy(error=null) } }
            catch(e: Exception) { if(e is CancellationException) throw e; mutable.update { it.copy(error=e.message) } }
        }
    }
    fun togglePlaybackDevice(device: Device) {
        val ui = state.value
        if (!canToggleMembership(ui, device)) return
        val current = requestedMembers(ui)
        val requested = if (device.id in current) current - device.id else current + device.id
        val token = ui.snapshot.membership.token ?: return
        mutable.update { it.copy(membership=PendingMembership(requested, ui.snapshot.membership.revision)) }
        scope.launch {
            try {
                backend.setPlaybackDevices(requested.sorted(), token)
                val snapshot = backend.snapshot()
                mutable.update { it.copy(snapshot=snapshot, membership=reconcileMembership(it.membership, snapshot)) }
            } catch (e: Exception) {
                if(e is CancellationException) throw e
                mutable.update { it.copy(membership=it.membership?.copy(error=e.message ?: "Speaker selection was not confirmed.")) }
            }
        }
    }
    fun dismissMembership() {
        scope.launch {
            try {
                backend.dismissMembership()
                val snapshot = backend.snapshot()
                mutable.update { it.copy(snapshot=snapshot, membership=null) }
            } catch (e: Exception) {
                if(e is CancellationException) throw e
                mutable.update { it.copy(membership=it.membership?.copy(error=e.message ?: "Could not read speakers.")) }
            }
        }
    }
    fun outputVolume(id: String, value: Double) {
        val output = state.value.snapshot.outputs.find { it.id == id } ?: return
        mutable.update { it.copy(outputVolumes=it.outputVolumes + (id to PendingVolume(value, output.volume_revision))) }
        action { backend.outputVolume(id, value) }
    }
    fun dismissOutputVolume(id: String) { mutable.update { it.copy(outputVolumes=it.outputVolumes - id) } }
    fun memberVolume(id: String, value: Double) {
        val member = state.value.snapshot.group?.members?.find { it.id == id } ?: return
        mutable.update { it.copy(pendingVolumes = it.pendingVolumes + (id to PendingVolume(value, member.volume_revision))) }
        action { backend.memberVolume(id, value) }
    }
    fun seek(position: Double) {
        mutable.update { it.copy(pendingSeek=PendingSeek(position, it.snapshot.receiver.seek_revision)) }
        action { backend.command("seek", position) }
    }
    fun dismissSeek() { mutable.update { it.copy(pendingSeek=null) } }
    fun mainVolume(value: Double) {
        mutable.update { it.copy(mainVolume=PendingVolume(value, it.snapshot.receiver.volume_revision)) }
        action { backend.command("volume", value) }
    }
    fun dismissMainVolume() { mutable.update { it.copy(mainVolume=null) } }
    fun dismissVolume(id: String) { mutable.update { it.copy(pendingVolumes = it.pendingVolumes - id) } }
    fun preview(id: String) { mutable.update { it.copy(previewId = id) } }
    fun selectDevice(id: String) {
        if (state.value.snapshot.busy || state.value.destination?.let { !it.failed } == true) return
        mutable.update { it.copy(destination=PendingDestination(id)) }
        action { backend.selectDevice(id) }
    }
    fun play(mix: Mix) {
        if (state.value.destination == null && canStart(state.value.snapshot, mix)) {
            mutable.update { it.copy(transport=PendingTransport("PLAYING",it.snapshot.receiver.state,it.snapshot.receiver.transport_revision,mix.url)) }
            action { backend.play(mix.url) }
        }
    }
    fun command(name: String, value: Double? = null) {
        if(name == "stop") mutable.update { it.copy(transport=null, membership=null) }
        if(name == "pause" || name == "resume") {
            mutable.update { it.copy(transport=PendingTransport(if(name == "pause") "PAUSED" else "PLAYING",it.snapshot.receiver.state,it.snapshot.receiver.transport_revision)) }
        }
        action { backend.command(name, value) }
    }
    private fun action(block: suspend () -> Unit) {
        mutable.update { it.copy(error = null, snapshot = it.snapshot.copy(busy = true)) }
        scope.launch {
            try { block(); val s = backend.snapshot(); mutable.update { it.copy(snapshot = s, destination = reconcileDestination(it.destination, s), membership = reconcileMembership(it.membership, s), outputVolumes = reconcileOutputVolumes(it.outputVolumes, s), transport = if(it.snapshot.target == s.target) reconcileTransport(it.transport, s) else null, pendingSeek = if(it.snapshot.target == s.target) reconcileSeek(it.pendingSeek, s) else null, mainVolume = if(it.snapshot.target == s.target) reconcileMainVolume(it.mainVolume, s) else null, pendingVolumes = if(it.snapshot.target == s.target) reconcileVolumes(it.pendingVolumes, s) else emptyMap()) } }
            catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(error = e.message, destination=it.destination?.copy(failed=true), snapshot = it.snapshot.copy(busy = false)) } }
        }
    }
}
