package com.moozaq

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Interactive visual fixture. It never constructs a network backend or opens a receiver. */
private class DesignPreviewBackend(mode:String="playing"): PlayerBackend {
    private val mode=mode
    private val devices=listOf(Device("group","All speakers",cast_type="group"),Device("bedroom","Bedroom speaker",cast_type="audio"),Device("kitchen","Kitchen speaker",cast_type="audio"))
    private val mixes=catalog.mapIndexed { index,mix -> mix.copy(duration=10966.0,category="Mock mixes — no speaker connection",first_seen_at=100L+index,recommended_at=if(index==0) 100L else null) }
    private var masterData=CatalogData(
        channels=listOf(CatalogChannel("Mock channel","https://www.youtube.com/@mock",count=6,last_attempt="2026-10-24T10:00:00Z",last_success="2026-10-24T10:00:00Z")),
        mixes=mixes,
    )
    private val previewItem=VideoPreview("WlPiQGR18jE","VHS Dreams","Silent Depths",10974.0,
        channel_url="https://www.youtube.com/@Silent-Depths-b9j",webpage_url="https://www.youtube.com/watch?v=WlPiQGR18jE")
    private val playing=Snapshot(devices=devices,target="bedroom",receiver=Receiver(connected=true,device="Bedroom speaker",title=mixes[0].title,state="PLAYING",source=mixes[0].url,position=623.0,duration=10966.0,volume=20),
        membership=PlaybackMembership(members=listOf("bedroom","kitchen"),available=true,token="mock"),
        outputs=devices.map {OutputState(it.id,connected=true,state="PLAYING",volume=.2)},message="Mock data. No Cast engine exists in this preview.",
        catalog_curation=true,catalog_updates=true,master_revision=1,app_revision=1,latest_revision=1,generated_revision=1)
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
    override suspend fun catalog()=masterData.copy(
        mixes=masterData.mixes.filter {(it.duration ?: 0.0)>=3600 && !it.hidden && it.review_status=="approved" && it.availability!="unavailable"},
        recommended_ids=masterData.mixes.filter {it.recommended_at!=null}.sortedByDescending {it.recommended_at}.map {it.id},
        recently_added_ids=masterData.mixes.filter {it.first_seen_at!=null}.sortedByDescending {it.first_seen_at}.map {it.id},
    )
    override suspend fun masterCatalog()=masterData
    override suspend fun previewVideo(source:String):VideoPreview { delay(100); return previewItem }
    override suspend fun importVideo(source:String,recommend:Boolean):CatalogActionResult {
        val eligible=(previewItem.duration ?: 0.0)>=3600
        val recommended=recommend && eligible
        val song=Mix(previewItem.id,previewItem.title,previewItem.creator,duration=previewItem.duration,
            recommended_at=if(recommended) 100 else null,first_seen_at=100)
        masterData=masterData.copy(mixes=masterData.mixes.filterNot {it.id==song.id}+song)
        s=s.copy(master_revision=s.master_revision+1,catalog_dirty=true)
        return CatalogActionResult(id=song.id,eligible=eligible,recommended=recommended)
    }
    override suspend fun followChannel(url:String,refresh:Boolean):CatalogActionResult {
        masterData=masterData.copy(channels=masterData.channels.filterNot {it.url==url}+CatalogChannel("Silent Depths",url,publication_mode="review",aliases=listOf(url)))
        s=s.copy(master_revision=s.master_revision+1,catalog_dirty=true)
        return CatalogActionResult(created=true,url=url,import_count=if(refresh) 1 else null)
    }
    override suspend fun curate(action:String,fields:Map<String,JsonElement>):CatalogActionResult {
        val id=fields["song"]?.jsonPrimitive?.content
        if(action=="source_mode") {
            val url=fields["url"]?.jsonPrimitive?.content
            val mode=fields["mode"]?.jsonPrimitive?.content ?: "review"
            masterData=masterData.copy(channels=masterData.channels.map {if(it.url==url) it.copy(publication_mode=mode) else it})
        } else {
            masterData=masterData.copy(mixes=masterData.mixes.map { mix ->
                if(mix.id!=id) mix else when(action) {
                    "recommend" -> mix.copy(recommended_at=100,hidden=false,review_status="approved")
                    "unrecommend" -> mix.copy(recommended_at=null)
                    "hide" -> mix.copy(hidden=true)
                    "restore" -> mix.copy(hidden=false)
                    "category" -> mix.copy(category_override=fields["category"]?.jsonPrimitive?.content)
                    "review" -> mix.copy(review_status=fields["status"]?.jsonPrimitive?.content ?: "approved")
                    else -> mix
                }
            })
        }
        s=s.copy(master_revision=s.master_revision+1,catalog_dirty=true)
        return CatalogActionResult(changed=true)
    }
    override suspend fun rebuildCatalog():Snapshot {
        s=s.copy(app_revision=s.master_revision,generated_revision=s.master_revision,catalog_dirty=false,catalog_building=false,catalog_revision=s.catalog_revision+1)
        return s
    }
    override suspend fun publishCatalogRelease(confirmed:Boolean,expectedRevision:Int):kotlinx.serialization.json.JsonObject {
        if(!confirmed || expectedRevision!=s.app_revision) error("The confirmed release revision does not match.")
        s=s.copy(latest_revision=expectedRevision)
        return kotlinx.serialization.json.JsonObject(mapOf(
            "revision" to kotlinx.serialization.json.JsonPrimitive(expectedRevision),
            "tag" to kotlinx.serialization.json.JsonPrimitive("catalog-v$expectedRevision"),
            "assets" to kotlinx.serialization.json.JsonArray(listOf(
                kotlinx.serialization.json.JsonPrimitive("app-catalog.db"),
                kotlinx.serialization.json.JsonPrimitive("catalog-manifest.json"),
            )),
        ))
    }
    override suspend fun checkCatalogUpdates():kotlinx.serialization.json.JsonObject {
        delay(100)
        s=s.copy(latest_revision=s.app_revision,last_update_check="2026-10-24T12:00:00Z")
        return kotlinx.serialization.json.JsonObject(mapOf(
            "status" to kotlinx.serialization.json.JsonPrimitive("current"),
            "revision" to kotlinx.serialization.json.JsonPrimitive(s.app_revision),
        ))
    }
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
        state=rememberWindowState(width=if(phone) 412.dp else 1428.dp,height=if(phone) 915.dp else (System.getenv("MOOZAQ_PREVIEW_HEIGHT")?.toIntOrNull() ?: 830).dp)) {
        MoozaqApp(store,
            initialChannelsOpen = System.getenv("MOOZAQ_PREVIEW_PANEL") == "catalog",
            initialSearchQuery = System.getenv("MOOZAQ_PREVIEW_QUERY") ?: "")
    }
}
