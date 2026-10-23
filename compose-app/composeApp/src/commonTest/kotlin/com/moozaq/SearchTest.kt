package com.moozaq

import kotlin.test.*

class SearchTest {
    private val mixes = listOf(
        Mix("a", "DARK TEHRAN NOIR ✦ 70s Persian Psych Jazz", "Afterhours Club '70", category = "Persian / Middle Eastern Jazz"),
        Mix("b", "Coffee Shop Focus Radio – 1930s Vintage Jazz", "Old Tape Jazz", category = "Vintage Lounge / Exotica"),
        Mix("c", "Café à Paris · Bossa for Slow Mornings", "Sepia Lounge", category = "Cozy Jazz / Swing"),
    )

    @Test fun emptyQueryKeepsEverything() {
        assertEquals(mixes, searchMixes(mixes, ""))
        assertEquals(mixes, searchMixes(mixes, "   "))
    }

    @Test fun matchesTitleChannelAndMoodIgnoringCase() {
        assertEquals(listOf("a"), searchMixes(mixes, "tehran").map { it.id })
        assertEquals(listOf("b"), searchMixes(mixes, "old tape").map { it.id })
        assertEquals(listOf("c"), searchMixes(mixes, "COZY").map { it.id })
    }

    @Test fun everyWordMustMatchInAnyOrder() {
        assertEquals(listOf("b"), searchMixes(mixes, "jazz coffee").map { it.id })
        assertEquals(emptyList(), searchMixes(mixes, "coffee tehran"))
    }

    @Test fun accentsAndPunctuationAreIgnored() {
        assertEquals(listOf("c"), searchMixes(mixes, "cafe a paris").map { it.id })
        assertEquals(listOf("a"), searchMixes(mixes, "persian-psych").map { it.id })
    }

    @Test fun noMatchIsEmpty() {
        assertTrue(searchMixes(mixes, "reggaeton").isEmpty())
    }
}
