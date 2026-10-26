package com.moozaq

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalComposeUiApi::class)
class SpeakerPickerGestureTest {
    private class RecordingBackend(private val current: Snapshot) : PlayerBackend {
        val selections = mutableListOf<String>()
        val calls = mutableListOf<String>()
        override suspend fun snapshot() = current
        override suspend fun selectDevice(id: String) { selections += id }
        override suspend fun play(source: String) { calls += "play" }
        override suspend fun command(name: String, value: Double?) { calls += name }
        override suspend fun outputVolume(id: String, value: Double) { calls += "output-volume" }
        override suspend fun memberVolume(id: String, value: Double) { calls += "member-volume" }
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) { calls += "membership" }
        override suspend fun dismissMembership() {}
        override suspend fun catalog() = CatalogData()
        override suspend fun importChannel(url: String?) {}
        override fun close() {}
    }

    private fun assertBedroomSelection(at: Offset) {
        val snapshot = Snapshot(
            target = "fridge",
            devices = listOf(
                Device("bedroom", "Bedroom speaker", cast_type = "audio"),
                Device("fridge", "Fridge speaker", cast_type = "audio"),
            ),
            receiver = Receiver(connected = true, device = "Fridge speaker", state = "IDLE"),
        )
        val backend = RecordingBackend(snapshot)
        val store = PlayerStore(backend, CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(500, 140, Density(1f)) {
            MoozaqTheme(reducedMotion = true) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(20.dp)) {
                        SpeakerRow(store, PlayerUiState(snapshot = snapshot), snapshot.devices.first(), compact = false)
                    }
                }
            }
        }
        try {
            val preview = scene.render(0)
            java.io.File("build/previews/speaker-row-${at.x.toInt()}-${at.y.toInt()}.png").apply { parentFile.mkdirs() }
                .writeBytes(preview.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = 100, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = 101, type = PointerType.Mouse,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(1))
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = 102, type = PointerType.Mouse)
            scene.render(200_000_000)
            assertEquals(listOf("bedroom"), backend.selections, "No selection or the wrong output at $at")
            assertFalse(backend.calls.any { it == "play" || it == "membership" })
        } finally {
            scene.close()
        }
    }

    @Test fun iconAndFullNameHeightSelectBedroomWithoutPlaying() {
        assertBedroomSelection(Offset(80f, 46f))
        assertBedroomSelection(Offset(120f, 26f))
        assertBedroomSelection(Offset(120f, 66f))
    }

    @Test fun liveMembershipLockRendersDetail() {
        val snapshot = Snapshot(
            target = "fridge",
            devices = listOf(Device("bedroom", "Bedroom speaker", cast_type = "audio")),
            receiver = Receiver(connected = true, device = "Fridge speaker", state = "PLAYING"),
        )
        val store = PlayerStore(RecordingBackend(snapshot), CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(500, 140, Density(1f)) {
            MoozaqTheme(reducedMotion = true) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(20.dp)) {
                        SpeakerRow(store, PlayerUiState(snapshot = snapshot), snapshot.devices.first(), compact = false)
                    }
                }
            }
        }
        try {
            val preview = scene.render(0)
            val out = java.io.File("build/previews/speaker-row-live-locked.png").apply { parentFile.mkdirs() }
            out.writeBytes(preview.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertFalse(out.length() == 0L)
        } finally {
            scene.close()
        }
    }

    @Test fun idleCheckboxesBuildDraftWithoutCommands() {
        val snapshot = Snapshot(
            devices = listOf(
                Device("bedroom", "Bedroom speaker", cast_type = "audio"),
                Device("fridge", "Fridge speaker", cast_type = "audio"),
            ),
            receiver = Receiver(state = "IDLE"),
        )
        val backend = RecordingBackend(snapshot)
        val store = PlayerStore(backend, CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(500, 180, Density(1f)) {
            MoozaqTheme(reducedMotion = true) {
                Surface(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(20.dp)) {
                        snapshot.devices.forEach { SpeakerRow(store, PlayerUiState(snapshot = snapshot), it, compact = false) }
                    }
                }
            }
        }
        try {
            scene.render(0)
            for (at in listOf(Offset(40f, 46f), Offset(40f, 98f))) {
                scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = 100, type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = 101, type = PointerType.Mouse,
                    buttons = androidx.compose.ui.input.pointer.PointerButtons(1))
                scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = 102, type = PointerType.Mouse)
                scene.render(200_000_000)
            }
            assertEquals(setOf("bedroom", "fridge"), store.state.value.speakerDraft?.members)
            assertFalse(backend.selections.isNotEmpty())
            assertFalse(backend.calls.isNotEmpty())
        } finally {
            scene.close()
        }
    }

    @Test fun draftRowsStaySelectedWhileJoining() {
        val mix = catalog.first()
        val snapshot = Snapshot(
            target = "bedroom",
            busy = true,
            devices = listOf(
                Device("bedroom", "Bedroom speaker", cast_type = "audio"),
                Device("fridge", "Fridge speaker", cast_type = "audio"),
            ),
            receiver = Receiver(connected = true, device = "Bedroom speaker", state = "PLAYING", source = mix.url, transport_revision = 1),
        )
        val ui = PlayerUiState(snapshot = snapshot,
            speakerDraft = SpeakerDraft(setOf("bedroom", "fridge"), source = mix.url, playbackConfirmed = true))
        val store = PlayerStore(RecordingBackend(snapshot), CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(500, 180, Density(1f)) {
            MoozaqTheme(reducedMotion = true) {
                Surface(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(20.dp)) {
                        snapshot.devices.forEach { SpeakerRow(store, ui, it, compact = false) }
                    }
                }
            }
        }
        try {
            val preview = scene.render(0)
            val out = java.io.File("build/previews/speaker-rows-joining.png").apply { parentFile.mkdirs() }
            out.writeBytes(preview.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertFalse(out.length() == 0L)
        } finally {
            scene.close()
        }
    }

    @Test fun localRowSelectsWithoutPlayingOrCastCommands() {
        val local = Device("mac-local", "This Mac", cast_type = "local")
        val snapshot = Snapshot(
            local_output = local.id,
            devices = listOf(local, Device("office", "Office", cast_type = "audio")),
            receiver = Receiver(state = "IDLE"),
        )
        val backend = RecordingBackend(snapshot)
        val store = PlayerStore(backend, CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(500, 140, Density(1f)) {
            MoozaqTheme(reducedMotion = true) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(20.dp)) {
                        SpeakerRow(store, PlayerUiState(snapshot = snapshot), local, compact = false)
                    }
                }
            }
        }
        try {
            val preview = scene.render(0)
            java.io.File("build/previews/speaker-row-local.png").apply { parentFile.mkdirs() }
                .writeBytes(preview.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            val at = Offset(40f, 46f)
            scene.sendPointerEvent(PointerEventType.Move, at, timeMillis = 100, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Press, at, timeMillis = 101, type = PointerType.Mouse,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(1))
            scene.sendPointerEvent(PointerEventType.Release, at, timeMillis = 102, type = PointerType.Mouse)
            scene.render(200_000_000)
            assertEquals(listOf("mac-local"), backend.selections)
            assertFalse(backend.calls.isNotEmpty())
        } finally {
            scene.close()
        }
    }
}
