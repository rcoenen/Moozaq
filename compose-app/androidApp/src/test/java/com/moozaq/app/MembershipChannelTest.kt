package com.moozaq.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MembershipChannelTest {
    private val office=SpeakerTarget("office","192.168.1.10",8009,"route-office")
    private val kitchen=SpeakerTarget("kitchen","192.168.1.11",8009,"route-kitchen")
    private val content="https://stream.example/mix"
    private val clock=MembershipClock().apply {nanos=0;waiting={ms-> nanos+=ms*1_000_000}}

    @Test fun eligibleFlagsAndPrivateEndpointRules() {
        val view=parseMultizone(json(zone(listOf("Office-Id"))))
        assertTrue(view.eligible)
        assertEquals(listOf("officeid"),view.members)
        assertFalse(parseMultizone(json(zone(listOf("office"),grouping=false))).eligible)
        assertFalse(parseMultizone(json(zone(listOf("office"),video=true))).eligible)
        assertFalse(parseMultizone(json(zone(listOf("office"),transfer=false))).eligible)
        assertFalse(parseMultizone(json(zone(emptyList()))).eligible)
        assertFalse(isLiteralPrivateHost("8.8.8.8"))
        assertFalse(isLiteralPrivateHost("127.0.0.1"))
        assertFalse(isLiteralPrivateHost("speaker.local"))
        assertTrue(isLiteralPrivateHost("192.168.1.10"))
        assertEquals(MembershipPath.PLATFORM,membershipRoute(true,false))
        assertEquals(MembershipPath.GROUP_ROUTE,membershipRoute(false,true))
        assertEquals(MembershipPath.REFUSE,membershipRoute(false,false))
    }

    @Test fun oneCommandConfirmsInPlaceAndKeepsPlaying() {
        val script=script()
        val outcome=channel(script).apply(request(),MembershipPermit()) {_,_-> fail("No endpoint to open");error("unreached")}
        assertEquals(listOf("kitchen","office"),outcome.members)
        assertEquals("PLAYING",outcome.playerState)
        assertNull(outcome.endpoint)
        assertEquals(1,script.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")})
        assertFalse(script.sent.any {it.payload.contains("\"type\":\"PLAY\"")})
        assertFalse(script.sent.any {it.payload.contains("LOAD")})
        val body=json(script.sent.first {it.payload.contains("SET_PLAYBACK_DEVICES")}.payload)
        assertEquals("sess",body["sessionId"]!!.jsonPrimitive.content)
        assertTrue(body["requestId"]!!.jsonPrimitive.content.all {it.isDigit()})
        assertNotEquals("sess",body["requestId"]!!.jsonPrimitive.content)
    }

    @Test fun omittedEndpointIsNotFailure() {
        val script=script(endpoint=false)
        val outcome=channel(script).apply(request(),MembershipPermit()) {_,_-> fail("in-place reply must not open a socket");error("unreached")}
        assertNull(outcome.endpoint)
    }

    @Test fun ineligibleOrStaleSessionDoesNotSend() {
        assertEquals(0,mutations(script(grouping=false)) {channel(it).apply(request(),MembershipPermit(),::refuse)})
        val changed=script()
        try {channel(changed).apply(request().copy(knownMembers=listOf("kitchen")),MembershipPermit(),::refuse);fail()}
        catch(e:MembershipRejected) {assertFalse(e.sent)}
        assertEquals(0,changed.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")})
    }

    @Test fun wrongRequestMalformedFrameAndTimeoutSendOnce() {
        val mismatched=script(badRequest=true)
        try {channel(mismatched).apply(request(),MembershipPermit(),::refuse);fail()}
        catch(e:MembershipRejected) {assertTrue(e.sent);assertNull(e.outcome)}
        assertEquals(1,mismatched.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")})
        val broken=script(malformed=true)
        val outcome=channel(broken).apply(request(),MembershipPermit(),::refuse)
        assertEquals(listOf("kitchen","office"),outcome.members)
        val silent=script(reply=false)
        try {channel(silent).apply(request(),MembershipPermit(),::refuse);fail()}
        catch(e:MembershipRejected) {assertTrue(e.sent)}
        assertEquals(1,silent.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")})
    }

    @Test fun foreignEndpointNeverOpensAndPartialKeepsLocation() {
        var opens=0
        val foreign=script(endpoint=true,endpointHost="8.8.8.8")
        try {channel(foreign).apply(request(),MembershipPermit()) {_,_-> opens++;refuse("",0)};fail()}
        catch(e:MembershipRejected) {assertTrue(e.sent);assertNull(e.outcome)}
        assertEquals(0,opens)
        val partial=script(failed=true)
        try {channel(partial).apply(request(),MembershipPermit(),::refuse);fail()}
        catch(e:MembershipRejected) {
            assertTrue(e.sent)
            assertEquals("Some speakers did not join or leave.",e.message)
            assertNotNull(e.outcome)
        }
        assertEquals(1,partial.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")})
    }

    @Test fun movedEndpointMustMatchMediaAndIsHiddenWhenNew() {
        var opens=0
        val moved=script(endpoint=true,endpointId="group-1",endpointSession="sess")
        val second=script(joined=true)
        val outcome=channel(moved).apply(request(),MembershipPermit()) {host,port->
            opens++;assertEquals("192.168.1.10",host);assertEquals(8009,port);second
        }
        assertEquals(1,opens)
        assertEquals("group1",outcome.temporaryDeviceId)
        assertEquals("sess",outcome.sessionId)
        assertEquals(content,outcome.contentId)
        val stranger=script(endpoint=true,endpointId="kitchen")
        try {channel(stranger).apply(request(listOf(office)),MembershipPermit()) {_,_-> opens++;script()};fail()}
        catch(e:MembershipRejected) {assertTrue(e.sent)}
        assertEquals(1,opens)
    }

    @Test fun pausedSessionIsPausedAgainAndPlayingIsNotRestarted() {
        val resumed=script(state="PLAYING")
        val outcome=channel(resumed).apply(request().copy(playerState="PAUSED"),MembershipPermit(),::refuse)
        assertEquals("PAUSED",outcome.playerState)
        assertEquals(1,resumed.sent.count {it.payload.contains("\"type\":\"PAUSE\"")})
        assertFalse(resumed.sent.any {it.payload.contains("\"type\":\"PLAY\"")})
        val paused=script(state="PAUSED")
        try {channel(paused).apply(request(),MembershipPermit(),::refuse);fail()}
        catch(e:MembershipRejected) {assertTrue(e.sent);assertEquals("The speaker did not keep playing.",e.message)}
        assertFalse(paused.sent.any {it.payload.contains("\"type\":\"PLAY\"")})
    }

    @Test fun stopBeforeSendWritesNothingAndStopAfterSendAdopts() {
        val early=script()
        val permit=MembershipPermit()
        permit.requestStop()
        try {channel(early).apply(request(),permit,::refuse);fail()}
        catch(e:MembershipRejected) {assertFalse(e.sent);assertEquals("Speaker change cancelled by Stop.",e.message)}
        assertEquals(0,early.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")})
        val late=script(stopOnSend=true)
        val stopping=MembershipPermit()
        late.onSet={stopping.requestStop()}
        try {channel(late).apply(request(),stopping,::refuse);fail()}
        catch(e:MembershipRejected) {assertTrue(e.sent);assertNotNull(e.outcome);assertEquals("Speaker change cancelled by Stop.",e.message)}
        assertEquals(1,late.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")})
    }

    @Test fun directCommandsPreserveSeekAndDoNotPlayAPausedMixByAccident() {
        val script=script(state="PAUSED")
        val channel=channel(script)
        assertEquals("PLAYING",channel.exec(MediaCommand.Resume,"sess",content).playerState)
        script.state="PLAYING"
        val sought=channel.exec(MediaCommand.Seek(10.0,false),"sess",content)
        assertEquals(10.0,sought.position)
        assertTrue(script.sent.any {it.payload.contains("PLAYBACK_UNCHANGED")})
        script.state="IDLE"
        assertEquals("IDLE",channel.exec(MediaCommand.Stop,"sess",content).playerState)
        val paused=script(state="PLAYING")
        try {channel(paused).exec(MediaCommand.Resume,"sess",content);fail()}
        catch(_:IllegalStateException) {}
        assertFalse(paused.sent.any {it.payload.contains("\"type\":\"PLAY\"")})
    }

    @Test fun heartbeatIsAnswered() {
        val script=script(ping=true)
        channel(script).readMultizone()
        assertTrue(script.sent.any {it.payload.contains("\"type\":\"PONG\"")})
    }

    @Test fun peekReadsStatusAndDoesNotMutate() {
        val script=script()
        val peek=channel(script).peek()
        assertEquals("sess",peek?.sessionId)
        assertEquals(content,peek?.contentId)
        assertEquals("PLAYING",peek?.playerState)
        assertFalse(script.sent.any {it.payload.contains("SET_PLAYBACK_DEVICES")})
        assertFalse(script.sent.any {it.payload.contains("LOAD")})
        assertFalse(script.sent.any {it.payload.contains("\"type\":\"PLAY\"")})
    }

    @Test fun socketTimeoutIsNotSleptAgain() {
        val sleeps=mutableListOf<Long>()
        val paced=MembershipClock().apply {nanos=System.nanoTime();waiting={ms-> sleeps+=ms; nanos+=ms*1_000_000}}
        val transport=object:CastFrameTransport {
            override fun waitsInsideReceive()=true
            override fun send(destination:String,namespace:String,payload:String) {}
            override fun receive(timeoutMs:Long):CastFrame {paced.nanos+=timeoutMs*1_000_000;error("timeout")}
            override fun close() {}
        }
        try {MembershipChannel(transport,paced).readSessionId();fail()}
        catch(e:MembershipRejected) {assertEquals("Speaker selection was not confirmed.",e.message)}
        assertTrue("slept $sleeps",sleeps.all {it<=50})
    }

    @Test fun instantSocketMissStepsInsteadOfSleepingTheBudget() {
        val sleeps=mutableListOf<Long>()
        val paced=MembershipClock().apply {nanos=System.nanoTime()+60_000_000_000L;waiting={ms-> sleeps+=ms; nanos+=ms*1_000_000}}
        val transport=object:CastFrameTransport {
            override fun waitsInsideReceive()=true
            override fun send(destination:String,namespace:String,payload:String) {}
            override fun receive(timeoutMs:Long):CastFrame = error("reset")
            override fun close() {}
        }
        try {MembershipChannel(transport,paced).readSessionId();fail()}
        catch(_:MembershipRejected) {}
        assertTrue(sleeps.isNotEmpty())
        assertTrue("slept $sleeps",sleeps.all {it<=50})
    }

    @Test fun movedGroupIsChosenOnlyWhenOneReadMatches() {
        val group=sighting("group","sess",content,"PLAYING")
        assertEquals("group",chooseMovedGroup("sess",content,listOf(group))?.routeId)
        assertEquals("group",chooseMovedGroup("sess",content,listOf(group,sighting("all","other",content,"PLAYING")))?.routeId)
        assertNull(chooseMovedGroup("sess",content,listOf(group.copy(currentRoute=true))))
        assertNull(chooseMovedGroup("sess",content,listOf(group.copy(playerState="IDLE"))))
        assertNull(chooseMovedGroup("sess","https://stream.example/other",listOf(group)))
        assertNull(chooseMovedGroup("sess",content,listOf(group,group.copy(routeId="second"))))
        assertNull(chooseMovedGroup("sess",content,listOf(group.copy(host="8.8.8.8"))))
        assertEquals("group",chooseMovedGroup("sess",null,listOf(group))?.routeId)
        assertNull(chooseMovedGroup("sess",null,listOf(group,group.copy(routeId="second"))))
        assertNull(chooseMovedGroup("other",null,listOf(group)))
        assertFalse(shouldSeekMovedGroup(true,false,7_999))
        assertTrue(shouldSeekMovedGroup(true,false,8_000))
        assertFalse(shouldSeekMovedGroup(true,true,8_000))
        assertFalse(shouldSeekMovedGroup(false,false,8_000))
        assertFalse(shouldSeekIdleGroup(true,false,true,8_000))
        assertFalse(shouldSeekIdleGroup(false,true,true,8_000))
        assertFalse(shouldSeekIdleGroup(false,false,false,8_000))
        assertFalse(shouldSeekIdleGroup(false,false,true,7_999))
        assertTrue(shouldSeekIdleGroup(false,false,true,8_000))
        val otherApp=Json.parseToJsonElement("""{"status":{"applications":[{"appId":"531A4F84","sessionId":"sess"}]}}""").jsonObject
        assertEquals("sess",applicationSessionId(otherApp))
        assertNull(applicationSessionId(Json.parseToJsonElement("""{"status":{}}""").jsonObject))
    }

    @Test fun receiverSessionIdDoesNotOpenTheMediaChannel() {
        val namespaces=mutableListOf<String>()
        val inbox=ArrayDeque<CastFrame>()
        val transport=object:CastFrameTransport {
            override fun send(destination:String,namespace:String,payload:String) {
                namespaces+=namespace
                if(payload.contains("GET_STATUS") && namespace==NS_RECEIVER)
                    inbox.add(CastFrame(destination,NS_RECEIVER,"""{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"531A4F84","sessionId":"kept"}]}}"""))
            }
            override fun receive(timeoutMs:Long)=inbox.removeFirstOrNull() ?: error("empty")
            override fun close() {}
        }
        assertEquals("kept",MembershipChannel(transport,clock).receiverSessionId())
        assertEquals(listOf(NS_CONNECTION,NS_RECEIVER),namespaces)
    }

    @Test fun memberVolumeIsOneSetVolumeConfirmedFromFreshReceiverStatus() {
        val script=VolumeScript(listOf(0.2,0.55))
        val confirmed=MembershipChannel(script,clock).setVolume(0.55)
        assertEquals(0.55,confirmed.level,0.001)
        assertEquals(1,script.sent.count {it.payload.contains("\"type\":\"SET_VOLUME\"")})
        assertFalse(script.sent.any {it.namespace==NS_MEDIA})
        assertFalse(script.sent.any {it.payload.contains("LOAD")})
        assertFalse(script.sent.any {it.payload.contains("LAUNCH")})
        assertFalse(script.sent.any {it.payload.contains("SET_PLAYBACK_DEVICES")})
        assertTrue(script.sent.all {it.namespace==NS_CONNECTION || it.namespace==NS_RECEIVER})
        assertEquals(1,script.sent.count {it.namespace==NS_CONNECTION})
    }

    @Test fun memberVolumeReadNeverOpensTheMediaChannel() {
        val script=VolumeScript(listOf(0.4))
        assertEquals(0.4,MembershipChannel(script,clock).receiverVolume()!!.level,0.001)
        assertEquals(listOf(NS_CONNECTION,NS_RECEIVER),script.sent.map {it.namespace})
    }

    @Test fun receiverVolumeCarriesTheMuteState() {
        val reply=Json.parseToJsonElement("""{"status":{"volume":{"level":0.4,"muted":true}}}""").jsonObject
        assertEquals(ReceiverVolume(0.4,true),receiverVolumeState(reply))
        val unmuted=Json.parseToJsonElement("""{"status":{"volume":{"level":0.4}}}""").jsonObject
        assertEquals(ReceiverVolume(0.4,false),receiverVolumeState(unmuted))
    }

    @Test fun unchangedReceiverLevelIsNotVolumeConfirmation() {
        val script=VolumeScript(listOf(0.2))
        try {MembershipChannel(script,clock).setVolume(0.55);fail()}
        catch(e:IllegalStateException) {assertEquals("The speaker did not confirm the new volume.",e.message)}
        assertEquals(1,script.sent.count {it.payload.contains("\"type\":\"SET_VOLUME\"")})
    }

    private class VolumeScript(levels:List<Double>):CastFrameTransport {
        private val queue=levels.toMutableList()
        private var current=levels.lastOrNull() ?: 0.0
        val sent=mutableListOf<CastFrame>()
        private val inbox=ArrayDeque<CastFrame>()
        override fun send(destination:String,namespace:String,payload:String) {
            sent+=CastFrame(destination,namespace,payload)
            if(namespace!=NS_RECEIVER) return
            val type=runCatching {Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content}.getOrNull()
            if(type!="GET_STATUS" && type!="SET_VOLUME") return
            val level=if(queue.isNotEmpty()) queue.removeAt(0) else current
            current=level
            inbox.add(CastFrame(destination,NS_RECEIVER,"""{"type":"RECEIVER_STATUS","status":{"volume":{"level":$level}}}"""))
        }
        override fun receive(timeoutMs:Long)=inbox.removeFirstOrNull() ?: error("empty")
        override fun close() {}
    }

    private fun sighting(routeId:String,sessionId:String,media:String,state:String)=GroupSighting(
        routeId,routeId,"192.168.1.10",8009,sessionId,media,state,12.0,currentRoute=false)

    private fun request(desired:List<SpeakerTarget> =listOf(office,kitchen))=MembershipRequest("sess",content,"PLAYING",listOf("office"),desired)
    private fun channel(script:Script)=MembershipChannel(script,clock)
    private fun refuse(host:String,port:Int):CastFrameTransport {fail("unexpected endpoint $host:$port");error("unreached")}
    private fun mutations(script:Script,block:(Script)->Unit):Int {try {block(script)} catch(_:MembershipRejected) {};return script.sent.count {it.payload.contains("SET_PLAYBACK_DEVICES")}}
    private fun json(payload:String)=Json.parseToJsonElement(payload).jsonObject
    private fun zone(members:List<String>,grouping:Boolean=true,video:Boolean=false,transfer:Boolean=true)=
        """{"type":"MULTIZONE_STATUS","status":{"devices":[${members.joinToString {"""{"deviceId":"$it"}"""} }],"playbackSession":{"appAllowsGrouping":$grouping,"isVideoContent":$video,"streamTransferSupported":$transfer}}}"""

    private fun script(grouping:Boolean=true,video:Boolean=false,transfer:Boolean=true,endpoint:Boolean=false,endpointHost:String="192.168.1.10",endpointId:String="office",endpointSession:String="sess",failed:Boolean=false,state:String="PLAYING",reply:Boolean=true,badRequest:Boolean=false,malformed:Boolean=false,ping:Boolean=false,stopOnSend:Boolean=false,joined:Boolean=false)=
        Script(clock,content,zone(listOf("office","kitchen"),grouping,video,transfer),zone(listOf("office"),grouping,video,transfer),endpoint,endpointHost,endpointId,endpointSession,failed,state,reply,badRequest,malformed,ping,stopOnSend,joined)

    private class Script(val clock:MembershipClock,val content:String,var expanded:String,val initial:String,val endpoint:Boolean,val endpointHost:String,val endpointId:String,val endpointSession:String,val failed:Boolean,var state:String,val reply:Boolean,val badRequest:Boolean,val malformed:Boolean,val ping:Boolean,val stopOnSend:Boolean,val joined:Boolean):CastFrameTransport {
        val sent=mutableListOf<CastFrame>()
        var onSet:()->Unit={}
        private val inbox=ArrayDeque<CastFrame>()
        private var setSeen=false
        override fun send(destination:String,namespace:String,payload:String) {
            sent+=CastFrame(destination,namespace,payload)
            val type=runCatching {Json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content}.getOrNull()
            if(type=="SET_PLAYBACK_DEVICES") {setSeen=true;onSet()}
            frames(destination,namespace,type,payload).forEach {inbox.add(CastFrame(destination,it.first,it.second))}
        }
        override fun receive(timeoutMs:Long)=inbox.removeFirstOrNull() ?: error("empty")
        override fun close() {}
        private fun frames(destination:String,namespace:String,type:String?,payload:String):List<Pair<String,String>> {
            if(ping && type=="GET_STATUS" && namespace==NS_MULTIZONE && !setSeen) return listOf(NS_HEARTBEAT to """{"type":"PING"}""",NS_MULTIZONE to initial)
            if(type=="GET_STATUS" && namespace==NS_MULTIZONE) return listOf(NS_MULTIZONE to if(setSeen || joined) expanded else initial)
            if(type=="GET_STATUS" && namespace==NS_RECEIVER) return listOf(NS_RECEIVER to """{"type":"RECEIVER_STATUS","status":{"applications":[{"appId":"CC1AD845","sessionId":"sess","transportId":"app-1"}],"volume":{"level":0.4}}}""")
            if(type=="GET_STATUS" && namespace==NS_MEDIA) return listOf(NS_MEDIA to """{"type":"MEDIA_STATUS","status":[{"mediaSessionId":7,"playerState":"$state","currentTime":10.0,"media":{"contentId":"$content"}}]}""")
            if(type=="PAUSE") {state="PAUSED";return emptyList()}
            if(type=="PLAY") {state="PLAYING";return emptyList()}
            if(type=="STOP") {state="IDLE";return emptyList()}
            if(type!="SET_PLAYBACK_DEVICES") return emptyList()
            if(!reply) return emptyList()
            val id=Json.parseToJsonElement(payload).jsonObject["requestId"]!!.jsonPrimitive.content
            val body=buildString {
                append("""{"type":"PLAYBACK_SESSION_UPDATED","requestId":${if(badRequest) 1 else id}""")
                if(failed) append(""","failedDevices":[{}]""")
                if(endpoint) append(""","sessionEndpoint":{"deviceId":"$endpointId","ipAddress":"$endpointHost","port":8009,"sessionId":"$endpointSession"}""")
                append("}")
            }
            return if(malformed) listOf(NS_MULTIZONE to "not-json",NS_MULTIZONE to body) else listOf(NS_MULTIZONE to body)
        }
    }
}
