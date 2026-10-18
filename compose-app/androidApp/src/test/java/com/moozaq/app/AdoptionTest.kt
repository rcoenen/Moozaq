package com.moozaq.app

import org.junit.Assert.*
import org.junit.Test

class AdoptionTest {
    @Test fun deadAdoptedRouteIsDroppedOnlyAfterGraceAndNeverMidChange() {
        assertFalse(shouldDropStaleAdoption(adopted=false,adopting=false,routePresent=false,missingForMs=10_000))
        assertFalse(shouldDropStaleAdoption(adopted=true,adopting=false,routePresent=true,missingForMs=10_000))
        assertFalse(shouldDropStaleAdoption(adopted=true,adopting=false,routePresent=false,missingForMs=3_999))
        assertFalse(shouldDropStaleAdoption(adopted=true,adopting=true,routePresent=false,missingForMs=10_000))
        assertTrue(shouldDropStaleAdoption(adopted=true,adopting=false,routePresent=false,missingForMs=4_000))
    }

    @Test fun membershipChangePollsFasterUntilTheExpectedSetArrives() {
        assertEquals(1_500L,membershipRefreshInterval(now=1_000,burstUntil=2_000))
        assertEquals(8_000L,membershipRefreshInterval(now=2_000,burstUntil=2_000))
        assertEquals(8_000L,membershipRefreshInterval(now=5_000,burstUntil=0))
        assertTrue(membershipBurstMatched(setOf("bedroom","kitchen"),listOf("bedroom","kitchen")))
        assertFalse(membershipBurstMatched(setOf("bedroom","kitchen"),listOf("bedroom")))
        assertFalse(membershipBurstMatched(null,listOf("bedroom")))
    }
}
