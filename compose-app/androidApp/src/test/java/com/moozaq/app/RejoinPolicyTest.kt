package com.moozaq.app

import org.junit.Assert.*
import org.junit.Test

class RejoinPolicyTest {
    @Test fun discoveryTicksDoNotFloodFailedRejoins() {
        val p=RejoinPolicy()
        assertTrue(p.ready(0));p.attempted(0)
        assertFalse(p.ready(4999));assertTrue(p.ready(5000));p.attempted(5000)
        assertFalse(p.ready(14999));assertTrue(p.ready(15000));p.attempted(15000)
        assertFalse(p.ready(34999));assertTrue(p.ready(35000));p.attempted(35000)
        assertFalse(p.ready(64999));assertTrue(p.ready(65000))
    }
    @Test fun manualSelectionInvalidatesOldProbe() {
        val p=RejoinPolicy();val ticket=p.generation
        p.attempted(0);p.replace()
        assertFalse(p.accepts(ticket));assertTrue(p.ready(0))
    }
    @Test fun restoredSpeakerIsUnavailableUntilRediscovered() {
        val speaker=RememberedSpeaker("stable-device","old-route","Bedroom speaker","audio","Nest Audio")
        val inventory=SpeakerInventory()
        assertFalse(inventory.update(listOf(speaker.placeholder())).single().available)
        assertFalse(inventory.update(emptyList()).single().available)
        assertTrue(inventory.update(listOf(speaker.placeholder().copy(available=true))).single().available)
    }
}
