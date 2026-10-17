package com.moozaq

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class ConnectAndPlayTest {
    private class FakeBackend(var current:Snapshot):PlayerBackend {
        val calls=mutableListOf<String>()
        var snapshotsUntilReady=-1
        var failSelection=false
        override suspend fun snapshot():Snapshot {
            if(current.connecting && snapshotsUntilReady>0) {
                snapshotsUntilReady--
                if(snapshotsUntilReady==0) current=current.copy(connecting=false,receiver=current.receiver.copy(connected=true,state="IDLE"))
            }
            return current
        }
        override suspend fun selectDevice(id:String) {
            calls+="select:$id"
            if(failSelection) {
                current=current.copy(connecting=false,connection_error="Speaker connection failed")
                return
            }
            current=current.copy(connecting=true,connection_error=null)
            if(snapshotsUntilReady<0) snapshotsUntilReady=1
        }
        override suspend fun play(source:String) {
            calls+="play:$source"
            current=current.copy(busy=false,receiver=current.receiver.copy(connected=true,state="PLAYING",source=source,
                transport_revision=current.receiver.transport_revision+1))
        }
        override suspend fun command(name:String,value:Double?) {
            calls+="command:$name"
            current=current.copy(busy=false,connecting=false,receiver=current.receiver.copy(connected=true,state="IDLE"))
        }
        override suspend fun catalog()=CatalogData()
        override suspend fun outputVolume(id:String,value:Double) {}
        override suspend fun memberVolume(id:String,value:Double) {}
        override suspend fun setPlaybackDevices(devices:List<String>,token:String) {}
        override suspend fun dismissMembership() {}
        override suspend fun importChannel(url:String?) {}
        override fun close() {}
    }

    private fun initial()=Snapshot(target="office",devices=listOf(Device("office","Office")),
        receiver=Receiver(connected=false,state="IDLE"))

    @Test fun explicitPlayConnectsThenStartsSelectedMix()=runBlocking {
        val backend=FakeBackend(initial())
        val store=PlayerStore(backend,this)
        val polling=store.start()
        try {
            withTimeout(3000) {store.state.first {it.snapshot.target!=null}}
            store.play(catalog.first())
            withTimeout(3000) {store.state.first {it.snapshot.receiver.state=="PLAYING" && it.transport==null}}
            assertEquals(listOf("select:office","play:${catalog.first().url}"),backend.calls)
        } finally {polling.cancelAndJoin()}
    }

    @Test fun stopBeforeReadyPreventsLatePlay()=runBlocking {
        val backend=FakeBackend(initial())
        backend.snapshotsUntilReady=Int.MAX_VALUE
        val store=PlayerStore(backend,this)
        val polling=store.start()
        try {
            withTimeout(3000) {store.state.first {it.snapshot.target!=null}}
            store.play(catalog.first())
            withTimeout(3000) {store.state.first {it.snapshot.connecting}}
            store.command("stop")
            withTimeout(3000) {while(!backend.calls.contains("command:stop")) delay(50)}
            delay(200)
            assertFalse(backend.calls.any {it.startsWith("play:")})
            assertTrue(backend.calls.contains("command:stop"))
        } finally {polling.cancelAndJoin()}
    }

    @Test fun connectionFailureIsVisibleAndDoesNotPlay()=runBlocking {
        val backend=FakeBackend(initial())
        backend.failSelection=true
        val store=PlayerStore(backend,this)
        val polling=store.start()
        try {
            withTimeout(3000) {store.state.first {it.snapshot.target!=null}}
            store.play(catalog.first())
            val failed=withTimeout(3000) {store.state.first {it.error!=null}}
            assertEquals("Speaker connection failed",failed.error)
            assertFalse(backend.calls.any {it.startsWith("play:")})
        } finally {polling.cancelAndJoin()}
    }
}
