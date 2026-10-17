package com.moozaq.app

import com.moozaq.Device
import org.junit.Assert.*
import org.junit.Test

class SpeakerInventoryTest {
    @Test fun disappearanceRetainsDisabledRowAndRediscoveryRestoresIt() {
        val inventory=SpeakerInventory()
        val bedroom=Device("bedroom","Bedroom speaker")
        val office=Device("office","Office speaker")
        val original=inventory.update(listOf(office,bedroom))
        val gap=inventory.update(listOf(bedroom))
        assertEquals(original.map {it.id},gap.map {it.id})
        assertFalse(gap.single {it.id=="office"}.available)
        assertEquals(original,inventory.update(listOf(bedroom,office)))
    }
    @Test fun temporaryGroupsAreNeverRetained() {
        val inventory=SpeakerInventory()
        val unknown=Device("group","Bedroom + 1",cast_type="group")
        inventory.update(listOf(unknown))
        inventory.update(listOf(unknown.copy(temporary=true)))
        assertTrue(inventory.update(emptyList()).isEmpty())
    }
}
