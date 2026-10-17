package com.moozaq

import androidx.compose.foundation.layout.Box
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
    var playerOpen by remember { mutableStateOf(false) }
    var castOpen by remember { mutableStateOf(false) }
    var channelsOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) {
        val message = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short)
    }
    BackHandler(enabled = playerOpen || castOpen || channelsOpen) {
        when {
            castOpen -> castOpen = false
            channelsOpen -> channelsOpen = false
            playerOpen -> playerOpen = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (playerOpen) {
            PhoneNowPlaying(
                store, ui, playback,
                onCollapse = { playerOpen = false },
                onOpenCast = { castOpen = true },
            )
        } else {
            PhoneBrowse(
                store, ui, library, playback, reducedMotion,
                onOpenPlayer = { playerOpen = true },
                onOpenCast = { castOpen = true },
                onOpenChannels = { channelsOpen = true },
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
