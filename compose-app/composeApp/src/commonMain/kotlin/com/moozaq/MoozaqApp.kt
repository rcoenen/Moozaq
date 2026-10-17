package com.moozaq

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import com.moozaq.resources.*

private val covers = listOf(Res.drawable.mix_0, Res.drawable.mix_1, Res.drawable.mix_2, Res.drawable.mix_3, Res.drawable.mix_4, Res.drawable.mix_5)
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun MoozaqApp(store: PlayerStore) {
    val ui by store.state.collectAsState()
    val s = ui.snapshot
    val r = s.receiver
    val library = remember(ui.library.mixes) { mergedCatalog(ui.library.mixes) }
    val eligible = remember(library) { library.filter { (it.duration ?: 0.0) >= 3600.0 } }
    var speakersOpen by remember { mutableStateOf(false) }
    var manageChannels by remember { mutableStateOf(false) }
    val moods = remember(eligible) { eligible.groupBy { it.category }.toSortedMap() }
    val browseState = rememberLazyListState()
    val active = library.find { it.url == r.source }
    val colors = darkColorScheme(primary=Color(0xFFFFB56B), background=Color(0xFF101319), surface=Color(0xFF1B2029), onSurface=Color(0xFFF3EEE8), secondary=Color(0xFFA9B5C7))
    MaterialTheme(colorScheme=colors) {
        if(manageChannels) {
            var input by remember { mutableStateOf("") }
            var showDebug by remember { mutableStateOf(false) }
            AlertDialog(onDismissRequest={manageChannels=false}, title={Text("Your channels")},
                text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(s.catalog_message.ifBlank { "Metadata and artwork only. Importing never starts playback." })
                    Column(Modifier.heightIn(max=260.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        ui.library.channels.forEach { c ->
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(c.name); Text(c.error ?: "${c.count} videos · ${c.refreshed?.take(10) ?: "Not imported"}",style=MaterialTheme.typography.labelSmall,color=if(c.error!=null) colors.error else colors.secondary) }
                                TextButton(onClick={store.importChannel(c.url)},enabled=!s.importing) { Text("Refresh") }
                            }
                        }
                    }
                    OutlinedTextField(value=input,onValueChange={input=it},label={Text("YouTube channel URL")},singleLine=true)
                    Button(onClick={store.importChannel(input.trim());input=""},enabled=input.isNotBlank()&&!s.importing) { Text("Import channel") }
                    Text("Only mixes with a known duration of at least one hour are shown.", style=MaterialTheme.typography.labelSmall)
                    TextButton(onClick={showDebug=!showDebug}) { Text(if(showDebug) "Hide debug info" else "Debug info") }
                    if(showDebug) Text(s.message, style=MaterialTheme.typography.labelSmall)
                } },
                confirmButton={TextButton(onClick={store.importChannel()},enabled=!s.importing) { Text("Refresh all") }},
                dismissButton={TextButton(onClick={manageChannels=false}) { Text("Done") }})
        }
        Surface(Modifier.fillMaxSize().blur(if(speakersOpen) 10.dp else 0.dp), color=colors.background) {
            Column(Modifier.padding(24.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Image(painterResource(Res.drawable.wordmark), "Moozaq",
                            Modifier.width(220.dp).aspectRatio(1280f / 324f), contentScale=ContentScale.Fit)
                        Text("Find your mood. Let it run.", color=colors.secondary)
                    }
                    TextButton(onClick={manageChannels=true}) { Text("Channels") }
                }
                if(s.importing) Text(s.catalog_message, color=colors.secondary, style=MaterialTheme.typography.labelSmall)
                if(eligible.isEmpty()) Text("Your hour-long mixes will appear here.",color=colors.secondary)
                LazyColumn(state=browseState, modifier=Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    moods.forEach { (name, mixes) ->
                        stickyHeader(key="heading:$name") {
                            Text(name, style=MaterialTheme.typography.titleLarge,
                                modifier=Modifier.fillMaxWidth().background(colors.background).padding(vertical=12.dp))
                        }
                        item(key="shelf:$name") {
                        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                                items(mixes, key={it.id}) { mix ->
                                    val hover = remember { MutableInteractionSource() }
                                    val hovered by hover.collectIsHoveredAsState()
                                    val starting = ui.transport?.source == mix.url && ui.transport?.failed == false
                                    Card(onClick={store.preview(mix.id)}, modifier=Modifier.width(240.dp).hoverable(hover), colors=CardDefaults.cardColors(containerColor=if(ui.previewId==mix.id) Color(0xFF303239) else colors.surface)) {
                                        Box {
                                            MixCover(mix, Modifier.fillMaxWidth().aspectRatio(16f/9f))
                                            if(hovered || starting) {
                                                FilledIconButton(onClick={store.play(mix)}, enabled=canStart(s,mix), modifier=Modifier.align(Alignment.BottomEnd).padding(10.dp).size(48.dp).semantics { contentDescription = if(starting) "Starting ${mix.title}" else "Play ${mix.title}" }) {
                                                    if(starting) CircularProgressIndicator(Modifier.size(22.dp), color=colors.primary, strokeWidth=2.dp)
                                                    else Canvas(Modifier.size(22.dp)) {
                                                        drawPath(Path().apply { moveTo(size.width*.25f, size.height*.1f); lineTo(size.width*.9f, size.height*.5f); lineTo(size.width*.25f, size.height*.9f); close() }, Color(0xFF101319))
                                                    }
                                                }
                                            }
                                        }
                                        Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                            Text(mix.title, maxLines=2, minLines=2, overflow=TextOverflow.Ellipsis, fontWeight=FontWeight.SemiBold)
                                            Text(mix.creator, color=colors.secondary, maxLines=1, overflow=TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                Surface(shape=RoundedCornerShape(20.dp), color=colors.surface) {
                    Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                            SpeakerPicker(store, ui, speakersOpen, { speakersOpen=it })
                        }
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                            MixCover(active, Modifier.size(60.dp))
                            Column(Modifier.weight(1f)) { Text(r.title?.takeIf { it.isNotBlank() } ?: if(s.connecting) "Checking playback…" else "Nothing playing", maxLines=2, overflow=TextOverflow.Ellipsis, fontWeight=FontWeight.SemiBold); Text(if(r.recovery == null && ui.transport?.failed == false) ui.transport?.previous?.lowercase()?.replaceFirstChar { it.uppercase() } ?: playbackLabel(s) else playbackLabel(s), color=colors.secondary) }
                            OutlinedButton(onClick={store.command("stop")}, enabled=s.target != null || s.busy) { Text("Stop") }
                            val transport = ui.transport
                            val awaiting = transport != null && !transport.failed
                            Button(onClick={store.command(if(canPause(s)) "pause" else "resume")},
                                enabled=!awaiting && (canPause(s)||canResume(s)),
                                colors=ButtonDefaults.buttonColors(
                                    disabledContainerColor=if(awaiting) colors.secondary else Color(0xFF343B47),
                                    disabledContentColor=if(awaiting) colors.background else colors.onSurface)) {
                                Text(if(awaiting) { if(transport?.expected == "PAUSED") "Pausing…" else "Starting…" } else if(r.state=="PLAYING") "Pause" else "Resume")
                            }
                            if(transport?.failed == true) Text("Not confirmed", color=colors.error, style=MaterialTheme.typography.labelSmall)
                        }
                        val duration=(r.duration ?: 0.0).coerceAtLeast(0.0)
                        var seek by remember(s.target) { mutableStateOf<Float?>(null) }
                        val pendingSeek = ui.pendingSeek
                        val seekTint = if(pendingSeek?.failed == true) colors.error else if(pendingSeek != null) colors.secondary else colors.primary
                        if(pendingSeek != null) Row(verticalAlignment=Alignment.CenterVertically) {
                            Text(if(pendingSeek.failed) "Seek not confirmed" else "Seeking…", color=seekTint, style=MaterialTheme.typography.labelMedium)
                            if(pendingSeek.failed) TextButton(onClick={store.dismissSeek()}) { Text("Read playback") }
                        }
                        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(clockTime(seek?.toDouble() ?: pendingSeek?.position ?: r.position), style=MaterialTheme.typography.labelMedium)
                            Slider(value=seek ?: (pendingSeek?.position ?: r.position ?: 0.0).toFloat().coerceIn(0f,duration.toFloat().coerceAtLeast(1f)), onValueChange={seek=it}, onValueChangeFinished={seek?.let {store.seek(it.toDouble())};seek=null}, valueRange=0f..duration.toFloat().coerceAtLeast(1f), enabled=r.connected && duration>0 && !s.busy && !s.connecting && (pendingSeek == null || pendingSeek.failed), colors=SliderDefaults.colors(thumbColor=seekTint, activeTrackColor=seekTint, disabledThumbColor=seekTint, disabledActiveTrackColor=seekTint), modifier=Modifier.weight(1f))
                            Text(clockTime(r.duration), style=MaterialTheme.typography.labelMedium)
                            var volume by remember(s.target) { mutableStateOf<Float?>(null) }
                            val pending = ui.mainVolume
                            val tint = if(pending?.failed == true) colors.error else if(pending != null) colors.secondary else colors.primary
                            Text(if(pending?.failed == true) "Not confirmed" else if(pending != null) "Syncing…" else "Volume", style=MaterialTheme.typography.labelMedium)
                            Slider(value=volume ?: pending?.value?.toFloat() ?: (r.volume ?: 0)/100f,
                                onValueChange={volume=it}, onValueChangeFinished={volume?.let {store.mainVolume(it.toDouble())};volume=null},
                                enabled=r.connected && !s.busy && !s.connecting && (pending == null || pending.failed),
                                colors=SliderDefaults.colors(thumbColor=tint, activeTrackColor=tint, disabledThumbColor=tint, disabledActiveTrackColor=tint),
                                modifier=Modifier.width(100.dp))
                            if(pending?.failed == true) TextButton(onClick={store.dismissMainVolume()}) { Text("Read speaker") }

                        }
                    }
                }
                val error = ui.error ?: s.message.takeIf { it.startsWith("Failed") }
                if(error != null) Text(error, color=colors.error, maxLines=2, style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
