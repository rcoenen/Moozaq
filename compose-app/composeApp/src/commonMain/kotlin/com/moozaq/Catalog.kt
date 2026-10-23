package com.moozaq

import kotlinx.serialization.Serializable

@Serializable data class Mix(val id: String, val title: String, val creator: String, val artwork: Int = -1, val thumbnail: String? = null, val duration: Double? = null, val category: String = "Starter mixes", val channel_id: String? = null) { val url get() = "https://www.youtube.com/watch?v=$id" }
val catalog = listOf(
    Mix("7vy0H3ERRiA", "No Rush. Just Coffee. – 3 Hours of Dark Vintage Noir Jazz for Slow Mornings & Quiet Starts", "The Worn Record", 0),
    Mix("MpoIuYCfEvQ", "''The Day Begins in Lagos'' | Afro House Jazz Mix | Morning Grooves for Focus & Work", "Rhythm & Form", 1),
    Mix("yYkRaDUMavk", "Old Guitars, Raw Blues & Mississippi Delta Nights", "Coyote Delta Blues", 2),
    Mix("zdIaNCDA4ZY", "Focus Music Deep Work 2026 | Study Focus Background | Hyperfocus Concentration Ambient Beats", "Deep Productivity", 3),
    Mix("jsLX0Kp1Zpg", "low cortisol jazz for overthinkers.", "SILEO", 4),
    Mix("eJA9E7RXf5U", "you’re in a cozy 1940's jazz lounge on a rainy night | vintage oldies ambience", "Retro Rainy Melodies", 5)
)

@Serializable data class CatalogChannel(val name: String, val url: String, val id: String? = null, val count: Int = 0, val error: String? = null, val refreshed: String? = null)
@Serializable data class CatalogData(val channels: List<CatalogChannel> = emptyList(), val mixes: List<Mix> = emptyList())
fun mergedCatalog(imported: List<Mix>) = (imported + catalog).distinctBy { it.id }

/** Case- and accent-insensitive search over title, channel and mood; every word must match. */
fun searchMixes(mixes: List<Mix>, query: String): List<Mix> {
    val words = searchKey(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return mixes
    return mixes.filter { mix ->
        val text = searchKey("${mix.title} ${mix.creator} ${mix.category}")
        words.all { it in text }
    }
}
private val accents = mapOf('à' to 'a','á' to 'a','â' to 'a','ã' to 'a','ä' to 'a','å' to 'a','ç' to 'c','è' to 'e','é' to 'e','ê' to 'e','ë' to 'e',
    'ì' to 'i','í' to 'i','î' to 'i','ï' to 'i','ñ' to 'n','ò' to 'o','ó' to 'o','ô' to 'o','õ' to 'o','ö' to 'o','ù' to 'u','ú' to 'u','û' to 'u','ü' to 'u','ý' to 'y','ÿ' to 'y')
private fun searchKey(text: String) = text.lowercase().map { accents[it] ?: if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
