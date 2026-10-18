package com.moozaq.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.net.InetAddress
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.*
import com.google.android.gms.cast.framework.*
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.google.android.gms.common.images.WebImage
import com.moozaq.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/** Android owns discovery, source resolution, session observation and commands. No desktop RPC. */
class AndroidBackend(context: Context, private val automaticRecovery:Boolean=true) : PlayerBackend {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val diagnostics=AndroidDiagnostics(context.applicationContext)
    private val sources=OnDeviceSources(context.applicationContext)
    private val preferences=SpeakerPreferences(context.applicationContext)
    private var remembered=preferences.read()
    private val rejoin=RejoinPolicy()
    private var recoveryJob:Job?=null
    private var foregroundAt=0L
    private var lastDiagnostic=""
    private var lastDiagnosticAt=0L
    private val castContext=CastContext.getSharedInstance(context.applicationContext)
    private val sessions=castContext.sessionManager
    private val router=MediaRouter.getInstance(context.applicationContext)
    private val selector=MediaRouteSelector.Builder().addControlCategory(
        CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build()
    private var session:CastSession?=null
    private var joiningSession:CastSession?=null
    private var explicitDeviceId:String?=null
    private val connectionEpoch=ConnectionEpoch()
    private val statusGate=StatusRequestGate()
    private var mediaCallback:RemoteMediaClient.Callback?=null
    private var castListener:Cast.Listener?=null
    private var closed=false
    private var client:RemoteMediaClient?=null
    private var target:String?=remembered?.routeId
    private var connecting=false
    private var connectionError:String?=null
    private var connectingAt=0L
    private var message="Choose speakers to begin"
    private var state=Receiver(device=remembered?.name,recovery=remembered?.let {"Connecting…"})
    private var mediaEvent=0L
    private var mediaAt=0L
    private var requestedAt=0L
    private var transportRevision=0L
    private var seekRevision=0L
    private var volumeRevision=0L
    /** Main-volume confirmation for a multi-member group. Bumped only after every member confirms,
     *  so an interim per-member write cannot release the group pending thumb early. */
    private var groupVolumeRevision=0L
    private val routeVolumeRevision=mutableMapOf<String,Long>()
    /** Receiver-reported volume per route id. A member of a temporary group often has a fixed or
     *  zero-volume MediaRouter route, while its own receiver still reports a real level. */
    private val memberVolumes=mutableMapOf<String,ReceiverVolume>()
    private val memberVolumeReadAt=mutableMapOf<String,Long>()
    private val memberVolumeJobs=mutableMapOf<String,Job>()
    /** Bumped after a confirmed member write so a slower read that started earlier cannot overwrite it. */
    private val memberVolumeWrites=mutableMapOf<String,Long>()
    /** Last confirmed group-member routes. Volume only: it never feeds checkboxes or membership
     *  edits, so a lapsed fresh membership read cannot silently disable every visible slider. */
    private var volumeMembers:Set<String> = emptySet()
    private var routeVolumeDumpAt=0L
    private var membershipRevision=0L
    private val fence=PlaybackFence()
    private val volumeJobs=mutableMapOf<String,Deferred<Unit>>()
    private var transportJob:Job?=null
    private var pendingMembers:List<String>?=null
    private var membershipError:String?=null
    private var platformMembers:List<String>?=null
    private var platformEligible=false
    private var platformSession:String?=null
    private var platformContent:String?=null
    private var platformObservedAt=0L
    private var platformAttemptAt=0L
    private var platformJob:Job?=null
    private var uncertain=false
    private var adopting=false
    private var adoptedEndpoint:ValidatedEndpoint?=null
    private var adoptedSession:String?=null
    private var adoptedContent:String?=null
    private var adoptedState:String?=null
    private var handoffRouteSelected=false
    private var followJob:Job?=null
    private var followAttemptAt=0L
    /** Set when an adopted endpoint's discovered route disappears, so recovery can take over. */
    private var adoptionMissingAt=0L
    /** A membership change can be audible before the group's own status converges. Poll the group
     *  faster for a short window so confirmed checkmarks catch up; never treat an unconfirmed
     *  request as a confirmed member. */
    private var membershipRefreshUntil=0L
    private var membershipRefreshExpected:Set<String>?=null
    /** Set when a connected speaker answers status with no media. Cleared when media arrives. */
    private var blindSince=0L
    /** Set once recovery has settled on "nothing playing". A restart uses this instead of [blindSince]. */
    private var idleSince=0L
    private var activePermit:MembershipPermit?=null
    private var membershipGate:CompletableDeferred<Unit>?=null
    private var library:CatalogData?=null
    private var catalogRevision=1
    private var importing=false
    private var refreshing=emptyList<String>()
    private var catalogMessage="Catalog ready"
    private var foreground=false
    private val routeChangeRecovery=RouteChangeRecovery()
    private var routeChangeJob:Job?=null
    private val topologyChecked=mutableMapOf<String,Long>()
    private val dynamicIds=mutableSetOf<String>()
    /** Saved Google Home groups. Once this list is known, an unknown group route is temporary. */
    private val savedGroupIds=mutableSetOf<String>()
    private var savedGroupsKnown=false
    private var topologyProbeAt=0L
    private val inventory=SpeakerInventory()
    private var activeLoad:LoadObservation?=null
    private val sourceByContent=mutableMapOf<String,String>()

    private val routeCallback=object:MediaRouter.Callback() {
        override fun onRouteSelected(router:MediaRouter,route:MediaRouter.RouteInfo,reason:Int) {
            diagnostics.event("route.selected","name=${route.name} type=${route.javaClass.simpleName} reason=$reason")
        }
        override fun onRouteUnselected(router:MediaRouter,route:MediaRouter.RouteInfo,reason:Int) {
            diagnostics.event("route.unselected","name=${route.name} reason=$reason selected=${router.selectedRoute.name}")
        }
        override fun onRouteChanged(router:MediaRouter,route:MediaRouter.RouteInfo) { membershipRevision++ }
        override fun onRouteVolumeChanged(router:MediaRouter,route:MediaRouter.RouteInfo) {
            routeVolumeRevision[route.id]=(routeVolumeRevision[route.id]?:0)+1
            routeVolume(route)?.let {level->
                memberVolumes[route.id]=memberVolumes[route.id]?.copy(level=level) ?: ReceiverVolume(level,false)
            }
        }
    }
    private fun sameDevice(left:String?,right:String?)=left!=null && right!=null &&
        ReceiverTopology.canonicalDeviceId(left)==ReceiverTopology.canonicalDeviceId(right)
    private fun acceptsSession(s:CastSession):Boolean = !closed &&
        (explicitDeviceId==null || s.castDevice?.deviceId==null || sameDevice(s.castDevice?.deviceId,explicitDeviceId))
    private fun attachmentRole(s:CastSession)=when {
        s===session->SessionAttachmentRole.ATTACHED
        s===joiningSession->SessionAttachmentRole.JOINING
        else->SessionAttachmentRole.STALE
    }
    private fun castReason(error:Int)=castContext.getCastReasonCodeForCastStatusCode(error)
    private fun logSessionFailure(kind:String,s:CastSession,error:Int) {
        val reason=castReason(error)
        diagnostics.event(kind,"code=$error reason=$reason/${castReasonName(reason)} role=${attachmentRole(s)} route=${router.selectedRoute.name}")
    }
    private fun cancelRouteChange() {routeChangeJob?.cancel();routeChangeJob=null}
    private fun sessionPending(s:CastSession) {
        if(!acceptsSession(s)) return
        joiningSession=s;connectionPending()
    }
    private fun sessionFailed(s:CastSession,message:String) {
        // A replaced route often ends after the new route has already connected.
        if(closed || (s!==session && s!==joiningSession)) {
            diagnostics.event("session.stale","ignored end/failure");return
        }
        cancelRouteChange();joiningSession=null
        disconnected(message)
    }
    private val sessionListener=object:SessionManagerListener<CastSession> {
        override fun onSessionStarting(s:CastSession) { diagnostics.event("session.starting");sessionPending(s) }
        override fun onSessionStarted(s:CastSession,id:String) { attach(s) }
        override fun onSessionStartFailed(s:CastSession,error:Int) { logSessionFailure("session.start.failed",s,error);sessionFailed(s,"Speaker connection failed ($error).") }
        override fun onSessionResuming(s:CastSession,id:String) { diagnostics.event("session.resuming");sessionPending(s) }
        override fun onSessionResumed(s:CastSession,wasSuspended:Boolean) { attach(s) }
        override fun onSessionResumeFailed(s:CastSession,error:Int) { logSessionFailure("session.resume.failed",s,error);sessionFailed(s,"Could not rejoin the speaker ($error).") }
        override fun onSessionSuspended(s:CastSession,reason:Int) {
            if(s!==session || closed) return
            connectionPending();mediaAt=0;state=state.copy(connected=false,recovery="Reconnecting…")
        }
        override fun onSessionEnding(s:CastSession) { }
        override fun onSessionEnded(s:CastSession,error:Int) {
            val mapped=castReason(error);val reason=effectiveCastReason(error,mapped);val role=attachmentRole(s)
            diagnostics.event("session.ended","code=$error mapped=$mapped/${castReasonName(mapped)} effective=$reason/${castReasonName(reason)} role=$role route=${router.selectedRoute.name}")
            if((adopting || adoptedEndpoint!=null) && role==SessionAttachmentRole.ATTACHED) {
                diagnostics.event("session.migrated","adopted=${adoptedEndpoint!=null}")
                detach();connecting=false;return
            }
            if(routeChangeRecovery.shouldHold(reason,role)) holdRouteChange(s,reason) else sessionFailed(s,"Speaker disconnected")
        }
    }
    init {
        diagnostics.event("backend.created","version=0.5.0-standalone currentSession=${sessions.currentCastSession!=null}")
        // Migration: the rejected companion build's token has no use in a standalone app.
        java.io.File(context.filesDir,"bridge.json").delete()
        sessions.addSessionManagerListener(sessionListener,CastSession::class.java)
        remembered?.let {inventory.update(listOf(it.placeholder()))}
        sessions.currentCastSession?.takeIf {it.isConnected}?.let {attach(it)}
    }
    fun foreground(active:Boolean) {
        if(closed || active==foreground) return
        foreground=active
        diagnostics.event("lifecycle.foreground",active.toString())
        if(active) {
            foregroundAt=SystemClock.elapsedRealtime()
            router.addCallback(selector,routeCallback,MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            sessions.currentCastSession?.takeIf {it.isConnected && it!==session}?.let {attach(it)}
            if(remembered!=null && session?.isConnected!=true) connectionPending()
        }
        else {router.removeCallback(routeCallback);rejoin.invalidate();cancelRouteChange();recoveryJob?.cancel();recoveryJob=null}
        if(active && !uncertain && !useDirect() && blindSince==0L) requestStatus()
    }
    private fun connectionPending() {connectionError=null;connecting=true;connectingAt=SystemClock.elapsedRealtime()}
    private fun detach() {
        connectionEpoch.advance();statusGate.clear()
        mediaCallback?.let {client?.unregisterCallback(it)}
        castListener?.let {session?.removeCastListener(it)}
        mediaCallback=null;castListener=null;client=null;session=null
    }
    private fun disconnected(reason:String) {
        diagnostics.event("session.disconnected",reason)
        detach();mediaAt=0;connecting=false
        connectionError=reason
        state=state.copy(connected=false,state="UNKNOWN",recovery=reason);message=reason
    }
    private fun attach(s:CastSession) {
        diagnostics.event("session.attach","device=${s.castDevice?.friendlyName} connected=${s.isConnected} route=${router.selectedRoute.name} kind=${router.selectedRoute.javaClass.simpleName} targetMatches=${target==router.selectedRoute.id}")
        if(!acceptsSession(s) || !s.isConnected || s!==sessions.currentCastSession) {
            diagnostics.event("session.stale","ignored attach");return
        }
        cancelRouteChange();detach()
        session=s;client=s.remoteMediaClient;joiningSession=null;explicitDeviceId=null
        if(adoptedEndpoint!=null && sameDevice(s.castDevice?.deviceId,adoptedEndpoint?.deviceId)) handoffRouteSelected=false
        val c=client
        val epoch=connectionEpoch.value
        mediaCallback=object:RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                if(connectionEpoch.accepts(epoch) && session===s && client===c) receiveMedia()
            }
            override fun onMediaError(error:MediaError) {
                if(connectionEpoch.accepts(epoch)) diagnostics.event("media.error","code=${error.detailedErrorCode}")
            }
        }.also {c?.registerCallback(it)}
        castListener=object:Cast.Listener() {
            override fun onVolumeChanged() {
                if(connectionEpoch.accepts(epoch) && session===s) {volumeRevision++;readVolume()}
            }
        }.also {s.addCastListener(it)}
        val route=router.selectedRoute
        blindSince=0;idleSince=0
        if(!route.isDefault && !route.isBluetooth) {
            target=route.id
            // A temporary group is where a moved mix plays. Remembering it would make the next
            // open chase a group that no longer exists. The 2026-10-17 Kitchen add created one.
            if(!device(route).temporary) remember(route,s.castDevice)
        }
        rejoin.confirmed()
        mediaAt=0;connectionPending();readVolume();requestStatus()
    }
    private fun remember(route:MediaRouter.RouteInfo,cast:CastDevice?=CastDevice.getFromBundle(route.extras)) {
        val id=cast?.deviceId ?: return
        val d=device(route)
        val value=RememberedSpeaker(id,route.id,route.name,d.cast_type,d.model)
        if(value!=remembered) {
            remembered?.takeIf {it.deviceId==value.deviceId && it.routeId!=value.routeId}?.let {inventory.remove(it.routeId)}
            remembered=value;preferences.write(value)
        }
    }
    private fun sessionActivity(s:CastSession?)=CastSessionActivity(
        connected=s?.isConnected==true,connecting=s?.isConnecting==true,
        resuming=s?.isResuming==true,suspended=s?.isSuspended==true)
    private fun holdRouteChange(s:CastSession,reason:Int) {
        cancelRouteChange()
        val ticket=rejoin.generation
        connectionPending();message="Reconnecting to ${s.castDevice?.friendlyName ?: remembered?.name ?: "speaker"}…"
        state=state.copy(connected=false,state="UNKNOWN",recovery="Reconnecting…")
        var job:Job?=null
        job=scope.launch {
            try {
                delay(1000)
                if(closed || !foreground || !rejoin.accepts(ticket)) return@launch
                if(session?.isConnected==true) return@launch
                val route=routes().find {it.id==target && it.isEnabled}
                val expected=explicitDeviceId
                val targetAvailable=route!=null &&
                    (expected==null || CastDevice.getFromBundle(route.extras)?.deviceId==expected)
                val role=when {
                    session?.isConnected==true->SessionAttachmentRole.ATTACHED
                    joiningSession===s->SessionAttachmentRole.JOINING
                    else->SessionAttachmentRole.STALE
                }
                val retry=routeChangeRecovery.shouldRetry(reason,role,foreground,rejoin.accepts(ticket),
                    targetAvailable,router.selectedRoute.isDefault,sessionActivity(sessions.currentCastSession))
                if(!retry) {
                    if(role==SessionAttachmentRole.JOINING) {
                        joiningSession=null;disconnected("Speaker connection changed. Select it to retry.")
                    }
                    return@launch
                }
                diagnostics.event("destination.retry","device=${route!!.name} reason=CASTING_ROUTE_CHANGED")
                connectionPending()
                try {route.select()} catch(e:Exception) {
                    diagnostics.event("destination.retry.failed",e.javaClass.simpleName)
                    joiningSession=null;disconnected("Speaker connection changed. Select it to retry.")
                }
            } finally {if(routeChangeJob===job) routeChangeJob=null}
        }
        routeChangeJob=job
    }
    /** A fallback only joins an app freshly observed on the remembered device,
     * never a same-named replacement. */
    private fun recover(now:Long) {
        if(closed || !automaticRecovery || !foreground || transportJob?.isActive==true || recoveryJob?.isActive==true) return
        if(adopting || adoptedEndpoint!=null || uncertain) return
        val sdk=sessions.currentCastSession
        if(sdk?.isConnected==true) {
            if(sdk!==session && acceptsSession(sdk)) attach(sdk)
            return
        }
        val saved=remembered ?: return
        if(now-foregroundAt<500 || sessionActivity(sdk).blocksFallbackSelection() || !rejoin.ready(now)) return
        val route=routes().firstOrNull {
            it.isEnabled && CastDevice.getFromBundle(it.extras)?.deviceId==saved.deviceId
        } ?: return
        val cast=CastDevice.getFromBundle(route.extras) ?: return
        val address=cast.inetAddress ?: return
        target=route.id;remember(route)
        val ticket=rejoin.generation
        rejoin.attempted(now)
        recoveryJob=scope.launch {
            val running=withContext(Dispatchers.IO) {
                runCatching {PassiveReceiverProbe.defaultReceiverRunning(address,cast.servicePort)}.getOrNull()
            }
            if(closed || !foreground || !rejoin.accepts(ticket) || sessionActivity(sessions.currentCastSession).blocksFallbackSelection()) return@launch
            if(running==true && routes().any {it.id==route.id && it.isEnabled}) {
                diagnostics.event("recovery.rejoin","device=${route.name} existingReceiver=true")
                connectionPending()
                // LaunchOptions.relaunchIfRunning=false preserves the app observed above.
                // No source resolution, LOAD, PLAY, seek or volume is sent here.
                try {route.select()} catch(e:Exception) {
                    diagnostics.event("recovery.failed",e.javaClass.simpleName)
                    connecting=false;connectionError="Could not reconnect. Trying again…"
                }
            } else {
                connecting=false
                if(running==false) {
                    connectionError=null;message="Nothing playing"
                    state=state.copy(connected=false,state="IDLE",recovery=null)
                } else {
                    connectionError="Cannot reach ${saved.name}. Trying again…"
                    state=state.copy(connected=false,state="UNKNOWN",recovery=connectionError)
                }
            }
        }
    }
    private fun requestStatus() {
        val c=client?:return
        if(closed || session?.isConnected!=true) return
        val now=SystemClock.elapsedRealtime()
        val ticket=statusGate.begin(now) ?: return
        val epoch=connectionEpoch.value
        requestedAt=now
        // One bounded poll for this attachment. Neither errors nor stale ACKs refresh state.
        try {
            c.requestStatus().setResultCallback {result->
                if(!connectionEpoch.accepts(epoch) || client!==c || closed) return@setResultCallback
                val timely=statusGate.complete(ticket,SystemClock.elapsedRealtime())
                if(timely && result.status.isSuccess) receiveMedia()
                else {
                    diagnostics.event("status.failed","code=${result.status.statusCode} timely=$timely")
                    if(!c.hasMediaSession() && blindSince==0L) blindSince=SystemClock.elapsedRealtime()
                }
            }
        } catch(e:Exception) {
            statusGate.complete(ticket,SystemClock.elapsedRealtime())
            diagnostics.event("status.failed",e.javaClass.simpleName)
        }
    }
    private fun receiveMedia() {
        val c=client?:return
        if(closed || session?.isConnected!=true) return
        val hasMedia=c.hasMediaSession()
        if(hasMedia) blindSince=0L else if(blindSince==0L) blindSince=SystemClock.elapsedRealtime()
        mediaEvent++;mediaAt=SystemClock.elapsedRealtime();connecting=false;connectionError=null
        val info=c.mediaInfo
        val status=c.mediaStatus
        activeLoad?.observe(info?.contentId,status?.idleReason==MediaStatus.IDLE_REASON_ERROR)
        diagnostics.event("media.status","player=${c.playerState} idleReason=${status?.idleReason} hasSession=${c.hasMediaSession()} contentPresent=${info!=null}")
        val tag=info?.customData?.optJSONObject("moozaq")
        val source=verifiedSource(info?.contentId,tag?.optString("source"),tag?.optString("content_hash"))
            ?: info?.contentId?.let {sourceByContent[it]}
        val player=when(c.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING->"PLAYING"
            MediaStatus.PLAYER_STATE_PAUSED->"PAUSED"
            MediaStatus.PLAYER_STATE_BUFFERING,MediaStatus.PLAYER_STATE_LOADING->"BUFFERING"
            MediaStatus.PLAYER_STATE_IDLE->"IDLE"
            else->if(!c.hasMediaSession()) "IDLE" else "UNKNOWN"
        }
        state=Receiver(connected=true,device=session?.castDevice?.friendlyName,title=info?.metadata?.getString(MediaMetadata.KEY_TITLE),
            state=player,position=if(c.hasMediaSession()) c.approximateStreamPosition/1000.0 else null,
            duration=info?.streamDuration?.takeIf {it>0}?.div(1000.0),source=source,
            mode=if(source!=null) "Direct · Android" else null,
            idle_reason=if(status?.idleReason==MediaStatus.IDLE_REASON_ERROR) "Speaker could not play this stream" else null,
            transport_revision=transportRevision,seek_revision=seekRevision,volume_revision=volumeRevision,volume=state.volume)
        readVolume()
    }
    private fun readVolume() {
        if(session?.isConnected==true) {
            val ids=currentMemberRoutes().ifEmpty {volumeMembers}
            val average=groupAverageLevel(ids)
            val targetIsGroup=target?.let {id->routes().find {it.id==id}?.let {device(it).cast_type=="group"}}==true
            state=state.copy(volume=when {
                average!=null -> (average*100).roundToInt()
                ids.size>1 || targetIsGroup -> null
                else -> (session!!.volume*100).roundToInt()
            },volume_revision=if(targetIsGroup) groupVolumeRevision else volumeRevision)
        }
    }
    private fun routes()=(router.routes+listOf(router.selectedRoute)).filter {
        !it.isDefault && !it.isBluetooth && it.matchesSelector(selector)
    }.distinctBy {it.id}
    private fun routeVolume(route:MediaRouter.RouteInfo):Double?=
        if(route.volumeMax>0 && route.volumeHandling==MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE)
            route.volume.toDouble()/route.volumeMax else null
    private fun device(route:MediaRouter.RouteInfo):Device {
        val d=CastDevice.getFromBundle(route.extras)
        val kind=routeKind(route)
        val canonical=d?.deviceId?.let(ReceiverTopology::canonicalDeviceId)
        return Device(route.id,route.name,d?.modelName?:"",kind,route.isEnabled,
            temporary=isTemporaryGroup(canonical,dynamicIds,savedGroupIds,savedGroupsKnown,
                kind=="group",route is MediaRouter.GroupRouteInfo && router.routes.none {it.id==route.id}))
    }
    private fun routeKind(route:MediaRouter.RouteInfo):String {
        val d=CastDevice.getFromBundle(route.extras)
        return when {
            d?.hasCapability(CastDevice.CAPABILITY_MULTIZONE_GROUP)==true || route.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_GROUP->"group"
            d?.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)==true || route.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_TV->"cast"
            else->"audio"
        }
    }
    private fun participants():List<MediaRouter.RouteInfo> {
        val selected=router.selectedRoute
        return (selected as? MediaRouter.GroupRouteInfo)?.let { g->g.routesInGroup.filter {g.getSelectionState(it)==androidx.mediarouter.media.MediaRouteProvider.DynamicGroupRouteController.DynamicRouteDescriptor.SELECTED} }.orEmpty().ifEmpty {
            if(!selected.isDefault && !selected.isBluetooth && device(selected).cast_type=="audio") listOf(selected) else emptyList()
        }
    }
    private fun refreshTopology(now:Long) {
        routes().filter {device(it).cast_type=="audio"}.forEach {route->
            if(now-(topologyChecked[route.id]?:-30000)<30000) return@forEach
            topologyChecked[route.id]=now
            val address=CastDevice.getFromBundle(route.extras)?.inetAddress?:return@forEach
            scope.launch {
                val groups=withContext(Dispatchers.IO) {runCatching {ReceiverTopology.groups(address)}.getOrNull()} ?: return@launch
                dynamicIds.addAll(groups.dynamic)
                savedGroupIds.addAll(groups.configured)
                if(groups.configuredKnown) savedGroupsKnown=true
                diagnostics.event("topology.groups","dynamic=${groups.dynamic.size} configured=${groups.configured.size} configuredKnown=${groups.configuredKnown} savedKnown=$savedGroupsKnown")
            }
        }
    }
    /** A new group route may be a temporary group. Read saved/dynamic topology now rather than
     *  waiting for the 30 s cadence, so it cannot appear as a normal picker row first. */
    private fun probeUnknownGroupTopology(now:Long) {
        if(!savedGroupsKnown || now-topologyProbeAt<5_000) return
        val unknown=routes().any {route->
            if(routeKind(route)!="group") return@any false
            val canonical=CastDevice.getFromBundle(route.extras)?.deviceId
                ?.let(ReceiverTopology::canonicalDeviceId) ?: return@any false
            canonical !in dynamicIds && canonical !in savedGroupIds
        }
        if(unknown) {topologyProbeAt=now;topologyChecked.clear()}
    }
    /** Read the playing members' own receiver volume. MediaRouter can report a fixed or zero-volume
     *  route for a speaker inside a temporary group, so a fresh receiver GET_STATUS is the honest
     *  source. CONNECT + receiver GET_STATUS does not open the media channel and does not launch an app. */
    private fun refreshMemberVolumes(now:Long,ids:Set<String>) {
        if(closed) return
        ids.forEach {id->
            if(memberVolumeJobs[id]?.isActive==true) return@forEach
            if(now-(memberVolumeReadAt[id]?:-30_000)<3_000) return@forEach
            val route=routes().find {it.id==id && it.isEnabled} ?: return@forEach
            val cast=CastDevice.getFromBundle(route.extras) ?: return@forEach
            val address=cast.inetAddress ?: return@forEach
            val host=address.hostAddress ?: return@forEach
            if(!isLiteralPrivateHost(host) || cast.servicePort !in 1..65535) return@forEach
            memberVolumeReadAt[id]=now
            val generation=memberVolumeWrites[id]?:0
            val job=scope.launch {
                val observed=withContext(Dispatchers.IO) {
                    runCatching {LanCastTransport(address,cast.servicePort).use {MembershipChannel(it).receiverVolume()}}.getOrNull()
                }
                if(closed || (memberVolumeWrites[id]?:0)!=generation) return@launch
                if(observed!=null) {
                    memberVolumes[id]=observed
                    routeVolumeRevision[id]=(routeVolumeRevision[id]?:0)+1
                }
            }
            memberVolumeJobs[id]=job
            job.invokeOnCompletion {if(memberVolumeJobs[id]===job) memberVolumeJobs.remove(id)}
        }
    }
    /** Names each route's volume handling so a fixed or zero-volume member can be tied to Bedroom or
     *  Kitchen instead of an unnamed system route. Names and levels only; no stream URL or credential. */
    private fun logRouteVolumes(now:Long,memberIds:Set<String>) {
        if(now-routeVolumeDumpAt<30_000) return
        routeVolumeDumpAt=now
        val detail=routes().joinToString(" | ") {route->
            val handling=when(route.volumeHandling) {
                0->"muted" // MediaRouter's muted constant was removed from this androidx artifact.
                MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED->"fixed"
                MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE->"variable"
                else->"unknown-${route.volumeHandling}"
            }
            "${route.name}:max=${route.volumeMax},handling=$handling,volume=${route.volume},"+
                "member=${route.id in memberIds},selected=${route.id==target},enabled=${route.isEnabled}"
        }
        diagnostics.event("router.volume",detail)
    }
    private fun token()="${controlSessionId()}:${controlContent()?.hashCode()}"
    /** The adopted endpoint is the session once the Cast SDK is no longer attached to it. */
    private fun controlSessionId():String?=if(useDirect()) adoptedSession else session?.sessionId ?: adoptedSession
    private fun controlContent():String?=if(useDirect()) adoptedContent else client?.mediaInfo?.contentId ?: adoptedContent
    override suspend fun snapshot():Snapshot=withContext(Dispatchers.Main.immediate) {
        val now=SystemClock.elapsedRealtime()
        watchAdoption(now)
        recover(now)
        // After the Kitchen add, and again after opening 0.4.3, Bedroom stayed connected with no
        // media and status 2103 while the mix played on the group. Stop polling once that is clear.
        // A later restart was not connected at all: recovery reported nothing playing and returned.
        if(foreground && now-requestedAt>5000 && !uncertain && !useDirect() && blindSince==0L) requestStatus()
        val sdkConnected=session?.isConnected==true
        val blindFor=if(blindSince>0L && sdkConnected && client?.hasMediaSession()!=true) now-blindSince else 0L
        val idleSettled=!sdkConnected && !state.connected && state.state=="IDLE" && connectionError==null && !useDirect() && !connecting
        if(idleSettled) {if(idleSince==0L) idleSince=now} else idleSince=0L
        val idleFor=if(idleSince>0L) now-idleSince else 0L
        val adopted=adoptedEndpoint!=null
        if(shouldSeekMovedGroup(sdkConnected, client?.hasMediaSession()==true, blindFor) ||
            shouldSeekIdleGroup(sdkConnected, adopted, idleSettled, idleFor)) seekMovedGroup(now)
        if(foreground) {probeUnknownGroupTopology(now);refreshTopology(now);refreshPlatform(now)}
        if(connecting && now-connectingAt>25000) {
            connecting=false;message="Connection was not confirmed. Select the speaker to retry."
            connectionError=message
        }
        val fresh=session?.isConnected==true && mediaAt>0 && now-mediaAt<20000
        val direct=useDirect() && platformFresh()
        val settledIdle=!state.connected && state.state=="IDLE" && connectionError==null && !direct
        val members=currentMemberRoutes().toList()
        val memberIds=members.toSet().ifEmpty {volumeMembers}.also {if(it.isNotEmpty()) volumeMembers=it}
        val groupLevel=groupAverageLevel(memberIds)
        val targetIsGroup=target?.let {id->routes().find {it.id==id}?.let {device(it).cast_type=="group"}}==true
        val receiverVolume=when {
            groupLevel!=null -> (groupLevel*100).roundToInt()
            memberIds.size>1 || targetIsGroup -> null
            else -> state.volume
        }
        val receiverRevision=if(targetIsGroup) groupVolumeRevision else volumeRevision
        val receiver=if(fresh) state.copy(
            position=client?.takeIf {it.hasMediaSession()}?.approximateStreamPosition?.div(1000.0),
            volume=receiverVolume,
            transport_revision=transportRevision,seek_revision=seekRevision,volume_revision=receiverRevision)
        else if(direct) state.copy(connected=true,state=adoptedState ?: "UNKNOWN",recovery=null,
            volume=receiverVolume,
            transport_revision=transportRevision,seek_revision=seekRevision,volume_revision=receiverRevision)
        else state.copy(connected=false,state=if(settledIdle) "IDLE" else "UNKNOWN",
            volume=receiverVolume,
            recovery=if(connecting) "Connecting…" else connectionError ?: if(target!=null && !settledIdle) "Connecting…" else null)
        if(foreground) {
            refreshMemberVolumes(now,memberIds)
            logRouteVolumes(now,memberIds)
        }
        val outputs=routes().map {r->
            val selected=r.id==target
            val reporting=r.id in memberIds
            val member=memberVolumes[r.id]
            OutputState(r.id,connected=r.isEnabled,state=if(selected && fresh) receiver.state?:"UNKNOWN" else "UNKNOWN",
                title=if(selected) receiver.title else null,
                volume=outputVolumeForMember(reporting,member?.level,routeVolume(r)),
                muted=if(reporting) member?.muted==true else false,
                volume_revision=routeVolumeRevision[r.id]?:0,control_id=r.id)
        }
        val diagnostic="target=${routes().find {it.id==target}?.name} sdkRoute=${router.selectedRoute.name} sdkSession=${sessions.currentCastSession?.isConnected} attached=${session?.isConnected} client=${client!=null} mediaStatus=${client?.mediaStatus!=null} state=${receiver.state} connected=${receiver.connected} connecting=$connecting busy=${transportJob?.isActive==true} ageMs=${if(mediaAt>0) now-mediaAt else -1}"
        if(diagnostic.substringBefore(" ageMs=")!=lastDiagnostic || now-lastDiagnosticAt>15000) {
            diagnostics.event("snapshot",diagnostic+" video=${receiver.source?.let {Uri.parse(it).getQueryParameter("v")}} position=${receiver.position?.toInt()} sessionHash=${session?.sessionId?.let {contentHash(it).take(12)}}")
            lastDiagnostic=diagnostic.substringBefore(" ageMs=");lastDiagnosticAt=now
        }
        Snapshot(devices=inventory.update(routes().map {device(it)}),receiver=receiver,target=target,connecting=connecting,connection_error=connectionError,
            busy=transportJob?.isActive==true,catalog_revision=catalogRevision,importing=importing,
            refreshing_channels=refreshing,catalog_message=catalogMessage,message=message,
            outputs=outputs,
            group=if(members.size>1) GroupInfo(members=members.mapNotNull {id->
                val route=routes().find {it.id==id} ?: return@mapNotNull null
                val output=outputs.find {it.id==route.id}
                GroupMember(route.id,route.name,connected=output?.connected==true,state=output?.state?:"UNKNOWN",
                    muted=output?.muted==true,volume=output?.volume,volume_revision=routeVolumeRevision[route.id]?:0)
            }) else null,
            membership=PlaybackMembership(members=members,available=platformFresh() && membersMapped() && (fresh || direct) && (client?.hasMediaSession()==true || (direct && adoptedContent!=null)),
                pending=pendingMembers,error=membershipError,revision=membershipRevision,token=if(platformFresh() && membersMapped() && (fresh || direct)) token() else null))
    }
    override suspend fun selectDevice(id:String)=withContext(Dispatchers.Main.immediate) {
        check(transportJob?.isActive!=true && activePermit==null) {"Wait for the current command to finish."}
        adoptedEndpoint=null;adoptedSession=null;adoptedContent=null;adoptedState=null;uncertain=false;handoffRouteSelected=false
        blindSince=0;idleSince=0;followJob?.cancel();followJob=null;volumeMembers=emptySet();clearMembershipBurst()
        val route=routes().find {it.id==id && it.isEnabled}?:error("That speaker is unavailable.")
        diagnostics.event("destination.request","name=${route.name} alreadySelected=${route.isSelected} sdkSession=${sessions.currentCastSession?.isConnected}")
        rejoin.replace();cancelRouteChange();routeChangeRecovery.reset();rejoin.attempted(SystemClock.elapsedRealtime());recoveryJob?.cancel();recoveryJob=null
        remember(route)
        explicitDeviceId=CastDevice.getFromBundle(route.extras)?.deviceId;joiningSession=null
        volumeJobs.values.forEach {it.cancel()};volumeJobs.clear()
        fence.next();detach();target=id;mediaAt=0;state=Receiver(device=route.name);connectionPending()
        if(route.isSelected && sessions.currentCastSession?.isConnected==true) attach(sessions.currentCastSession!!)
        else route.select()
    }
    private fun connected():Pair<CastSession,RemoteMediaClient> {
        val s=session;val c=client
        check(s?.isConnected==true && c!=null && mediaAt>0 && SystemClock.elapsedRealtime()-mediaAt<20000) {"Wait for the speaker to synchronize."}
        return s!! to c!!
    }
    /** Generation + session binding ensures Stop/route changes invalidate late extraction results. */
    private suspend fun transport(block:suspend (Long,CastSession,RemoteMediaClient)->Unit) {
        val (s,c)=connected()
        val my=fence.next()
        transportJob?.cancel()
        val task=scope.async(start=CoroutineStart.LAZY) {block(my,s,c)}
        transportJob=task;task.start()
        try {task.await()} catch(e:Exception) {if(e !is CancellationException) message=e.message?:"Command failed.";diagnostics.event("command.failed","type=${e.javaClass.simpleName} message=${e.message}");throw e} finally {if(transportJob===task) transportJob=null}
    }
    private fun bound(g:Long,s:CastSession,c:RemoteMediaClient) {
        check(fence.isCurrent(g) && session===s && client===c && s.isConnected) {"The speaker session changed; command cancelled."}
    }
    private suspend fun confirmed(g:Long,s:CastSession,c:RemoteMediaClient,after:Long,predicate:()->Boolean) {
        receiverDeadline(20000, "The speaker did not confirm the requested playback change. No automatic retry was started.") {
            requestStatus()
            while(true) {
                bound(g,s,c)
                if(mediaEvent>after && predicate()) break
                delay(100)
            }
        }
    }
    override suspend fun play(source:String)=withContext(Dispatchers.Main.immediate) {
        check(activePermit==null && !useDirect()) {"Wait for the speaker group to reconnect before starting a mix."}
        transport {g,s,c->
            diagnostics.event("play.request","video=${Uri.parse(source).getQueryParameter("v")} device=${s.castDevice?.friendlyName}")
            message="Preparing mix on phone…"
            val audio=sources.resolve(source)
            diagnostics.event("source.ready","mime=${audio.mime} duration=${audio.duration}")
            bound(g,s,c)
            verifyDirectStream(audio.url)
            diagnostics.event("source.preflight","ordinaryFetch=accepted resolver=${BundledExtractor.VERSION}")
            bound(g,s,c)
            val metadata=MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE,audio.title);putString(MediaMetadata.KEY_ARTIST,audio.creator)
                audio.thumbnail?.let {addImage(WebImage(Uri.parse(it)))}
            }
            val media=MediaInfo.Builder(audio.url).setContentType(audio.mime).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(metadata).setCustomData(JSONObject().put("moozaq",JSONObject().put("source",source).put("route","direct").put("content_hash",contentHash(audio.url))))
                .apply {audio.duration?.let {setStreamDuration((it*1000).toLong())}}.build()
            val event=mediaEvent
            sourceByContent[audio.url]=source
            val observation=LoadObservation(audio.url)
            activeLoad=observation
            try {
            diagnostics.event("media.load","device=${s.castDevice?.friendlyName}")
            c.load(MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build()).checked()
            diagnostics.event("media.load.acknowledged")
            confirmed(g,s,c,event) {observation.requireNotFailed();c.isPlaying && c.mediaInfo?.contentId==audio.url}
            transportRevision++;message="Playing";diagnostics.event("play.confirmed")
            } finally {if(activeLoad===observation) activeLoad=null}
        }
    }
    override suspend fun command(name:String,value:Double?)=withContext(Dispatchers.Main.immediate) {
        diagnostics.event("command.request",name)
        if(name=="stop") awaitMembershipStop()
        if(name=="volume" && currentMemberRoutes().ifEmpty {volumeMembers}.size>1) {setGroupVolume(value!!);return@withContext}
        if(useDirect()) {directCommand(name,value);return@withContext}
        if(name=="volume") {
            // The Now playing slider writes the observed Cast session volume. On a temporary group
            // that is a group-level value, not proof that Bedroom and Kitchen each changed; the
            // Cast-to member rows use their own receiver. Success still needs a fresh callback and
            // a matching level, never the local setter alone.
            val (s,c)=connected();val level=value!!.coerceIn(0.0,1.0);val revision=volumeRevision
            volumeOperation("main") {
                s.volume=level
                try {
                    receiverDeadline(10000, "The speaker did not confirm the new volume.") {while(session===s && (volumeRevision<=revision || abs(s.volume-level)>.015)) delay(100)}
                    check(session===s && client===c) {"The volume session changed."}
                } catch(e:Exception) {
                    if(e !is CancellationException)
                        diagnostics.event("volume.main.failed","message=${e.message} sessionCurrent=${session===s}")
                    throw e
                }
            }
            return@withContext
        }
        if(name=="stop") {
            fence.next();transportJob?.cancel();transportJob=null
            rejoin.invalidate();cancelRouteChange();recoveryJob?.cancel();recoveryJob=null
            volumeJobs.values.forEach {it.cancel()};volumeJobs.clear()
            clearMembershipBurst()
        }
        transport {g,s,c->
            val event=mediaEvent
            val content=c.mediaInfo?.contentId
            val wasPaused=c.isPaused
            when(name) {
                "stop"->c.stop().checked()
                "pause"->{check(c.isPlaying) {"Speaker is not playing."};c.pause().checked()}
                "resume"->{check(c.isPaused) {"Speaker is not paused."};c.play().checked()}
                "seek"->{
                    check(c.hasMediaSession() && (c.isPlaying || wasPaused)) {"Wait for playback to settle before seeking."}
                    c.seek(MediaSeekOptions.Builder().setPosition((value!!*1000).toLong()).setResumeState(MediaSeekOptions.RESUME_STATE_UNCHANGED).build()).checked()
                }
                else->error("Unsupported command.")
            }
            confirmed(g,s,c,event) {
                requireSameMedia(content,c.mediaInfo?.contentId)
                when(name) {
                "stop"->!c.hasMediaSession() || c.playerState==MediaStatus.PLAYER_STATE_IDLE
                "pause"->c.isPaused
                "resume"->c.isPlaying
                "seek"->seekConfirmed(c.approximateStreamPosition/1000.0,value!!,wasPaused,c.isPlaying,c.isPaused)
                else->false
            }}
            if(name=="stop") {clearAdoption();clearMembershipBurst()}
            if(name=="seek") seekRevision++ else transportRevision++
        }
    }
    override suspend fun outputVolume(id:String,value:Double)=withContext(Dispatchers.Main.immediate) {
        val r=routes().find {it.id==id && it.isEnabled}?:error("Speaker unavailable.")
        val memberIds=currentMemberRoutes().ifEmpty {volumeMembers}
        if(usesReceiverVolume(memberIds,id)) {
            setMemberReceiverVolume(r,value)
            return@withContext
        }
        check(r.volumeHandling==MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE && r.volumeMax>0) {"Volume unavailable."}
        val requested=(value.coerceIn(0.0,1.0)*r.volumeMax).roundToInt()
        val revision=routeVolumeRevision[id]?:0
        volumeOperation(id) {
            r.requestSetVolume(requested)
            receiverDeadline(10000, "The speaker did not confirm the new volume.") {while((routeVolumeRevision[id]?:0)<=revision || r.volume!=requested) delay(100)}
        }
    }
    /** A group change writes every member proportionally from the confirmed average. The main
     *  pending clears only after every member reports its own new level. No LOAD or session volume. */
    private suspend fun setGroupVolume(value:Double) {
        val ids=currentMemberRoutes().ifEmpty {volumeMembers}
        if(ids.size<2) error("This speaker is not a group.")
        val routes=ids.map {id-> routes().find {it.id==id && it.isEnabled} ?: error("Speaker unavailable.")}
        val levels=ids.map {memberVolumes[it]?.level ?: error("Wait for speaker volumes.")}
        val targets=scaledGroupVolumes(levels,value)
        volumeOperation("main") {
            try {
                receiverDeadline(9_000, "The group volume was not confirmed.") {
                    ids.forEachIndexed {index,id->
                        check(groupVolumeMembershipValid(currentMemberRoutes().ifEmpty {volumeMembers},ids)) {
                            "Speakers changed while adjusting volume. Move the slider again."
                        }
                        writeMemberVolume(routes[index],targets[index],4_000)
                    }
                }
                val confirmed=ids.map {memberVolumes[it]?.level ?: error("The speaker did not confirm the new volume.")}
                val average=confirmed.average()
                groupVolumeRevision++
                state=state.copy(volume=(average*100).roundToInt(),volume_revision=groupVolumeRevision)
                diagnostics.event("volume.group","members=${ids.size} target=${(value.coerceIn(0.0,1.0)*100).roundToInt()} confirmed=${confirmed.size}")
            } catch(e:Exception) {
                if(e !is CancellationException)
                    diagnostics.event("volume.group.failed","members=${ids.size} message=${e.message}")
                throw e
            }
        }
    }
    /** The Mac path that has changed a member: SET_VOLUME on that speaker's own receiver, then a fresh
     *  receiver status. No media channel, no LOAD, no LAUNCH, no multizone SET. The group session
     *  volume is not this speaker's volume. */
    private suspend fun setMemberReceiverVolume(route:MediaRouter.RouteInfo,value:Double) {
        val level=value.coerceIn(0.0,1.0)
        volumeOperation(route.id) {
            try {
                writeMemberVolume(route,level)
                diagnostics.event("volume.member","route=${route.name} confirmed=true")
            } catch(e:Exception) {
                if(e !is CancellationException)
                    diagnostics.event("volume.member.failed","route=${route.name} message=${e.message}")
                throw e
            }
        }
    }
    /** Write and confirm one member without starting its own job. The caller owns cancellation. */
    private suspend fun writeMemberVolume(route:MediaRouter.RouteInfo,level:Double,timeoutMs:Long=6_000) {
        val cast=CastDevice.getFromBundle(route.extras) ?: error("Speaker unavailable.")
        val address=cast.inetAddress ?: error("Speaker unavailable.")
        val host=address.hostAddress ?: error("Speaker unavailable.")
        require(isLiteralPrivateHost(host)) {"Speaker unavailable."}
        require(cast.servicePort in 1..65535) {"Speaker unavailable."}
        val confirmed=withContext(Dispatchers.IO) {
            LanCastTransport(address,cast.servicePort).use {MembershipChannel(it).setVolume(level.coerceIn(0.0,1.0),timeoutMs)}
        }
        memberVolumes[route.id]=confirmed
        memberVolumeWrites[route.id]=(memberVolumeWrites[route.id]?:0)+1
        routeVolumeRevision[route.id]=(routeVolumeRevision[route.id]?:0)+1
    }
    private suspend fun volumeOperation(id:String, action:suspend ()->Unit) {
        volumeJobs.remove(id)?.cancel()
        val job=scope.async(start=CoroutineStart.LAZY) {action()}
        volumeJobs[id]=job;job.start()
        try {job.await()} finally {if(volumeJobs[id]===job) volumeJobs.remove(id)}
    }
    override suspend fun memberVolume(id:String,value:Double)=outputVolume(id,value)
    override suspend fun setPlaybackDevices(devices:List<String>,token:String)=withContext(Dispatchers.Main.immediate) {
        check(token==token()) {"The playback session changed."}
        when(membershipRoute(platformFresh(),router.selectedRoute is MediaRouter.GroupRouteInfo)) {
            MembershipPath.PLATFORM->platformChange(devices)
            MembershipPath.GROUP_ROUTE->groupRouteChange(devices)
            MembershipPath.REFUSE->error("This session does not allow adding a speaker.")
        }
    }
    private suspend fun groupRouteChange(devices:List<String>) {
        val group=(router.selectedRoute as? MediaRouter.GroupRouteInfo)?:error("This session does not allow adding a speaker.")
        val current=participants().map {it.id}.toSet()
        val desired=devices.toSet()
        require(desired.isNotEmpty()) {"Use Stop to silence the last speaker."}
        val added=desired-current;val removed=current-desired
        require(added.size+removed.size==1) {"Change one speaker at a time."}
        val route=routes().find {it.id in added+removed}?:error("Speaker unavailable.")
        pendingMembers=devices;membershipError=null
        try {
            val revision=membershipRevision
            if(added.isNotEmpty()) {
                check(group.isGroupable(route)) {"This speaker cannot join this session."}
                check(group.addRoute(route)==MediaRouter.GroupRouteInfo.ADD_ROUTE_SUCCESSFUL) {"Speaker join was not accepted."}
            } else {
                check(group.isUnselectable(route)) {"This speaker cannot leave this session."}
                check(group.removeRoute(route)==MediaRouter.GroupRouteInfo.REMOVE_ROUTE_SUCCESSFUL) {"Speaker removal was not accepted."}
            }
            receiverDeadline(30000, "Speaker selection was not confirmed.") {while(membershipRevision<=revision || participants().map {it.id}.toSet()!=desired) delay(100)}
            pendingMembers=null
        } catch(e:Exception) {pendingMembers=null;membershipError="Speaker selection was not confirmed.";throw e}
    }
    private suspend fun platformChange(devices:List<String>) {
        if(uncertain) error("Previous speaker change location is unconfirmed. Read the current speakers before changing it.")
        val current=currentMemberRoutes()
        val desired=devices.toSet()
        require(desired.isNotEmpty()) {"Use Stop to silence the last speaker."}
        require((desired-current).size+(current-desired).size==1) {"Change one speaker at a time."}
        val targets=devices.map {audioTarget(it)}
        startMembershipBurst(targets.map {it.deviceId}.toSet())
        topologyChecked.clear()
        val sessionId=controlSessionId() ?: error("Wait for the speaker to synchronize.")
        val contentId=controlContent() ?: error("Wait for the speaker to synchronize.")
        val player=controlPlayer()
        val known=platformMembers ?: targets.filter {it.routeId in current}.map {it.deviceId}
        val point=controlPoint() ?: error("Speaker unavailable.")
        val permit=MembershipPermit()
        val gate=CompletableDeferred<Unit>()
        activePermit=permit;membershipGate=gate;adopting=true;pendingMembers=devices;membershipError=null
        diagnostics.event("membership.request","speakers=${devices.size}")
        try {
            val outcome=withContext(Dispatchers.IO) {
                LanCastTransport(point.address,point.port).use {socket->
                    MembershipChannel(socket).apply(MembershipRequest(sessionId,contentId,player,known,targets),permit) {host,port->
                        val address=InetAddress.getByName(host)
                        check(isLiteralPrivateHost(host)) {"Returned group endpoint is not on a requested local speaker."}
                        LanCastTransport(address,port)
                    }
                }
            }
            rememberOutcome(outcome)
            pendingMembers=null;membershipRevision++
            diagnostics.event("membership.result","endpoint=${outcome.endpoint!=null} temporary=${outcome.temporaryDeviceId!=null}")
        } catch(e:MembershipRejected) {
            diagnostics.event("membership.outcome","sent=${e.sent} endpoint=${e.outcome?.endpoint!=null} members=${e.outcome?.members?.size} error=${e.message}")
            e.outcome?.let {rememberOutcome(it)}
            if(e.sent) {
                pendingMembers=null
                val current=adoptedEndpoint
                val currentHasMedia=current?.let {endpointHasMedia(it,sessionId,contentId)}==true
                // An omitted sessionEndpoint is not proof that playback stayed on the old
                // endpoint. If that endpoint no longer has the media, read discovered groups
                // once and adopt the one still playing this session. No SET and no LOAD.
                if(shouldFollowUnlocated(e.outcome!=null,e.outcome?.endpoint!=null,currentHasMedia)) {
                    uncertain=true
                    followUnlocated(sessionId,contentId)
                }
                val stale=adoptedEndpoint
                if(stale!=null && endpointRoute(stale)==null) {
                    diagnostics.event("membership.adoption.dropped","device=${stale.deviceId}")
                    dropStaleAdoption()
                }
                if(current!=null && adoptedEndpoint===current && !currentHasMedia && e.outcome?.endpoint==null) {
                    // The old single-speaker endpoint no longer has the mix and no group was
                    // found. Drop it so recovery can probe the remaining speaker read-only.
                    diagnostics.event("membership.adoption.dropped","device=${current.deviceId} reason=no-media")
                    dropStaleAdoption()
                }
                // A removal to one speaker ends the temporary group without a sessionEndpoint.
                // Confirm that speaker from its own fresh receiver/media status instead of
                // reporting a false "not confirmed" after playback already moved there.
                if(adoptedEndpoint==null) {
                    val remaining=confirmRemainingSpeaker(targets,sessionId,contentId)
                    if(remaining!=null) {
                        rememberOutcome(remaining)
                        pendingMembers=null;membershipError=null;membershipRevision++
                        diagnostics.event("membership.result","endpoint=true temporary=false remaining=true")
                        return
                    }
                }
            }
            val located=e.outcome?.endpoint!=null || adoptedEndpoint!=null
            membershipError=if(located && e.outcome?.endpoint==null) null else e.message
            diagnostics.event("membership.failed","sent=${e.sent} located=$located")
            if(located && e.outcome?.endpoint==null) return
            throw e
        } finally {adopting=false;activePermit=null;gate.complete(Unit)}
    }
    /** The mix is already on a temporary group. Read discovered groups and select the one
     *  still playing this session. No SET and no LOAD.
     *  Connected with no media: use the Cast session id (the 2103 Bedroom case).
     *  Not connected: read the session id from the remembered speaker. Opening 0.4.3 took that
     *  path, reported nothing playing, and never saw "Bedroom speaker + 1". */
    private fun seekMovedGroup(now:Long) {
        if(!foreground || closed || adopting || handoffRouteSelected || followJob?.isActive==true || recoveryJob?.isActive==true) return
        if(now-followAttemptAt<8_000) return
        val connectedId=session?.takeIf {it.isConnected}?.sessionId
        val content=client?.mediaInfo?.contentId
        followAttemptAt=now
        followJob=scope.launch {
            val sessionId=connectedId ?: rememberedSessionId()
            if(sessionId.isNullOrEmpty()) {
                diagnostics.event("membership.follow","matched=false groups=0 reason=no-session")
                return@launch
            }
            followUnlocated(sessionId,content)
        }
    }
    /** GET_STATUS on the remembered speaker only. No media channel and no route selection. */
    private suspend fun rememberedSessionId():String? {
        val saved=remembered ?: return null
        val route=routes().firstOrNull {it.isEnabled && CastDevice.getFromBundle(it.extras)?.deviceId==saved.deviceId} ?: return null
        val cast=CastDevice.getFromBundle(route.extras) ?: return null
        val address=cast.inetAddress ?: return null
        val host=address.hostAddress ?: return null
        if(!isLiteralPrivateHost(host) || cast.servicePort !in 1..65535) return null
        return withContext(Dispatchers.IO) {
            runCatching {LanCastTransport(address,cast.servicePort).use {MembershipChannel(it).receiverSessionId()}}.getOrNull()
        }
    }
    /** One passive pass over discovered groups. Selects at most one route.
     *  No second SET_PLAYBACK_DEVICES and no LOAD. Stop during a membership pass records the
     *  endpoint for the direct stop and does not also select the route. */
    private suspend fun followUnlocated(sessionId:String,contentId:String?) {
        data class Candidate(val routeId:String,val deviceId:String,val host:String,val port:Int,val address:InetAddress)
        val current=target
        val candidates=routes().mapNotNull {route->
            if(device(route).cast_type!="group" || !route.isEnabled || route.id==current) return@mapNotNull null
            val cast=CastDevice.getFromBundle(route.extras) ?: return@mapNotNull null
            val address=cast.inetAddress ?: return@mapNotNull null
            val host=address.hostAddress ?: return@mapNotNull null
            if(!isLiteralPrivateHost(host) || cast.servicePort !in 1..65535) return@mapNotNull null
            Candidate(route.id,ReceiverTopology.canonicalDeviceId(cast.deviceId),host,cast.servicePort,address)
        }
        val sightings=if(candidates.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            candidates.map {candidate->
                val peek=runCatching {
                    LanCastTransport(candidate.address,candidate.port).use {MembershipChannel(it).peek()}
                }.getOrNull()
                GroupSighting(candidate.routeId,candidate.deviceId,candidate.host,candidate.port,
                    peek?.sessionId,peek?.contentId,peek?.playerState,peek?.position,currentRoute=false)
            }
        }
        yield()
        if(closed) return
        val chosen=chooseMovedGroup(sessionId,contentId,sightings)
        val media=chosen?.contentId
        diagnostics.event("membership.follow","matched=${chosen!=null} groups=${sightings.size}")
        if(chosen==null || media.isNullOrEmpty() || routes().none {it.id==chosen.routeId && it.isEnabled}) return
        adoptedEndpoint=ValidatedEndpoint(chosen.deviceId,chosen.host,chosen.port,sessionId)
        adoptedSession=sessionId
        adoptedContent=media
        adoptedState=chosen.playerState
        uncertain=false
        platformSession=sessionId
        platformContent=media
        platformObservedAt=SystemClock.elapsedRealtime()
        dynamicIds.add(chosen.deviceId)
        startMembershipBurst(null,15_000)
        topologyChecked.clear()
        state=state.copy(connected=true,state=chosen.playerState ?: "UNKNOWN",position=chosen.position,recovery=null)
        if(activePermit?.stopRequested()==true) return
        if(!adoptRoute(adoptedEndpoint!!)) {
            // The route disappeared between the read and the select. Drop the adoption so a later
            // pass can look again. A membership pass keeps the endpoint for its own Stop path.
            if(activePermit==null) {
                clearAdoption()
                platformSession=null;platformContent=null;platformObservedAt=0L
                state=state.copy(connected=false,state="IDLE",recovery=null)
            }
        }
    }
    private suspend fun awaitMembershipStop() {
        val permit=activePermit;val gate=membershipGate
        if(permit?.requestStop()!=true) return
        try {withTimeout(20_000) {gate?.await()}}
        catch(_:TimeoutCancellationException) {error("Speaker change is still settling; Stop cannot yet locate playback.")}
    }
    /** A removal can end the temporary group and leave the same session on one physical speaker.
     *  Read that speaker's own receiver and media status. This is confirmation, not a new command;
     *  it opens no media channel and sends no LOAD or PLAY. */
    private suspend fun confirmRemainingSpeaker(targets:List<SpeakerTarget>,sessionId:String,contentId:String):MembershipOutcome? {
        if(targets.size!=1) return null
        val target=targets.single()
        if(!isLiteralPrivateHost(target.host) || target.port !in 1..65535) return null
        val peek=withContext(Dispatchers.IO) {
            runCatching {
                LanCastTransport(InetAddress.getByName(target.host),target.port).use {MembershipChannel(it).peek()}
            }.getOrNull()
        } ?: return null
        if(peek.sessionId!=sessionId || peek.contentId!=contentId || !isActivePlayback(peek.playerState)) return null
        return MembershipOutcome(listOf(target.deviceId),sessionId,contentId,peek.playerState,
            ValidatedEndpoint(target.deviceId,target.host,target.port,sessionId),null)
    }
    /** The current adopted endpoint still has the mix in its own Default Media Receiver. */
    private suspend fun endpointHasMedia(endpoint:ValidatedEndpoint,sessionId:String,contentId:String):Boolean {
        if(!isLiteralPrivateHost(endpoint.host) || endpoint.port !in 1..65535) return false
        val peek=withContext(Dispatchers.IO) {
            runCatching {
                LanCastTransport(InetAddress.getByName(endpoint.host),endpoint.port).use {MembershipChannel(it).peek()}
            }.getOrNull()
        } ?: return false
        return peek.sessionId==sessionId && (contentId.isEmpty() || peek.contentId==contentId)
    }
    private suspend fun directCommand(name:String,value:Double?) {
        val endpoint=adoptedEndpoint ?: error("Wait for the speaker to synchronize.")
        val content=adoptedContent ?: error("Wait for the speaker to synchronize.")
        val sessionId=adoptedSession ?: error("Wait for the speaker to synchronize.")
        val action=when(name) {
            "pause"->MediaCommand.Pause
            "resume"->MediaCommand.Resume
            "stop"->MediaCommand.Stop
            "seek"->MediaCommand.Seek(value!!,adoptedState=="PAUSED")
            "volume"->MediaCommand.Volume(value!!)
            else->error("Unsupported command.")
        }
        val view=withContext(Dispatchers.IO) {
            try {
                LanCastTransport(InetAddress.getByName(endpoint.host),endpoint.port).use {MembershipChannel(it).exec(action,sessionId,content)}
            } catch(e:Exception) {
                if(name=="volume" && e !is CancellationException)
                    diagnostics.event("volume.direct.failed","message=${e.message}")
                throw e
            }
        }
        adoptedState=view.playerState
        when(name) {
            "seek"->seekRevision++
            "volume"->volumeRevision++
            else->transportRevision++
        }
        if(name=="stop") {
            val stopped=sessionId
            clearAdoption();clearMembershipBurst()
            volumeJobs.values.forEach {it.cancel()};volumeJobs.clear()
            if(session?.isConnected==true && session?.sessionId!=stopped) detach()
            mediaAt=0
            state=state.copy(connected=false,state="IDLE",recovery=null,transport_revision=transportRevision)
        } else state=state.copy(connected=true,state=view.playerState,position=view.position,
            volume=if(name=="volume") view.volume?.times(100)?.roundToInt() ?: state.volume else state.volume,
            transport_revision=transportRevision,seek_revision=seekRevision,volume_revision=volumeRevision)
    }
    private fun clearAdoption() {
        adoptedEndpoint=null;adoptedSession=null;adoptedContent=null;adoptedState=null
        uncertain=false;platformEligible=false;handoffRouteSelected=false;platformMembers=null;volumeMembers=emptySet()
    }
    private fun controlPlayer():String {
        if(useDirect()) return when(adoptedState) {
            "PAUSED"->"PAUSED"
            "PLAYING","BUFFERING"->"PLAYING"
            else->error("Wait for playback to settle before changing speakers.")
        }
        return when {
            client?.isPaused==true->"PAUSED"
            client?.isPlaying==true || client?.isBuffering==true->"PLAYING"
            else->error("Wait for playback to settle before changing speakers.")
        }
    }
    private fun rememberOutcome(outcome:MembershipOutcome) {
        platformMembers=outcome.members;platformEligible=true;platformSession=outcome.sessionId;platformContent=outcome.contentId
        platformObservedAt=SystemClock.elapsedRealtime();adoptedState=outcome.playerState
        outcome.temporaryDeviceId?.let {dynamicIds.add(ReceiverTopology.canonicalDeviceId(it))}
        val endpoint=outcome.endpoint ?: return
        adoptedEndpoint=endpoint;adoptedSession=outcome.sessionId;adoptedContent=outcome.contentId;uncertain=false
        adoptRoute(endpoint)
    }
    /** @return false when no discovered route matches, so the caller can retry without selecting. */
    private fun adoptRoute(endpoint:ValidatedEndpoint):Boolean {
        val route=endpointRoute(endpoint) ?: return false
        if(handoffRouteSelected && route.isSelected) return true
        handoffRouteSelected=true
        explicitDeviceId=CastDevice.getFromBundle(route.extras)?.deviceId
        diagnostics.event("membership.adopt","route=${route.name}")
        if(!route.isSelected) route.select()
        return true
    }
    /** The discovered route that still matches an adopted endpoint, if any. */
    private fun endpointRoute(endpoint:ValidatedEndpoint)=routes().find {candidate->
        if(!candidate.isEnabled) return@find false
        val cast=CastDevice.getFromBundle(candidate.extras) ?: return@find false
        ReceiverTopology.canonicalDeviceId(cast.deviceId)==endpoint.deviceId && cast.servicePort==endpoint.port
    }
    /** A removal can end the temporary group and leave playback on one physical speaker. Keeping the
     *  dead group endpoint blocks recovery, so the phone never rejoins and live add looks unavailable.
     *  Drop it after a short grace period; recovery then reattaches read-only to the remaining speaker. */
    private fun watchAdoption(now:Long) {
        val endpoint=adoptedEndpoint
        if(endpoint==null || adopting) {adoptionMissingAt=0;return}
        if(endpointRoute(endpoint)!=null) {adoptionMissingAt=0;return}
        if(adoptionMissingAt==0L) {adoptionMissingAt=now;return}
        if(shouldDropStaleAdoption(true,false,false,now-adoptionMissingAt)) {
            diagnostics.event("membership.adoption.dropped","device=${endpoint.deviceId}")
            dropStaleAdoption()
        }
    }
    /** Forget a dead adopted endpoint so recover() may run. Sends no command and clears no playback. */
    private fun dropStaleAdoption() {
        clearAdoption()
        platformSession=null;platformContent=null;platformObservedAt=0L
        adoptionMissingAt=0L
        state=state.copy(connected=false,state="UNKNOWN",recovery=null)
    }
    private fun useDirect():Boolean=adoptedEndpoint!=null && (session?.isConnected!=true || session?.sessionId!=adoptedSession)
    private fun platformFresh():Boolean {
        val sessionId=controlSessionId()
        val content=controlContent()
        return platformEligible && !uncertain && platformObservedAt>0 && SystemClock.elapsedRealtime()-platformObservedAt<12_000 &&
            platformSession==sessionId && platformContent==content && platformMembers?.isNotEmpty()==true
    }
    /** Checkbox ids are route ids. A partial map would show the wrong speakers, so it does not count. */
    private fun membersMapped():Boolean {
        val ids=platformMembers ?: return false
        return ids.isNotEmpty() && ids.mapNotNull {routeIdFor(it)}.size==ids.size
    }
    private fun currentMemberRoutes():Set<String> {
        val ids=platformMembers
        if(platformFresh() && membersMapped() && ids!=null) return ids.mapNotNull {routeIdFor(it)}.toSet()
        return participants().map {it.id}.toSet()
    }
    /** Null until every current member has a fresh connected receiver level. */
    private fun groupAverageLevel():Double?=groupAverageLevel(currentMemberRoutes())
    private fun groupAverageLevel(ids:Set<String>):Double? {
        if(ids.size<2) return null
        val levels=ids.map {memberVolumes[it]?.level ?: return null}
        return levels.average()
    }
    private fun routeIdFor(canonical:String)=routes().firstOrNull {
        val id=CastDevice.getFromBundle(it.extras)?.deviceId ?: return@firstOrNull false
        ReceiverTopology.canonicalDeviceId(id)==canonical && device(it).cast_type=="audio"
    }?.id
    private fun audioTarget(routeId:String):SpeakerTarget {
        val route=routes().find {it.id==routeId && it.isEnabled} ?: error("Speaker unavailable.")
        val described=device(route)
        require(described.cast_type=="audio" && !described.temporary) {"Only individual speakers can join."}
        val cast=CastDevice.getFromBundle(route.extras) ?: error("Speaker unavailable.")
        val host=cast.inetAddress?.hostAddress ?: error("Speaker unavailable.")
        require(isLiteralPrivateHost(host)) {"Speaker unavailable."}
        require(cast.servicePort in 1..65535) {"Speaker unavailable."}
        return SpeakerTarget(ReceiverTopology.canonicalDeviceId(cast.deviceId),host,cast.servicePort,route.id)
    }
    private data class ControlPoint(val address:InetAddress,val port:Int)
    private fun controlPoint():ControlPoint? {
        adoptedEndpoint?.let {endpoint->
            if(!isLiteralPrivateHost(endpoint.host)) return null
            return ControlPoint(InetAddress.getByName(endpoint.host),endpoint.port)
        }
        val cast=session?.castDevice ?: return null
        val address=cast.inetAddress ?: return null
        if(!(address.isSiteLocalAddress || address.isLinkLocalAddress) || cast.servicePort !in 1..65535) return null
        return ControlPoint(address,cast.servicePort)
    }
    private fun refreshPlatform(now:Long) {
        if(!foreground || closed || adopting || platformJob?.isActive==true) return
        if(membershipRefreshUntil>0 && now>=membershipRefreshUntil) clearMembershipBurst()
        if(now-platformAttemptAt<membershipRefreshInterval(now,membershipRefreshUntil)) return
        val point=controlPoint() ?: return
        val sessionId=controlSessionId() ?: return
        val content=controlContent() ?: return
        val active=client?.let {it.isPlaying || it.isPaused || it.isBuffering}==true || useDirect()
        if(!active) return
        platformAttemptAt=now
        val epoch=connectionEpoch.value
        platformJob=scope.launch {
            val read=withContext(Dispatchers.IO) {
                runCatching {
                    LanCastTransport(point.address,point.port).use {socket->
                        val channel=MembershipChannel(socket)
                        channel.readMultizone() to channel.readSessionId()
                    }
                }.getOrNull()
            } ?: return@launch
            if(closed || !connectionEpoch.accepts(epoch)) return@launch
            val (zone,observed)=read
            if(observed!=sessionId) return@launch
            val previous=platformMembers
            platformMembers=zone.members;platformEligible=zone.eligible;platformSession=sessionId;platformContent=content
            platformObservedAt=SystemClock.elapsedRealtime()
            if(zone.eligible) uncertain=false
            if(previous!=zone.members) membershipRevision++
            if(membershipBurstMatched(membershipRefreshExpected,zone.members)) clearMembershipBurst()
        }
    }
    /** A short 1.5 s membership poll after a change; otherwise the normal 8 s cadence. */
    private fun startMembershipBurst(expected:Set<String>?,windowMs:Long=20_000) {
        membershipRefreshUntil=SystemClock.elapsedRealtime()+windowMs
        membershipRefreshExpected=expected
    }
    private fun clearMembershipBurst() {
        membershipRefreshUntil=0L;membershipRefreshExpected=null
    }
    override suspend fun dismissMembership() {pendingMembers=null;membershipError=null}
    override suspend fun catalog():CatalogData = library ?: sources.readCatalog().also {library=it}
    override suspend fun importChannel(url:String?) {
        check(!importing) {"A catalog refresh is already running."}
        val data=catalog();val targets=if(url!=null) listOf(url.trim()) else data.channels.map {it.url}
        importing=true;refreshing=targets
        scope.launch {
            try {
                for(target in targets) {
                    try {library=sources.refresh(target,catalog());catalogRevision++;catalogMessage="Catalog up to date"}
                    catch(e:CancellationException) {throw e}
                    catch(e:Exception) {catalogMessage=e.message?:"Refresh failed; previous mixes retained."}
                    refreshing=refreshing-target
                }
            } finally {importing=false;refreshing=emptyList()}
        }
    }
    override fun close() {
        if(closed) return
        closed=true;rejoin.invalidate();joiningSession=null
        diagnostics.event("backend.closed");diagnostics.close()
        fence.next();cancelRouteChange();scope.cancel();router.removeCallback(routeCallback)
        sessions.removeSessionManagerListener(sessionListener,CastSession::class.java);detach()
        // Intentionally no endCurrentSession/STOP: Nest continues independently.
    }
}
private suspend fun <R:Result> PendingResult<R>.checked():R=receiverDeadline(20000, "The speaker did not acknowledge the command. No automatic retry was started.") {
    suspendCancellableCoroutine { continuation->
        setResultCallback {result-> if(continuation.isActive) continuation.resume(result) }
    }
}.also {check(it.status.isSuccess) {"Speaker rejected the command (${it.status.statusCode})."}}
