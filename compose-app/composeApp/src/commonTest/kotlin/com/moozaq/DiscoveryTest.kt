package com.moozaq

import kotlin.test.*

class DiscoveryTest {
    private fun mix(id: String, channel: String? = "channel", first: Long? = 100, recommended: Long? = null) =
        Mix(id, "Mix $id", "Creator $channel", duration=7200.0, channel_id=channel,
            first_seen_at=first, recommended_at=recommended)

    @Test fun recommendedUsesEditorialOrderAndLimit() {
        val mixes = (0 until 14).map { mix("m$it", recommended=200L+it) }
        val snapshot = buildDiscovery(CatalogData(mixes=mixes, recommended_ids=mixes.map { it.id }))
        assertEquals(mixes.take(12), snapshot.recommended)
    }

    @Test fun recentlyAddedRequiresRealDatesAndCapsAtTwenty() {
        val mixes = (0 until 25).map { mix("m$it", first=100L+it) }
        val unknown = mix("unknown", first=null)
        val snapshot = buildDiscovery(CatalogData(mixes=mixes+unknown,
            recently_added_ids=listOf("unknown")+mixes.map { it.id }))
        assertEquals(20, snapshot.recentlyAdded.size)
        assertFalse(snapshot.recentlyAdded.any { it.first_seen_at == null })
        assertEquals("m24", snapshot.recentlyAdded.first().id)
    }

    @Test fun coldStartIsDeterministicAndHonestlyNotPersonalized() {
        val mixes = (0 until 10).map { mix("m$it", channel="c${it%4}") }
        val catalog = CatalogData(mixes=mixes)
        val first = buildDiscovery(catalog, seed=42)
        val second = buildDiscovery(catalog, seed=42)
        assertFalse(first.personalized)
        assertEquals(first.forYou.map { it.id }, second.forYou.map { it.id })
        assertTrue(first.reasons.isEmpty())
        assertEquals(10, first.forYou.size)
        assertTrue(first.forYou.size > 1)
    }

    @Test fun likesExplainRecommendationsAndDislikesAreExcluded() {
        val liked = mix("liked", channel="noir")
        val candidate = mix("candidate", channel="noir")
        val other = mix("other", channel="other")
        val disliked = mix("disliked", channel="other")
        val snapshot = buildDiscovery(
            CatalogData(mixes=listOf(liked, candidate, other, disliked)),
            PersonalProfile(ratings=mapOf("liked" to 1, "disliked" to -1),),
        )
        assertTrue(snapshot.personalized)
        assertFalse(snapshot.forYou.any { it.id == "disliked" })
        assertTrue(snapshot.forYou.any { it.id == "candidate" })
        assertEquals("Because you liked Mix liked", snapshot.reasons["candidate"])
    }

    @Test fun likesOutweighPassiveListening() {
        val liked = mix("liked", channel="shared")
        val listened = mix("listened", channel="other")
        val likeCandidate = mix("likeCandidate", channel="shared")
        val listenCandidate = mix("listenCandidate", channel="other")
        val snapshot = buildDiscovery(
            CatalogData(mixes=listOf(likeCandidate, listenCandidate, liked, listened)),
            PersonalProfile(ratings=mapOf("liked" to 1),
                listening=mapOf("listened" to ListeningAggregate(qualified_seconds=3600, qualified_sessions=20, last_listened_at=100)),),
        )
        val ids = snapshot.forYou.map { it.id }
        assertTrue(ids.indexOf("likeCandidate") < ids.indexOf("listenCandidate"))
    }

    @Test fun channelDiversityDoesNotBackfillDominantChannelWhenAlternativesExist() {
        val dominant = (0 until 5).map { mix("dom$it", channel="dom") }
        val alternatives = (0 until 10).map { mix("alt$it", channel="alt$it") }
        val snapshot = buildDiscovery(CatalogData(mixes=dominant+alternatives),
            PersonalProfile(ratings=mapOf("dom0" to 1)))
        val counts = snapshot.forYou.groupBy { it.channel_id }.mapValues { it.value.size }
        assertTrue((counts["dom"] ?: 0) <= 2)
        assertTrue(snapshot.forYou.any { it.id == "alt0" })
    }

    @Test fun listeningAffinityDecaysAndRepeatedSessionsAreCapped() {
        val old = mix("old", channel="shared")
        val fresh = mix("fresh", channel="shared")
        val oldCandidate = mix("oldCandidate", channel="shared")
        val freshCandidate = mix("freshCandidate", channel="shared")
        val snapshot = buildDiscovery(
            CatalogData(mixes=listOf(oldCandidate, freshCandidate, old, fresh)),
            PersonalProfile(listening=mapOf(
                "old" to ListeningAggregate(qualified_seconds=999999, qualified_sessions=20, last_listened_at=100),
                "fresh" to ListeningAggregate(qualified_seconds=100, qualified_sessions=1, last_listened_at=100),
            )),
            now=100+60*86400,
        )
        val ids = snapshot.forYou.map { it.id }
        assertTrue(ids.indexOf("freshCandidate") < ids.indexOf("oldCandidate"))
    }

    @Test fun dislikedAffinityReducesSimilarItemsWithoutBanningThem() {
        val disliked = mix("badItem", channel="bad")
        val similar = mix("similar", channel="bad")
        val other = mix("other", channel="good")
        val snapshot = buildDiscovery(CatalogData(mixes=listOf(disliked, similar, other)),
            PersonalProfile(ratings=mapOf("badItem" to -1)))
        val ids = snapshot.forYou.map { it.id }
        assertTrue(ids.indexOf("other") < ids.indexOf("similar"))
        assertTrue(snapshot.forYou.any { it.id == "similar" })
        assertFalse(snapshot.forYou.any { it.id == "badItem" })
        assertFalse(snapshot.reasons["similar"]?.contains("Because you liked") == true)
    }

    @Test fun rotationUsesDifferentSeedsAndExplorationHasNoFabricatedReason() {
        val mixes = (0 until 10).map { mix("m$it", channel="c$it") }
        val first = buildDiscovery(CatalogData(mixes=mixes), seed=1)
        val second = buildDiscovery(CatalogData(mixes=mixes), seed=99)
        assertNotEquals(first.forYou.map { it.id }, second.forYou.map { it.id })
        assertTrue(first.reasons.isEmpty())
        assertTrue(second.reasons.isEmpty())
    }

    @Test fun oldEditorialChoiceDoesNotExpireDaily() {
        val mix = mix("old", recommended=1)
        val snapshot = buildDiscovery(CatalogData(mixes=listOf(mix), recommended_ids=listOf("old")), now=999999)
        assertEquals(listOf(mix), snapshot.recommended)
    }
}
