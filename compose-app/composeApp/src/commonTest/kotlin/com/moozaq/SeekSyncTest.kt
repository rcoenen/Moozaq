package com.moozaq
import kotlin.test.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds
class SeekSyncTest {
    @Test fun holdsUntilConfirmed() {
        val request=PendingSeek(120.0,3)
        assertEquals(request,reconcileSeek(request,Snapshot(receiver=Receiver(position=10.0,seek_revision=3))))
        assertEquals(request,reconcileSeek(request,Snapshot(receiver=Receiver(position=120.0,seek_revision=3))))
        assertNull(reconcileSeek(request,Snapshot(receiver=Receiver(position=121.0,seek_revision=4))))
    }
    @Test fun timeoutRetainsRequestedPosition() {
        val request=PendingSeek(120.0,3,TimeSource.Monotonic.markNow()-11.seconds)
        val result=reconcileSeek(request,Snapshot())!!
        assertTrue(result.failed)
        assertEquals(120.0,result.position)
    }
}
