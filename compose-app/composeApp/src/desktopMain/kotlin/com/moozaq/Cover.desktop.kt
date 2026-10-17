package com.moozaq

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URI
import java.security.MessageDigest

private val artworkSlots = Semaphore(4)
actual suspend fun loadCatalogCover(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    artworkSlots.withPermit {
        try {
            val uri=URI(url)
            require(uri.scheme=="https" && uri.host in listOf("i.ytimg.com","img.youtube.com"))
            val root=System.getProperty("moozaq.root") ?: System.getenv("MOOZAQ_ROOT") ?: "."
            val directory=File(root,"mac-spike/data/catalog-artwork").apply { mkdirs() }
            val key=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val cached=File(directory,key)
            val bytes=if(cached.exists()) cached.readBytes() else {
                val connection=uri.toURL().openConnection().apply { connectTimeout=5000; readTimeout=8000 }
                val data=connection.getInputStream().use { it.readNBytes(2_000_001) }
                require(data.size<=2_000_000)
                org.jetbrains.skia.Image.makeFromEncoded(data) // Validate before caching.
                val temp=File.createTempFile("cover-",".tmp",directory)
                temp.writeBytes(data); temp.renameTo(cached); temp.delete()
                data
            }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
    }
}
