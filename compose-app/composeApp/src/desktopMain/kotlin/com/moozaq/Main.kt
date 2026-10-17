package com.moozaq

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.io.File
import org.jetbrains.compose.resources.painterResource
import com.moozaq.resources.*

fun main() = application {
    val scope = rememberCoroutineScope()
    val backend = remember {
        val root = (System.getenv("MOOZAQ_ROOT") ?: System.getProperty("moozaq.root"))?.let(::File) ?: generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it,"mac-spike/engine.py").exists() }
        File(root,"mac-spike/data").mkdirs()
        PythonBackend(root)
    }
    val store = remember { PlayerStore(backend, scope) }
    LaunchedEffect(Unit) { store.start() }
    DisposableEffect(Unit) { onDispose { backend.close() } }
    Window(onCloseRequest=::exitApplication, title="Moozaq", state=rememberWindowState(width=1428.dp,height=830.dp), icon=painterResource(Res.drawable.logo)) {
        window.minimumSize=java.awt.Dimension(1100,650)
        MoozaqApp(store)
    }
}
