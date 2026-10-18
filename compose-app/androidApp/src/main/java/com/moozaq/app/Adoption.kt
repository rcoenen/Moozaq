package com.moozaq.app

/** A temporary group can end when its last extra member leaves. The phone then holds an adopted
 *  endpoint whose route no longer exists, which blocks recovery and makes every live-add attempt
 *  look unavailable. Drop that adoption only after a short grace period, and only when no
 *  membership change is in flight, so a real handoff is not torn down mid-flight. */
internal fun shouldDropStaleAdoption(adopted:Boolean,adopting:Boolean,routePresent:Boolean,missingForMs:Long):Boolean=
    adopted && !adopting && !routePresent && missingForMs>=4_000L

/** A membership change can be audible before the group's status converges. Poll faster for a
 *  short window, then return to the normal cadence. */
internal fun membershipRefreshInterval(now:Long,burstUntil:Long):Long=
    if(now<burstUntil) 1_500L else 8_000L

/** Stop the fast poll once the receiver reports exactly the requested canonical members. */
internal fun membershipBurstMatched(expected:Set<String>?,members:List<String>):Boolean=
    expected!=null && expected==members.toSet()
