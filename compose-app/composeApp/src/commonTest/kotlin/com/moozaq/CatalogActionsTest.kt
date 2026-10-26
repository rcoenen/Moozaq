package com.moozaq

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

class CatalogActionsTest {
    private class Backend : PlayerBackend {
        var master = CatalogData()
        var library = CatalogData()
        var current = Snapshot(catalog_curation=true, catalog_revision=1, master_revision=1, app_revision=1, generated_revision=1)
        var preview = VideoPreview("WlPiQGR18jE", "VHS Dreams", "Silent Depths", 10974.0,
            channel_url="https://www.youtube.com/@Silent-Depths-b9j", webpage_url="https://www.youtube.com/watch?v=WlPiQGR18jE")
        var fail = false
        var playCalls = 0
        var snapshotCalls = 0
        var catalogCalls = 0
        var refreshing = false

        override suspend fun snapshot(): Snapshot {
            snapshotCalls++
            if(refreshing && snapshotCalls >= 2) {
                refreshing = false
                current = current.copy(importing=false)
            }
            return current
        }
        override suspend fun catalog(): CatalogData { catalogCalls++; return library }
        override suspend fun masterCatalog() = master
        override suspend fun importChannel(url: String?) {
            current = current.copy(importing=true)
            refreshing = true
            snapshotCalls = 0
        }
        override suspend fun previewVideo(source: String): VideoPreview {
            if(fail) error("preview failed")
            return preview
        }
        override suspend fun importVideo(source: String, recommend: Boolean): CatalogActionResult {
            if(fail) error("import failed")
            val eligible = (preview.duration ?: 0.0) >= 3600
            val recommended = recommend && eligible
            val song = Mix(preview.id, preview.title, preview.creator, duration=preview.duration,
                channel_id=null, recommended_at=if(recommended) 100 else null)
            master = master.copy(mixes=master.mixes.filterNot { it.id == song.id } + song)
            return CatalogActionResult(id=song.id, eligible=eligible, recommended=recommended)
        }
        override suspend fun followChannel(url: String, refresh: Boolean): CatalogActionResult {
            if(fail) error("follow failed")
            val channel = CatalogChannel("Channel", url, publication_mode="review", aliases=listOf(url))
            master = master.copy(channels=master.channels.filterNot { it.url == url } + channel)
            return CatalogActionResult(created=true, url=url, import_count=if(refresh) 1 else null)
        }
        override suspend fun curate(action: String, fields: Map<String, JsonElement>): CatalogActionResult {
            if(fail) error("curate failed")
            val id = fields["song"]?.jsonPrimitive?.content
            master = master.copy(mixes=master.mixes.map { mix ->
                if(mix.id != id) mix else when(action) {
                    "recommend" -> mix.copy(recommended_at=100, hidden=false, review_status="approved")
                    "unrecommend" -> mix.copy(recommended_at=null)
                    "hide" -> mix.copy(hidden=true)
                    "restore" -> mix.copy(hidden=false)
                    "category" -> mix.copy(category_override=fields["category"]?.jsonPrimitive?.content)
                    "review" -> mix.copy(review_status=fields["status"]?.jsonPrimitive?.content ?: "approved")
                    else -> mix
                }
            })
            return CatalogActionResult(changed=true)
        }
        override suspend fun rebuildCatalog(): Snapshot {
            current = current.copy(app_revision=current.master_revision, generated_revision=current.master_revision,
                catalog_dirty=false, catalog_building=false, catalog_revision=current.catalog_revision+1)
            library = master.copy(mixes=master.mixes.filter { (it.duration ?: 0.0) >= 3600 && !it.hidden && it.review_status == "approved" })
            return current
        }
        override suspend fun publishCatalogRelease(confirmed: Boolean, expectedRevision: Int): kotlinx.serialization.json.JsonObject {
            if(fail) error("release failed")
            if(!confirmed || expectedRevision != current.app_revision) error("The confirmed release revision does not match.")
            current = current.copy(latest_revision=expectedRevision)
            return kotlinx.serialization.json.JsonObject(mapOf(
                "revision" to kotlinx.serialization.json.JsonPrimitive(expectedRevision),
                "tag" to kotlinx.serialization.json.JsonPrimitive("catalog-v$expectedRevision"),
                "assets" to kotlinx.serialization.json.JsonArray(listOf(
                    kotlinx.serialization.json.JsonPrimitive("app-catalog.db"),
                    kotlinx.serialization.json.JsonPrimitive("catalog-manifest.json"),
                )),
            ))
        }
        override suspend fun selectDevice(id: String) {}
        override suspend fun play(source: String) { playCalls++ }
        override suspend fun command(name: String, value: Double?) {}
        override suspend fun outputVolume(id: String, value: Double) {}
        override suspend fun memberVolume(id: String, value: Double) {}
        override suspend fun setPlaybackDevices(devices: List<String>, token: String) {}
        override suspend fun dismissMembership() {}
        override fun close() {}
    }

    @Test fun previewAndRecommendAreConfirmedWithoutPlayback() = runBlocking {
        val backend = Backend()
        val store = PlayerStore(backend, this)
        store.previewVideo("WlPiQGR18jE")
        delay(200)
        assertEquals("VHS Dreams", stateValue(store).videoPreview?.title)
        assertNull(stateValue(store).catalogAction)
        store.importVideo("WlPiQGR18jE", true)
        delay(200)
        assertNull(stateValue(store).videoPreview)
        assertNull(stateValue(store).catalogError)
        assertEquals(100, stateValue(store).masterLibrary?.mixes?.single()?.recommended_at)
        assertEquals(0, backend.playCalls)
    }

    @Test fun failedCurationKeepsErrorAndPendingIntent() = runBlocking {
        val backend = Backend().apply { fail = true }
        val store = PlayerStore(backend, this)
        store.curate("recommend", song="abcdefghijk")
        delay(200)
        assertEquals(true, stateValue(store).catalogAction?.failed)
        assertNotNull(stateValue(store).catalogError)
        assertEquals(0, backend.playCalls)
    }

    @Test fun rebuildUsesFreshRevisionConfirmation() = runBlocking {
        val backend = Backend().apply {
            master = CatalogData(mixes=listOf(Mix("abcdefghijk", "Mix", "Creator", duration=7200.0)))
            current = current.copy(catalog_dirty=true)
        }
        val store = PlayerStore(backend, this)
        store.rebuildCatalog()
        delay(200)
        assertNull(stateValue(store).catalogAction)
        assertEquals(backend.current.master_revision, stateValue(store).snapshot.app_revision)
        assertEquals(1, stateValue(store).library.mixes.size)
    }

    @Test fun refreshWaitsUntilImportingClears() = runBlocking {
        val backend = Backend()
        val store = PlayerStore(backend, this)
        store.importChannel()
        delay(200)
        assertNull(stateValue(store).catalogAction)
        assertFalse(stateValue(store).snapshot.importing)
        assertTrue(backend.snapshotCalls >= 2)
        assertTrue(backend.catalogCalls > 0)
    }

    @Test fun shortVideoStaysInMasterWithHonestWarning() = runBlocking {
        val backend = Backend().apply { preview = preview.copy(duration=120.0) }
        val store = PlayerStore(backend, this)
        store.importVideo("WlPiQGR18jE", true)
        delay(200)
        assertEquals(1, stateValue(store).masterLibrary?.mixes?.size)
        assertContains(stateValue(store).catalogError.orEmpty(), "no confirmed one-hour duration")
        assertEquals(0, backend.playCalls)
    }

    @Test fun publishConfirmsExactRevisionAndAllowsRetryAfterFailure() = runBlocking {
        val backend = Backend().apply { fail = true }
        val store = PlayerStore(backend, this)
        store.publishCatalogRelease(revision=1)
        delay(200)
        assertEquals(true, stateValue(store).catalogAction?.failed)
        assertEquals("release failed", stateValue(store).catalogError)
        backend.fail = false
        store.publishCatalogRelease(revision=1)
        delay(200)
        assertNull(stateValue(store).catalogAction)
        assertNull(stateValue(store).catalogError)
        assertEquals(1, stateValue(store).snapshot.latest_revision)
        assertEquals(0, backend.playCalls)
    }

    private fun stateValue(store: PlayerStore) = store.state.value
}
