package com.moozaq

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives the real phone UI with touch: swipe the mini player up to open the player, then swipe
 * the artwork down to close it. Screens are written to build/previews. No backend or speaker.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PlayerSheetGestureTest {
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

    private val density = 2.625f
    private val width = (412 * density).toInt()
    private val height = (915 * density).toInt()
    private var clock = 0L

    private fun ImageComposeScene.frames(count: Int = 40): Image {
        var image = render(clock * 1_000_000)
        repeat(count) { clock += 16; image = render(clock * 1_000_000) }
        return image
    }

    private fun ImageComposeScene.swipe(from: Offset, to: Offset, steps: Int = 24) {
        sendPointerEvent(PointerEventType.Press, from, timeMillis = clock, type = PointerType.Touch)
        for (i in 1..steps) {
            clock += 16
            val p = from + (to - from) * (i / steps.toFloat())
            sendPointerEvent(PointerEventType.Move, p, timeMillis = clock, type = PointerType.Touch)
            render(clock * 1_000_000)
        }
        // Hold still before lifting so this is a deliberate drag rather than a flick.
        repeat(6) { clock += 16; sendPointerEvent(PointerEventType.Move, to, timeMillis = clock, type = PointerType.Touch); render(clock * 1_000_000) }
        clock += 16
        sendPointerEvent(PointerEventType.Release, to, timeMillis = clock, type = PointerType.Touch)
    }

    private fun save(image: Image, name: String) =
        File("build/previews/$name.png").apply { parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }

    /** Between the player's close arrow and its title: Browse shows the orange wordmark there, the open player nothing. */
    private fun cornerIsDark(image: Image): Boolean {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        var bright = 0
        for (x in (80 * density).toInt() until (150 * density).toInt() step 6)
            for (y in (24 * density).toInt() until (44 * density).toInt() step 6) {
                val c = bitmap.getColor(x, y)
                if (((c shr 16) and 0xff) > 160) bright++
            }
        return bright == 0
    }

    @Test fun swipeUpOpensAndSwipeDownCloses() {
        val playing = Snapshot(
            target = "bedroom",
            devices = listOf(Device("bedroom", "Bedroom speaker", cast_type = "audio")),
            receiver = Receiver(connected = true, device = "Bedroom speaker", title = catalog[0].title, state = "PLAYING",
                source = catalog[0].url, position = 600.0, duration = 10000.0, volume = 40),
        )
        val ui = PlayerUiState(snapshot = playing)
        val store = PlayerStore(Unused, CoroutineScope(Dispatchers.Unconfined))
        val scene = ImageComposeScene(width, height, Density(density)) {
            MoozaqTheme(reducedMotion = false) { PhoneApp(store, ui, catalog, manualReducedMotion = false) {} }
        }
        try {
            val browse = scene.frames()
            save(browse, "sheet-1-browse")
            assertTrue(!cornerIsDark(browse), "Browse shows the wordmark")

            val mini = Offset(width / 2f, height - (16 + 32) * density)
            scene.swipe(mini, Offset(width / 2f, height * 0.45f))
            val opened = scene.frames(60)
            save(opened, "sheet-2-opened")
            assertTrue(cornerIsDark(opened), "Swiping the mini player up opens the player")

            scene.swipe(Offset(width / 2f, 260 * density), Offset(width / 2f, 700 * density))
            val closed = scene.frames(60)
            save(closed, "sheet-3-closed")
            assertTrue(!cornerIsDark(closed), "Swiping the player down closes it")

            // A downward swipe that starts on the (horizontal) volume slider also closes it.
            scene.swipe(mini, Offset(width / 2f, height * 0.45f))
            assertTrue(cornerIsDark(scene.frames(60)), "Reopened")
            scene.swipe(Offset(width / 2f, 717 * density), Offset(width / 2f, 717 * density + 190 * density))
            val closedFromSlider = scene.frames(60)
            save(closedFromSlider, "sheet-4-closed-from-slider")
            assertTrue(!cornerIsDark(closedFromSlider), "Swiping down from the volume slider closes it")
        } finally {
            scene.close()
        }
    }
}
