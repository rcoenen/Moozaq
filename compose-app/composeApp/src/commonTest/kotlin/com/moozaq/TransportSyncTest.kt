package com.moozaq
import kotlin.test.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
class TransportSyncTest {
    @Test fun retainsPendingUntilConfirmed() {
        val request=PendingTransport("PAUSED","PLAYING",3)
        assertEquals(request,reconcileTransport(request,Snapshot(busy=true,receiver=Receiver(state="PAUSED",transport_revision=3))))
        assertNull(reconcileTransport(request,Snapshot(receiver=Receiver(state="PAUSED",transport_revision=4))))
    }
    @Test fun failedCommandIsExplicitWithoutAutomaticRetry() {
        val request=PendingTransport("PLAYING","PAUSED",3,started=TimeSource.Monotonic.markNow()-2.seconds)
        assertTrue(reconcileTransport(request,Snapshot())!!.failed)
        assertFalse(reconcileTransport(request,Snapshot(busy=true))!!.failed)
    }
}
