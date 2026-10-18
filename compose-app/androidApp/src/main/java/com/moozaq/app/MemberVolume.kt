package com.moozaq.app

/** A physical speaker playing inside a temporary group often reports a fixed or zero-volume
 *  MediaRouter route. Its own receiver still answers with a real level, so a group member's
 *  Cast-to slider must not depend on the route's volume handling. Other rows keep the router value. */
internal fun outputVolumeForMember(isGroupMember:Boolean,receiverLevel:Double?,routerLevel:Double?):Double?=
    if(isGroupMember) receiverLevel ?: routerLevel else routerLevel

/** A member of the selected group is changed through its own receiver, never through the group
 *  session and never through MediaRouter.requestSetVolume on a fixed route. */
internal fun usesReceiverVolume(memberIds:Set<String>,routeId:String):Boolean=
    routeId in memberIds
