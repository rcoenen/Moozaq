package com.moozaq

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the Cast to device list from mock state to build/previews for visual review. No backend or speaker. */
class CastToRenderTest {
    private object Unused : PlayerBackend {
        override suspend fun snapshot() = error("not used")
        override suspend fun selectDevice(id: String) = error("not used")
        override suspend fun play(source: String) = error("not used")
        override suspend fun command(name: String, value: Double?) = error("not used")
        override suspend fun outputVolume(id: String, value: Double) = error("not used")
        override suspend fun memberVolume(id: String, value: Double) = error("not used")
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) = error("not used")
        override suspend fun dismissMembership() = error("not used")
        override suspend fun catalog() = error("not used")
        override suspend fun importChannel(url: String?) = error("not used")
        override fun close() {}
    }

    private val devices = listOf(
        Device("all", "All speakers", cast_type = "group"),
        Device("bedroom", "Bedroom speaker", cast_type = "audio"),
        Device("tv", "Bedroom TV", cast_type = "cast"),
        Device("fridge", "Fridge speaker", cast_type = "audio"),
        Device("kitchen", "Kitchen speaker", cast_type = "audio"),
        Device("office", "Office speaker", cast_type = "audio"),
    )

    private fun render(name: String, snapshot: Snapshot) {
        val ui = PlayerUiState(snapshot = snapshot)
        val store = PlayerStore(Unused, CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(width = 1080, height = 2000, density = Density(2.625f)) {
            MoozaqTheme(reducedMotion = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    CastToDeviceList(store, ui, phonePlayback(ui, catalog), onLocked = {}, onMove = {})
                }
            }
        }
        var image = scene.render(0)
        repeat(5) { frame -> Thread.sleep(100); image = scene.render((frame + 1) * 100_000_000L) }
        val out = File("build/previews/$name.png").apply { parentFile.mkdirs() }
        out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(out.length() > 0)
    }

    @Test fun twoSpeakersPlaying() = render("cast-to-playing", Snapshot(
        devices = devices, target = "bedroom",
        receiver = Receiver(connected = true, device = "Bedroom speaker", title = catalog[0].title, state = "PLAYING", source = catalog[0].url, volume = 38),
        membership = PlaybackMembership(members = listOf("bedroom", "office"), available = true, token = "mock"),
        outputs = listOf(
            OutputState("all", connected = true, state = "IDLE", volume = .3),
            OutputState("bedroom", connected = true, state = "PLAYING", volume = .38, session_id = "bedroom"),
            OutputState("tv", connected = true, state = "UNKNOWN", volume = 0.0),
            OutputState("fridge", connected = true, state = "IDLE", volume = .26),
            OutputState("kitchen", connected = true, state = "PLAYING", volume = .15),
            OutputState("office", connected = true, state = "PLAYING", volume = .34, session_id = "bedroom"),
        ),
    ))

    @Test fun nothingPlaying() = render("cast-to-idle", Snapshot(
        devices = devices, target = "bedroom",
        receiver = Receiver(connected = true, device = "Bedroom speaker", state = "IDLE", volume = 40),
        outputs = devices.map { OutputState(it.id, connected = true, state = "IDLE", volume = .3) },
    ))
}
