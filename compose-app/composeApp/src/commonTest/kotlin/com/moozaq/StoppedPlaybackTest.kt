package com.moozaq

import kotlinx.coroutines.*
import kotlin.test.*

class StoppedPlaybackTest {
    private val track=Receiver(connected=true,state="PLAYING",title="Our mix",source=catalog.first().url,transport_revision=3)

    @Test fun rememberedTrackSurvivesIdleAndDisconnectButNeverClaimsPlayback() {
        assertEquals(track,rememberPlayback(track,Receiver(connected=true,state="IDLE")))
        assertEquals(track,rememberPlayback(track,Receiver()))
        assertNull(rememberPlayback(null,Receiver(title="Stale title",state="UNKNOWN")))
        val next=track.copy(title="New mix",state="PAUSED")
        assertEquals(next,rememberPlayback(track,next))
    }

    @Test fun removingLastParticipantUsesVerifiedStopAndKeepsPendingUntilAcknowledged() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val device=Device("kitchen","Kitchen",cast_type="audio")
        var snapshot=Snapshot(target=device.id,devices=listOf(device),receiver=track,
            membership=PlaybackMembership(members=listOf(device.id),available=true,revision=1,token="session"))
        val commands=mutableListOf<String>()
        val backend=object:PlayerBackend {
            override suspend fun snapshot()=snapshot
            override suspend fun catalog()=CatalogData()
            override suspend fun command(name:String,value:Double?) {commands+=name;snapshot=snapshot.copy(busy=true)}
            override suspend fun setPlaybackDevices(devices:List<String>,token:String) {fail("Must stop rather than send an empty membership")}
            override suspend fun play(source:String) {fail("No playback allowed")}
            override suspend fun selectDevice(id:String) {fail("No remote transfer needed")}
            override suspend fun outputVolume(id:String,value:Double) {fail("No volume change")}
            override suspend fun memberVolume(id:String,value:Double) {fail("No volume change")}
            override suspend fun dismissMembership() {}
            override suspend fun importChannel(url:String?) {}
            override fun close() {}
        }
        try {
            val store=PlayerStore(backend,scope)
            store.start()
            store.togglePlaybackDevice(device)
            assertEquals(listOf("stop"),commands)
            val pending=assertNotNull(store.state.value.transport)
            assertEquals("IDLE",pending.expected)
            assertEquals(pending,reconcileTransport(pending,snapshot))
            assertEquals(track,store.state.value.lastPlayed)
            assertNull(reconcileTransport(pending,snapshot.copy(busy=false,receiver=track.copy(state="IDLE",transport_revision=4))))
        } finally {scope.cancel()}
    }
}
