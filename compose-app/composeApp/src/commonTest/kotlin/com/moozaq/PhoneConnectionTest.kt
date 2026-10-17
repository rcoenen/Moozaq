package com.moozaq

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class PhoneConnectionTest {
    @Test fun explicitConnectionFailureAllowsImmediateRetry() {
        val request=PendingDestination("bedroom")
        val failed=Snapshot(target="bedroom",connection_error="Speaker connection failed.")
        val pending=reconcileDestination(request,failed)
        assertTrue(pending?.failed==true)
        assertTrue(canSelectDestination(PlayerUiState(snapshot=failed,destination=pending),Device("bedroom","Bedroom")))
        assertNull(reconcileDestination(request,failed.copy(connection_error=null,receiver=Receiver(connected=true))))
    }
    @Test fun lostBridgeRetainsUnconfirmedTrackAndReconnectsWithoutCommands()=runBlocking {
        var reads=0
        val active=Snapshot(target="speaker",receiver=Receiver(connected=true,state="PLAYING",title="Our mix",position=45.0))
        val backend=object:PlayerBackend {
            override suspend fun snapshot():Snapshot {reads++;if(reads==2) error("Mac connection unavailable");return active}
            override suspend fun catalog()=CatalogData()
            override suspend fun selectDevice(id:String) {fail("Must not reconnect a Cast session")}
            override suspend fun play(source:String) {fail("Must never restart playback")}
            override suspend fun command(name:String,value:Double?) {fail("Must never change playback")}
            override suspend fun outputVolume(id:String,value:Double) {fail("Must not restore volume")}
            override suspend fun memberVolume(id:String,value:Double) {fail("Must not restore volume")}
            override suspend fun setPlaybackDevices(devices:List<String>,token:String) {fail("Must not recreate group")}
            override suspend fun dismissMembership() {}
            override suspend fun importChannel(url:String?) {}
            override fun close() {}
        }
        val store=PlayerStore(backend,this)
        val job=store.start()
        try {
            val lost=withTimeout(3000) {store.state.first {it.error!=null}}
            assertEquals("Our mix",lost.snapshot.receiver.title)
            assertEquals(45.0,lost.snapshot.receiver.position)
            assertFalse(canPause(lost.snapshot))
            assertEquals("UNKNOWN",lost.snapshot.receiver.state)
            val recovered=withTimeout(3000) {store.state.first {it.error==null&&it.snapshot.receiver.connected}}
            assertTrue(canPause(recovered.snapshot))
            assertEquals("Our mix",recovered.snapshot.receiver.title)
        } finally {job.cancelAndJoin()}
    }
}
