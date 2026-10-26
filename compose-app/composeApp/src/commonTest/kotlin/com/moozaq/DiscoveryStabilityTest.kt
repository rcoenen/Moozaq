package com.moozaq

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class DiscoveryStabilityTest {
    private class Backend : PlayerBackend {
        var current = Snapshot(catalog_curation=true, catalog_revision=1, master_revision=1, app_revision=1, generated_revision=1)
        var library = CatalogData(
            mixes=(0 until 8).map { Mix("m$it", "Mix $it", "Creator", duration=7200.0, first_seen_at=100L+it, recommended_at=200L+it) },
            recommended_ids=(0 until 8).map { "m$it" }, recently_added_ids=(0 until 8).map { "m$it" },
        )
        override suspend fun snapshot() = current
        override suspend fun catalog() = library
        override suspend fun masterCatalog() = library
        override suspend fun selectDevice(id: String) {}
        var playCalls = 0
        override suspend fun play(source: String) { playCalls++ }
        override suspend fun command(name: String, value: Double?) {}
        override suspend fun outputVolume(id: String, value: Double) {}
        override suspend fun memberVolume(id: String, value: Double) {}
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) {}
        override suspend fun dismissMembership() {}
        override suspend fun importChannel(url: String?) {}
        override suspend fun checkCatalogUpdates(): kotlinx.serialization.json.JsonObject {
            current = current.copy(
                catalog_revision=current.catalog_revision+1,
                app_revision=current.app_revision+1,
                latest_revision=current.app_revision+1,
                last_update_check="2026-10-24T12:00:00Z",
            )
            library = library.copy(
                mixes=(0 until 8).map { Mix("new$it", "New $it", "Creator", duration=7200.0, first_seen_at=300L+it, recommended_at=400L+it) },
                recommended_ids=(0 until 8).map { "new$it" }, recently_added_ids=(0 until 8).map { "new$it" },
            )
            return kotlinx.serialization.json.buildJsonObject {
                put("status", "updated")
                put("revision", current.app_revision)
            }
        }
        override fun close() {}
    }

    @Test fun pollsAndDatabaseReplacementKeepVisibleSnapshotUntilExplicitRefresh() = runBlocking {
        val backend = Backend()
        val store = PlayerStore(backend, this)
        val job = store.start()
        try {
            delay(250)
            val initial = store.state.value.discovery ?: error("No initial discovery")
            val initialIds = initial.recommended.map { it.id }
            backend.library = backend.library.copy(
                mixes=(0 until 8).map { Mix("new$it", "New $it", "Creator", duration=7200.0, first_seen_at=300L+it, recommended_at=400L+it) },
                recommended_ids=(0 until 8).map { "new$it" }, recently_added_ids=(0 until 8).map { "new$it" },
            )
            backend.current = backend.current.copy(catalog_revision=backend.current.catalog_revision+1, app_revision=backend.current.app_revision+1)
            delay(1600)
            val afterPoll = store.state.value.discovery
            assertSame(initial, afterPoll)
            assertEquals(initialIds, afterPoll?.recommended?.map { it.id })
            assertEquals("new0", store.state.value.library.mixes.first().id)
            store.refreshDiscovery()
            delay(250)
            val refreshed = store.state.value.discovery
            assertNotSame(initial, refreshed)
            assertEquals("new0", refreshed?.recommended?.first()?.id)
            assertEquals("new0", refreshed?.catalog?.mixes?.first()?.id)
            assertNull(store.state.value.catalogAction)
            assertEquals(0, backend.playCalls)
        } finally {
            job.cancel()
        }
    }

    @Test fun updateActivationConfirmsStatusWithoutReorderingVisibleCards() = runBlocking {
        val backend = Backend()
        val store = PlayerStore(backend, this)
        val job = store.start()
        try {
            delay(250)
            val initial = store.state.value.discovery ?: error("No initial discovery")
            store.checkCatalogUpdates()
            delay(1000)
            assertNull(store.state.value.catalogAction)
            assertSame(initial, store.state.value.discovery)
            assertEquals("m0", store.state.value.discovery?.recommended?.first()?.id)
            assertEquals("new0", store.state.value.library.mixes.first().id)
            assertEquals(2, store.state.value.snapshot.app_revision)
            assertEquals(2, store.state.value.snapshot.latest_revision)
            assertEquals("2026-10-24T12:00:00Z", store.state.value.snapshot.last_update_check)
            assertEquals(0, backend.playCalls)
        } finally {
            job.cancel()
        }
    }
}
