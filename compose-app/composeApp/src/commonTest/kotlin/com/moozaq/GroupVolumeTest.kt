package com.moozaq

import kotlin.test.*

class GroupVolumeTest {
    @Test fun groupVolumeScalesMembersAndKeepsTheirRatio() {
        val levels=listOf(.2,.4)
        val scaled=scaledGroupVolumes(levels,.45)
        assertEquals(.30,scaled[0],.001)
        assertEquals(.60,scaled[1],.001)
        assertEquals(.45,scaled.average(),.01)
        assertEquals(.30,scaledGroupVolumes(levels,.30).average(),.01)
        assertEquals(listOf(0.0,.0),scaledGroupVolumes(levels,.0))
    }

    @Test fun zeroAverageStartsFromEqualLevels() {
        assertEquals(listOf(.3,.3),scaledGroupVolumes(listOf(0.0,0.0),.3))
    }

    @Test fun clampingCannotInventAnUnreachableAverage() {
        val scaled=scaledGroupVolumes(listOf(0.0,.8),1.0)
        assertEquals(listOf(0.0,1.0),scaled)
        assertEquals(.5,scaled.average(),.001)
        assertEquals(listOf(1.0,1.0),scaledGroupVolumes(listOf(.9,.9),1.0))
    }

    @Test fun averageRequiresEveryCurrentMemberToBeConfirmed() {
        val members=listOf(
            GroupMember("bedroom","Bedroom",connected=true,volume=.2),
            GroupMember("kitchen","Kitchen",connected=true,volume=.4),
        )
        assertEquals(.3,groupAverageVolume(members)!!,.001)
        assertNull(groupAverageVolume(members.take(1)))
        assertNull(groupAverageVolume(listOf(members[0],members[1].copy(volume=null))))
        assertNull(groupAverageVolume(listOf(members[0],members[1].copy(connected=false))))
    }
}
