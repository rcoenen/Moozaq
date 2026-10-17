package com.moozaq.app

import org.junit.Assert.*
import org.junit.Test

class PassiveReceiverProbeTest {
    @Test fun onlyExistingDefaultReceiverAllowsFallback() {
        assertTrue(PassiveReceiverProbe.hasDefaultReceiver("""{"status":{"applications":[{"appId":"CC1AD845"}]}}"""))
        assertFalse(PassiveReceiverProbe.hasDefaultReceiver("""{"status":{"applications":[{"appId":"OTHER"}]}}"""))
        assertFalse(PassiveReceiverProbe.hasDefaultReceiver("""{"status":{}}"""))
    }
    @Test fun wireRoundTripWithMultibyteLength() {
        val payload="x".repeat(300)
        assertEquals("namespace" to payload,CastStatusWire.decode(CastStatusWire.encode("namespace",payload)))
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedFrameRejected() {
        CastStatusWire.decode(byteArrayOf(50,100,1))
    }
    @Test(expected=IllegalArgumentException::class) fun hugeFrameFieldRejected() {
        CastStatusWire.decode(byteArrayOf(50,-1,-1,-1,-1,7))
    }
}
