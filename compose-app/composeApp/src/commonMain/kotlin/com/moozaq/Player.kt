package com.moozaq

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.TimeSource
import kotlin.time.TimeMark
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Serializable data class Device(val id: String, val name: String, val model: String = "", val cast_type: String = "", val available: Boolean = true, val temporary: Boolean = false)
@Serializable data class OutputState(val id: String, val connected: Boolean = false, val state: String = "UNKNOWN", val title: String? = null, val volume: Double? = null, val muted: Boolean = false, val volume_revision: Long = 0, val control_id: String? = null, val session_id: String? = null)
@Serializable data class GroupMember(val id: String, val name: String, val connected: Boolean = false, val state: String = "UNKNOWN", val muted: Boolean = false, val volume: Double? = null, val volume_revision: Long = 0)
@Serializable data class GroupInfo(val leader: String = "Unknown", val members: List<GroupMember> = emptyList(), val status: String = "Membership unverified")
@Serializable data class Receiver(
    val recovery: String? = null,
    val connected: Boolean = false, val device: String? = null, val title: String? = null,
    val state: String? = null, val position: Double? = null, val duration: Double? = null,
    val transport_revision: Long = 0, val seek_revision: Long = 0, val volume: Int? = null, val volume_revision: Long = 0, val source: String? = null, val mode: String? = null, val idle_reason: String? = null,
    /** The signed stream link expired; state EXPIRED means the speaker stopped on it. */
    val source_expired: Boolean = false, val expires_in: Double? = null,
    /** The link would run out before the mix ends; Resume renews it first. */
    val refresh_needed: Boolean = false
)
@Serializable data class Snapshot(
    val devices: List<Device> = emptyList(), val receiver: Receiver = Receiver(),
    val target: String? = null, val busy: Boolean = false, val connecting: Boolean = false,
    val catalog_revision: Int = 0, val importing: Boolean = false, val refreshing_channels: List<String> = emptyList(), val catalog_message: String = "",
    val message: String = "Starting local engine…", val group: GroupInfo? = null,
    val membership: PlaybackMembership = PlaybackMembership(), val outputs: List<OutputState> = emptyList(),
    val connection_error: String? = null, val local_output: String? = null,
    val catalog_backend: String = "legacy", val catalog_curation: Boolean = false,
    val catalog_updates: Boolean = false, val personal_profile: Boolean = false,
    val app_revision: Int = 0, val master_revision: Int = 0, val generated_revision: Int = 0,
    val catalog_dirty: Boolean = false, val catalog_building: Boolean = false,
    val last_generation_error: String? = null, val personal_revision: Int = 0,
    val latest_revision: Int = 0, val last_update_check: String? = null,
    val update_error: String? = null, val last_release_url: String? = null
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
    suspend fun masterCatalog(): CatalogData = catalog()
    suspend fun personalProfile(): PersonalProfile = PersonalProfile()
    suspend fun rateSong(id: String, rating: Int): CatalogActionResult = error("Ratings are not available for this catalog.")
    suspend fun resetPersonal(): CatalogActionResult = error("Personal data reset is not available for this catalog.")
    suspend fun importChannel(url: String?)
    suspend fun previewVideo(source: String): VideoPreview = error("Video preview is not available for this catalog.")
    suspend fun importVideo(source: String, recommend: Boolean): CatalogActionResult = error("Video import is not available for this catalog.")
    suspend fun followChannel(url: String, refresh: Boolean): CatalogActionResult = error("Following channels is not available for this catalog.")
    suspend fun curate(action: String, fields: Map<String, JsonElement>): CatalogActionResult = error("Catalog curation is not available for this catalog.")
    suspend fun rebuildCatalog(): Snapshot = error("Catalog rebuild is not available for this catalog.")
    suspend fun publishCatalogRelease(confirmed: Boolean, expectedRevision: Int): JsonObject = error("Catalog releases are not available for this catalog.")
    suspend fun checkCatalogUpdates(): JsonObject = error("Catalog update checks are not available for this catalog.")
    fun close()
}
data class PendingCatalogAction(val key: String, val failed: Boolean = false)
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
        !snapshot.busy && !snapshot.connecting && request.started.elapsedNow().inWholeSeconds >= 1 -> request.copy(failed=true)
        else -> request
    }
}
data class PlayerUiState(val library: CatalogData = CatalogData(), val snapshot: Snapshot = Snapshot(), val previewId: String? = null, val error: String? = null, val pendingVolumes: Map<String, PendingVolume> = emptyMap(), val mainVolume: PendingVolume? = null, val pendingSeek: PendingSeek? = null, val transport: PendingTransport? = null, val membership: PendingMembership? = null, val outputVolumes: Map<String, PendingVolume> = emptyMap(), val destination: PendingDestination? = null, val lastPlayed: Receiver? = null, val speakerDraft: SpeakerDraft? = null, val masterLibrary: CatalogData? = null, val videoPreview: VideoPreview? = null, val catalogAction: PendingCatalogAction? = null, val catalogError: String? = null, val discovery: DiscoverySnapshot? = null, val profile: PersonalProfile? = null, val latest_revision: Int = 0, val last_update_check: String? = null, val update_error: String? = null, val last_release_url: String? = null)
fun canResume(s: Snapshot) = s.receiver.connected && !s.connecting && !s.busy &&
    (s.receiver.state == "PAUSED" || (s.receiver.state == "EXPIRED" && s.receiver.source != null))
fun playbackLabel(s: Snapshot) = s.receiver.recovery ?: if(s.connecting) "Synchronizing…" else expiryLabel(s.receiver) ?: s.receiver.state?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Choose a mix to begin"
fun expiryLabel(r: Receiver) = when {
    r.state == "EXPIRED" -> "Stream link expired · Resume reloads from ${clockTime(r.position)}"
    r.source_expired && r.state == "PAUSED" -> "Paused · stream link expired, Resume refreshes it"
    else -> null
}
fun canPause(s: Snapshot) = s.receiver.connected && !s.connecting && !s.busy && s.receiver.state == "PLAYING"
fun canStart(s: Snapshot, mix: Mix) = s.receiver.connected && !s.connecting && !s.busy && !(s.receiver.source == mix.url && s.receiver.state in listOf("PLAYING", "BUFFERING"))
fun canRequestPlay(s: Snapshot, mix: Mix) = !s.busy && (s.target != null || s.local_output != null) && !(s.receiver.source == mix.url && s.receiver.state in listOf("PLAYING", "BUFFERING"))
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
    private var masterRevision = -1
    private var discoverySeed = 0L
    private var personalRevision = -1
    private var pollError: String? = null
    fun start() = scope.launch {
        while (isActive) {
            try {
                val s = backend.snapshot()
                if(s.catalog_revision != catalogRevision) {
                    val library = backend.catalog()
                    mutable.update { it.copy(library=library) }
                    catalogRevision=s.catalog_revision
                }
                if(s.catalog_curation && s.master_revision != masterRevision) {
                    val master = backend.masterCatalog()
                    mutable.update { it.copy(masterLibrary=master) }
                    masterRevision=s.master_revision
                }
                if(s.personal_profile && s.personal_revision != personalRevision) {
                    val profile = backend.personalProfile()
                    mutable.update { it.copy(profile=profile) }
                    personalRevision = s.personal_revision
                }
                if(state.value.discovery == null) {
                    val profile = state.value.profile ?: if(s.personal_profile) backend.personalProfile() else PersonalProfile()
                    val discovery = buildDiscovery(state.value.library, profile)
                    mutable.update { it.copy(profile=profile, discovery=discovery) }
                }
                mutable.update { it.copy(error=if(it.error==pollError) null else it.error, snapshot = s, lastPlayed = rememberPlayback(it.lastPlayed, s.receiver), destination = reconcileDestination(it.destination, s), membership = reconcileMembership(it.membership, s), outputVolumes = reconcileOutputVolumes(it.outputVolumes, s), transport = if(it.snapshot.target == s.target) reconcileTransport(it.transport, s) else null, pendingSeek = if(it.snapshot.target == s.target) reconcileSeek(it.pendingSeek, s) else null, mainVolume = if(it.snapshot.target == s.target) reconcileMainVolume(it.mainVolume, s) else null, pendingVolumes = if(it.snapshot.target == s.target) reconcileVolumes(it.pendingVolumes, s) else emptyMap()) }
            }
            catch (e: Exception) { if (e is CancellationException) throw e; pollError=e.message; mutable.update { it.copy(snapshot = it.snapshot.copy(receiver = it.snapshot.receiver.copy(connected=false,state="UNKNOWN",recovery="Remote connection unavailable"), connecting = false), error = e.message) } }
            delay(700)
        }
    }
    private fun beginCatalogAction(key: String): Boolean {
        if(state.value.catalogAction?.failed == false) return false
        mutable.update { it.copy(catalogAction=PendingCatalogAction(key), catalogError=null, error=if(it.error==pollError) null else it.error) }
        return true
    }
    private fun completeCatalogAction(key: String, warning: String? = null) {
        mutable.update { current -> if(current.catalogAction?.key == key) current.copy(catalogAction=null, catalogError=warning, error=if(current.snapshot.catalog_curation) current.error else null) else current }
    }
    private fun failCatalogAction(key: String, error: Exception) {
        if(error is CancellationException) throw error
        val message=error.message ?: "Catalog action was not confirmed."
        mutable.update { current -> if(current.catalogAction?.key == key) current.copy(catalogAction=current.catalogAction.copy(failed=true), catalogError=message, error=if(current.snapshot.catalog_curation) current.error else message) else current }
    }
    private suspend fun refreshCatalogs() {
        val library=backend.catalog()
        val master=if(state.value.snapshot.catalog_curation) backend.masterCatalog() else null
        mutable.update { it.copy(library=library, masterLibrary=master ?: it.masterLibrary) }
    }
    private suspend fun awaitRefresh() {
        val started=TimeSource.Monotonic.markNow()
        var snapshot=backend.snapshot()
        while(snapshot.importing && started.elapsedNow().inWholeSeconds<300) {
            delay(100)
            snapshot=backend.snapshot()
        }
        if(snapshot.importing) error("Catalog refresh was not confirmed in time.")
        refreshCatalogs()
    }
    fun importChannel(url: String? = null) {
        val key="refresh:${url ?: "all"}"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try { backend.importChannel(url); awaitRefresh(); completeCatalogAction(key) }
            catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun previewVideo(source: String) {
        val key="preview:$source"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                val preview=backend.previewVideo(source)
                mutable.update { current -> if(current.catalogAction?.key == key) current.copy(videoPreview=preview) else current }
                completeCatalogAction(key)
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun importVideo(source: String, recommend: Boolean) {
        val key="import:$source:$recommend"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                val result=backend.importVideo(source,recommend)
                val master=backend.masterCatalog()
                val id=result.id ?: state.value.videoPreview?.id
                val song=master.mixes.find { it.id == id }
                if(song==null || (recommend && result.eligible == true && (result.recommended != true || song.recommended_at==null))) error("The video was saved, but the recommendation was not confirmed.")
                mutable.update { it.copy(masterLibrary=master, videoPreview=null) }
                completeCatalogAction(key, if(recommend && result.recommended != true) "Saved in master, but no confirmed one-hour duration; not recommended." else null)
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun followChannel(url: String, refresh: Boolean) {
        val key="follow:$url:$refresh"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                val result=backend.followChannel(url,refresh)
                val master=backend.masterCatalog()
                val expected=result.url ?: url
                if(master.channels.none { it.url == expected || expected in it.aliases }) error("The channel was not confirmed in the master catalog.")
                mutable.update { it.copy(masterLibrary=master, videoPreview=null) }
                completeCatalogAction(key)
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun curate(action: String, song: String? = null, category: String? = null, status: String? = null, url: String? = null, mode: String? = null) {
        val target=song ?: url ?: action
        val key="$action:$target"
        if(!beginCatalogAction(key)) return
        val fields=buildMap {
            song?.let { put("song", JsonPrimitive(it)) }
            category?.let { put("category", JsonPrimitive(it)) }
            status?.let { put("status", JsonPrimitive(it)) }
            url?.let { put("url", JsonPrimitive(it)) }
            mode?.let { put("mode", JsonPrimitive(it)) }
        }
        scope.launch {
            try {
                backend.curate(action,fields)
                val master=backend.masterCatalog()
                val mix=song?.let { id -> master.mixes.find { it.id == id } }
                val channel=url?.let { expected -> master.channels.find { it.url == expected || expected in it.aliases } }
                val confirmed=when(action) {
                    "recommend" -> mix?.recommended_at != null
                    "unrecommend" -> mix?.recommended_at == null
                    "hide" -> mix?.hidden == true
                    "restore" -> mix?.hidden == false
                    "category" -> mix?.category_override == category
                    "review" -> mix?.review_status == status
                    "source_mode" -> channel?.publication_mode == mode
                    else -> false
                }
                if(!confirmed) error("Catalog change was not confirmed in the master catalog.")
                mutable.update { it.copy(masterLibrary=master) }
                completeCatalogAction(key)
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun rebuildCatalog() {
        val key="rebuild"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                val result=backend.rebuildCatalog()
                val fresh=backend.snapshot()
                if(fresh.catalog_dirty || fresh.catalog_building || fresh.app_revision != result.app_revision) error("The app catalog revision was not confirmed.")
                refreshCatalogs()
                mutable.update { it.copy(snapshot=fresh) }
                completeCatalogAction(key)
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun publishCatalogRelease(revision: Int = state.value.snapshot.app_revision) {
        val key="publish-release"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                val result=backend.publishCatalogRelease(true, revision)
                val releasedRevision=result["revision"]?.jsonPrimitive?.int ?: error("The release revision was not confirmed.")
                val assets=result["assets"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                if(releasedRevision != revision || result["tag"]?.jsonPrimitive?.contentOrNull != "catalog-v$revision" ||
                    assets.toSet() != setOf("app-catalog.db", "catalog-manifest.json")) {
                    error("The confirmed release revision or assets do not match.")
                }
                val fresh=backend.snapshot()
                if(fresh.catalog_dirty || fresh.catalog_building || fresh.app_revision != revision) error("The release was not confirmed in the app catalog.")
                mutable.update { it.copy(snapshot=fresh) }
                completeCatalogAction(key)
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun checkCatalogUpdates() {
        val key="check-updates"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                val result=backend.checkCatalogUpdates()
                val status=result["status"]?.jsonPrimitive?.contentOrNull ?: error("The update check did not return a status.")
                if(status !in setOf("current", "updated", "update_required", "error")) error("The update check returned an unknown status.")
                val fresh=backend.snapshot()
                if(fresh.last_update_check == null) error("The update check time was not confirmed.")
                if(status == "updated" && fresh.app_revision != result["revision"]?.jsonPrimitive?.int) {
                    error("The downloaded catalog revision was not confirmed.")
                }
                mutable.update { it.copy(snapshot=fresh) }
                completeCatalogAction(key, when(status) {
                    "error" -> result["error"]?.jsonPrimitive?.contentOrNull ?: "Catalog update failed."
                    "update_required" -> result["required_version"]?.jsonPrimitive?.contentOrNull?.let { "App update required: $it" }
                    else -> null
                })
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun rateSong(id: String, rating: Int) {
        val key="rate:$id:$rating"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                backend.rateSong(id,rating)
                val profile=backend.personalProfile()
                val confirmed=if(rating==0) id !in profile.ratings else profile.ratings[id] == rating
                if(!confirmed) error("The rating was not confirmed in the personal database.")
                mutable.update { it.copy(profile=profile) }
                completeCatalogAction(key)
                refreshDiscovery()
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun resetPersonal() {
        val key="reset-personal"
        if(!beginCatalogAction(key)) return
        scope.launch {
            try {
                backend.resetPersonal()
                val profile=backend.personalProfile()
                if(profile.ratings.isNotEmpty() || profile.listening.isNotEmpty()) error("Personal data was not completely cleared.")
                mutable.update { it.copy(profile=profile) }
                completeCatalogAction(key)
                refreshDiscovery()
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun refreshDiscovery() {
        val key="discovery-refresh"
        if(!beginCatalogAction(key)) return
        val seed=++discoverySeed
        scope.launch {
            try {
                val library=backend.catalog()
                val profile=backend.personalProfile()
                val discovery=buildDiscovery(library,profile,seed)
                mutable.update { it.copy(library=library, discovery=discovery) }
                completeCatalogAction(key)
            } catch(e: Exception) { failCatalogAction(key,e) }
        }
    }
    fun togglePlaybackDevice(device: Device) {
        val ui = state.value
        if (!canToggleMembership(ui, device)) return
        val current = requestedMembers(ui)
        if (device.id in current && current.size == 1) { command("stop"); return }
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
                val message=e.message ?: "Speaker selection was not confirmed."
                mutable.update { current->
                    current.copy(
                        membership=null,
                        snapshot=current.snapshot.copy(membership=current.snapshot.membership.copy(error=message, pending=null)),
                    )
                }
            }
        }
    }
    fun dismissMembership() {
        val draftGeneration=if(state.value.speakerDraft!=null) ++playGeneration else null
        if(draftGeneration!=null) mutable.update {it.copy(speakerDraft=null,error=null)}
        scope.launch {
            try {
                backend.dismissMembership()
                val snapshot = backend.snapshot()
                mutable.update {
                    if(draftGeneration!=null && draftGeneration!=playGeneration) it
                    else it.copy(snapshot=snapshot,membership=null,speakerDraft=if(draftGeneration!=null) null else it.speakerDraft)
                }
            } catch (e: Exception) {
                if(e is CancellationException) throw e
                mutable.update {
                    if(draftGeneration!=null && draftGeneration!=playGeneration) it
                    else it.copy(membership=it.membership?.copy(error=e.message ?: "Could not read speakers."))
                }
            }
        }
    }
    fun outputVolume(id: String, value: Double) {
        val output = state.value.snapshot.outputs.find { it.id == id } ?: return
        mutable.update { it.copy(outputVolumes=it.outputVolumes + (id to PendingVolume(value, output.volume_revision))) }
        action(blocksTransport = false) { backend.outputVolume(id, value) }
    }
    fun dismissOutputVolume(id: String) { mutable.update { it.copy(outputVolumes=it.outputVolumes - id) } }
    fun memberVolume(id: String, value: Double) {
        val member = state.value.snapshot.group?.members?.find { it.id == id } ?: return
        mutable.update { it.copy(pendingVolumes = it.pendingVolumes + (id to PendingVolume(value, member.volume_revision))) }
        action(blocksTransport = false) { backend.memberVolume(id, value) }
    }
    fun seek(position: Double) {
        mutable.update { it.copy(pendingSeek=PendingSeek(position, it.snapshot.receiver.seek_revision)) }
        action { backend.command("seek", position) }
    }
    fun dismissSeek() { mutable.update { it.copy(pendingSeek=null) } }
    fun mainVolume(value: Double) {
        mutable.update { it.copy(mainVolume=PendingVolume(value, it.snapshot.receiver.volume_revision)) }
        action(blocksTransport = false) { backend.command("volume", value) }
    }
    fun dismissMainVolume() { mutable.update { it.copy(mainVolume=null) } }
    fun dismissVolume(id: String) { mutable.update { it.copy(pendingVolumes = it.pendingVolumes - id) } }
    private var playGeneration=0L
    internal var draftJoinTimeoutMs=15000L
    fun preview(id: String) { mutable.update { it.copy(previewId = id) } }
    fun toggleSpeakerDraft(device: Device) {
        val ui=state.value
        if(!canToggleSpeakerDraft(ui,device)) return
        playGeneration++
        val current=speakerDraftMembers(ui)
        val requested=if(device.id in current) current-device.id else current+device.id
        mutable.update { it.copy(destination=null,speakerDraft=SpeakerDraft(requested),error=null) }
    }
    fun selectDevice(id: String) {
        if (state.value.snapshot.busy || state.value.destination?.let { !it.failed } == true) return
        playGeneration++
        mutable.update { it.copy(destination=PendingDestination(id),speakerDraft=null) }
        action { backend.selectDevice(id) }
    }
    fun play(mix: Mix) {
        val ui=state.value
        if(ui.destination!=null) return
        val target=ui.snapshot.target ?: ui.snapshot.local_output
        if(target==null) {
            mutable.update {it.copy(error="Choose a speaker first")};return
        }
        if(!canRequestPlay(ui.snapshot,mix)) return
        val generation=++playGeneration
        val localFallback=ui.snapshot.target==null
        val ready=canStart(ui.snapshot,mix)
        mutable.update { it.copy(
            destination=if(localFallback) PendingDestination(target) else it.destination,
            transport=PendingTransport("PLAYING",it.snapshot.receiver.state,it.snapshot.receiver.transport_revision,mix.url),
        ) }
        action {
            if(ui.snapshot.target!=target || (!ready && !ui.snapshot.receiver.connected && !ui.snapshot.connecting)) backend.selectDevice(target)
            if(!preparePlay(mix,target,generation)) return@action
            backend.play(mix.url)
        }
    }

    fun playDraft(mix: Mix) {
        val ui=state.value
        val draft=ui.speakerDraft ?: return
        if(draft.members.isEmpty()) {
            mutable.update {it.copy(error="Choose at least one speaker")};return
        }
        if(ui.destination?.let {!it.failed}==true || !canRequestDraftPlay(ui,mix)) return
        val target=draftPlayTarget(ui.snapshot,draft)
        if(target==null) {
            val message="Some selected speakers are unavailable. Read the speakers and try again."
            mutable.update {it.copy(error=message,speakerDraft=draft.copy(error=message))};return
        }
        val generation=++playGeneration
        val requested=draft.members
        val baseRevision=ui.snapshot.receiver.transport_revision
        val ready=ui.snapshot.target==target && canStart(ui.snapshot,mix)
        mutable.update {it.copy(destination=null,transport=PendingTransport("PLAYING",it.snapshot.receiver.state,baseRevision,mix.url),
            speakerDraft=draft.copy(playbackRevision=baseRevision,source=mix.url,playbackConfirmed=false,error=null))}
        action(generation=generation,onError={current,exception->
            val message=exception.message ?: "Speaker selection was not confirmed."
            val currentDraft=current.speakerDraft
            val pending=current.membership
            when {
                currentDraft!=null -> current.copy(speakerDraft=currentDraft.copy(error=if(currentDraft.playbackConfirmed)
                    "$message The mix started on the starting speaker, but the other speakers were not added." else message))
                pending!=null -> current.copy(membership=pending.copy(error=message))
                else -> current
            }
        }) {
            if(ui.snapshot.target!=target || (!ready && !ui.snapshot.receiver.connected && !ui.snapshot.connecting)) backend.selectDevice(target)
            if(!preparePlay(mix,target,generation)) return@action
            backend.play(mix.url)
            completeDraftPlay(mix,target,requested,baseRevision,generation)
        }
    }

    private suspend fun completeDraftPlay(mix:Mix,target:String,requested:Set<String>,baseRevision:Long,generation:Long) {
        var confirmedAt:TimeMark?=null
        while(generation==playGeneration) {
            val current=backend.snapshot()
            if(generation!=playGeneration) return
            if(current.target!=target) error("Playback moved before the selected speakers could join.")
            val active=current.receiver.connected && current.receiver.transport_revision>baseRevision &&
                current.receiver.source==mix.url && current.receiver.state in listOf("PLAYING","PAUSED","BUFFERING")
            if(!active) {
                if(confirmedAt!=null) error("Playback changed before the selected speakers could join.")
                current.connection_error?.let {error(it)}
                if(current.message.startsWith("Failed:")) error(current.message.removePrefix("Failed: "))
                delay(100)
                continue
            }
            if(confirmedAt==null) {
                confirmedAt=TimeSource.Monotonic.markNow()
                var valid=true
                mutable.update {state->
                    if(generation!=playGeneration) {valid=false;state}
                    else if(requested.size==1) state.copy(speakerDraft=null)
                    else state.copy(speakerDraft=state.speakerDraft?.copy(playbackConfirmed=true))
                }
                if(!valid || requested.size==1) return
            }
            val devicesReady=requested.all {id->current.devices.any {it.id==id && it.available && deviceKind(it)==DeviceKind.SPEAKER}}
            if(!devicesReady) error("A selected speaker became unavailable before it could join.")
            if(!current.busy && !current.connecting) {
                val token=current.membership.token
                if(current.membership.available && token!=null) {
                    var converted=false
                    mutable.update {state->
                        if(generation==playGeneration && state.speakerDraft!=null) {
                            converted=true
                            state.copy(speakerDraft=null,membership=PendingMembership(requested,current.membership.revision))
                        } else state
                    }
                    if(!converted) return
                    backend.setPlaybackDevices(requested.sorted(),token)
                    return
                }
            }
            if(confirmedAt?.elapsedNow()?.inWholeMilliseconds?.let {it>=draftJoinTimeoutMs}==true)
                error("The selected speakers could not join this session.")
            delay(100)
        }
    }

    /**
     * Explicitly move the current mix to another destination, e.g. from one speaker to a saved
     * speaker group. The click is the authorization; the mix is loaded on the new destination
     * only after its receiver connects. Stop or a newer request cancels before that LOAD.
     */
    fun movePlaybackTo(destinationId: String, mix: Mix) {
        val ui=state.value
        if(ui.destination!=null || ui.snapshot.busy) return
        mutable.update {it.copy(speakerDraft=null)}
        if(ui.snapshot.target==destinationId && ui.snapshot.receiver.connected && !ui.snapshot.connecting) {
            play(mix);return
        }
        val generation=++playGeneration
        mutable.update { it.copy(
            destination=PendingDestination(destinationId),
            transport=PendingTransport("PLAYING",it.snapshot.receiver.state,it.snapshot.receiver.transport_revision,mix.url),
        ) }
        action {
            backend.selectDevice(destinationId)
            if(!prepareMove(mix,destinationId,generation)) return@action
            backend.play(mix.url)
        }
    }
    private suspend fun prepareMove(mix:Mix,target:String,generation:Long):Boolean {
        val result=withTimeoutOrNull(20000) {
            while(true) {
                if(generation!=playGeneration) return@withTimeoutOrNull false
                val current=backend.snapshot()
                if(current.target!=target) return@withTimeoutOrNull false
                if(current.receiver.connected && !current.connecting) return@withTimeoutOrNull true
                current.connection_error?.let {error(it)}
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if(result==null) error("The mix was not moved: the speaker did not connect in time.")
        return result==true
    }
    private suspend fun preparePlay(mix:Mix,target:String,generation:Long):Boolean {
        val result=withTimeoutOrNull(15000) {
            var ready=false
            while(!ready) {
                if(generation!=playGeneration) return@withTimeoutOrNull false
                val current=backend.snapshot()
                if(current.target!=target) return@withTimeoutOrNull false
                if(canStart(current,mix)) ready=true
                else {
                    current.connection_error?.let {error(it)}
                    delay(100)
                }
            }
            ready
        }
        if(result==null) error("Speaker was not ready in time. Play was not started.")
        return result
    }
    fun command(name: String, value: Double? = null) {
        if(name == "stop") {
            playGeneration++
            mutable.update { it.copy(transport=PendingTransport("IDLE",it.snapshot.receiver.state,it.snapshot.receiver.transport_revision), membership=null, destination=null, speakerDraft=null) }
        }
        if(name == "pause" || name == "resume") {
            mutable.update { it.copy(transport=PendingTransport(if(name == "pause") "PAUSED" else "PLAYING",it.snapshot.receiver.state,it.snapshot.receiver.transport_revision)) }
        }
        action { backend.command(name, value) }
    }
    /** Runs a backend action. Volume changes pass blocksTransport=false: they have their own
     * pending state, so play, pause, seek and Stop stay enabled while a level is confirmed. */
    private fun action(blocksTransport:Boolean=true,generation:Long?=null,
        onError:(PlayerUiState,Exception)->PlayerUiState={state,_->state},block:suspend()->Unit) {
        mutable.update { it.copy(error = null, snapshot = if (blocksTransport) it.snapshot.copy(busy = true) else it.snapshot) }
        scope.launch {
            try {
                block()
                val s = backend.snapshot()
                mutable.update {current->
                    if(generation!=null && generation!=playGeneration) current
                    else current.copy(snapshot = s, lastPlayed = rememberPlayback(current.lastPlayed, s.receiver), destination = reconcileDestination(current.destination, s), membership = reconcileMembership(current.membership, s), outputVolumes = reconcileOutputVolumes(current.outputVolumes, s), transport = if(current.snapshot.target == s.target) reconcileTransport(current.transport, s) else null, pendingSeek = if(current.snapshot.target == s.target) reconcileSeek(current.pendingSeek, s) else null, mainVolume = if(current.snapshot.target == s.target) reconcileMainVolume(current.mainVolume, s) else null, pendingVolumes = if(current.snapshot.target == s.target) reconcileVolumes(current.pendingVolumes, s) else emptyMap())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.update {current->
                    if(generation!=null && generation!=playGeneration) current
                    else {
                        val updated=onError(current,e)
                        updated.copy(error = e.message, destination=updated.destination?.copy(failed=true), snapshot = updated.snapshot.copy(busy = false))
                    }
                }
            }
        }
    }
}

fun rememberPlayback(previous: Receiver?, receiver: Receiver): Receiver? =
    if(receiver.connected && receiver.state in listOf("PLAYING","PAUSED","BUFFERING") && !receiver.title.isNullOrBlank()) receiver else previous

/** Retain presentation during a remote handoff without enabling stale controls. */
fun presentedReceiver(ui: PlayerUiState): Receiver {
    val r=ui.snapshot.receiver
    val recovering=ui.snapshot.connecting || r.recovery!=null || membershipPending(ui)
    val previous=ui.lastPlayed
    return if(recovering && ui.destination==null && previous!=null && !r.connected)
        r.copy(title=r.title?:previous.title,source=r.source?:previous.source,
            position=r.position?:previous.position,duration=r.duration?:previous.duration,
            volume=r.volume?:previous.volume,recovery=r.recovery?:"Connecting…")
    else r
}

fun pickerDevices(s: Snapshot): List<Device> = s.devices.filterNot {it.temporary} +
    s.group?.members.orEmpty().filter {m->s.devices.none {it.id==m.id}}.map {Device(it.id,it.name,cast_type="audio")}
