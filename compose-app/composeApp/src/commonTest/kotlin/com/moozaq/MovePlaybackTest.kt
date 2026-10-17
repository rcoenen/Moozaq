package com.moozaq

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class MovePlaybackTest {
    private class FakeBackend(var current:Snapshot):PlayerBackend {
        val calls=mutableListOf<String>()
        var snapshotsUntilReady=-1
        override suspend fun snapshot():Snapshot {
            if(current.connecting && snapshotsUntilReady>0) {
                snapshotsUntilReady--
                if(snapshotsUntilReady==0) current=current.copy(connecting=false,receiver=current.receiver.copy(connected=true,state="IDLE"))
            }
            return current
        }
        override suspend fun selectDevice(id:String) {
            calls+="select:$id"
            current=current.copy(target=id,connecting=true,connection_error=null,receiver=Receiver(device=id))
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

    private val mix=catalog.first()
    private fun playing()=Snapshot(
        target="office",
        devices=listOf(Device("office","Office speaker",cast_type="audio"),Device("group","All speakers",cast_type="group")),
        receiver=Receiver(connected=true,device="Office speaker",state="PLAYING",source=mix.url,title=mix.title,position=30.0,duration=7200.0),
    )

    @Test fun movingToAGroupSelectsThenStartsTheSameMixOnce()=runBlocking {
        val backend=FakeBackend(playing())
        val store=PlayerStore(backend,this)
        val polling=store.start()
        try {
            withTimeout(3000) {store.state.first {it.snapshot.target!=null}}
            store.movePlaybackTo("group",mix)
            withTimeout(3000) {store.state.first {it.snapshot.receiver.state=="PLAYING" && it.snapshot.target=="group"}}
            assertEquals(listOf("select:group","play:${mix.url}"),backend.calls)
        } finally {polling.cancelAndJoin()}
    }

    @Test fun stopDuringTheMovePreventsALateLoad()=runBlocking {
        val backend=FakeBackend(playing())
        backend.snapshotsUntilReady=Int.MAX_VALUE
        val store=PlayerStore(backend,this)
        val polling=store.start()
        try {
            withTimeout(3000) {store.state.first {it.snapshot.target!=null}}
            store.movePlaybackTo("group",mix)
            withTimeout(3000) {store.state.first {it.snapshot.connecting && it.snapshot.target=="group"}}
            store.command("stop")
            withTimeout(3000) {while(!backend.calls.contains("command:stop")) delay(50)}
            delay(200)
            assertFalse(backend.calls.any {it.startsWith("play:")}, "Stop must cancel the move before it loads a new source")
            assertEquals(listOf("select:group","command:stop"),backend.calls)
        } finally {polling.cancelAndJoin()}
    }

    @Test fun movingToTheCurrentDestinationOnlyStartsTheMix()=runBlocking {
        val backend=FakeBackend(playing().copy(receiver=playing().receiver.copy(state="PAUSED")))
        val store=PlayerStore(backend,this)
        val polling=store.start()
        try {
            withTimeout(3000) {store.state.first {it.snapshot.target!=null}}
            store.movePlaybackTo("office",mix)
            withTimeout(3000) {store.state.first {it.snapshot.receiver.state=="PLAYING"}}
            assertEquals(listOf("play:${mix.url}"),backend.calls,"No route selection when the destination is already connected")
        } finally {polling.cancelAndJoin()}
    }
}
