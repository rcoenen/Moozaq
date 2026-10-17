package com.moozaq.app

import org.junit.Assert.*
import org.junit.Test

class LoadObservationTest {
    @Test fun terminalErrorSurvivesEmptyStatus() {
        val attempt=LoadObservation("new-stream")
        attempt.observe("new-stream",false)
        attempt.observe(null,true)
        attempt.observe(null,false)
        assertTrue(runCatching {attempt.requireNotFailed()}.exceptionOrNull() is IllegalStateException)
    }
    @Test fun oldSessionErrorCannotFailNewLoad() {
        val attempt=LoadObservation("new-stream")
        attempt.observe("old-stream",true)
        attempt.observe("new-stream",false)
        attempt.observe("old-stream",true)
        assertNull(attempt.failure)
    }
    @Test fun emptyIdleIsNotAnError() {
        val attempt=LoadObservation("new-stream")
        attempt.observe("new-stream",false)
        attempt.observe(null,false)
        assertNull(attempt.failure)
    }
}
