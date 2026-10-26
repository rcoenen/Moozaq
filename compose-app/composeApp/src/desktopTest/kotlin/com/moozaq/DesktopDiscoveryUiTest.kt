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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopDiscoveryUiTest {
    private class Backend : PlayerBackend {
        private val featured = Mix(
            "WlPiQGR18jE", "'VHS Dreams' | Nostalgic 80's Synth & Electro Music", "Silent Depths",
            thumbnail="https://i.ytimg.com/vi/WlPiQGR18jE/hqdefault.jpg", duration=10974.0,
            category="Synthwave", first_seen_at=200, recommended_at=200,
        )
        private val recent = List(6) { index ->
            Mix("recent-$index", "Recently added mix $index", "Recent creator $index", artwork=index,
                duration=7200.0, category="Recent", first_seen_at=190L-index)
        }
        private val discovery = List(12) { index ->
            Mix("discovery-$index", "Discovery mix $index", "Discovery creator $index", artwork=index%6,
                duration=7200.0, category="Discovery")
        }
        private val data = CatalogData(
            mixes=listOf(featured)+recent+discovery,
            recommended_ids=listOf(featured.id),
            recently_added_ids=recent.map { it.id },
        )

        override suspend fun snapshot() = Snapshot(
            catalog_backend="sqlite", catalog_revision=1, app_revision=1,
            personal_profile=true, personal_revision=1,
        )
        override suspend fun catalog() = data
        override suspend fun masterCatalog() = error("This is not an owner-catalog test")
        override suspend fun personalProfile() = PersonalProfile()
        override suspend fun selectDevice(id: String) = error("Device selection is forbidden in this render test")
        override suspend fun play(source: String) = error("Playback is forbidden in this render test")
        override suspend fun command(name: String, value: Double?) = error("Receiver commands are forbidden in this render test")
        override suspend fun outputVolume(id: String, value: Double) = error("Volume is forbidden in this render test")
        override suspend fun memberVolume(id: String, value: Double) = error("Member volume is forbidden in this render test")
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) = error("Membership is forbidden in this render test")
        override suspend fun dismissMembership() = error("Membership is forbidden in this render test")
        override suspend fun importChannel(url: String?) = error("Channel mutation is forbidden in this render test")
        override suspend fun checkCatalogUpdates(): JsonObject = error("Updates are not part of this render test")
        override fun close() {}
    }

    @Test fun oneRecommendationSharesTheDiscoveryRow() = runBlocking {
        val backend = Backend()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val store = PlayerStore(backend, scope)
        val poll = store.start()
        val scene = ImageComposeScene(1428, 830, Density(1f)) { MoozaqApp(store) }
        try {
            withTimeout(5_000) {
                while (store.state.value.discovery == null) delay(20)
            }
            val cards = discoveryCards(visibleDiscoverySections(store.state.value.discovery!!, ""))
            assertEquals("WlPiQGR18jE", cards.first().mix.id)
            assertEquals(DiscoverySectionKind.RECOMMENDED, cards.first().section.kind)
            assertEquals(DiscoverySectionKind.RECENTLY_ADDED, cards[1].section.kind)
            assertEquals(1+6+12, cards.size)
            scene.render(0)
            val image = scene.render(16_000_000)
            val output = File("build/previews/discovery-shared-row.png").apply { parentFile.mkdirs() }
            output.writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertTrue(output.length()>0)
        } finally {
            poll.cancel()
            scene.close()
            scope.cancel()
        }
    }
}
