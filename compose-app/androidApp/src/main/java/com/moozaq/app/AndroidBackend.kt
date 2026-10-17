package com.moozaq.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
    private val routeVolumeRevision=mutableMapOf<String,Long>()
    private var membershipRevision=0L
    private val fence=PlaybackFence()
    private val volumeJobs=mutableMapOf<String,Deferred<Unit>>()
    private var transportJob:Job?=null
    private var pendingMembers:List<String>?=null
    private var membershipError:String?=null
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
        }
    }
    private fun acceptsSession(s:CastSession):Boolean = !closed &&
        (explicitDeviceId==null || s.castDevice?.deviceId==null || s.castDevice?.deviceId==explicitDeviceId)
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
            if(routeChangeRecovery.shouldHold(reason,role)) holdRouteChange(s,reason) else sessionFailed(s,"Speaker disconnected")
        }
    }
    init {
        diagnostics.event("backend.created","version=0.3.7-standalone currentSession=${sessions.currentCastSession!=null}")
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
        if(active) requestStatus()
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
        if(!route.isDefault && !route.isBluetooth) {
            target=route.id
            remember(route,s.castDevice)
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
                else diagnostics.event("status.failed","code=${result.status.statusCode} timely=$timely")
            }
        } catch(e:Exception) {
            statusGate.complete(ticket,SystemClock.elapsedRealtime())
            diagnostics.event("status.failed",e.javaClass.simpleName)
        }
    }
    private fun receiveMedia() {
        val c=client?:return
        if(closed || session?.isConnected!=true) return
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
    private fun readVolume() { if(session?.isConnected==true) state=state.copy(volume=(session!!.volume*100).roundToInt(),volume_revision=volumeRevision) }
    private fun routes()=(router.routes+listOf(router.selectedRoute)).filter {
        !it.isDefault && !it.isBluetooth && it.matchesSelector(selector)
    }.distinctBy {it.id}
    private fun device(route:MediaRouter.RouteInfo):Device {
        val d=CastDevice.getFromBundle(route.extras)
        val kind=when {
            d?.hasCapability(CastDevice.CAPABILITY_MULTIZONE_GROUP)==true || route.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_GROUP->"group"
            d?.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)==true || route.deviceType==MediaRouter.RouteInfo.DEVICE_TYPE_TV->"cast"
            else->"audio"
        }
        return Device(route.id,route.name,d?.modelName?:"",kind,route.isEnabled,
            temporary=d?.deviceId?.let {ReceiverTopology.canonicalDeviceId(it) in dynamicIds}==true || (route is MediaRouter.GroupRouteInfo && router.routes.none {it.id==route.id}))
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
                val ids=withContext(Dispatchers.IO) {runCatching {ReceiverTopology.dynamicGroups(address)}.getOrDefault(emptySet())}
                dynamicIds.addAll(ids)
            }
        }
    }
    private fun token()="${session?.sessionId}:${client?.mediaInfo?.contentId?.hashCode()}"
    override suspend fun snapshot():Snapshot=withContext(Dispatchers.Main.immediate) {
        val now=SystemClock.elapsedRealtime()
        recover(now)
        if(foreground && now-requestedAt>5000) requestStatus()
        if(foreground) refreshTopology(now)
        if(connecting && now-connectingAt>25000) {
            connecting=false;message="Connection was not confirmed. Select the speaker to retry."
            connectionError=message
        }
        val fresh=session?.isConnected==true && mediaAt>0 && now-mediaAt<20000
        val settledIdle=!state.connected && state.state=="IDLE" && connectionError==null
        val receiver=if(fresh) state.copy(
            position=client?.takeIf {it.hasMediaSession()}?.approximateStreamPosition?.div(1000.0),
            transport_revision=transportRevision,seek_revision=seekRevision,volume_revision=volumeRevision)
        else state.copy(connected=false,state=if(settledIdle) "IDLE" else "UNKNOWN",
            recovery=if(connecting) "Connecting…" else connectionError ?: if(target!=null && !settledIdle) "Connecting…" else null)
        val members=participants()
        val group=(router.selectedRoute as? MediaRouter.GroupRouteInfo)
        val outputs=routes().map {r->
            val selected=r.id==target
            OutputState(r.id,connected=r.isEnabled,state=if(selected && fresh) receiver.state?:"UNKNOWN" else "UNKNOWN",
                title=if(selected) receiver.title else null,
                volume=if(r.volumeMax>0 && r.volumeHandling==MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE) r.volume.toDouble()/r.volumeMax else null,
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
            group=if(members.size>1) GroupInfo(members=members.map {r->GroupMember(r.id,r.name,volume=outputs.find {it.id==r.id}?.volume,
                volume_revision=routeVolumeRevision[r.id]?:0)}) else null,
            membership=PlaybackMembership(members=members.map {it.id},available=group!=null && fresh && client?.hasMediaSession()==true,
                pending=pendingMembers,error=membershipError,revision=membershipRevision,token=if(fresh) token() else null))
    }
    override suspend fun selectDevice(id:String)=withContext(Dispatchers.Main.immediate) {
        check(transportJob?.isActive!=true) {"Wait for the current command to finish."}
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
        if(name=="volume") {
            val (s,c)=connected();val level=value!!.coerceIn(0.0,1.0);val revision=volumeRevision
            volumeOperation("main") {
                s.volume=level
                receiverDeadline(10000, "The speaker did not confirm the new volume.") {while(session===s && (volumeRevision<=revision || abs(s.volume-level)>.015)) delay(100)}
                check(session===s && client===c) {"The volume session changed."}
            }
            return@withContext
        }
        if(name=="stop") {
            fence.next();transportJob?.cancel();transportJob=null
            rejoin.invalidate();cancelRouteChange();recoveryJob?.cancel();recoveryJob=null
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
            if(name=="seek") seekRevision++ else transportRevision++
        }
    }
    override suspend fun outputVolume(id:String,value:Double)=withContext(Dispatchers.Main.immediate) {
        val r=routes().find {it.id==id && it.isEnabled}?:error("Speaker unavailable.")
        check(r.volumeHandling==MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE && r.volumeMax>0) {"Volume unavailable."}
        val requested=(value.coerceIn(0.0,1.0)*r.volumeMax).roundToInt()
        val revision=routeVolumeRevision[id]?:0
        volumeOperation(id) {
            r.requestSetVolume(requested)
            receiverDeadline(10000, "The speaker did not confirm the new volume.") {while((routeVolumeRevision[id]?:0)<=revision || r.volume!=requested) delay(100)}
        }
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
        val group=(router.selectedRoute as? MediaRouter.GroupRouteInfo)?:error("This receiver does not offer native live grouping. Select a saved speaker group instead.")
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
        } catch(e:Exception) {membershipError="Speaker selection was not confirmed.";throw e}
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
