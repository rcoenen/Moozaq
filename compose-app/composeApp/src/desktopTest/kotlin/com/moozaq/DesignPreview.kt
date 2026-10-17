package com.moozaq

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay

/** Interactive visual fixture. It never constructs a network backend or opens a receiver. */
private class DesignPreviewBackend(mode:String="playing"): PlayerBackend {
    private val mode=mode
    private val devices=listOf(Device("group","All speakers",cast_type="group"),Device("bedroom","Bedroom speaker",cast_type="audio"),Device("kitchen","Kitchen speaker",cast_type="audio"))
    private val mixes=catalog.map {it.copy(duration=10966.0,category="Mock mixes — no speaker connection")}
    private val playing=Snapshot(devices=devices,target="bedroom",receiver=Receiver(connected=true,device="Bedroom speaker",title=mixes[0].title,state="PLAYING",source=mixes[0].url,position=623.0,duration=10966.0,volume=20),
        membership=PlaybackMembership(members=listOf("bedroom","kitchen"),available=true,token="mock"),
        outputs=devices.map {OutputState(it.id,connected=true,state="PLAYING",volume=.2)},message="Mock data. No Cast engine exists in this preview.")
    private val idle=playing.copy(receiver=Receiver(connected=true,device="Bedroom speaker",state="IDLE",volume=20))
    private val started=kotlin.time.TimeSource.Monotonic.markNow()
    private var s=when(mode) {
        "fresh" -> playing.copy(receiver=Receiver(connected=true,device="Bedroom speaker",state="IDLE",volume=20))
        "paused" -> playing.copy(receiver=playing.receiver.copy(state="PAUSED"))
        else -> playing
    }
    override suspend fun snapshot():Snapshot {
        if(mode=="stopped" && s.receiver.state=="PLAYING" && started.elapsedNow().inWholeSeconds>=3)
            s=idle.copy(receiver=idle.receiver.copy(transport_revision=s.receiver.transport_revision+1))
        return s
    }
    override suspend fun catalog()=CatalogData(channels=listOf(CatalogChannel("Mock channel","https://www.youtube.com/@mock",count=6)),mixes=mixes)
    override suspend fun selectDevice(id:String) {s=s.copy(target=id,receiver=s.receiver.copy(device=devices.first {it.id==id}.name))}
    override suspend fun play(source:String) {
        s=s.copy(busy=true);delay(1500)
        s=s.copy(busy=false,receiver=s.receiver.copy(state="PLAYING",source=source,title=mixes.first {it.url==source}.title,position=0.0,transport_revision=s.receiver.transport_revision+1))
    }
    override suspend fun command(name:String,value:Double?) {
        s=s.copy(busy=true);delay(1500)
        val r=s.receiver
        s=s.copy(busy=false,receiver=when(name) {
            "stop"->r.copy(state="IDLE",transport_revision=r.transport_revision+1)
            "pause"->r.copy(state="PAUSED",transport_revision=r.transport_revision+1)
            "resume"->r.copy(state="PLAYING",transport_revision=r.transport_revision+1)
            "volume"->r.copy(volume=((value?:.2)*100).toInt(),volume_revision=r.volume_revision+1)
            "seek"->r.copy(position=value,seek_revision=r.seek_revision+1)
            else->r
        })
    }
    override suspend fun outputVolume(id:String,value:Double) {delay(1500);s=s.copy(outputs=s.outputs.map {if(it.id==id) it.copy(volume=value,volume_revision=it.volume_revision+1) else it})}
    override suspend fun memberVolume(id:String,value:Double)=outputVolume(id,value)
    override suspend fun setPlaybackDevices(devices:List<String>,token:String) {delay(1500);s=s.copy(membership=s.membership.copy(members=devices,revision=s.membership.revision+1))}
    override suspend fun dismissMembership() {}
    override suspend fun importChannel(url:String?) {}
    override fun close() {}
}

/**
 * MOOZAQ_PREVIEW selects a mock state: playing (default), paused, stopped (plays then goes
 * idle) or fresh (never played). MOOZAQ_PREVIEW_SIZE=phone opens a 412 x 915 dp window so the
 * phone layouts can be reviewed without a device or a speaker.
 */
fun main()=application {
    val scope=rememberCoroutineScope()
    val mode=System.getenv("MOOZAQ_PREVIEW") ?: "playing"
    val phone=System.getenv("MOOZAQ_PREVIEW_SIZE")=="phone"
    val store=remember {PlayerStore(DesignPreviewBackend(mode),scope)}
    LaunchedEffect(Unit) {store.start()}
    Window(onCloseRequest=::exitApplication,title="Moozaq design preview · Mock data · No speakers",
        state=rememberWindowState(width=if(phone) 412.dp else 1428.dp,height=if(phone) 915.dp else 830.dp)) {
        MoozaqApp(store)
    }
}
