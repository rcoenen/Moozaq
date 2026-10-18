package com.moozaq.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import com.moozaq.MoozaqApp
import com.moozaq.PlayerStore
import kotlinx.coroutines.*

class MainActivity:ComponentActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var backend:ChaquopyBackend
    private lateinit var store:PlayerStore
    private var polling:Job?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(0xff10131a.toInt()),navigationBarStyle=SystemBarStyle.dark(0xff10131a.toInt()))
        com.moozaq.coverCacheDir=java.io.File(cacheDir,"covers")
        backend=ChaquopyBackend(applicationContext)
        store=PlayerStore(backend,scope)
        setContent {MoozaqApp(store,"v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")}
    }
    override fun onStart() {super.onStart();backend.foreground(true);polling=store.start()}
    override fun onStop() {polling?.cancel();polling=null;backend.foreground(false);super.onStop()}
    override fun onDestroy() {scope.cancel();backend.close();super.onDestroy()}
}
