package com.moozaq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class LocalPlaybackUiTest {
    private class Backend : PlayerBackend {
        val mix = Mix("local-mix", "Local test mix", "Mac output", artwork=0, duration=7200.0, category="Local")
        val local = Device("mac-local", "This Mac", cast_type="local")
        val current = Snapshot(
            local_output=local.id,
            devices=listOf(local, Device("office", "Office", cast_type="audio")),
            target=local.id, catalog_revision=1, app_revision=1,
            receiver=Receiver(connected=true, device="This Mac", state="PLAYING", source=mix.url,
                title=mix.title, position=125.0, duration=7200.0, volume=15),
        )
        val calls = mutableListOf<String>()
        override suspend fun snapshot() = current
        override suspend fun catalog() = CatalogData(mixes=listOf(mix))
        override suspend fun masterCatalog() = error("No owner catalog in this test")
        override suspend fun selectDevice(id: String) { calls += "select:$id" }
        override suspend fun play(source: String) = error("Playback is forbidden in this render test")
        override suspend fun command(name: String, value: Double?) = error("Receiver commands are forbidden in this render test")
        override suspend fun outputVolume(id: String, value: Double) = error("Cast volume is forbidden in this render test")
        override suspend fun memberVolume(id: String, value: Double) = error("Membership is forbidden in this render test")
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) = error("Membership is forbidden in this render test")
        override suspend fun dismissMembership() = error("Membership is forbidden in this render test")
        override suspend fun importChannel(url: String?) = error("Channel mutation is forbidden in this render test")
        override suspend fun checkCatalogUpdates(): JsonObject = error("Updates are not part of this render test")
        override fun close() {}
    }

    @Test fun localPlayerAndPickerRenderWithoutCastActions() = runBlocking {
        val backend = Backend()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val store = PlayerStore(backend, scope)
        val poll = store.start()
        val scenes = mutableListOf<ImageComposeScene>()
        try {
            withTimeout(5_000) {
                while (store.state.value.library.mixes.isEmpty() || store.state.value.discovery == null) delay(20)
            }
            val player = ImageComposeScene(1428, 830, Density(1f)) { MoozaqApp(store) }.also(scenes::add)
            val playerImage = player.render(16_000_000)
            val playerOutput = File("build/previews/local-player.png").apply { parentFile.mkdirs() }
            playerOutput.writeBytes(playerImage.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertTrue(playerOutput.length()>0)

            val picker = ImageComposeScene(1100, 300, Density(1f)) {
                MoozaqTheme(reducedMotion = true) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
                        backend.current.devices.forEach { device ->
                            SpeakerRow(store, store.state.value, device, compact=false)
                        }
                    }
                }
            }.also(scenes::add)
            val pickerImage = picker.render(16_000_000)
            val pickerOutput = File("build/previews/local-output-picker.png").apply { parentFile.mkdirs() }
            pickerOutput.writeBytes(pickerImage.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertTrue(pickerOutput.length()>0)
            assertEquals("This Mac", store.state.value.snapshot.receiver.device)
            assertFalse(canSelectDestination(store.state.value, store.state.value.snapshot.devices[1]))
            assertTrue(backend.calls.isEmpty())
        } finally {
            poll.cancel()
            scenes.forEach { it.close() }
            scope.cancel()
        }
    }
}
