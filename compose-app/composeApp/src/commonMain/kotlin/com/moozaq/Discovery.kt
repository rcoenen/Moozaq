package com.moozaq

import kotlin.math.max
import kotlin.random.Random

private const val DISCOVERY_SHELF_SIZE = 12

data class DiscoverySnapshot(
    val catalog: CatalogData,
    val recommended: List<Mix>,
    val recentlyAdded: List<Mix>,
    val forYou: List<Mix>,
    val personalized: Boolean,
    val reasons: Map<String, String>,
)

enum class DiscoverySectionKind { RECOMMENDED, RECENTLY_ADDED, FOR_YOU, DISCOVER }
data class DiscoverySection(val kind: DiscoverySectionKind, val title: String, val mixes: List<Mix>)
data class DiscoveryCard(val section: DiscoverySection, val mix: Mix)

fun discoveryCards(sections: List<DiscoverySection>): List<DiscoveryCard> = sections.flatMap { section ->
    section.mixes.map { mix -> DiscoveryCard(section, mix) }
}

fun visibleDiscoverySections(discovery: DiscoverySnapshot, query: String): List<DiscoverySection> {
    if (query.isNotBlank()) return emptyList()
    return buildList {
        if (discovery.recommended.isNotEmpty()) add(DiscoverySection(DiscoverySectionKind.RECOMMENDED, "Recommended", discovery.recommended))
        if (discovery.recentlyAdded.isNotEmpty()) add(DiscoverySection(DiscoverySectionKind.RECENTLY_ADDED, "Recently added", discovery.recentlyAdded))
        if (discovery.forYou.isNotEmpty()) add(DiscoverySection(if (discovery.personalized) DiscoverySectionKind.FOR_YOU else DiscoverySectionKind.DISCOVER, if (discovery.personalized) "For you" else "Discover", discovery.forYou))
    }
}

fun buildDiscovery(catalog: CatalogData, profile: PersonalProfile = PersonalProfile(), seed: Long = 0, now: Long? = null): DiscoverySnapshot {
    val byId = catalog.mixes.associateBy { it.id }
    val recommended = catalog.recommended_ids.mapNotNull { byId[it] }.distinctBy { it.id }.take(12)
    val recent = catalog.recently_added_ids.mapNotNull { byId[it] }.filter { it.first_seen_at != null }.distinctBy { it.id }.sortedByDescending { it.first_seen_at }.take(20)
    val eligible = catalog.mixes.filter { (it.duration ?: 0.0) >= 3600 }
    val personalized = profile.ratings.isNotEmpty() || profile.listening.any { it.value.qualified_sessions > 0 }
    val ranked = if (personalized) rankPersonalized(eligible, byId, profile, seed, now) else variedDiscovery(eligible, seed)
    val forYou = ranked.map { it.first }
    val reasons = if (personalized) ranked.mapNotNull { (mix, reason) -> reason?.let { mix.id to it } }.toMap() else emptyMap()
    return DiscoverySnapshot(catalog, recommended, recent, forYou, personalized, reasons)
}

private fun variedDiscovery(mixes: List<Mix>, seed: Long): List<Pair<Mix, String?>> {
    val shuffled = mixes.shuffled(Random(seed))
    return diverse(shuffled.map { it to null }).take(DISCOVERY_SHELF_SIZE)
}

private fun rankPersonalized(mixes: List<Mix>, byId: Map<String, Mix>, profile: PersonalProfile, seed: Long, now: Long?): List<Pair<Mix, String?>> {
    val category = mutableMapOf<String, Double>()
    val creator = mutableMapOf<String, Double>()
    val channel = mutableMapOf<String, Double>()
    fun boost(target: MutableMap<String, Double>, key: String?, value: Double) {
        if (!key.isNullOrBlank()) target[key] = (target[key] ?: 0.0) + value
    }
    profile.ratings.forEach { (id, rating) ->
        val mix = byId[id] ?: return@forEach
        if (rating > 0) {
            boost(category, mix.category, 6.0)
            boost(creator, mix.creator, 5.0)
            boost(channel, mix.channel_id, 4.0)
        } else if (rating < 0) {
            boost(category, mix.category, -2.0)
            boost(creator, mix.creator, -2.0)
            boost(channel, mix.channel_id, -2.0)
        }
    }
    profile.listening.forEach { (id, aggregate) ->
        if (aggregate.qualified_sessions <= 0) return@forEach
        val mix = byId[id] ?: return@forEach
        val ageDays = if (now == null || aggregate.last_listened_at == null) 0.0 else max(0.0, (now - aggregate.last_listened_at) / 86400.0)
        val weight = aggregate.qualified_sessions.coerceAtMost(3) / (1.0 + ageDays / 30.0)
        boost(category, mix.category, 2.0 * weight)
        boost(creator, mix.creator, 2.0 * weight)
        boost(channel, mix.channel_id, weight)
    }
    val scored = mixes.filter { profile.ratings[it.id] != -1 }.map { mix ->
        val score = (category[mix.category] ?: 0.0) + (creator[mix.creator] ?: 0.0) + (channel[mix.channel_id] ?: 0.0) -
            (profile.listening[mix.id]?.qualified_sessions ?: 0).coerceAtMost(3) * 0.75
        val liked = byId.entries.find { profile.ratings[it.key] == 1 &&
            (it.value.category == mix.category || it.value.creator == mix.creator || it.value.channel_id == mix.channel_id) }?.value
        val listened = byId.entries.find { (profile.listening[it.key]?.qualified_sessions ?: 0) > 0 &&
            (it.value.category == mix.category || it.value.creator == mix.creator || it.value.channel_id == mix.channel_id) }?.value
        val reason = liked?.let { "Because you liked ${it.title}" } ?: listened?.let { "Because you listened to ${it.title}" }
        Triple(mix, score, reason)
    }
    val preferred = diverse(scored.sortedWith(compareByDescending<Triple<Mix, Double, String?>> { it.second }.thenBy { it.first.id }).map { it.first to it.third })
    val exploration = variedDiscovery(mixes.filter { mix ->
        profile.ratings[mix.id] != -1 && profile.ratings[mix.id] != 1 && (profile.listening[mix.id]?.qualified_sessions ?: 0) == 0
    }, seed)
    val result = preferred.take((DISCOVERY_SHELF_SIZE - DISCOVERY_SHELF_SIZE / 4).coerceAtLeast(0)).toMutableList()
    exploration.take(DISCOVERY_SHELF_SIZE / 4).forEach { item ->
        if (result.none { it.first.id == item.first.id } && result.size < DISCOVERY_SHELF_SIZE) result += item
    }
    return diverse(result).take(DISCOVERY_SHELF_SIZE)
}

private fun <T> diverse(items: List<Pair<T, String?>>): List<Pair<T, String?>> {
    if (items.size <= 2) return items
    val counts = mutableMapOf<String?, Int>()
    val result = mutableListOf<Pair<T, String?>>()
    fun channel(item: T): String? = (item as? Mix)?.channel_id ?: (item as? Mix)?.creator
    val grouped = items.groupBy { channel(it.first) }
    val canDiversify = grouped.size > 1 && grouped.values.any { it.size > 2 }
    if (!canDiversify) return items
    items.forEach { item ->
        val key = channel(item.first)
        if ((counts[key] ?: 0) < 2) {
            result += item
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    val remaining = items.filter { it !in result }
    val alternatives = remaining.filter { (counts[channel(it.first)] ?: 0) < 2 }
    val target = if (alternatives.isNotEmpty()) result.size + alternatives.size else minOf(12, items.size)
    result += alternatives
    if (result.size < target && remaining.map { channel(it.first) }.distinct().size > 1) result += remaining.filter { it !in result }
    return result
}
