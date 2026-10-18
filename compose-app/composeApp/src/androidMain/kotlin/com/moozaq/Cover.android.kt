package com.moozaq
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Set by the app at startup; covers are cached there across launches. Null keeps memory-only caching. */
var coverCacheDir: File? = null

// Decoded covers stay in memory so scrolling back shows them at once instead of the placeholder.
private val memory = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
}
private val downloads = Semaphore(4)
private val inFlight = mutableMapOf<String, Deferred<ImageBitmap?>>()
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

actual fun cachedCatalogCover(url: String): ImageBitmap? = memory.get(url)

actual suspend fun loadCatalogCover(url: String): ImageBitmap? {
    memory.get(url)?.let { return it }
    // One load per URL, shared by every card asking for it; a card leaving the screen does not cancel it.
    val load = synchronized(inFlight) { inFlight.getOrPut(url) { scope.async { fetch(url) } } }
    return try { load.await() } finally { if (load.isCompleted) synchronized(inFlight) { if (inFlight[url] === load) inFlight.remove(url) } }
}

private suspend fun fetch(url: String): ImageBitmap? = downloads.withPermit {
    try {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host in listOf("i.ytimg.com", "img.youtube.com"))
        val file = coverCacheDir?.let { dir ->
            File(dir.apply { mkdirs() }, MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
        }
        val bytes = file?.takeIf { it.isFile }?.readBytes() ?: run {
            val connection = uri.toURL().openConnection().apply { connectTimeout = 5000; readTimeout = 8000 }
            val data = connection.getInputStream().use { it.readNBytes(2_000_001) }
            require(data.size <= 2_000_000)
            data
        }
        // Thumbnails have no transparency; RGB_565 halves memory per cover.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap() ?: return@withPermit null
        if (file != null && !file.isFile) {
            val temp = File.createTempFile("cover-", ".tmp", file.parentFile)
            temp.writeBytes(bytes); if (!temp.renameTo(file)) temp.delete()
        }
        memory.put(url, image)
        image
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
