package com.moozaq

import kotlin.test.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

class VolumeSyncTest {
    private fun snapshot(volume: Double, revision: Long) = Snapshot(group=GroupInfo(members=listOf(GroupMember("room", "Room", volume=volume, volume_revision=revision))))
    @Test fun staleReadbackDoesNotMoveReleasedSlider() {
        val pending=mapOf("room" to PendingVolume(.7, 3))
        assertEquals(.7, reconcileVolumes(pending,snapshot(.2,3))["room"]?.value)
        assertEquals(.7, reconcileVolumes(pending,snapshot(.7,3))["room"]?.value)
    }
    @Test fun onlyConfirmedMatchingValueReleasesSlider() {
        val pending=mapOf("room" to PendingVolume(.7, 3))
        assertTrue(reconcileVolumes(pending,snapshot(.7,4)).isEmpty())
        assertFalse(reconcileVolumes(pending,snapshot(.2,4)).isEmpty())
    }
    @Test fun timeoutRetainsRequestedPositionAndMarksFailure() {
        val pending=mapOf("room" to PendingVolume(.7,3,TimeSource.Monotonic.markNow()-11.seconds))
        val result=reconcileVolumes(pending,snapshot(.2,3))["room"]!!
        assertTrue(result.failed)
        assertEquals(.7,result.value)
    }
    @Test fun mainSliderWaitsForConfirmationAndRetainsFailurePosition() {
        val request=PendingVolume(.7,3)
        assertEquals(request, reconcileMainVolume(request, Snapshot(receiver=Receiver(volume=20,volume_revision=3))))
        assertEquals(request, reconcileMainVolume(request, Snapshot(receiver=Receiver(volume=70,volume_revision=3))))
        assertNull(reconcileMainVolume(request, Snapshot(receiver=Receiver(volume=70,volume_revision=4))))
        val failed=reconcileMainVolume(request.copy(started=TimeSource.Monotonic.markNow()-11.seconds), Snapshot())!!
        assertTrue(failed.failed)
        assertEquals(.7,failed.value)
    }
}
