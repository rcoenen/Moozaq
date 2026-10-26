package com.moozaq

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class SpeakerDraftTest {
    private class FakeBackend(var current: Snapshot) : PlayerBackend {
        val calls = mutableListOf<String>()
        var confirmAfter = 1
        var membershipReady = true
        var failPlayMessage: String? = null
        var playSourceOverride: String? = null
        var advanceRevision = true
        var unavailableOnPlay: String? = null
        var targetAfterPlay: String? = null
        var failSet: Exception? = null
        var playApplied = false
        var holdNextSnapshot: CompletableDeferred<Unit>? = null
        var snapshotHeld: CompletableDeferred<Unit>? = null
        private var pendingSource: String? = null
        private var remaining = 0

        override suspend fun snapshot(): Snapshot {
            val gate = holdNextSnapshot
            if (gate != null) {
                holdNextSnapshot = null
                snapshotHeld?.complete(Unit)
                gate.await()
            }
            val source = pendingSource
            if (source != null && remaining > 0) {
                remaining--
                if (remaining == 0) applyPlay(source)
            }
            return current
        }

        private fun applyPlay(source: String) {
            pendingSource = null
            playApplied = true
            val failure = failPlayMessage
            if (failure != null) {
                current = current.copy(busy = false, message = failure)
                return
            }
            val target = targetAfterPlay ?: current.target
            current = current.copy(
                target = target,
                busy = false,
                message = "Starting selected mix completed.",
                devices = current.devices.map { if (it.id == unavailableOnPlay) it.copy(available = false) else it },
                receiver = current.receiver.copy(
                    connected = true,
                    state = "PLAYING",
                    source = playSourceOverride ?: source,
                    transport_revision = current.receiver.transport_revision + if (advanceRevision) 1 else 0,
                ),
                membership = current.membership.copy(
                    members = listOfNotNull(target),
                    available = membershipReady,
                    token = if (membershipReady) "fresh-token" else null,
                    pending = null,
                ),
            )
        }

        override suspend fun selectDevice(id: String) {
            calls += "select:$id"
            current = current.copy(
                target = id,
                connecting = false,
                connection_error = null,
                receiver = Receiver(connected = true, device = id, state = "IDLE", transport_revision = current.receiver.transport_revision),
                membership = PlaybackMembership(),
            )
        }

        override suspend fun play(source: String) {
            calls += "play:$source@${current.target}"
            playApplied = false
            pendingSource = source
            remaining = confirmAfter
            current = current.copy(busy = true, message = "Starting selected mix…")
        }

        override suspend fun command(name: String, value: Double?) {
            calls += "command:$name"
            if (name == "stop") {
                pendingSource = null
                current = current.copy(
                    busy = false,
                    message = "Stopped",
                    receiver = current.receiver.copy(connected = true, state = "IDLE", source = null, transport_revision = current.receiver.transport_revision + 1),
                    membership = PlaybackMembership(),
                )
            }
        }

        override suspend fun setPlaybackDevices(devices: List<String>, token: String) {
            calls += "members:${devices.sorted().joinToString(",")}"
            failSet?.let { throw it }
            current = current.copy(membership = current.membership.copy(
                members = devices,
                pending = null,
                error = null,
                revision = current.membership.revision + 1,
            ))
        }

        override suspend fun dismissMembership() {
            current = current.copy(membership = current.membership.copy(error = null, pending = null))
        }
        override suspend fun outputVolume(id: String, value: Double) { calls += "output-volume" }
        override suspend fun memberVolume(id: String, value: Double) { calls += "member-volume" }
        override suspend fun catalog() = CatalogData()
        override suspend fun importChannel(url: String?) {}
        override fun close() {}
    }

    private val mix = catalog.first()
    private val devices = listOf(
        Device("bedroom", "Bedroom speaker", cast_type = "audio"),
        Device("fridge", "Fridge speaker", cast_type = "audio"),
        Device("kitchen", "Kitchen speaker", cast_type = "audio"),
    )

    private fun initial(target: String? = "bedroom") = Snapshot(
        target = target,
        devices = devices,
        receiver = if (target == null) Receiver() else Receiver(connected = true, device = target, state = "IDLE"),
    )

    private suspend fun CoroutineScope.seed(backend: FakeBackend): PlayerStore {
        val store = PlayerStore(backend, this)
        store.draftJoinTimeoutMs = 50
        val polling = store.start()
        withTimeout(3000) { store.state.first { it.snapshot == backend.current } }
        polling.cancelAndJoin()
        return store
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(3000) {
        while (!condition()) delay(5)
    }

    private fun toggle(store: PlayerStore, id: String) {
        store.toggleSpeakerDraft(store.state.value.snapshot.devices.first { it.id == id })
    }

    @Test fun idleDraftBuildsSetWithoutCommandsAndEmptyDoesNotFallback() = runBlocking {
        val backend = FakeBackend(initial(target = null))
        val store = seed(backend)
        toggle(store, "bedroom")
        toggle(store, "fridge")
        assertEquals(setOf("bedroom", "fridge"), speakerDraftMembers(store.state.value))
        assertTrue(backend.calls.isEmpty())
        toggle(store, "bedroom")
        toggle(store, "fridge")
        assertTrue(speakerDraftMembers(store.state.value).isEmpty())
        assertFalse(canRequestDraftPlay(store.state.value, mix))
        store.playDraft(mix)
        assertEquals("Choose at least one speaker", store.state.value.error)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun firstRunDraftLoadsOnceOnFirstSpeakerThenJoinsFullSet() = runBlocking {
        val backend = FakeBackend(initial(target = null))
        backend.confirmAfter = 2
        val store = seed(backend)
        toggle(store, "bedroom")
        toggle(store, "fridge")
        toggle(store, "kitchen")
        store.playDraft(mix)
        await { backend.calls.any { it.startsWith("members:") } || store.state.value.error != null }
        assertEquals(listOf("select:bedroom", "play:${mix.url}@bedroom", "members:bedroom,fridge,kitchen"), backend.calls)
        await { store.state.value.speakerDraft == null && store.state.value.membership == null }
        assertEquals(setOf("bedroom", "fridge", "kitchen"), store.state.value.snapshot.membership.members.toSet())
    }

    @Test fun currentDraftedSpeakerIsTheCoordinator() = runBlocking {
        val backend = FakeBackend(initial(target = "fridge"))
        val store = seed(backend)
        toggle(store, "bedroom")
        store.playDraft(mix)
        await { backend.calls.any { it.startsWith("members:") } || store.state.value.error != null }
        assertEquals(listOf("play:${mix.url}@fridge", "members:bedroom,fridge"), backend.calls)
    }

    @Test fun singleDraftNeedsNoMembershipCommand() = runBlocking {
        val backend = FakeBackend(initial(target = null))
        val store = seed(backend)
        toggle(store, "bedroom")
        store.playDraft(mix)
        await { store.state.value.speakerDraft == null || store.state.value.error != null }
        assertEquals(listOf("select:bedroom", "play:${mix.url}@bedroom"), backend.calls)
        assertNull(store.state.value.error)
    }

    @Test fun stopBeforeSetAllowsANewDraftWithoutLateWrites() = runBlocking {
        val backend = FakeBackend(initial())
        backend.membershipReady = false
        val store = seed(backend)
        toggle(store, "fridge")
        store.playDraft(mix)
        await { store.state.value.speakerDraft?.playbackConfirmed == true }
        val gate = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        backend.holdNextSnapshot = gate
        backend.snapshotHeld = held
        withTimeout(3000) { held.await() }
        store.command("stop")
        await { backend.calls.contains("command:stop") && store.state.value.snapshot.receiver.state == "IDLE" }
        assertFalse(backend.calls.any { it.startsWith("members:") })
        toggle(store, "kitchen")
        val replacement = store.state.value.speakerDraft
        gate.complete(Unit)
        delay(100)
        assertEquals(setOf("bedroom", "kitchen"), replacement?.members)
        assertEquals(replacement, store.state.value.speakerDraft)
        assertNull(store.state.value.error)
        assertFalse(backend.calls.any { it.startsWith("members:") })
    }

    @Test fun wrongSourceOrUnchangedRevisionNeverSendsSet() = runBlocking {
        for (wrongSource in listOf(true, false)) {
            val backend = FakeBackend(initial())
            if (wrongSource) backend.playSourceOverride = "wrong-source" else backend.advanceRevision = false
            val store = seed(backend)
            toggle(store, "fridge")
            store.playDraft(mix)
            await { backend.playApplied }
            store.command("stop")
            await { backend.calls.contains("command:stop") }
            assertFalse(backend.calls.any { it.startsWith("members:") })
            assertEquals(1, backend.calls.count { it.startsWith("play:") })
        }
    }

    @Test fun changedTargetFailsBeforeSet() = runBlocking {
        val backend = FakeBackend(initial())
        backend.targetAfterPlay = "kitchen"
        val store = seed(backend)
        toggle(store, "fridge")
        store.playDraft(mix)
        await { store.state.value.speakerDraft?.error != null }
        assertFalse(backend.calls.any { it.startsWith("members:") })
        assertEquals(1, backend.calls.count { it.startsWith("play:") })
    }

    @Test fun unavailableMemberFailsAfterPlaybackWithoutSet() = runBlocking {
        val backend = FakeBackend(initial())
        backend.unavailableOnPlay = "fridge"
        val store = seed(backend)
        toggle(store, "fridge")
        store.playDraft(mix)
        await { store.state.value.speakerDraft?.error != null }
        val draft = store.state.value.speakerDraft!!
        assertTrue(draft.playbackConfirmed)
        assertTrue(draft.error!!.contains("The mix started on the starting speaker"))
        assertFalse(backend.calls.any { it.startsWith("members:") })
        assertEquals(1, backend.calls.count { it.startsWith("play:") })
    }

    @Test fun sourceFailureRetainsRetryableDraft() = runBlocking {
        val backend = FakeBackend(initial())
        backend.failPlayMessage = "Failed: resolver failed"
        val store = seed(backend)
        toggle(store, "fridge")
        store.playDraft(mix)
        await { store.state.value.speakerDraft?.error != null }
        assertEquals("resolver failed", store.state.value.speakerDraft?.error)
        assertFalse(store.state.value.speakerDraft!!.playbackConfirmed)
        assertTrue(canRequestDraftPlay(store.state.value, mix))
        assertFalse(backend.calls.any { it.startsWith("members:") })
    }

    @Test fun groupingTimeoutAfterPlaybackDoesNotLoadAgain() = runBlocking {
        val backend = FakeBackend(initial())
        backend.membershipReady = false
        val store = seed(backend)
        toggle(store, "fridge")
        store.playDraft(mix)
        await { store.state.value.speakerDraft?.error != null }
        assertTrue(store.state.value.speakerDraft!!.playbackConfirmed)
        assertFalse(canRequestDraftPlay(store.state.value, mix))
        store.playDraft(mix)
        assertEquals(1, backend.calls.count { it.startsWith("play:") })
        assertFalse(backend.calls.any { it.startsWith("members:") })
    }

    @Test fun setFailureNeverAutomaticallyRetries() = runBlocking {
        val backend = FakeBackend(initial())
        backend.failSet = RuntimeException("SET rejected")
        val store = seed(backend)
        toggle(store, "fridge")
        store.playDraft(mix)
        await { store.state.value.membership?.error != null }
        assertNull(store.state.value.speakerDraft)
        assertEquals("SET rejected", store.state.value.membership?.error)
        store.playDraft(mix)
        store.play(mix)
        assertEquals(1, backend.calls.count { it.startsWith("play:") })
        assertEquals(1, backend.calls.count { it.startsWith("members:") })
    }

    @Test fun ordinaryPlayIgnoresDraftAndGroupMoveClearsIt() = runBlocking {
        val backend = FakeBackend(initial(target = "group").copy(
            devices = listOf(Device("group", "All speakers", cast_type = "group")) + devices,
            receiver = Receiver(connected = true, device = "group", state = "IDLE"),
        ))
        val store = seed(backend)
        toggle(store, "bedroom")
        store.play(mix)
        await { backend.playApplied && store.state.value.snapshot.receiver.state == "PLAYING" }
        assertEquals(listOf("play:${mix.url}@group"), backend.calls)
        assertEquals(setOf("bedroom"), store.state.value.speakerDraft?.members)
        store.movePlaybackTo("group", mix)
        assertNull(store.state.value.speakerDraft)
        assertEquals(1, backend.calls.count { it.startsWith("play:") })
    }
}
