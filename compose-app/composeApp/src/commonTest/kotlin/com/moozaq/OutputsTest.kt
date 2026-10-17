package com.moozaq
import kotlin.test.*

class OutputsTest {
    @Test fun idleSpeakerSelectionReplacesGroupDestination() {
        val group=Device("all", "All speakers", cast_type="group")
        val office=Device("office", "Office", cast_type="audio")
        val idle=PlayerUiState(snapshot=Snapshot(target="all",receiver=Receiver(connected=true,state="IDLE")))
        assertTrue(usesDestinationCheckbox(idle,office))
        assertTrue(canSelectDestination(idle,office))
        assertTrue(destinationChecked(idle,group.id))
        val pending=idle.copy(destination=PendingDestination(office.id))
        assertTrue(usesDestinationCheckbox(pending,office))
        assertTrue(destinationChecked(pending,office.id))
        assertFalse(destinationChecked(pending,group.id))
        val confirmed=idle.copy(snapshot=idle.snapshot.copy(target=office.id))
        assertTrue(destinationChecked(confirmed,office.id))
        assertFalse(destinationChecked(confirmed,group.id))
        assertTrue(canSelectDestination(confirmed,group))
    }

    @Test fun activeSpeakerCheckboxesRemainMembershipControls() {
        val speaker=Device("office", "Office", cast_type="audio")
        for(state in listOf("PLAYING", "PAUSED", "BUFFERING")) {
            val ui=PlayerUiState(snapshot=Snapshot(target="all",receiver=Receiver(connected=true,state=state)))
            assertFalse(usesDestinationCheckbox(ui,speaker))
            assertTrue(usesDestinationCheckbox(ui,Device("all","All speakers",cast_type="group")))
        }
        val pending=PlayerUiState(membership=PendingMembership(setOf("office"),1))
        assertFalse(usesDestinationCheckbox(pending,speaker))
    }

    @Test fun idleGroupCanBeSelectedWithoutAnActiveMembershipSession() {
        val group=Device("all", "All speakers", cast_type="group")
        val ui=PlayerUiState(snapshot=Snapshot(target="office", receiver=Receiver(connected=true,state="IDLE")))
        assertTrue(canSelectDestination(ui,group))
        assertFalse(canSelectDestination(ui,group.copy(available=false)))
        val requested=ui.copy(destination=PendingDestination("all"))
        assertTrue(destinationChecked(requested,"all"))
        assertFalse(destinationChecked(requested,"office"))
        assertFalse(canSelectDestination(requested,group))
    }

    @Test fun groupSelectionRequiresConfirmedConnectionAndRetainsFailedIntent() {
        val request=PendingDestination("all")
        val before=Snapshot(target="office",receiver=Receiver(connected=true,state="PLAYING"))
        assertEquals(request,reconcileDestination(request,before))
        val joining=before.copy(target="all",connecting=true)
        assertEquals(request,reconcileDestination(request,joining))
        assertEquals(request,reconcileDestination(request,joining.copy(connecting=false,receiver=Receiver())))
        assertNull(reconcileDestination(request,joining.copy(connecting=false,receiver=Receiver(connected=true,state="IDLE"))))
        val failed=PlayerUiState(snapshot=before,destination=request.copy(failed=true))
        assertTrue(destinationChecked(failed,"all"))
        assertTrue(canSelectDestination(failed,Device("all","All speakers",cast_type="group")))
    }

    @Test fun retainedUnavailableSpeakerDoesNotHideOtherVolumeControls() {
        val s=Snapshot(target="office",connecting=true,devices=listOf(Device("office","Office",available=false),Device("kitchen","Kitchen")))
        assertTrue(previousOutputUnavailable(s))
        assertTrue(canAdjustOutputVolume(s,OutputState("kitchen",connected=true,volume=.2),false,null))
        assertFalse(canToggleMembership(PlayerUiState(snapshot=s),s.devices.first()))
    }
    @Test fun expiredGroupDoesNotBlockIndependentSpeakerVolume() {
        val output=OutputState("kitchen",connected=true,volume=.2)
        val snapshot=Snapshot(target="expired-group",devices=listOf(Device("kitchen","Kitchen")))
        assertTrue(previousOutputUnavailable(snapshot))
        assertTrue(canAdjustOutputVolume(snapshot,output,false,null))
        assertFalse(canAdjustOutputVolume(snapshot,output,true,null))
        assertFalse(canAdjustOutputVolume(snapshot,output.copy(connected=false),false,null))
        assertFalse(canAdjustOutputVolume(snapshot.copy(busy=true),output,false,null))
        assertFalse(previousOutputUnavailable(snapshot.copy(devices=emptyList())))
    }

    @Test fun distinguishesTwoPlayingSessionsAndMute() {
        val s=Snapshot(target="group",membership=PlaybackMembership(members=listOf("kitchen","bedroom")))
        assertEquals("Playing this music",outputActivity(OutputState("kitchen",true,"PLAYING",volume=.2,session_id="group"),s))
        assertEquals("Playing other music",outputActivity(OutputState("office",true,"PLAYING",volume=.2,session_id="office"),s))
        assertEquals("Playing · silent",outputActivity(OutputState("kitchen",true,"PLAYING",volume=0.0,session_id="group"),s))
        assertEquals("Unavailable",outputActivity(OutputState("bedroom"),s))
    }
    @Test fun reportedMatchingVolumeCannotClearAnUnconfirmedIntent() {
        val intent=mapOf("kitchen" to PendingVolume(.2,4))
        val old=OutputState("kitchen",connected=true,volume=.2,volume_revision=4)
        assertEquals(intent,reconcileOutputVolumes(intent,Snapshot(outputs=listOf(old))))
        assertTrue(reconcileOutputVolumes(intent,Snapshot(outputs=listOf(old.copy(volume_revision=5)))).isEmpty())
        assertEquals(intent,reconcileOutputVolumes(intent,Snapshot(outputs=listOf(old.copy(volume_revision=5,connected=false)))))
    }
}
