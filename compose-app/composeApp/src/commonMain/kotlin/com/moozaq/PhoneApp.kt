package com.moozaq

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable

/**
 * The four-screen phone app from the handoff: Browse, Now playing, Cast to, and Browse in
 * its Nothing playing state. Browse is home; stopping returns to Browse.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PhoneApp(
    store: PlayerStore,
    ui: PlayerUiState,
    library: List<Mix>,
    manualReducedMotion: Boolean,
    onManualReducedMotion: (Boolean) -> Unit,
) {
    val reducedMotion = manualReducedMotion || systemReducedMotion()
    val playback = phonePlayback(ui, library)
    var castOpen by remember { mutableStateOf(false) }
    var channelsOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) {
        val message = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheet = rememberPlayerSheet(constraints.maxHeight.toFloat(), reducedMotion)
        BackHandler(enabled = sheet.visible || castOpen || channelsOpen) {
            when {
                castOpen -> castOpen = false
                channelsOpen -> channelsOpen = false
                sheet.visible -> sheet.close()
            }
        }
        PhoneBrowse(
            store, ui, library, playback, reducedMotion,
            onOpenPlayer = { sheet.open() },
            onOpenCast = { castOpen = true },
            onOpenChannels = { channelsOpen = true },
            miniPlayerGestures = Modifier.draggable(sheet.dragState, Orientation.Vertical, onDragStopped = { sheet.release(it) }),
        )
        if (sheet.visible) {
            PhoneNowPlaying(
                store, ui, playback, sheet,
                onCollapse = { sheet.close() },
                onOpenCast = { castOpen = true },
            )
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        )
    }

    if (castOpen) PhoneCastToSheet(store, ui, phonePlayback(ui, library)) { castOpen = false }
    if (channelsOpen) {
        ChannelsDialog(store, ui, manualReducedMotion, onManualReducedMotion) { channelsOpen = false }
    }
}
