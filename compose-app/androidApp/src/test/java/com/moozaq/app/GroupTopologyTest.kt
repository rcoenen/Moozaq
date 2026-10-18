package com.moozaq.app

import org.junit.Assert.*
import org.junit.Test

class GroupTopologyTest {
    @Test fun knownDynamicGroupIsAlwaysTemporary() {
        assertTrue(isTemporaryGroup("temp",setOf("temp"),setOf("saved"),true,true,false))
        assertTrue(isTemporaryGroup("temp",setOf("temp"),emptySet(),false,true,false))
    }

    @Test fun unknownGroupIsTemporaryOnceSavedGroupsAreKnown() {
        assertTrue(isTemporaryGroup("unknown",emptySet(),setOf("saved"),true,true,false))
        assertFalse(isTemporaryGroup("saved",emptySet(),setOf("saved"),true,true,false))
        assertFalse(isTemporaryGroup("unknown",emptySet(),setOf("saved"),false,true,false))
        assertFalse(isTemporaryGroup("unknown",emptySet(),setOf("saved"),true,false,false))
    }

    @Test fun missingGroupRouteRemainsATemporarySignal() {
        assertTrue(isTemporaryGroup("unknown",emptySet(),emptySet(),false,true,true))
    }

    @Test fun followIsRequiredWhenTheOutcomeHasNoEndpointAndTheOldEndpointLostMedia() {
        assertTrue(shouldFollowUnlocated(outcomePresent=false,outcomeHasEndpoint=false,currentHasMedia=false))
        assertTrue(shouldFollowUnlocated(outcomePresent=false,outcomeHasEndpoint=false,currentHasMedia=true))
        assertTrue(shouldFollowUnlocated(outcomePresent=true,outcomeHasEndpoint=false,currentHasMedia=false))
        assertFalse(shouldFollowUnlocated(outcomePresent=true,outcomeHasEndpoint=false,currentHasMedia=true))
        assertFalse(shouldFollowUnlocated(outcomePresent=true,outcomeHasEndpoint=true,currentHasMedia=false))
    }

    @Test fun sameMembershipSetDoesNotCancelGroupVolumeButARealChangeDoes() {
        assertTrue(groupVolumeMembershipValid(setOf("bedroom","fridge"),setOf("bedroom","fridge")))
        assertFalse(groupVolumeMembershipValid(setOf("bedroom"),setOf("bedroom","fridge")))
        assertFalse(groupVolumeMembershipValid(setOf("bedroom","fridge","kitchen"),setOf("bedroom","fridge")))
        assertFalse(groupVolumeMembershipValid(emptySet(),setOf("bedroom","fridge")))
    }
}
