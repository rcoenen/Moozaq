package com.moozaq

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class CatalogDistributionUiTest {
    private class Backend : PlayerBackend {
        var current = Snapshot(
            catalog_backend="sqlite", catalog_curation=true, catalog_updates=true,
            catalog_revision=7, master_revision=7, app_revision=7, generated_revision=7, latest_revision=7,
        )
        val publications = mutableListOf<Pair<Boolean, Int>>()
        override suspend fun snapshot() = current
        override suspend fun selectDevice(id: String) {}
        override suspend fun play(source: String) { error("Playback is forbidden in this render test") }
        override suspend fun command(name: String, value: Double?) { error("Receiver commands are forbidden in this render test") }
        override suspend fun outputVolume(id: String, value: Double) {}
        override suspend fun memberVolume(id: String, value: Double) {}
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) {}
        override suspend fun dismissMembership() {}
        override suspend fun catalog() = CatalogData()
        override suspend fun masterCatalog() = CatalogData()
        override suspend fun importChannel(url: String?) {}
        override suspend fun checkCatalogUpdates(): JsonObject {
            current = current.copy(last_update_check="2026-10-24T12:00:00Z")
            return JsonObject(mapOf("status" to JsonPrimitive("current"), "revision" to JsonPrimitive(7)))
        }
        override suspend fun publishCatalogRelease(confirmed: Boolean, expectedRevision: Int): JsonObject {
            publications += confirmed to expectedRevision
            return JsonObject(mapOf(
                "revision" to JsonPrimitive(expectedRevision),
                "tag" to JsonPrimitive("catalog-v$expectedRevision"),
                "assets" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("app-catalog.db"), JsonPrimitive("catalog-manifest.json"))),
            ))
        }
        override fun close() {}
    }

    @Test fun releaseControlsRenderWithConfirmedUpdateStatus() {
        val backend = Backend()
        val store = PlayerStore(backend, CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(1100, 780, Density(1f)) {
            MoozaqTheme(reducedMotion = true) {
                val ui by store.state.collectAsState()
                DesktopCatalogDialog(store, ui, reducedMotion=true, onReducedMotion={}, onClose={})
            }
        }
        val poll = store.start()
        fun click(at: androidx.compose.ui.geometry.Offset) {
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, at, timeMillis=100, type=androidx.compose.ui.input.pointer.PointerType.Mouse)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at, timeMillis=101, type=androidx.compose.ui.input.pointer.PointerType.Mouse,
                buttons=androidx.compose.ui.input.pointer.PointerButtons(1))
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at, timeMillis=102, type=androidx.compose.ui.input.pointer.PointerType.Mouse)
        }
        try {
            scene.render(0)
            click(androidx.compose.ui.geometry.Offset(762f, 725f))
            val image = scene.render(200_000_000)
            val output = java.io.File("build/previews/catalog-release-controls.png").apply { parentFile.mkdirs() }
            output.writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertTrue(output.length() > 0)
            assertEquals("2026-10-24T12:00:00Z", store.state.value.snapshot.last_update_check)
            assertNull(store.state.value.catalogAction)
            click(androidx.compose.ui.geometry.Offset(870f, 725f))
            val confirmation = scene.render(400_000_000)
            val confirmationOutput = java.io.File("build/previews/catalog-release-confirmation.png").apply { parentFile.mkdirs() }
            confirmationOutput.writeBytes(confirmation.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            assertTrue(confirmationOutput.length() > 0)
            assertTrue(backend.publications.isEmpty())
        } finally {
            poll.cancel()
            scene.close()
        }
    }
}
