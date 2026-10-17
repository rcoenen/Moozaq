package com.moozaq.app

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.*

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context) = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        // SDK 22.x saved-session restore fails with 2152 after ~10s on the test phone.
        // Our remembered-device recovery probes the running receiver before rejoining.
        .setResumeSavedSession(false)
        .setLaunchOptions(LaunchOptions.Builder().setRelaunchIfRunning(false).build())
        .setStopReceiverApplicationWhenEndingSession(false)
        .setEnableReconnectionService(true)
        .setSessionTransferEnabled(true)
        .build()
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
