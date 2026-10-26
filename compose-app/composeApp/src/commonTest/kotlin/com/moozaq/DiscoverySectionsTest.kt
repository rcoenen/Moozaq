package com.moozaq

import kotlin.test.*

class DiscoverySectionsTest {
    @Test fun specialSectionsLeadMoodsAndSearchTemporarilyHidesThem() {
        val recommended = Mix("recommended", "Recommended", "Creator", duration=7200.0, recommended_at=100)
        val recent = Mix("recent", "Recent", "Creator", duration=7200.0, first_seen_at=200)
        val personal = Mix("personal", "Personal", "Creator", duration=7200.0)
        val discovery = DiscoverySnapshot(
            catalog=CatalogData(mixes=listOf(recommended, recent, personal)),
            recommended=listOf(recommended), recentlyAdded=listOf(recent), forYou=listOf(personal),
            personalized=true, reasons=mapOf("personal" to "Because you liked Recommended"),
        )
        val sections = visibleDiscoverySections(discovery, "")
        assertEquals(listOf("Recommended", "Recently added", "For you"), sections.map { it.title })
        assertEquals(listOf(recommended, recent, personal), sections.flatMap { it.mixes })
        assertEquals("Because you liked Recommended", discovery.reasons["personal"])
        assertTrue(visibleDiscoverySections(discovery, "jazz").isEmpty())
        assertEquals(sections, visibleDiscoverySections(discovery, ""))
    }

    @Test fun coldStartUsesDiscoverAndEmptySectionsAreOmitted() {
        val recent = Mix("recent", "Recent", "Creator", duration=7200.0, first_seen_at=200)
        val discovery = DiscoverySnapshot(CatalogData(mixes=listOf(recent)), recommended=emptyList(),
            recentlyAdded=listOf(recent), forYou=listOf(recent), personalized=false, reasons=emptyMap())
        val sections = visibleDiscoverySections(discovery, "")
        assertEquals(listOf("Recently added", "Discover"), sections.map { it.title })
    }

    @Test fun sparseSectionsShareOneOrderedCardSequence() {
        val recommended = Mix("recommended", "Recommended", "Creator", duration=7200.0, recommended_at=100)
        val recent = Mix("recent", "Recent", "Creator", duration=7200.0, first_seen_at=200)
        val older = Mix("older", "Older", "Creator", duration=7200.0, first_seen_at=100)
        val personal = Mix("personal", "Personal", "Creator", duration=7200.0)
        val discovery = DiscoverySnapshot(
            catalog=CatalogData(mixes=listOf(recommended, recent, older, personal)),
            recommended=listOf(recommended), recentlyAdded=listOf(recent, older), forYou=listOf(personal),
            personalized=true, reasons=emptyMap(),
        )
        val cards = discoveryCards(visibleDiscoverySections(discovery, ""))
        assertEquals(listOf("recommended", "recent", "older", "personal"), cards.map { it.mix.id })
        assertEquals(listOf("Recommended", "Recently added", "Recently added", "For you"), cards.map { it.section.title })
        assertEquals(listOf(
            DiscoverySectionKind.RECOMMENDED, DiscoverySectionKind.RECENTLY_ADDED,
            DiscoverySectionKind.RECENTLY_ADDED, DiscoverySectionKind.FOR_YOU,
        ), cards.map { it.section.kind })
        assertEquals(4, cards.map { "${it.section.kind}:${it.mix.id}" }.distinct().size)
    }
}
