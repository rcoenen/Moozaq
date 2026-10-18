package com.moozaq.app

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val NS_CONNECTION="urn:x-cast:com.google.cast.tp.connection"
internal const val NS_HEARTBEAT="urn:x-cast:com.google.cast.tp.heartbeat"
internal const val NS_MULTIZONE="urn:x-cast:com.google.cast.multizone"
internal const val NS_RECEIVER="urn:x-cast:com.google.cast.receiver"
internal const val NS_MEDIA="urn:x-cast:com.google.cast.media"
internal const val CAST_PLATFORM="receiver-0"
internal const val DEFAULT_RECEIVER_APP="CC1AD845"

internal data class CastFrame(val destination:String,val namespace:String,val payload:String)

internal interface CastFrameTransport:Closeable {
    fun send(destination:String,namespace:String,payload:String)
    fun receive(timeoutMs:Long):CastFrame
    /** A scripted transport fails at once, so a miss must still move the manual clock by the whole budget.
     *  LanCastTransport blocks inside [receive]; sleeping that budget again was the 2026-10-17 Kitchen hang. */
    fun waitsInsideReceive():Boolean=false
}

/** A manual clock keeps the scripted conversation off real timeouts. */
internal class MembershipClock {
    var nanos:Long=System.nanoTime()
    var waiting:(Long)->Unit={ms-> if(ms>0) Thread.sleep(ms); nanos=System.nanoTime()}
    fun advance(ms:Long) {waiting(ms.coerceAtLeast(0))}
}

internal data class SpeakerTarget(val deviceId:String,val host:String,val port:Int,val routeId:String)

internal data class MultizoneView(
    val members:List<String>,
    val appAllowsGrouping:Boolean?,
    val isVideoContent:Boolean?,
    val streamTransferSupported:Boolean?,
) {
    val eligible get()=appAllowsGrouping==true && isVideoContent==false && streamTransferSupported==true && members.isNotEmpty()
}

internal data class ValidatedEndpoint(val deviceId:String,val host:String,val port:Int,val sessionId:String?)

/** One speaker's own receiver level and mute state. */
internal data class ReceiverVolume(val level:Double,val muted:Boolean)

internal data class MediaView(
    val sessionId:String,
    val transportId:String,
    val mediaSessionId:Int,
    val contentId:String,
    val playerState:String,
    val position:Double?,
    val volume:Double?,
)

internal data class MembershipRequest(
    val sessionId:String,
    val contentId:String,
    val playerState:String,
    val knownMembers:List<String>,
    val desired:List<SpeakerTarget>,
)

/** Read-only identity of a receiver that is already running. No media URL is logged from this value. */
internal data class PlaybackPeek(val sessionId:String,val contentId:String,val playerState:String,val position:Double?)

/** One discovered group, after a status read. [currentRoute] is the speaker the phone already selected. */
internal data class GroupSighting(
    val routeId:String,
    val deviceId:String,
    val host:String,
    val port:Int,
    val sessionId:String?,
    val contentId:String?,
    val playerState:String?,
    val position:Double?,
    val currentRoute:Boolean,
)

internal data class MembershipOutcome(
    val members:List<String>,
    val sessionId:String,
    val contentId:String,
    val playerState:String,
    val endpoint:ValidatedEndpoint?,
    val temporaryDeviceId:String?,
)

internal class MembershipRejected(message:String,val sent:Boolean,val outcome:MembershipOutcome?):IllegalStateException(message)

/** Stop and the membership write share one lock so a cancel cannot race the send. */
internal class MembershipPermit {
    private val lock=Any()
    private var sent=false
    private var stop=false
    fun beginSend():Boolean=synchronized(lock) {if(stop) false else {sent=true;true}}
    fun stopRequested():Boolean=synchronized(lock) {stop}
    /** @return true when the command may already have left the phone. */
    fun requestStop():Boolean=synchronized(lock) {stop=true;sent}
}

internal sealed class MediaCommand {
    data object Pause:MediaCommand()
    data object Resume:MediaCommand()
    data object Stop:MediaCommand()
    data class Seek(val seconds:Double,val wasPaused:Boolean):MediaCommand()
    data class Volume(val level:Double):MediaCommand()
}

internal fun membershipRoute(eligible:Boolean,groupRoute:Boolean)=when {
    eligible->MembershipPath.PLATFORM
    groupRoute->MembershipPath.GROUP_ROUTE
    else->MembershipPath.REFUSE
}
internal enum class MembershipPath {PLATFORM,GROUP_ROUTE,REFUSE}

private val activePlayback=setOf("PLAYING","PAUSED","BUFFERING")

/** The phone may select a group only when one read proves it is the session that moved.
 *  Zero matches and two matches both mean leave the route alone. The current route is never re-selected.
 *  An unknown content id still matches the session: after the 0.4.3 restart Bedroom kept the old
 *  session id and had no media, while "Bedroom speaker + 1" was playing that session.
 *  A known content id must match. All speakers is left alone unless it is that session. */
internal fun chooseMovedGroup(expectedSession:String,expectedContent:String?,sightings:List<GroupSighting>):GroupSighting? {
    if(expectedSession.isEmpty()) return null
    return sightings.filter {
        !it.currentRoute && it.sessionId==expectedSession && !it.contentId.isNullOrEmpty() &&
            (expectedContent.isNullOrEmpty() || it.contentId==expectedContent) &&
            it.playerState in activePlayback && isLiteralPrivateHost(it.host) && it.port in 1..65535
    }.singleOrNull()
}

/** Bedroom can stay Cast-connected after its app is gone, and a confirmed remaining speaker can
 *  later be replaced by a new group. A single-speaker adoption must not disable this look. */
internal fun shouldSeekMovedGroup(connected:Boolean,hasMedia:Boolean,blindForMs:Long)=
    connected && !hasMedia && blindForMs>=8_000L

/** A restart is not the blind case. The Cast session is gone, recovery finds no Default Media Receiver
 *  on the remembered speaker, and the mix is on a hidden temporary group. The 2026-10-17 reopen of
 *  0.4.3 reported "Nothing playing" and never looked. Wait for that idle result, then look. */
internal fun shouldSeekIdleGroup(connected:Boolean,adopted:Boolean,settledIdle:Boolean,idleForMs:Long)=
    !connected && !adopted && settledIdle && idleForMs>=8_000L

/** Session id of whatever application is running. Bedroom kept the moved session id on a different
 *  app after the Kitchen add, which is how a restart can recognise the group. Empty when no app is up. */
internal fun applicationSessionId(reply:JsonObject):String?=
    reply["status"]?.jsonObject?.get("applications")?.jsonArray?.firstNotNullOfOrNull {
        it.jsonObject.str("sessionId")?.takeIf {id->id.isNotEmpty()}
    }

internal fun isLiteralPrivateHost(host:String):Boolean {
    if(host.isEmpty() || host.any {it.isLetter()}) return false
    val address=runCatching {InetAddress.getByName(host)}.getOrNull() ?: return false
    val literal=address.hostAddress ?: return false
    if(literal!=host) return false
    return !address.isLoopbackAddress && !address.isAnyLocalAddress && (address.isSiteLocalAddress || address.isLinkLocalAddress)
}

internal fun validateEndpoint(deviceId:String,host:String,port:Int,sessionId:String?,desired:List<SpeakerTarget>):ValidatedEndpoint {
    val id=ReceiverTopology.canonicalDeviceId(deviceId)
    require(port in 1..65535) {"Invalid returned group port."}
    require(isLiteralPrivateHost(host) && desired.any {it.host==host}) {"Returned group endpoint is not on a requested local speaker."}
    if(desired.size==1) require(id==desired.single().deviceId) {"Returned individual endpoint is not the requested speaker."}
    return ValidatedEndpoint(id,host,port,sessionId)
}

internal fun setPlaybackPayload(requestId:Int,sessionId:String,desired:List<SpeakerTarget>):String {
    val rows=desired.sortedBy {it.deviceId}
    require(rows.isNotEmpty()) {"Use Stop to silence the last speaker."}
    require(rows.map {it.deviceId}.distinct().size==rows.size) {"Duplicate speaker."}
    return buildJsonObject {
        put("type","SET_PLAYBACK_DEVICES")
        put("requestId",requestId)
        put("sessionId",sessionId)
        put("deviceList",buildJsonArray {
            rows.forEach {row->
                add(buildJsonObject {
                    put("deviceId",row.deviceId)
                    put("ipAddress",row.host)
                    put("port",row.port)
                })
            }
        })
    }.toString()
}

/** One platform conversation. The transport is injected so tests never open a socket. */
internal class MembershipChannel(private val transport:CastFrameTransport,private val clock:MembershipClock=MembershipClock()) {
    private var request=1000
    private var commandSent=false
    private fun nextRequest()=++request

    fun readMultizone():MultizoneView {
        connect(CAST_PLATFORM)
        return fetchMultizone()
    }

    fun readSessionId():String? {
        connect(CAST_PLATFORM)
        val id=nextRequest()
        transport.send(CAST_PLATFORM,NS_RECEIVER,"""{"type":"GET_STATUS","requestId":$id}""")
        val reply=await(5_000) {it.str("type")=="RECEIVER_STATUS"}
        return defaultApp(reply,null)?.str("sessionId")
    }

    /** Receiver and media GET_STATUS only. Used to recognise a group the speakers already formed.
     *  The 2026-10-17 Kitchen add moved Bedroom's session onto "Bedroom speaker + 1" and kept playing;
     *  this read is how the phone can notice that group without sending SET, LOAD, or PLAY. */
    fun peek():PlaybackPeek? {
        connect(CAST_PLATFORM)
        val id=nextRequest()
        transport.send(CAST_PLATFORM,NS_RECEIVER,"""{"type":"GET_STATUS","requestId":$id}""")
        val reply=await(5_000) {it.str("type")=="RECEIVER_STATUS"}
        val app=defaultApp(reply,null) ?: return null
        val sessionId=app.str("sessionId") ?: return null
        val transportId=app.str("transportId") ?: return null
        connect(transportId)
        val media=fetchMedia(transportId,sessionId)
        if(media.contentId.isEmpty() || media.playerState.isEmpty()) return null
        return PlaybackPeek(sessionId,media.contentId,media.playerState,media.position)
    }

    /** Receiver GET_STATUS only. Does not open the media channel and does not launch an app.
     *  Used when the phone has no Cast session and still needs the id Bedroom is advertising. */
    fun receiverSessionId():String? {
        connect(CAST_PLATFORM)
        val id=nextRequest()
        transport.send(CAST_PLATFORM,NS_RECEIVER,"""{"type":"GET_STATUS","requestId":$id}""")
        val reply=await(5_000) {it.str("type")=="RECEIVER_STATUS"}
        return applicationSessionId(reply)
    }

    /** One speaker's own receiver volume. Receiver GET_STATUS only: no media channel, no LAUNCH.
     *  A physical speaker keeps a real volume level even when its MediaRouter route reports fixed
     *  or zero volume because playback moved onto a temporary group. */
    fun receiverVolume():ReceiverVolume? {
        connect(CAST_PLATFORM)
        val id=nextRequest()
        transport.send(CAST_PLATFORM,NS_RECEIVER,"""{"type":"GET_STATUS","requestId":$id}""")
        val reply=await(5_000,"The speaker did not report its volume.") {it.str("type")=="RECEIVER_STATUS"}
        return receiverVolumeState(reply)
    }

    /** The Mac path that has been shown to change a group member: SET_VOLUME on that speaker, then
     *  that speaker's own fresh receiver status. No media channel, no LOAD, no multizone SET.
     *  A group session volume is not proof that this member changed, so it is never used here. */
    fun setVolume(level:Double,timeoutMs:Long=6_000):ReceiverVolume {
        val value=level.coerceIn(0.0,1.0)
        connect(CAST_PLATFORM)
        val id=nextRequest()
        transport.send(CAST_PLATFORM,NS_RECEIVER,buildJsonObject {
            put("type","SET_VOLUME");put("requestId",id)
            put("volume",buildJsonObject {put("level",value)})
        }.toString())
        val deadline=clock.nanos+timeoutMs*1_000_000L
        while(clock.nanos<deadline) {
            val readId=nextRequest()
            transport.send(CAST_PLATFORM,NS_RECEIVER,"""{"type":"GET_STATUS","requestId":$readId}""")
            val remaining=((deadline-clock.nanos)/1_000_000L).coerceAtLeast(1)
            val reply=await(remaining,"The speaker did not confirm the new volume.") {it.str("type")=="RECEIVER_STATUS"}
            val confirmed=receiverVolumeState(reply)
            if(confirmed!=null && kotlin.math.abs(confirmed.level-value)<=.015) return confirmed
            // Pace the fresh reads like the Mac bridge; a receiver reply is not a confirmation.
            clock.advance(150)
        }
        error("The speaker did not confirm the new volume.")
    }

    fun apply(request:MembershipRequest,permit:MembershipPermit,open:(String,Int)->CastFrameTransport):MembershipOutcome {
        var sent=false
        try {
            connect(CAST_PLATFORM)
            val status=fetchMultizone()
            if(!status.eligible) reject(false,"This session does not allow adding a speaker.")
            if(status.members!=request.knownMembers.map {ReceiverTopology.canonicalDeviceId(it)}.distinct().sorted())
                reject(false,"Playback speakers changed before sending. Read the current membership.")
            val requestId=nextRequest()
            val payload=setPlaybackPayload(requestId,request.sessionId,request.desired)
            if(!permit.beginSend()) reject(false,"Speaker change cancelled by Stop.")
            sent=true
            commandSent=true
            transport.send(CAST_PLATFORM,NS_MULTIZONE,payload)
            val reply=await(15_000) {
                val type=it.str("type")
                it.int("requestId")==requestId && (type=="PLAYBACK_SESSION_UPDATED" || type=="INVALID_REQUEST")
            }
            if(reply.str("type")!="PLAYBACK_SESSION_UPDATED") reject(true,"Speaker selection was not confirmed.")
            val failed=(reply["failedDevices"]?.jsonArray?.size ?: 0)>0
            val raw=reply["sessionEndpoint"]?.jsonObject
            val outcome=if(raw==null) confirmHere(request,permit,!failed) else confirmMoved(request,raw,open,permit,!failed)
            if(failed) reject(true,"Some speakers did not join or leave.",outcome)
            if(permit.stopRequested()) reject(true,"Speaker change cancelled by Stop.",outcome)
            return outcome
        } catch(e:MembershipRejected) {
            if(commandSent && !e.sent) reject(true,e.message ?: "Speaker selection was not confirmed.",e.outcome)
            throw e
        } catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(e:Exception) {reject(sent,e.message ?: "Speaker selection was not confirmed.")}
    }

    /** Direct control for a session the Cast SDK is no longer attached to. Never sends LOAD or LAUNCH. */
    fun exec(command:MediaCommand,sessionId:String,contentId:String):MediaView {
        connect(CAST_PLATFORM)
        val media=readMedia(sessionId,contentId,null)
        when(command) {
            MediaCommand.Pause->{
                check(media.playerState=="PLAYING") {"Speaker is not playing."}
                sendMedia(media,"PAUSE")
                return awaitState(media,"PAUSED")
            }
            MediaCommand.Resume->{
                check(media.playerState=="PAUSED") {"Speaker is not paused."}
                sendMedia(media,"PLAY")
                return awaitState(media,"PLAYING")
            }
            MediaCommand.Stop->{
                sendMedia(media,"STOP")
                return awaitState(media,"IDLE")
            }
            is MediaCommand.Seek->{
                val id=nextRequest()
                transport.send(media.transportId,NS_MEDIA,buildJsonObject {
                    put("type","SEEK");put("requestId",id);put("mediaSessionId",media.mediaSessionId)
                    put("currentTime",command.seconds);put("resumeState","PLAYBACK_UNCHANGED")
                }.toString())
                val updated=awaitState(media,null)
                check(seekConfirmed(updated.position ?: -1.0,command.seconds,command.wasPaused,updated.playerState=="PLAYING",updated.playerState=="PAUSED"))
                    {"The speaker did not confirm the requested playback change. No automatic retry was started."}
                return updated
            }
            is MediaCommand.Volume->{
                val level=command.level.coerceIn(0.0,1.0)
                val id=nextRequest()
                transport.send(CAST_PLATFORM,NS_RECEIVER,buildJsonObject {
                    put("type","SET_VOLUME");put("requestId",id)
                    put("volume",buildJsonObject {put("level",level)})
                }.toString())
                val updated=readMedia(sessionId,contentId,null)
                val confirmed=updated.volume
                check(confirmed!=null && kotlin.math.abs(confirmed-level)<=.015) {"The speaker did not confirm the new volume."}
                return updated
            }
        }
    }

    private fun confirmHere(request:MembershipRequest,permit:MembershipPermit,exact:Boolean):MembershipOutcome {
        val media=readMedia(request.sessionId,request.contentId,request.playerState)
        val members=if(exact) converge(request,permit) else fetchMultizone().members
        return outcome(members,media,null,request)
    }

    private fun confirmMoved(request:MembershipRequest,raw:JsonObject,open:(String,Int)->CastFrameTransport,permit:MembershipPermit,exact:Boolean):MembershipOutcome {
        val endpoint=validateEndpoint(
            raw.str("deviceId") ?: reject(true,"Returned group endpoint is not on a requested local speaker."),
            raw.str("ipAddress") ?: reject(true,"Returned group endpoint is not on a requested local speaker."),
            raw.int("port") ?: reject(true,"Invalid returned group port."),
            raw.str("sessionId"),
            request.desired)
        open(endpoint.host,endpoint.port).use {next->
            val adopted=MembershipChannel(next,clock)
            val media=adopted.readMedia(request.sessionId,request.contentId,request.playerState)
            if(media.sessionId!=request.sessionId || (endpoint.sessionId!=null && endpoint.sessionId!=request.sessionId))
                reject(true,"Returned endpoint does not match the original playback.")
            val members=if(exact) adopted.converge(request,permit) else adopted.fetchMultizone().members
            return outcome(members,media,endpoint,request)
        }
    }

    private fun outcome(members:List<String>,media:MediaView,endpoint:ValidatedEndpoint?,request:MembershipRequest):MembershipOutcome {
        val temporary=endpoint?.deviceId?.takeUnless {id-> request.desired.any {it.deviceId==id}}
        return MembershipOutcome(members,media.sessionId,media.contentId,media.playerState,endpoint,temporary)
    }

    private fun converge(request:MembershipRequest,permit:MembershipPermit):List<String> {
        val wanted=request.desired.map {it.deviceId}.distinct().sorted()
        val deadline=clock.nanos+12_000_000_000L
        var latest=emptyList<String>()
        while(clock.nanos<deadline) {
            val view=try {fetchMultizone()} catch(_:Exception) {clock.advance(250);continue}
            latest=view.members
            if(latest==wanted || permit.stopRequested()) return latest
            clock.advance(250)
        }
        reject(true,"Speaker selection was not confirmed.",outcome(latest,MediaView(request.sessionId,"",0,request.contentId,request.playerState,null,null),null,request))
    }

    private fun fetchMultizone():MultizoneView {
        val id=nextRequest()
        transport.send(CAST_PLATFORM,NS_MULTIZONE,"""{"type":"GET_STATUS","requestId":$id}""")
        return parseMultizone(await(5_000) {it.str("type")=="MULTIZONE_STATUS"})
    }

    private fun readMedia(expectedSession:String,expectedContent:String,expectedState:String?):MediaView {
        val id=nextRequest()
        transport.send(CAST_PLATFORM,NS_RECEIVER,"""{"type":"GET_STATUS","requestId":$id}""")
        val reply=await(5_000) {it.str("type")=="RECEIVER_STATUS"}
        val level=reply["status"]?.jsonObject?.get("volume")?.jsonObject?.get("level")?.jsonPrimitive?.doubleOrNull
        val app=defaultApp(reply,expectedSession) ?: reject(commandSent,"The mix changed on the speaker. Read its current playback before trying again.")
        val transportId=app.str("transportId") ?: reject(commandSent,"The mix changed on the speaker. Read its current playback before trying again.")
        val sessionId=app.str("sessionId") ?: expectedSession
        connect(transportId)
        val media=fetchMedia(transportId,sessionId).copy(volume=level)
        if(media.contentId!=expectedContent) reject(commandSent,"The mix changed on the speaker. Read its current playback before trying again.")
        if(expectedState==null) return media
        return correctState(media,expectedState)
    }

    private fun correctState(media:MediaView,expected:String):MediaView {
        if(expected=="PAUSED" && media.playerState=="PLAYING") {
            sendMedia(media,"PAUSE")
            val paused=awaitState(media,"PAUSED")
            if(paused.playerState!="PAUSED") reject(true,"Playback resumed during the speaker change.")
            return paused
        }
        if(expected=="PAUSED" && media.playerState!="PAUSED") reject(true,"Playback resumed during the speaker change.")
        if(expected=="PLAYING" && media.playerState=="PAUSED") reject(true,"The speaker did not keep playing.")
        return media
    }

    private fun sendMedia(media:MediaView,type:String) {
        val id=nextRequest()
        transport.send(media.transportId,NS_MEDIA,buildJsonObject {
            put("type",type);put("requestId",id);put("mediaSessionId",media.mediaSessionId)
        }.toString())
    }

    private fun awaitState(media:MediaView,state:String?):MediaView {
        val updated=fetchMedia(media.transportId,media.sessionId)
        if(state!=null && updated.playerState!=state) reject(true,"The speaker did not confirm the requested playback change. No automatic retry was started.")
        return updated
    }

    private fun fetchMedia(transportId:String,sessionId:String):MediaView {
        val id=nextRequest()
        transport.send(transportId,NS_MEDIA,"""{"type":"GET_STATUS","requestId":$id}""")
        val reply=await(5_000) {it.str("type")=="MEDIA_STATUS"}
        val row=reply["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: reject(commandSent,"The speaker did not confirm the requested playback change. No automatic retry was started.")
        val content=row["media"]?.jsonObject?.str("contentId") ?: ""
        return MediaView(sessionId,transportId,row.int("mediaSessionId") ?: 0,content,row.str("playerState") ?: "",
            row["currentTime"]?.jsonPrimitive?.doubleOrNull,null)
    }

    private fun connect(destination:String) {
        transport.send(destination,NS_CONNECTION,"""{"type":"CONNECT","origin":{},"userAgent":"Moozaq membership"}""")
    }

    private fun await(timeoutMs:Long,message:String="Speaker selection was not confirmed.",match:(JsonObject)->Boolean):JsonObject {
        val deadline=clock.nanos+timeoutMs*1_000_000L
        while(clock.nanos<deadline) {
            val remaining=((deadline-clock.nanos)/1_000_000L).coerceAtLeast(1)
            val frame=try {transport.receive(remaining)} catch(_:Exception) {noteWaitSpent(remaining);continue}
            if(frame.namespace==NS_HEARTBEAT && jsonType(frame.payload)=="PING") {
                transport.send(frame.destination,NS_HEARTBEAT,"""{"type":"PONG"}""")
                continue
            }
            val json=runCatching {Json.parseToJsonElement(frame.payload).jsonObject}.getOrNull() ?: continue
            if(match(json)) return json
        }
        reject(commandSent,message)
    }

    /** A real socket has already spent the budget inside read. Sleeping it again made the
     *  Kitchen confirmation die at about 10s with located=false, before the phone could see the group.
     *  A scripted miss still jumps the manual clock by the full remaining time. */
    private fun noteWaitSpent(remaining:Long) {
        if(!transport.waitsInsideReceive()) {
            clock.advance(remaining)
            return
        }
        val elapsedMs=(System.nanoTime()-clock.nanos)/1_000_000L
        if(elapsedMs>=200) clock.nanos=System.nanoTime() else clock.advance(50)
    }

    private fun reject(sent:Boolean,message:String,outcome:MembershipOutcome?=null):Nothing=
        throw MembershipRejected(message,sent,outcome)
}

internal fun parseMultizone(json:JsonObject):MultizoneView {
    val status=json["status"]?.jsonObject
    val session=status?.get("playbackSession")?.jsonObject
    val members=status?.get("devices")?.jsonArray.orEmpty().mapNotNull {
        it.jsonObject.str("deviceId")?.let(ReceiverTopology::canonicalDeviceId)
    }.distinct().sorted()
    return MultizoneView(members,session?.bool("appAllowsGrouping"),session?.bool("isVideoContent"),session?.bool("streamTransferSupported"))
}

private fun defaultApp(reply:JsonObject,sessionId:String?):JsonObject? =
    reply["status"]?.jsonObject?.get("applications")?.jsonArray?.map {it.jsonObject}?.firstOrNull {
        it.str("appId")==DEFAULT_RECEIVER_APP && (sessionId==null || it.str("sessionId")==sessionId)
    }

internal fun volumeLevel(reply:JsonObject):Double?=
    reply["status"]?.jsonObject?.get("volume")?.jsonObject?.get("level")?.jsonPrimitive?.doubleOrNull

internal fun receiverVolumeState(reply:JsonObject):ReceiverVolume? {
    val level=volumeLevel(reply) ?: return null
    val muted=reply["status"]?.jsonObject?.get("volume")?.jsonObject?.bool("muted") ?: false
    return ReceiverVolume(level,muted)
}

internal fun isActivePlayback(state:String?):Boolean=state in activePlayback

private fun jsonType(payload:String)=runCatching {Json.parseToJsonElement(payload).jsonObject.str("type")}.getOrNull()
private fun JsonObject.str(key:String)=this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key:String)=this[key]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.int(key:String)=this[key]?.jsonPrimitive?.intOrNull
private val kotlinx.serialization.json.JsonPrimitive.doubleOrNull get()=contentOrNull?.toDoubleOrNull()

/** LAN-only Cast socket. The trust exception is this one discovered address, and the frames carry no media URL. */
internal class LanCastTransport(address:InetAddress,port:Int):CastFrameTransport {
    private val socket:SSLSocket
    private val input:DataInputStream
    private val output:DataOutputStream
    init {
        require(address.isSiteLocalAddress || address.isLinkLocalAddress) {"Receiver must be on the local network."}
        require(port in 1..65535)
        val ssl=SSLContext.getInstance("TLS").apply {init(null,arrayOf<TrustManager>(object:X509TrustManager {
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?) {}
            override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) {}
        }),null)}
        socket=(ssl.socketFactory.createSocket() as SSLSocket).also {
            it.soTimeout=2500
            it.connect(InetSocketAddress(address,port),2500)
            it.startHandshake()
        }
        input=DataInputStream(socket.inputStream)
        output=DataOutputStream(socket.outputStream)
    }
    override fun send(destination:String,namespace:String,payload:String) {
        val bytes=CastStatusWire.encode(namespace,payload,destination=destination)
        output.writeInt(bytes.size);output.write(bytes);output.flush()
    }
    override fun waitsInsideReceive()=true
    override fun receive(timeoutMs:Long):CastFrame {
        socket.soTimeout=timeoutMs.toInt().coerceIn(1,60_000)
        val size=input.readInt()
        require(size in 1..131072)
        val bytes=ByteArray(size)
        input.readFully(bytes)
        val (namespace,payload)=CastStatusWire.decode(bytes)
        return CastFrame(CAST_PLATFORM,namespace,payload)
    }
    override fun close() {socket.close()}
}
