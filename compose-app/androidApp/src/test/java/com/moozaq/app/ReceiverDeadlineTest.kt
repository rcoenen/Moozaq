package com.moozaq.app

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverDeadlineTest {
    @Test fun missingConfirmationIsFailureNotCancellation()=runBlocking {
        val failure=runCatching {receiverDeadline(20,"Speaker did not confirm playback.") {awaitCancellation()}}.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Speaker did not confirm playback.",failure?.message)
    }
    @Test fun stopCancellationStaysCancellation()=runBlocking {
        val ready=CompletableDeferred<Unit>()
        var caught:Throwable?=null
        val job=launch {
            try {receiverDeadline(1000,"Timed out") {ready.complete(Unit);awaitCancellation()}}
            catch(e:Throwable) {caught=e;throw e}
        }
        ready.await();job.cancelAndJoin()
        assertTrue(caught is CancellationException)
    }
    @Test fun confirmedResultPassesThrough()=runBlocking {
        assertEquals("confirmed",receiverDeadline(1000,"Timed out") {"confirmed"})
    }
}
