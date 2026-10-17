package com.moozaq.app

import android.content.Context
import android.net.Uri
import com.moozaq.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.UUID

/** No cookies, Mac endpoint, audio relay or media downloads. URLs stay in memory. */
class OnDeviceSources(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val initLock = Mutex()
    private var initialized = false
    private val cache = File(context.filesDir, "catalog.json")
    suspend fun initialize() = initLock.withLock {
        if (!initialized) withContext(Dispatchers.IO) {
            BundledExtractor.install(context)
            YoutubeDL.init(context)
            initialized = true
        }
    }
    suspend fun readCatalog(): CatalogData = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<CatalogData>(cache.readText()) }.getOrElse {
            json.decodeFromString(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
        }
    }
    suspend fun save(data: CatalogData) = withContext(Dispatchers.IO) {
        val temp = File(context.filesDir, "catalog.tmp")
        temp.writeText(json.encodeToString(CatalogData.serializer(), data))
        check(temp.renameTo(cache)) { "Could not save the catalog." }
    }
    suspend fun execute(url: String, options: List<Pair<String, String?>>): JsonObject = coroutineScope {
        initialize()
        val process = UUID.randomUUID().toString()
        val request = YoutubeDLRequest(url).apply {
            addOption("--ignore-config"); addOption("--skip-download")
            addOption("--socket-timeout", "20"); addOption("--retries", "1")
            addOption("--dump-single-json")
            options.forEach { (key,value) -> if(value==null) addOption(key) else addOption(key,value) }
        }
        // Cancelling a coroutine alone cannot stop a native subprocess blocked in execute().
        val guard = launch(Dispatchers.IO) {
            try { delay(180_000); YoutubeDL.destroyProcessById(process) }
            finally { YoutubeDL.destroyProcessById(process) }
        }
        try {
            val out = withContext(Dispatchers.IO) { YoutubeDL.execute(request, process).out }
            ensureActive()
            json.parseToJsonElement(out).jsonObject
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            AndroidDiagnostics(context).also {it.event("resolver.failed","${e.javaClass.simpleName}: ${e.message}");it.close()}
            throw IllegalStateException("YouTube could not resolve this source on the phone. Try again later.")
        }
        finally { guard.cancelAndJoin() }
    }
    suspend fun resolve(source: String): ResolvedAudio {
        val id = Uri.parse(source).getQueryParameter("v")
        require(id != null && Regex("[\\w-]{11}").matches(id)) { "Invalid YouTube video." }
        val data = execute("https://www.youtube.com/watch?v=$id", listOf(
            "--no-playlist" to null, "-f" to "bestaudio[ext=m4a][protocol=https]/bestaudio[protocol=https]"))
        val url = data.text("url") ?: error("YouTube returned no direct audio stream.")
        require(Uri.parse(url).scheme == "https") { "The audio source is not HTTPS." }
        require(data.text("vcodec") == "none") { "The source is not audio-only." }
        val type = if(data.text("ext") == "m4a") "audio/mp4" else "audio/webm"
        return ResolvedAudio(source, url, type, data.text("title") ?: id,
            data.text("uploader") ?: "", data.number("duration"), data.text("thumbnail"))
    }
    suspend fun refresh(url: String, previous: CatalogData): CatalogData {
        require(Regex("https://(?:www\\.)?youtube\\.com/(?:@[\\w.\\-]+|channel/UC[\\w\\-]{22})/?").matches(url)) {
            "Use a YouTube channel URL or @handle."
        }
        val data = execute(url, listOf("--flat-playlist" to null, "--ignore-errors" to null))
        val channelId = data.text("channel_id") ?: data.text("id") ?: error("Missing channel identity.")
        require(Regex("UC[\\w-]{22}").matches(channelId)) { "Invalid channel identity." }
        val creator = data.text("channel") ?: data.text("uploader") ?: data.text("title") ?: url
        fun entries(o: JsonObject): List<JsonObject> = o["entries"]?.jsonArray.orEmpty().flatMap {
            val child = it as? JsonObject ?: return@flatMap emptyList()
            if("entries" in child) entries(child) else listOf(child)
        }
        val mixes = entries(data).mapNotNull { e ->
            val id = e.text("id") ?: return@mapNotNull null
            val title = e.text("title") ?: return@mapNotNull null
            if(!Regex("[\\w-]{11}").matches(id) || title.startsWith("[")) return@mapNotNull null
            Mix(id, title, creator, thumbnail="https://i.ytimg.com/vi/$id/hqdefault.jpg",
                duration=e.number("duration"), category=category(title), channel_id=channelId)
        }
        check(mixes.isNotEmpty()) { "No public mixes returned; existing entries were retained." }
        val channel = CatalogChannel(creator,url,channelId,mixes.size,refreshed=Instant.now().toString())
        return CatalogData(previous.channels.filterNot {it.url==url || it.id==channelId}+channel,
            (mixes+previous.mixes).distinctBy {it.id}).also { save(it) }
    }
    private fun category(title: String): String {
        val text=title.lowercase()
        val rules=listOf("Psychedelic Dub" to listOf("dub"),
            "Persian / Middle Eastern Jazz" to listOf("persian","iranian","middle eastern","syrian"),
            "Dark Jazz / Trip-Hop" to listOf("trip-hop","trip hop"),
            "Desert / Psychedelia" to listOf("desert","psychedelic","stoner"),
            "Spy / Space-Age Lounge" to listOf("spy","space age","space-age","orbital","sci-fi"),
            "Vintage Lounge / Exotica" to listOf("exotica","lounge"),
            "Noir Jazz" to listOf("noir","crime jazz","dark jazz"),
            "Cozy Jazz / Swing" to listOf("cozy","swing","warm","jazz","vintage"))
        return rules.firstOrNull { (_,words)->words.any {it in text} }?.first ?: "More moods"
    }
}
class ResolvedAudio(val source:String,val url:String,val mime:String,val title:String,val creator:String,val duration:Double?,val thumbnail:String?)
private fun JsonObject.text(key:String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key:String) = (get(key) as? JsonPrimitive)?.doubleOrNull
