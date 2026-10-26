package com.moozaq

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class ReadOnlyCatalogBackend(private val delegate: PlayerBackend) : PlayerBackend {
    override suspend fun snapshot() = delegate.snapshot()
    override suspend fun catalog() = delegate.catalog()
    override suspend fun masterCatalog() = delegate.masterCatalog()
    override suspend fun personalProfile() = delegate.personalProfile()
    override suspend fun checkCatalogUpdates() = delegate.checkCatalogUpdates()
    override suspend fun selectDevice(id: String) = error("Read-only acceptance forbids device selection")
    override suspend fun play(source: String) = error("Read-only acceptance forbids Play")
    override suspend fun command(name: String, value: Double?) = error("Read-only acceptance forbids receiver commands")
    override suspend fun outputVolume(id: String, value: Double) = error("Read-only acceptance forbids volume changes")
    override suspend fun memberVolume(id: String, value: Double) = error("Read-only acceptance forbids member volume changes")
    override suspend fun setPlaybackDevices(devices: List<String>, token: String) = error("Read-only acceptance forbids membership changes")
    override suspend fun dismissMembership() = error("Read-only acceptance forbids membership changes")
    override suspend fun rateSong(id: String, rating: Int) = error("Use the separate personal-data acceptance")
    override suspend fun resetPersonal() = error("Use the separate personal-data acceptance")
    override suspend fun importChannel(url: String?) = error("Use the separate metadata acceptance")
    override suspend fun previewVideo(source: String) = error("Use the separate metadata acceptance")
    override suspend fun importVideo(source: String, recommend: Boolean) = error("Use the separate metadata acceptance")
    override suspend fun followChannel(url: String, refresh: Boolean) = error("Use the separate metadata acceptance")
    override suspend fun curate(action: String, fields: Map<String, JsonElement>) = error("Use the separate curation acceptance")
    override suspend fun rebuildCatalog() = error("Use the separate migration acceptance")
    override suspend fun publishCatalogRelease(confirmed: Boolean, expectedRevision: Int): JsonObject = error("Public release is not part of this UI acceptance")
    override fun close() = delegate.close()
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking {
    val root = (System.getenv("MOOZAQ_ROOT") ?: System.getProperty("moozaq.root"))?.let(::File)
        ?: generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "mac-spike/engine.py").exists() }
    val backend = PythonBackend(root)
    val readOnly = ReadOnlyCatalogBackend(backend)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val store = PlayerStore(readOnly, scope)
    val poll = store.start()
    val scenes = mutableListOf<ImageComposeScene>()
    fun capture(name: String, scene: ImageComposeScene) {
        val image = scene.render(0)
        val output = File("build/previews/$name.png").apply { parentFile.mkdirs() }
        output.writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
    }
    try {
        withTimeout(45_000) {
            while (true) {
                val ui = store.state.value
                if (ui.snapshot.catalog_curation && ui.snapshot.catalog_updates && ui.masterLibrary != null && ui.discovery != null) break
                if (ui.error?.contains("Local playback engine disconnected") == true) error(ui.error)
                delay(100)
            }
        }
        val initial = store.state.value.discovery ?: error("No discovery from the real app catalog")
        check(initial.recommended.firstOrNull()?.id == "WlPiQGR18jE") { "VHS Dreams is not first in Recommended" }
        check(initial.recentlyAdded.map { it.id } == store.state.value.library.recently_added_ids)
        check(initial.recentlyAdded.isNotEmpty()) { "No truthful recent additions" }
        check(initial.recentlyAdded.all { it.first_seen_at != null })
        check(initial.recentlyAdded.zipWithNext { a, b -> a.first_seen_at!! >= b.first_seen_at!! }.all { it })
        check(initial.forYou.isNotEmpty())
        check(visibleDiscoverySections(initial, "").map { it.kind } == listOf(
            DiscoverySectionKind.RECOMMENDED, DiscoverySectionKind.RECENTLY_ADDED,
            if (initial.personalized) DiscoverySectionKind.FOR_YOU else DiscoverySectionKind.DISCOVER,
        ))
        check(store.state.value.masterLibrary?.mixes?.count { it.id == "WlPiQGR18jE" } == 1)
        val browse = ImageComposeScene(1428, 1450, Density(1f)) { MoozaqApp(store) }.also(scenes::add)
        browse.render(0)
        withTimeout(300_000) {
            while (store.state.value.snapshot.importing || store.state.value.snapshot.catalog_building ||
                store.state.value.snapshot.refreshing_channels.isNotEmpty()) {
                delay(200)
            }
        }
        delay(2000)
        capture("catalog-acceptance-browse", browse)
        browse.render(16_000_000)
        check(store.state.value.discovery === initial) { "Receiver polling changed visible discovery" }
        var stableAfterUpdate: Boolean? = null
        System.getenv("MOOZAQ_ACCEPTANCE_EXPECT_INITIAL_CATEGORY")?.let { expected ->
            val actual = initial.catalog.mixes.firstOrNull { it.id == "WlPiQGR18jE" }?.category
            check(actual == expected) { "Unexpected initial VHS Dreams category: $actual" }
        }
        if (System.getenv("MOOZAQ_ACCEPTANCE_CHECK") == "1") {
            System.getenv("MOOZAQ_ACCEPTANCE_UPDATE_GATE")?.let { path ->
                val gate = File(path)
                check(gate.mkdirs()) { "Update gate already exists: $gate" }
                File(gate, "initial-ready").writeText("ready")
                println("CATALOG_ACCEPTANCE_INITIAL_READY")
                withTimeout(90_000) {
                    while (!File(gate, "published").isFile) delay(100)
                }
            }
            store.checkCatalogUpdates()
            withTimeout(45_000) {
                while (store.state.value.catalogAction != null) {
                    if (store.state.value.catalogAction?.failed == true) error(store.state.value.catalogError ?: "Update check failed")
                    delay(100)
                }
            }
            check(store.state.value.catalogError == null) { store.state.value.catalogError ?: "Update was not confirmed" }
            check(store.state.value.snapshot.update_error == null) { store.state.value.snapshot.update_error ?: "Update failed" }
            val expectedRevision = System.getenv("MOOZAQ_ACCEPTANCE_EXPECT_REVISION")?.toIntOrNull()
            val expectedCategory = System.getenv("MOOZAQ_ACCEPTANCE_EXPECT_CATEGORY")
            withTimeout(15_000) {
                while ((expectedRevision != null && store.state.value.snapshot.app_revision < expectedRevision) ||
                    (expectedCategory != null && store.state.value.library.mixes.firstOrNull { it.id == "WlPiQGR18jE" }?.category != expectedCategory)) {
                    delay(100)
                }
            }
            browse.render(32_000_000)
            stableAfterUpdate = store.state.value.discovery === initial
            check(stableAfterUpdate == true) { "Catalog activation changed visible discovery" }
        }
        val catalogScene = ImageComposeScene(1428, 830, Density(1f)) {
            MoozaqApp(store, initialChannelsOpen = true)
        }.also(scenes::add)
        capture("catalog-acceptance-management", catalogScene)
        val ui = store.state.value
        println(buildJsonObject {
            put("master_songs", ui.masterLibrary?.mixes?.size ?: 0)
            put("public_songs", ui.library.mixes.size)
            put("recommended_first", initial.recommended.first().id)
            put("recently_added_first", initial.recentlyAdded.first().id)
            put("stable_during_polls", true)
            stableAfterUpdate?.let { put("stable_after_update", it) }
            put("active_revision", ui.snapshot.app_revision)
            put("latest_revision", ui.snapshot.latest_revision)
            ui.snapshot.last_update_check?.let { put("last_update_check", it) }
            put("receiver_or_release_commands", 0)
        })
    } finally {
        scenes.forEach { runCatching { it.close() } }
        poll.cancel()
        scope.cancel()
        readOnly.close()
    }
}
