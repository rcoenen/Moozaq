package com.moozaq.app

import com.google.android.gms.cast.framework.CastReasonCodes

internal enum class SessionAttachmentRole { ATTACHED, JOINING, STALE }

internal class RouteChangeRecovery {
    var retryUsed=false; private set
    fun reset() {retryUsed=false}
    fun shouldHold(reason:Int,role:SessionAttachmentRole)=
        reason==CastReasonCodes.CASTING_ROUTE_CHANGED && role==SessionAttachmentRole.JOINING && !retryUsed
    fun shouldRetry(
        reason:Int,
        role:SessionAttachmentRole,
        foreground:Boolean,
        generationCurrent:Boolean,
        targetAvailable:Boolean,
        defaultRouteSelected:Boolean,
        sdkActivity:CastSessionActivity,
    ):Boolean {
        val allowed=shouldHold(reason,role) && foreground && generationCurrent && targetAvailable &&
            defaultRouteSelected && !sdkActivity.blocksFallbackSelection()
        if(allowed) retryUsed=true
        return allowed
    }
}

internal fun effectiveCastReason(status:Int,mapped:Int)=
    if(status==2155 && mapped==CastReasonCodes.UNKNOWN_REASON) CastReasonCodes.CASTING_ROUTE_CHANGED else mapped

internal fun castReasonName(reason:Int)=when(reason) {
    CastReasonCodes.UNKNOWN_REASON->"UNKNOWN_REASON"
    CastReasonCodes.CAST_INTERNAL_ERROR->"CAST_INTERNAL_ERROR"
    CastReasonCodes.CASTING_STOPPED->"CASTING_STOPPED"
    CastReasonCodes.RECEIVER_APP_NOT_RUNNING->"RECEIVER_APP_NOT_RUNNING"
    CastReasonCodes.SESSION_START_FAILED->"SESSION_START_FAILED"
    CastReasonCodes.SESSION_RESUME_FAILED->"SESSION_RESUME_FAILED"
    CastReasonCodes.CAST_SOCKET_ERROR->"CAST_SOCKET_ERROR"
    CastReasonCodes.CAST_TIMEOUT->"CAST_TIMEOUT"
    CastReasonCodes.NETWORK_ERROR->"NETWORK_ERROR"
    CastReasonCodes.CASTING_ROUTE_CHANGED->"CASTING_ROUTE_CHANGED"
    CastReasonCodes.APPLICATION_LAUNCH_ERROR->"APPLICATION_LAUNCH_ERROR"
    CastReasonCodes.CAST_CANCELLED->"CAST_CANCELLED"
    else->"REASON_$reason"
}
