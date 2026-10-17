package com.moozaq.app

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in existing-session check. Never starts music: requires a receiver already
 * running before selecting its remote, and never calls play/command/volume.
 * Run this class explicitly while the owner has Bedroom playing.
 */
@RunWith(AndroidJUnit4::class)
class ExistingSessionTest {
    @Test fun observeExistingBedroom()=runBlocking {
        org.junit.Assume.assumeTrue("Requires explicit existing-session test opt-in",
            InstrumentationRegistry.getArguments().getString("observe_existing_session")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val backend=withContext(Dispatchers.Main) {AndroidBackend(context,automaticRecovery=false).also {it.foreground(true)}}
        try {
            val route=withTimeout(30000) {
                var found:MediaRouter.RouteInfo?=null
                while(found==null) {
                    found=withContext(Dispatchers.Main) {MediaRouter.getInstance(context).routes.firstOrNull {it.name=="Bedroom speaker" && it.isEnabled}}
                    if(found==null) delay(500)
                }
                found!!
            }
            val cast=withContext(Dispatchers.Main) {CastDevice.getFromBundle(route.extras)!!}
            assertTrue("Must already be running; this test cannot launch playback",withContext(Dispatchers.IO) {
                PassiveReceiverProbe.defaultReceiverRunning(cast.inetAddress!!,cast.servicePort)
            })
            val id=withContext(Dispatchers.Main) {route.id}
            backend.selectDevice(id)
            val state=withTimeout(30000) {
                var snapshot=backend.snapshot()
                while(!snapshot.receiver.connected) {delay(500);snapshot=backend.snapshot()}
                snapshot.receiver
            }
            assertTrue(state.state in listOf("PLAYING","PAUSED","BUFFERING"))
            assertNotNull("Receiver identity must restore catalog artwork",state.source)
            instrumentation.sendStatus(0,Bundle().apply {putString("existingSession","device=${state.device} state=${state.state} title=${state.title} position=${state.position} sourceRecognized=${state.source!=null}")})
        } finally {withContext(Dispatchers.Main) {backend.close()}}
    }
}
