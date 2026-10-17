package com.moozaq

import kotlin.test.*

class HandoffPresentationTest {
    private val last=Receiver(connected=true,state="PLAYING",title="Our mix",source="source",position=120.0,duration=7200.0,volume=20)

    @Test fun speakerHandoffRetainsTrackAndFrozenTimelineWithoutEnablingControls() {
        val ui=PlayerUiState(lastPlayed=last,snapshot=Snapshot(target="group",connecting=true,
            receiver=Receiver(state="UNKNOWN",recovery="Reconnecting…")))
        val display=presentedReceiver(ui)
        assertEquals(last.title,display.title)
        assertEquals(last.source,display.source)
        assertEquals(last.position,display.position)
        assertEquals(last.duration,display.duration)
        assertFalse(display.connected)
        assertEquals("UNKNOWN",display.state)
        assertFalse(canResume(ui.snapshot))
        assertFalse(canPause(ui.snapshot))
    }

    @Test fun idleAndExplicitDifferentDestinationDoNotBorrowOldPlayback() {
        val idle=Receiver(connected=true,state="IDLE")
        assertEquals(idle,presentedReceiver(PlayerUiState(lastPlayed=last,snapshot=Snapshot(receiver=idle))))
        val next=PlayerUiState(lastPlayed=last,destination=PendingDestination("other"),snapshot=Snapshot(connecting=true))
        assertNull(presentedReceiver(next).title)
    }

    @Test fun pickerHidesTemporaryEndpointButKeepsSavedGroupsAndPhysicalMembers() {
        val devices=listOf(Device("saved","All speakers",cast_type="group"),
            Device("dynamic","Fridge speaker + 1",cast_type="group",temporary=true),
            Device("fridge","Fridge speaker",cast_type="audio"))
        val snapshot=Snapshot(devices=devices,target="dynamic",group=GroupInfo(members=listOf(GroupMember("bedroom","Bedroom speaker"))))
        assertEquals(listOf("saved","fridge","bedroom"),pickerDevices(snapshot).map {it.id})
    }

    @Test fun settledIdleUsesPlainNothingPlayingAndLastPlayed() {
        assertEquals("Nothing playing",mobilePlaybackTitle(false,false,"Checking playback…"))
        assertEquals("Last played: Our mix",lastPlayedLabel(last))
        assertEquals("Connecting…",mobilePlaybackTitle(false,true,null))
    }

    @Test fun startingNewMixUsesRequestedMixInsteadOfCurrentTrack() {
        val request=PendingTransport("PLAYING","PLAYING",0,catalog[1].url)
        val pending=pendingTransportMix(request,catalog)
        assertEquals(catalog[1],pending)
        assertEquals(catalog[1].title,mobilePlaybackTitle(true,false,pending?.title))
        assertNull(pendingTransportMix(request.copy(expected="IDLE"),catalog))
    }
}
