package com.moozaq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class CatalogCapabilitiesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun legacySnapshotKeepsDefaults() {
        val snapshot = json.decodeFromString<Snapshot>("{}")
        assertEquals("legacy", snapshot.catalog_backend)
        assertFalse(snapshot.catalog_curation)
        assertFalse(snapshot.catalog_updates)
        assertFalse(snapshot.personal_profile)
    }

    @Test
    fun desktopCapabilitiesDecodeWithoutAffectingOtherFields() {
        val snapshot = json.decodeFromString<Snapshot>(
            """{"catalog_backend":"sqlite","catalog_curation":true,"catalog_updates":true,"personal_profile":true}"""
        )
        assertEquals("sqlite", snapshot.catalog_backend)
        assertTrue(snapshot.catalog_curation)
        assertTrue(snapshot.catalog_updates)
        assertTrue(snapshot.personal_profile)
        assertEquals("Starting local engine…", snapshot.message)
    }
}
