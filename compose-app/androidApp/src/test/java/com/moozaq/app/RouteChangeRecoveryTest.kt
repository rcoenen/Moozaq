package com.moozaq.app

import com.google.android.gms.cast.framework.CastReasonCodes
import org.junit.Assert.*
import org.junit.Test

class RouteChangeRecoveryTest {
    private fun eligible(policy:RouteChangeRecovery,role:SessionAttachmentRole=SessionAttachmentRole.JOINING)=policy.shouldRetry(
        CastReasonCodes.CASTING_ROUTE_CHANGED,role,foreground=true,generationCurrent=true,
        targetAvailable=true,defaultRouteSelected=true,sdkActivity=CastSessionActivity())

    @Test fun pendingJoinGetsOnlyOneRouteRetry() {
        val policy=RouteChangeRecovery()
        assertTrue(policy.shouldHold(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING))
        assertTrue(eligible(policy))
        assertTrue(policy.retryUsed)
        assertFalse(policy.shouldHold(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING))
        assertFalse(eligible(policy))
    }

    @Test fun attachedAndStaleSessionsNeverRetry() {
        assertFalse(eligible(RouteChangeRecovery(),SessionAttachmentRole.ATTACHED))
        assertFalse(eligible(RouteChangeRecovery(),SessionAttachmentRole.STALE))
        assertFalse(RouteChangeRecovery().shouldHold(CastReasonCodes.SESSION_START_FAILED,SessionAttachmentRole.JOINING))
    }

    @Test fun changedEnvironmentCancelsRouteRetry() {
        val variations=listOf(
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,false,true,true,true,CastSessionActivity()),
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,true,false,true,true,CastSessionActivity()),
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,true,true,false,true,CastSessionActivity()),
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,true,true,true,false,CastSessionActivity()),
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,true,true,true,true,CastSessionActivity(connecting=true)),
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,true,true,true,true,CastSessionActivity(resuming=true)),
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,true,true,true,true,CastSessionActivity(suspended=true)),
            RouteChangeRecovery().shouldRetry(CastReasonCodes.CASTING_ROUTE_CHANGED,SessionAttachmentRole.JOINING,true,true,true,true,CastSessionActivity(connected=true)),
        )
        variations.forEach {assertFalse(it)}
    }

    @Test fun attachmentBeforeDelayedRetryDoesNotSelectAgain() {
        assertFalse(eligible(RouteChangeRecovery(),SessionAttachmentRole.ATTACHED))
    }

    @Test fun uninitializedReasonBundleUsesOnlyKnown2155Fallback() {
        assertEquals(CastReasonCodes.CASTING_ROUTE_CHANGED,effectiveCastReason(2155,CastReasonCodes.UNKNOWN_REASON))
        assertEquals(CastReasonCodes.UNKNOWN_REASON,effectiveCastReason(2199,CastReasonCodes.UNKNOWN_REASON))
        assertEquals(CastReasonCodes.NETWORK_ERROR,effectiveCastReason(2155,CastReasonCodes.NETWORK_ERROR))
    }
}
