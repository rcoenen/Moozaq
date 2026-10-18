package com.moozaq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moozaq.resources.*

internal fun mobilePlaybackTitle(active:Boolean,connecting:Boolean,title:String?)=
    if(active || connecting) title ?: "Connecting…" else "Nothing playing"
internal fun lastPlayedLabel(receiver:Receiver?)=receiver?.title?.let {"Last played: $it"} ?: "Last played"
internal fun pendingTransportMix(transport:PendingTransport?,library:List<Mix>)=
    transport?.takeIf {!it.failed && it.expected=="PLAYING"}?.source?.let {source->library.find {it.url==source}}

@Composable fun PlayerBar(store:PlayerStore,ui:PlayerUiState,library:List<Mix>,speakersOpen:Boolean,onSpeakersOpen:(Boolean)->Unit) {
    val s=ui.snapshot; val r=presentedReceiver(ui); val c=MaterialTheme.colorScheme
    val transport=ui.transport
    val awaiting=transport!=null && !transport.failed
    val playing=r.state in listOf("PLAYING","PAUSED","BUFFERING","EXPIRED") || awaiting || (r.recovery!=null && !r.title.isNullOrBlank())
    val pendingMix=pendingTransportMix(transport,library)
    val known=if(playing || s.connecting) r else ui.lastPlayed
    val mix=pendingMix ?: library.find {it.url==known?.source}
    val hasLast=ui.lastPlayed!=null
    Surface(Modifier.fillMaxWidth().height(136.dp),shape=RoundedCornerShape(20.dp),color=c.surfaceContainer) {
        Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceContainerHigh),contentAlignment=Alignment.Center) {
                if(mix!=null) MixCover(mix,Modifier.fillMaxSize().alpha(if(!playing&&!s.connecting) .7f else 1f))
                else UiIcon(Res.drawable.ui_music_note,modifier=Modifier.size(36.dp),tint=c.onSurfaceVariant)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(playing) pendingMix?.title ?: r.title ?: ui.lastPlayed?.title ?: "Connecting…" else if(s.connecting) "Connecting…" else "Nothing playing",
                    style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                val status=when {
                    ui.pendingSeek?.failed==true -> "Seek not confirmed"
                    ui.pendingSeek!=null -> "Seeking…"
                    transport?.failed==true -> "Command not confirmed"
                    pendingMix!=null -> "Starting…"
                    r.recovery!=null -> r.recovery
                    awaiting -> transport?.previous?.lowercase()?.replaceFirstChar {it.uppercase()} ?: "Starting…"
                    playing -> playbackLabel(s)
                    hasLast -> "Last played: ${ui.lastPlayed?.title}"
                    else -> "Pick a mix above to start playing${r.device?.let {" on $it"} ?: ""}"
                }
                Text(status + if(playing && mix!=null && ui.pendingSeek==null && !awaiting) " · ${mix.creator}" else "",style=MaterialTheme.typography.bodyMedium,color=if(transport?.failed==true || ui.pendingSeek?.failed==true || (r.state=="EXPIRED" && !awaiting)) c.error else c.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(playing && pendingMix==null) SeekControl(store,ui.copy(snapshot=s.copy(receiver=r)))
                else Spacer(Modifier.height(20.dp))
            }
            Column(Modifier.width(440.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                SpeakerPicker(store,ui,speakersOpen,onSpeakersOpen)
                if(playing) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    MainVolume(store,ui.copy(snapshot=s.copy(receiver=r)))
                    val stopping=awaiting && transport?.expected=="IDLE"
                    OutlinedButton(onClick={store.command("stop")},enabled=!stopping && (s.target!=null || s.busy),modifier=Modifier.height(40.dp),contentPadding=PaddingValues(horizontal=14.dp)) {
                        Text(if(stopping) "Stopping…" else "Stop casting")
                    }
                    Button(onClick={store.command(if(canPause(s)) "pause" else "resume")},
                        enabled=!awaiting && (canPause(s)||canResume(s)),modifier=Modifier.widthIn(min=96.dp).height(40.dp),contentPadding=PaddingValues(horizontal=16.dp),
                        colors=ButtonDefaults.buttonColors(disabledContainerColor=if(awaiting) c.onSurfaceVariant else c.onSurface.copy(alpha=.12f),disabledContentColor=if(awaiting) c.surface else c.onSurface.copy(alpha=.38f))) {
                        Text(if(awaiting) when(transport?.expected) {"PAUSED"->"Pausing…";"IDLE"->"Stopping…";else->if(r.source_expired || r.refresh_needed) "Refreshing…" else "Starting…"} else if(!r.connected || s.connecting) "Syncing…" else if(r.state=="PLAYING") "Pause" else "Resume")
                    }
                } else if(hasLast && mix!=null) Button(onClick={store.play(mix)},enabled=ui.destination==null&&canRequestPlay(s,mix),modifier=Modifier.height(40.dp)) {
                    UiIcon(Res.drawable.ui_play_arrow,modifier=Modifier.size(22.dp));Spacer(Modifier.width(6.dp));Text("Play again")
                }
            }
        }
    }
}
@Composable private fun SeekControl(store:PlayerStore,ui:PlayerUiState) {
    val s=ui.snapshot;val r=s.receiver;val request=ui.pendingSeek
    val duration=(r.duration?:0.0).coerceAtLeast(0.0)
    var drag by remember(s.target) { mutableStateOf<Float?>(null) }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(clockTime(drag?.toDouble() ?: request?.position ?: r.position),style=MaterialTheme.typography.labelMedium)
        Slider(value=drag ?: (request?.position ?: r.position ?: 0.0).toFloat().coerceIn(0f,duration.toFloat().coerceAtLeast(1f)),
            onValueChange={drag=it},onValueChangeFinished={drag?.let {store.seek(it.toDouble())};drag=null},valueRange=0f..duration.toFloat().coerceAtLeast(1f),
            enabled=r.connected&&r.state!="EXPIRED"&&duration>0&&!s.busy&&!s.connecting&&(request==null||request.failed),colors=pendingSliderColors(request!=null,request?.failed==true),
            modifier=Modifier.weight(1f).height(44.dp).semantics {contentDescription="Playback position"})
        Text(clockTime(r.duration),style=MaterialTheme.typography.labelMedium)
        if(request?.failed==true) TextButton(onClick={store.dismissSeek()},contentPadding=PaddingValues(0.dp)) {Text("Read")}
    }
}
@Composable private fun MainVolume(store:PlayerStore,ui:PlayerUiState,width:androidx.compose.ui.unit.Dp=112.dp) {
    val s=ui.snapshot;val request=ui.mainVolume
    var drag by remember(s.target) { mutableStateOf<Float?>(null) }
    UiIcon(Res.drawable.ui_volume_up,if(request?.failed==true) "Volume not confirmed" else if(request!=null) "Syncing volume" else "Volume",modifier=Modifier.size(20.dp),tint=if(request?.failed==true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.width(width).height(60.dp)) {
    Slider(value=drag ?: request?.value?.toFloat() ?: (s.receiver.volume?:0)/100f,onValueChange={drag=it},
        onValueChangeFinished={drag?.let {store.mainVolume(it.toDouble())};drag=null},
        enabled=s.receiver.connected&&!s.busy&&!s.connecting&&!membershipPending(ui)&&(request==null||request.failed),colors=pendingSliderColors(request!=null,request?.failed==true),
        modifier=Modifier.width(width).height(44.dp).semantics {contentDescription=if(request?.failed==true) "Volume not confirmed; adjust to retry" else if(request!=null) "Volume pending" else "Volume"})
    Text(if(request?.failed==true) "Not confirmed" else if(request!=null) "Syncing…" else "",style=MaterialTheme.typography.labelMedium,color=if(request?.failed==true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
