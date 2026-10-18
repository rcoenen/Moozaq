package com.moozaq

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.painterResource
import com.moozaq.resources.*

expect suspend fun loadCatalogCover(url: String): ImageBitmap?
/** A cover already decoded in memory, so a card scrolled back into view skips the placeholder. */
expect fun cachedCatalogCover(url: String): ImageBitmap?
private val starterCovers = listOf(Res.drawable.mix_0, Res.drawable.mix_1, Res.drawable.mix_2, Res.drawable.mix_3, Res.drawable.mix_4, Res.drawable.mix_5)
@Composable fun MixCover(mix: Mix?, modifier: Modifier) {
    val bitmap by produceState(mix?.thumbnail?.let(::cachedCatalogCover), mix?.thumbnail) {
        val url = mix?.thumbnail
        value = url?.let(::cachedCatalogCover)
        if (value == null && url != null) value = loadCatalogCover(url)
    }
    if(bitmap != null) Image(bitmap!!, mix?.title, modifier, contentScale=ContentScale.Crop)
    else {
        val artwork = mix?.artwork?.let { starterCovers.getOrNull(it) }
        val placeholder = artwork == null
        val grayscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
        Image(
            painterResource(artwork ?: Res.drawable.logo),
            mix?.title ?: "Moozaq",
            modifier,
            contentScale=ContentScale.Crop,
            colorFilter=if(placeholder) grayscale else null,
            alpha=if(placeholder) 0.35f else 1f
        )
    }
}
