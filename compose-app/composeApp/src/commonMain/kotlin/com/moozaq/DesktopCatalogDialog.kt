package com.moozaq

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private enum class CatalogTab { Sources, Songs, AddVideo }

private fun catalogBusy(ui: PlayerUiState) = ui.catalogAction?.failed == false

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopCatalogDialog(store: PlayerStore, ui: PlayerUiState, reducedMotion: Boolean, onReducedMotion: (Boolean) -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(CatalogTab.Sources) }
    var debug by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmPublish by remember { mutableStateOf<Int?>(null) }
    val snapshot = ui.snapshot
    val actionBusy = ui.catalogAction?.failed == false
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).widthIn(max = 1000.dp).fillMaxWidth(.96f).heightIn(max = 780.dp),
            shape = RoundedCornerShape(28.dp), color = colors.surfaceContainer,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Catalog", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    Text("Master #${snapshot.master_revision} · App #${snapshot.app_revision}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
                when {
                    snapshot.catalog_building -> Text("Rebuilding the app catalog…", color = colors.tertiary)
                    snapshot.last_generation_error != null -> Text(snapshot.last_generation_error, color = colors.error, maxLines = 2)
                    snapshot.catalog_dirty -> Text("Master changes are not yet in the app catalog", color = colors.tertiary)
                    else -> Text("App catalog is up to date", color = colors.onSurfaceVariant)
                }
                if (snapshot.catalog_updates) {
                    Text(
                        "Latest observed #${snapshot.latest_revision} · Last checked: ${snapshot.last_update_check ?: "not yet"}",
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    )
                }
                snapshot.update_error?.let { Text("Update error: $it", color = colors.error, style = MaterialTheme.typography.bodyMedium) }
                if (ui.catalogAction != null) {
                    Text(
                        when {
                            ui.catalogAction.failed -> ui.catalogError ?: "Catalog action failed"
                            ui.catalogAction.key == "publish-release" -> "Publishing public release…"
                            ui.catalogAction.key == "check-updates" -> "Checking for catalog updates…"
                            else -> "Working: ${ui.catalogAction.key}"
                        },
                        color = if (ui.catalogAction.failed) colors.error else colors.tertiary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (ui.catalogError != null) {
                    Text(ui.catalogError, color = colors.error, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { tab = CatalogTab.Sources }) { Text(if (tab == CatalogTab.Sources) "Sources •" else "Sources") }
                    TextButton(onClick = { tab = CatalogTab.Songs }) { Text(if (tab == CatalogTab.Songs) "Songs •" else "Songs") }
                    TextButton(onClick = { tab = CatalogTab.AddVideo }) { Text(if (tab == CatalogTab.AddVideo) "Add video •" else "Add video") }
                }
                when (tab) {
                    CatalogTab.Sources -> SourcesTab(store, ui)
                    CatalogTab.Songs -> SongsTab(store, ui)
                    CatalogTab.AddVideo -> AddVideoTab(store, ui)
                }
                if (debug) {
                    Text(snapshot.message, style = MaterialTheme.typography.labelSmall, maxLines = 3, color = colors.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(reducedMotion, onReducedMotion)
                        Text("Reduce motion", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset personal data", color = colors.error) }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { debug = !debug }) { Text("Debug info", color = colors.outline) }
                    TextButton(onClick = { store.importChannel() }, enabled = !snapshot.importing && !actionBusy) { Text("Refresh all") }
                    TextButton(onClick = { store.rebuildCatalog() }, enabled = !snapshot.catalog_building && !actionBusy) { Text("Rebuild") }
                    if (snapshot.catalog_updates) {
                        TextButton(onClick = { store.checkCatalogUpdates() }, enabled = !actionBusy) { Text("Check now") }
                    }
                    if (snapshot.catalog_curation) {
                        TextButton(
                            onClick = { confirmPublish = snapshot.app_revision },
                            enabled = !actionBusy && !snapshot.catalog_building && !snapshot.catalog_dirty && snapshot.app_revision > 0,
                        ) { Text("Publish release") }
                    }
                    Button(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
    confirmPublish?.let { revision ->
        AlertDialog(
            onDismissRequest = { confirmPublish = null },
            title = { Text("Publish catalog revision #$revision?") },
            text = {
                Text(
                    "Repository: rcoenen/Moozaq-catalog\nTag: catalog-v$revision\n\n" +
                        "Public assets:\n• app-catalog.db\n• catalog-manifest.json\n\n" +
                        "Only the validated app catalog is uploaded. master.db and personal.db remain on this Mac.",
                )
            },
            confirmButton = {
                TextButton(onClick = { store.publishCatalogRelease(revision); confirmPublish = null }) { Text("Publish revision #$revision") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPublish = null }) { Text("Cancel") }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset personal data?") },
            text = { Text("This removes likes and listening history from this Mac. The catalog and current playback are unchanged.") },
            confirmButton = {
                TextButton(onClick = { store.resetPersonal(); confirmReset = false }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ColumnScope.SourcesTab(store: PlayerStore, ui: PlayerUiState) {
    val colors = MaterialTheme.colorScheme
    val channels = ui.masterLibrary?.channels ?: emptyList()
    var adding by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    if (adding) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("YouTube channel link") }, singleLine = true)
            Button(onClick = { store.importChannel(input.trim()); input = ""; adding = false }, enabled = input.isNotBlank() && !catalogBusy(ui)) { Text("Add") }
        }
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(channels, key = { it.url }) { channel ->
            val busy = channel.url in ui.snapshot.refreshing_channels
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    busy -> "Refreshing…"
                                    channel.error != null -> channel.error
                                    else -> "${channel.count} videos · ${channel.last_attempt?.take(10) ?: channel.refreshed?.take(10) ?: "Not imported"}"
                                },
                                color = if (channel.error != null) colors.error else colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { store.importChannel(channel.url) }, enabled = !ui.snapshot.importing && !catalogBusy(ui)) { Text("Refresh") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("New uploads", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        val next = if (channel.publication_mode == "automatic") "review" else "automatic"
                        TextButton(onClick = { store.curate("source_mode", url = channel.url, mode = next) }, enabled = !catalogBusy(ui)) {
                            Text(if (channel.publication_mode == "automatic") "Automatic" else "Review first")
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { adding = !adding }) { Text(if (adding) "Cancel" else "Add channel") }
        }
    }
}

@Composable
private fun ColumnScope.SongsTab(store: PlayerStore, ui: PlayerUiState) {
    val songs = ui.masterLibrary?.mixes ?: emptyList()
    var query by remember { mutableStateOf("") }
    val filtered = remember(songs, query) { searchMixes(songs, query).take(100) }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Filter songs") }, singleLine = true)
    Text("${filtered.size} of ${songs.size} songs", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filtered, key = { it.id }) { mix -> CatalogSongRow(store, ui, mix) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogSongRow(store: PlayerStore, ui: PlayerUiState, mix: Mix) {
    val colors = MaterialTheme.colorScheme
    var editing by remember(mix.id) { mutableStateOf(false) }
    var category by remember(mix.id) { mutableStateOf(mix.category_override ?: mix.category) }
    val busy = catalogBusy(ui)
    Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(mix.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${mix.creator} · ${clockTime(mix.duration)} · ${mix.category}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mix.recommended_at != null) Text("Recommended", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                if (mix.hidden) Text("Hidden", color = colors.tertiary, style = MaterialTheme.typography.labelMedium)
                if (mix.review_status == "pending") Text("Review", color = colors.tertiary, style = MaterialTheme.typography.labelMedium)
                if (mix.availability == "unavailable") Text("Unavailable", color = colors.error, style = MaterialTheme.typography.labelMedium)
                if ((mix.duration ?: 0.0) < 3600) Text("Short", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { store.curate(if (mix.recommended_at == null) "recommend" else "unrecommend", song = mix.id) },
                    enabled = !busy && (mix.recommended_at != null || ((mix.duration ?: 0.0) >= 3600 && mix.availability != "unavailable"))) {
                    Text(if (mix.recommended_at == null) "Recommend" else "Unrecommend")
                }
                TextButton(onClick = { store.curate(if (mix.hidden) "restore" else "hide", song = mix.id) }, enabled = !busy) {
                    Text(if (mix.hidden) "Restore" else "Hide")
                }
                TextButton(onClick = { store.curate("review", song = mix.id, status = if (mix.review_status == "pending") "approved" else "pending") }, enabled = !busy) {
                    Text(if (mix.review_status == "pending") "Approve" else "Send to review")
                }
                TextButton(onClick = { editing = !editing }, enabled = !busy) { Text("Category") }
            }
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(category, { category = it }, Modifier.weight(1f), singleLine = true)
                    TextButton(onClick = { store.curate("category", song = mix.id, category = category); editing = false }, enabled = !busy) { Text("Apply") }
                    TextButton(onClick = { store.curate("category", song = mix.id, category = null); category = mix.automatic_category ?: mix.category; editing = false }, enabled = !busy) { Text("Reset") }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.AddVideoTab(store: PlayerStore, ui: PlayerUiState) {
    val colors = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    val busy = catalogBusy(ui)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("YouTube video link or ID") }, singleLine = true)
        Button(onClick = { store.previewVideo(input.trim()) }, enabled = input.isNotBlank() && !busy) { Text("Preview") }
    }
    ui.videoPreview?.let { preview ->
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(preview.title, style = MaterialTheme.typography.titleMedium)
                Text("${preview.creator} · ${clockTime(preview.duration)}", color = colors.onSurfaceVariant)
                preview.channel_url?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                if ((preview.duration ?: 0.0) < 3600) {
                    Text("Add to master is allowed. Recommend requires a confirmed duration of at least one hour.", color = colors.tertiary, style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { store.importVideo(preview.webpage_url, false) }, enabled = !busy) { Text("Add to master") }
                    Button(onClick = { store.importVideo(preview.webpage_url, true) }, enabled = !busy && (preview.duration ?: 0.0) >= 3600) { Text("Recommend") }
                    preview.channel_url?.let { channel ->
                        TextButton(onClick = { store.followChannel(channel, false) }, enabled = !busy) { Text("Follow channel") }
                        TextButton(onClick = { store.followChannel(channel, true) }, enabled = !busy) { Text("Follow + refresh") }
                    }
                }
            }
        }
    } ?: Text("Preview a video before adding or recommending it. No audio is resolved or played.", color = colors.onSurfaceVariant)
    if (ui.catalogError != null && !catalogBusy(ui)) {
        Text(ui.catalogError, color = colors.error, style = MaterialTheme.typography.bodyMedium)
    }
}
