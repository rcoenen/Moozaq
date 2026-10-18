package com.moozaq

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/** Dragging a volume slider must not grey out play/pause, seek or Stop while the level is confirmed. */
class VolumeKeepsTransportTest {
    private class SlowVolumeBackend(val playing: Snapshot) : PlayerBackend {
        val release = CompletableDeferred<Unit>()
        override suspend fun snapshot() = playing
        override suspend fun command(name: String, value: Double?) { if (name == "volume") release.await() }
        override suspend fun outputVolume(id: String, value: Double) { release.await() }
        override suspend fun memberVolume(id: String, value: Double) { release.await() }
        override suspend fun selectDevice(id: String) {}
        override suspend fun play(source: String) {}
        override suspend fun catalog() = CatalogData()
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) {}
        override suspend fun dismissMembership() {}
        override suspend fun importChannel(url: String?) {}
        override fun close() {}
    }

    private val playing = Snapshot(
        target = "bedroom",
        receiver = Receiver(connected = true, device = "Bedroom speaker", state = "PLAYING", volume = 30),
        outputs = listOf(OutputState("office", connected = true, state = "PLAYING", volume = .3)),
    )

    @Test fun pendingVolumeKeepsPauseAvailable() = runBlocking {
        val backend = SlowVolumeBackend(playing)
        val store = PlayerStore(backend, this)
        val polling = store.start()
        try {
            withTimeout(3000) { store.state.first { it.snapshot.receiver.state == "PLAYING" } }
            store.mainVolume(.5)
            store.outputVolume("office", .4)
            yield()
            val ui = store.state.value
            assertNotNull(ui.mainVolume)
            assertTrue(canPause(ui.snapshot), "Pause stays enabled while the volume is pending")
            assertFalse(ui.snapshot.busy)
        } finally {
            backend.release.complete(Unit)
            polling.cancelAndJoin()
        }
    }
}
