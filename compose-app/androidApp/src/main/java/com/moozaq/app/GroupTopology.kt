package com.moozaq.app

/** A group is hidden from the destination picker when it is known to be temporary. Do not wait
 *  for the dynamic-group list to catch up: once the saved-group list is known, any group route
 *  that is not a saved group is temporary. If the receiver does not expose the saved list, fall
 *  back to the dynamic list and the missing-group-route signal only. */
internal fun isTemporaryGroup(
    canonicalId:String?,
    dynamicIds:Set<String>,
    savedGroupIds:Set<String>,
    savedGroupsKnown:Boolean,
    isGroup:Boolean,
    missingGroupRoute:Boolean,
):Boolean=when {
    canonicalId!=null && canonicalId in dynamicIds -> true
    isGroup && savedGroupsKnown && canonicalId!=null && canonicalId !in savedGroupIds -> true
    missingGroupRoute -> true
    else -> false
}

/** The group-volume transaction may continue while the proposed member set is still exactly the
 *  same. A real join/removal, or an unreadable/empty set, invalidates the scale. */
internal fun groupVolumeMembershipValid(currentIds:Set<String>,startedIds:Set<String>):Boolean=
    currentIds==startedIds

/** A membership command can move playback without returning a sessionEndpoint. Follow discovered
 *  groups when there is no outcome, or when the outcome has no endpoint and the old endpoint no
 *  longer carries the mix. An in-place outcome on an endpoint that still has media is trusted. */
internal fun shouldFollowUnlocated(outcomePresent:Boolean,outcomeHasEndpoint:Boolean,currentHasMedia:Boolean):Boolean=
    !outcomePresent || (!outcomeHasEndpoint && !currentHasMedia)
