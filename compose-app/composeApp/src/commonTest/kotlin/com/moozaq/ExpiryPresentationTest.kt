package com.moozaq

import kotlin.test.*

class ExpiryPresentationTest {
    private fun snapshot(receiver: Receiver) = Snapshot(receiver = receiver, target = "bedroom")
    private val source = "https://www.youtube.com/watch?v=7vy0H3ERRiA"

    @Test fun expiredFailureOffersExplicitResumeFromPosition() {
        val s = snapshot(Receiver(connected = true, state = "EXPIRED", source_expired = true, source = source, position = 4200.0))
        assertTrue(canResume(s))
        assertFalse(canPause(s))
        assertEquals("Stream link expired · Resume reloads from 1:10:00", playbackLabel(s))
    }

    @Test fun expiredFailureWithoutKnownMixCannotResume() {
        assertFalse(canResume(snapshot(Receiver(connected = true, state = "EXPIRED", source_expired = true))))
    }

    @Test fun pausedOnExpiredLinkExplainsRefresh() {
        val s = snapshot(Receiver(connected = true, state = "PAUSED", source_expired = true, source = source))
        assertTrue(canResume(s))
        assertEquals("Paused · stream link expired, Resume refreshes it", playbackLabel(s))
    }

    @Test fun liveLinkKeepsOrdinaryLabels() {
        assertEquals("Playing", playbackLabel(snapshot(Receiver(connected = true, state = "PLAYING", expires_in = 3600.0))))
    }

    @Test fun expiredStateNeverResumesWhileBusyOrSynchronizing() {
        val r = Receiver(connected = true, state = "EXPIRED", source_expired = true, source = source)
        assertFalse(canResume(snapshot(r).copy(busy = true)))
        assertFalse(canResume(snapshot(r).copy(connecting = true)))
    }

    @Test fun olderBridgeSnapshotsDecodeWithoutExpiryFields() {
        val r = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(Receiver.serializer(), """{"connected":true,"state":"PAUSED"}""")
        assertFalse(r.source_expired)
        assertNull(r.expires_in)
        assertFalse(r.refresh_needed)
    }
}
