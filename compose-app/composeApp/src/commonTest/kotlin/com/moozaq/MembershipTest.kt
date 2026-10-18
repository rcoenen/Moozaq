package com.moozaq

import kotlin.test.*
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

class MembershipTest {
    private val kitchen = Device("kitchen", "Kitchen", cast_type="audio")
    private val bedroom = Device("bedroom", "Bedroom", cast_type="audio")
    private val initial = Snapshot(
        target="kitchen", receiver=Receiver(connected=true, state="PLAYING"),
        membership=PlaybackMembership(members=listOf("kitchen"), available=true, revision=4, token="session"),
    )

    @Test fun remoteSelectionIsNotMembership() {
        val ui = PlayerUiState(snapshot=initial.copy(target="bedroom"))
        assertEquals(setOf("kitchen"), requestedMembers(ui))
    }

    @Test fun lastSpeakerCanStopButUnsupportedDevicesCannotJoin() {
        val ui=PlayerUiState(snapshot=initial)
        assertTrue(canToggleMembership(ui, kitchen))
        assertTrue(canToggleMembership(ui, bedroom))
        assertFalse(canToggleMembership(ui, Device("tv", "TV", cast_type="cast")))
        assertFalse(canToggleMembership(ui, Device("group", "Group", cast_type="group")))
        assertFalse(canToggleMembership(ui.copy(snapshot=initial.copy(membership=PlaybackMembership())), bedroom))
    }

    @Test fun pendingIntentDoesNotSnapBackOnOldMembership() {
        val request=PendingMembership(setOf("kitchen", "bedroom"),4)
        val ui=PlayerUiState(snapshot=initial, membership=request)
        assertEquals(setOf("kitchen", "bedroom"), requestedMembers(ui))
        assertEquals(request,reconcileMembership(request,initial))
        assertFalse(canToggleMembership(ui, bedroom))
    }

    @Test fun confirmationRequiresFreshExactSetAndCompletedBackendOperation() {
        val request=PendingMembership(setOf("kitchen", "bedroom"),4)
        val matches=initial.copy(membership=initial.membership.copy(members=listOf("bedroom", "kitchen")))
        assertNotNull(reconcileMembership(request,matches))
        assertNotNull(reconcileMembership(request,matches.copy(membership=matches.membership.copy(revision=5,pending=listOf("kitchen", "bedroom")))))
        assertNull(reconcileMembership(request,matches.copy(target="temporary-group",membership=matches.membership.copy(revision=5))))
    }

    @Test fun failureClearsTheUnfinishedCheckAndKeepsTheError() {
        val request=PendingMembership(setOf("kitchen", "bedroom"),4)
        val failed=initial.copy(target="temporary-group",membership=initial.membership.copy(error="Speaker selection was not confirmed.", pending=null))
        assertNull(reconcileMembership(request, failed))
        val ui=PlayerUiState(snapshot=failed)
        assertEquals(setOf("kitchen"), requestedMembers(ui))
        assertEquals("Speaker selection was not confirmed.", membershipError(ui))
        assertFalse(membershipPending(ui))
        val inflight=initial.copy(membership=initial.membership.copy(error="Speaker selection was not confirmed.", pending=listOf("kitchen", "bedroom")))
        assertEquals(request.members, reconcileMembership(request, inflight)!!.members)
        val expired=reconcileMembership(request.copy(started=TimeSource.Monotonic.markNow()-46.seconds),initial)!!
        assertNotNull(expired.error)
        assertEquals(request.members,expired.members)
    }

    @Test fun backendPendingSurvivesUiRecreation() {
        val ui=PlayerUiState(snapshot=initial.copy(membership=initial.membership.copy(pending=listOf("kitchen", "bedroom"),error="Not confirmed")))
        assertEquals(setOf("kitchen", "bedroom"),requestedMembers(ui))
        assertTrue(membershipPending(ui))
        assertEquals("Not confirmed",membershipError(ui))
    }

    @Test fun confirmedMemberExcludedByPendingRequestIsLeaving() {
        assertTrue(leavingMember(pending=true,confirmed=true,requested=false))
        assertFalse(leavingMember(pending=true,confirmed=false,requested=true))
        assertFalse(leavingMember(pending=true,confirmed=true,requested=true))
        assertFalse(leavingMember(pending=false,confirmed=true,requested=false))
    }
}
