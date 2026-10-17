package com.moozaq.app

import org.junit.Assert.*
import org.junit.Test

class PlaybackFenceTest {
    @Test fun rejoinRecognizesOnlyContentBoundMoozaqMetadata() {
        val source="https://www.youtube.com/watch?v=7vy0H3ERRiA"
        assertEquals(source,verifiedSource("stream-A",source,contentHash("stream-A")))
        assertNull(verifiedSource("stream-B",source,contentHash("stream-A")))
        assertNull(verifiedSource("stream-A",null,contentHash("stream-A")))
        assertNull(verifiedSource("stream-A","https://unrelated.example/",contentHash("stream-A")))
    }
    @Test fun stopDuringExtractionRejectsLateLoad() {
        val fence=PlaybackFence()
        val resolving=fence.next()
        val stopping=fence.next()
        assertFalse("Late resolution must not LOAD after Stop",fence.isCurrent(resolving))
        assertTrue(fence.isCurrent(stopping))
    }
    @Test fun selectingAnotherReceiverInvalidatesOldCommand() {
        val fence=PlaybackFence()
        val previousSpeaker=fence.next()
        fence.next()
        assertFalse(fence.isCurrent(previousSpeaker))
    }
    @Test fun freshExplicitPlayIsAllowedAfterCancellation() {
        val fence=PlaybackFence()
        val abandoned=fence.next();fence.next()
        val explicitPlay=fence.next()
        assertTrue(fence.isCurrent(explicitPlay))
        assertFalse(fence.isCurrent(abandoned))
    }
}
