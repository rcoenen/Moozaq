package com.moozaq

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement

class PersonalActionsTest {
    private class Backend : PlayerBackend {
        var current = Snapshot(catalog_curation=true, personal_profile=true, catalog_revision=1, master_revision=1, app_revision=1, generated_revision=1, personal_revision=1)
        var profile = PersonalProfile()
        var library = CatalogData(mixes=listOf(Mix("mix", "Mix", "Creator", duration=7200.0, first_seen_at=100, recommended_at=100)))
        var fail = false
        var playCalls = 0
        var events = mutableListOf<String>()

        override suspend fun snapshot() = current
        override suspend fun catalog(): CatalogData { events += "catalog"; return library }
        override suspend fun masterCatalog() = library
        override suspend fun personalProfile(): PersonalProfile { events += "profile"; return profile }
        override suspend fun rateSong(id: String, rating: Int): CatalogActionResult {
            if(fail) error("rating failed")
            events += "rate"
            profile = profile.copy(ratings=profile.ratings.toMutableMap().apply { if(rating==0) remove(id) else this[id]=rating })
            current = current.copy(personal_revision=current.personal_revision+1)
            return CatalogActionResult(changed=true)
        }
        override suspend fun resetPersonal(): CatalogActionResult {
            events += "reset"
            profile = PersonalProfile()
            current = current.copy(personal_revision=current.personal_revision+1)
            return CatalogActionResult(changed=true)
        }
        override suspend fun selectDevice(id: String) {}
        override suspend fun play(source: String) { playCalls++ }
        override suspend fun command(name: String, value: Double?) {}
        override suspend fun outputVolume(id: String, value: Double) {}
        override suspend fun memberVolume(id: String, value: Double) {}
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) {}
        override suspend fun dismissMembership() {}
        override suspend fun importChannel(url: String?) {}
        override fun close() {}
    }

    @Test fun ratingsPersistRecreateAndResetWithoutPlayback() = runBlocking {
        val backend = Backend()
        val store = PlayerStore(backend, this)
        store.rateSong("mix", 1)
        delay(500)
        assertEquals(1, store.state.value.profile?.ratings?.get("mix"))
        assertTrue(store.state.value.discovery?.personalized == true)
        assertEquals(0, backend.playCalls)
        assertNull(store.state.value.catalogAction)
        val recreated = PlayerStore(backend, this)
        val job = recreated.start()
        try {
            delay(500)
            assertEquals(1, recreated.state.value.profile?.ratings?.get("mix"))
            assertTrue(recreated.state.value.discovery?.personalized == true)
            store.rateSong("mix", -1)
            delay(500)
            assertEquals(-1, store.state.value.profile?.ratings?.get("mix"))
            store.resetPersonal()
            delay(500)
            assertTrue(store.state.value.profile?.ratings.isNullOrEmpty())
            assertTrue(store.state.value.profile?.listening.isNullOrEmpty())
            assertFalse(store.state.value.discovery?.personalized == true)
            assertEquals(0, backend.playCalls)
        } finally {
            job.cancel()
        }
    }

    @Test fun failedRatingKeepsErrorAndDoesNotPlay() = runBlocking {
        val backend = Backend().apply { fail = true }
        val store = PlayerStore(backend, this)
        store.rateSong("mix", 1)
        delay(500)
        assertEquals(true, store.state.value.catalogAction?.failed)
        assertNotNull(store.state.value.catalogError)
        assertTrue(store.state.value.profile?.ratings.isNullOrEmpty())
        assertEquals(0, backend.playCalls)
    }

    @Test fun refreshHappensAfterPersistentRatingConfirmation() = runBlocking {
        val backend = Backend()
        val store = PlayerStore(backend, this)
        store.rateSong("mix", 1)
        delay(500)
        val rateIndex = backend.events.indexOf("rate")
        val catalogIndex = backend.events.indexOf("catalog")
        assertTrue(rateIndex >= 0 && catalogIndex > rateIndex)
        assertEquals(0, backend.playCalls)
    }
}
