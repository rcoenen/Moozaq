package com.moozaq
import kotlin.test.*
class PlayerTest {
    @Test fun recoveryRetainsTrackButDisablesPlaybackUntilConfirmed() {
        val s=Snapshot(connecting=true,receiver=Receiver(connected=false,title="Our mix",state="UNKNOWN",recovery="Reconnecting…"))
        assertEquals("Reconnecting…",playbackLabel(s))
        assertFalse(canPause(s)); assertFalse(canResume(s)); assertFalse(canStart(s,catalog.first()))
        assertEquals("Our mix",s.receiver.title)
    }
    private val playing=Snapshot(receiver=Receiver(connected=true,state="PLAYING",source=catalog.first().url))
    @Test fun activeMixCannotRestart() { assertFalse(canStart(playing,catalog.first())); assertTrue(canStart(playing,catalog[1])); assertFalse(canResume(playing)); assertTrue(canPause(playing)) }
    @Test fun reconnectAndPendingCommandsDisableTransport() { assertFalse(canPause(playing.copy(connecting=true))); assertFalse(canStart(playing.copy(busy=true),catalog[1])); assertFalse(canResume(playing.copy(receiver=playing.receiver.copy(state="PAUSED",connected=false)))) }
    @Test fun pausedSessionCanResume() { assertTrue(canResume(playing.copy(receiver=playing.receiver.copy(state="PAUSED")))) }
    @Test fun humanTime() { assertEquals("3:02:46",clockTime(10966.0));assertEquals("—",clockTime(null));assertEquals("0:00",clockTime(-1.0)) }
}
