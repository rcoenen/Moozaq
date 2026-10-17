package com.moozaq
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import java.net.URI
actual suspend fun loadCatalogCover(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val uri=URI(url)
        require(uri.scheme=="https" && uri.host in listOf("i.ytimg.com","img.youtube.com"))
        val connection=uri.toURL().openConnection().apply { connectTimeout=5000; readTimeout=8000 }
        connection.getInputStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    } catch(e: CancellationException) { throw e } catch(_: Exception) { null }
}
