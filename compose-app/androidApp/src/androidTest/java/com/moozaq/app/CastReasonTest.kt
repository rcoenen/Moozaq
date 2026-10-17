package com.moozaq.app

import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastReasonCodes
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CastReasonTest {
    @Test fun routeChangeStatusMapsToPublicReason()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val task=withContext(Dispatchers.Main) {
            CastContext.getSharedInstance(context,ContextCompat.getMainExecutor(context))
        }
        val cast=withContext(Dispatchers.IO) {Tasks.await(task,30,TimeUnit.SECONDS)}
        var reason=CastReasonCodes.UNKNOWN_REASON
        withTimeoutOrNull(10000) {
            while(reason==CastReasonCodes.UNKNOWN_REASON) {
                reason=withContext(Dispatchers.Main) {cast.getCastReasonCodeForCastStatusCode(2155)}
                if(reason==CastReasonCodes.UNKNOWN_REASON) delay(100)
            }
        }
        assertEquals(CastReasonCodes.CASTING_ROUTE_CHANGED,reason)
    }
}
