package com.moozaq.app

import org.junit.Assert.*
import org.junit.Test

class CastGuardsTest {
    @Test fun oldReceiverCallbacksCannotRefreshNewAttachment() {
        val epoch=ConnectionEpoch()
        val bedroom=epoch.advance()
        epoch.advance() // detach on user selection
        val kitchen=epoch.advance()
        assertFalse(epoch.accepts(bedroom))
        assertTrue(epoch.accepts(kitchen))
        epoch.advance() // close
        assertFalse(epoch.accepts(kitchen))
    }
    @Test fun statusPollsDoNotPileUpAndOldRepliesCannotCompleteNewPoll() {
        val gate=StatusRequestGate()
        val first=gate.begin(0)!!
        assertNull(gate.begin(5000))
        val replacement=gate.begin(6000)!!
        assertFalse(gate.complete(first,6100))
        assertNull(gate.begin(6101))
        assertTrue(gate.complete(replacement,6200))
        assertNotNull(gate.begin(6201))
    }
    @Test fun replyAfterDeadlineDoesNotMakeReceiverFresh() {
        val gate=StatusRequestGate()
        val ticket=gate.begin(0)!!
        assertFalse(gate.complete(ticket,6000))
        assertNotNull(gate.begin(6001))
    }
    @Test fun detachDiscardsOutstandingRequestEvenIfSdkReusesClient() {
        val gate=StatusRequestGate();val old=gate.begin(0)!!
        gate.clear();val current=gate.begin(10)!!
        assertFalse(gate.complete(old,20))
        assertTrue(gate.complete(current,21))
    }
    @Test fun pausedSeekWaitsUntilPausedAndNearRequestedPosition() {
        assertFalse(seekConfirmed(60.0,60.0,true,playing=true,paused=false))
        assertFalse(seekConfirmed(60.0,60.0,true,playing=false,paused=false)) // buffering
        assertFalse(seekConfirmed(80.0,60.0,true,playing=false,paused=true))
        assertTrue(seekConfirmed(60.5,60.0,true,playing=false,paused=true))
        assertTrue(seekConfirmed(61.0,60.0,false,playing=true,paused=false))
    }
    @Test(expected=IllegalStateException::class) fun replacementMixCannotConfirmTransport() {
        requireSameMedia("mix-A","mix-B")
    }
    @Test fun stoppedReceiverCanConfirmStopWithoutContent() {requireSameMedia("mix-A",null)}
    @Test fun backgroundedProbeRemainsInvalidAfterForegroundReturn() {
        val p=RejoinPolicy();val old=p.generation
        p.attempted(0);p.invalidate()
        assertFalse(p.accepts(old));assertFalse(p.ready(100))
        assertTrue(p.ready(5000))
    }
    @Test fun activeSdkSessionsBlockFallbackRouteSelection() {
        assertFalse(CastSessionActivity().blocksFallbackSelection())
        listOf(
            CastSessionActivity(connected=true),
            CastSessionActivity(connecting=true),
            CastSessionActivity(resuming=true),
            CastSessionActivity(suspended=true),
        ).forEach {assertTrue(it.blocksFallbackSelection())}
    }
    @Test fun sessionThatStartsResumingDuringProbeBlocksFallbackRouteSelection() {
        val beforeProbe=CastSessionActivity()
        val afterProbe=beforeProbe.copy(resuming=true)
        assertFalse(beforeProbe.blocksFallbackSelection())
        assertTrue(afterProbe.blocksFallbackSelection())
    }
}
