package com.moozaq.app

import com.moozaq.OutputState
import com.moozaq.Snapshot
import com.moozaq.canAdjustOutputVolume
import org.junit.Assert.*
import org.junit.Test

class MemberVolumeTest {
    @Test fun fixedRouterRouteStillPublishesAnEnabledMemberSlider() {
        // MediaRouter reports max=0/fixed for a member inside a temporary group; the speaker's own
        // receiver still has a level, so the Cast-to slider must be enabled from that value.
        val volume=outputVolumeForMember(isGroupMember=true,receiverLevel=.35,routerLevel=null)
        assertEquals(.35,volume!!,.0001)
        val output=OutputState("bedroom",connected=true,volume=volume)
        assertTrue(canAdjustOutputVolume(Snapshot(target="group"),output,selected=false,pending=null))
        assertNull(outputVolumeForMember(isGroupMember=false,receiverLevel=.35,routerLevel=null))
        assertFalse(canAdjustOutputVolume(Snapshot(),OutputState("bedroom",connected=true,volume=null),false,null))
    }

    @Test fun routerVolumeRemainsTheFallbackUntilTheReceiverAnswers() {
        assertEquals(.5,outputVolumeForMember(true,null,.5)!!,.0001)
        assertEquals(.2,outputVolumeForMember(false,.8,.2)!!,.0001)
        assertEquals(.8,outputVolumeForMember(true,.8,.2)!!,.0001)
    }

    @Test fun onlyAGroupMemberUsesTheReceiverPath() {
        val members=setOf("bedroom","kitchen")
        assertTrue(usesReceiverVolume(members,"bedroom"))
        assertFalse(usesReceiverVolume(members,"office"))
        assertFalse(usesReceiverVolume(emptySet(),"bedroom"))
    }
}
