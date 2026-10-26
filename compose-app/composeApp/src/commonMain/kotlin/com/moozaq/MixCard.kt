package com.moozaq

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moozaq.resources.*

@Composable fun MixCard(mix:Mix,ui:PlayerUiState,store:PlayerStore,compact:Boolean=false) {
    val c=MaterialTheme.colorScheme
    val hover=remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val active=ui.snapshot.receiver.source==mix.url && ui.snapshot.receiver.state in listOf("PLAYING","PAUSED","BUFFERING")
    val paused=active && ui.snapshot.receiver.state=="PAUSED"
    val starting=ui.transport?.source==mix.url && ui.transport?.failed==false
    val selected=ui.previewId==mix.id
    val revealed=hovered || focused || selected || starting || compact
    val reduced=LocalReducedMotion.current
    val scale by animateFloatAsState(if(revealed && !reduced) 1.04f else 1f,tween(if(reduced) 0 else 300))
    val reveal by animateFloatAsState(if(revealed) 1f else 0f,tween(if(reduced) 0 else 160))
    val scrim by animateFloatAsState(if(revealed || active) .35f else 0f,tween(if(reduced) 0 else 200))
    val shape=RoundedCornerShape(12.dp)
    Card(onClick={store.preview(mix.id)},shape=shape,
        modifier=Modifier.width(if(compact) 200.dp else 240.dp).onFocusChanged { focused=it.hasFocus }.hoverable(hover)
            .then(if(active || focused) Modifier.border(2.dp,c.primary,shape) else Modifier),
        colors=CardDefaults.cardColors(containerColor=if(selected) c.surfaceContainerHighest else if(revealed) c.surfaceContainerHigh else c.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().height(if(compact) 113.dp else 135.dp).clip(RoundedCornerShape(topStart=12.dp,topEnd=12.dp))) {
            MixCover(mix,Modifier.fillMaxSize().graphicsLayer { scaleX=scale;scaleY=scale })
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=scrim)))
            if(revealed || active) Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                RatingControls(mix,ui,store)
            }
            if(active) Surface(Modifier.align(Alignment.TopStart).padding(10.dp),shape=RoundedCornerShape(12.dp),color=c.surface.copy(alpha=.85f)) {
                Row(Modifier.height(24.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Equalizer(paused || reduced)
                    Text(if(paused) "Paused" else "Now playing",style=MaterialTheme.typography.labelMedium,color=c.primary)
                }
            }
            val awaiting=ui.transport!=null && ui.transport?.failed==false
            val canPlay=if(ui.speakerDraft!=null) canRequestDraftPlay(ui,mix) else canRequestPlay(ui.snapshot,mix)
            val enabled=if(active) !awaiting && (canPause(ui.snapshot)||canResume(ui.snapshot)) else ui.destination==null && canPlay
            FilledIconButton(onClick={if(active) store.command(if(paused) "resume" else "pause") else if(ui.speakerDraft!=null) store.playDraft(mix) else store.play(mix)},enabled=enabled,
                modifier=Modifier.align(Alignment.BottomEnd).padding(10.dp).size(48.dp)
                    .graphicsLayer { alpha=reveal;translationY=6.dp.toPx()*(1-reveal);scaleX=.9f+.1f*reveal;scaleY=scaleX }
                    .semantics { contentDescription=if(starting) "Starting ${mix.title}" else if(active && !paused) "Pause ${mix.title}" else "Play ${mix.title}" },
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=c.primary,contentColor=c.onPrimary,disabledContainerColor=c.onSurfaceVariant,disabledContentColor=c.surface)) {
                if(starting) CircularProgressIndicator(Modifier.size(22.dp),color=c.onPrimary,strokeWidth=2.dp)
                else UiIcon(if(active && !paused) Res.drawable.ui_pause else Res.drawable.ui_play_arrow,modifier=Modifier.size(22.dp))
            }
        }
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(mix.title,style=MaterialTheme.typography.titleMedium,maxLines=2,minLines=2,overflow=TextOverflow.Ellipsis)
            Text(mix.creator,style=MaterialTheme.typography.bodyLarge,color=c.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun Equalizer(still:Boolean) {
    val height=if(still) .6f else {
        val transition=rememberInfiniteTransition()
        val animated by transition.animateFloat(.3f,1f,infiniteRepeatable(tween(900,easing=FastOutSlowInEasing),RepeatMode.Reverse))
        animated
    }
    val c=MaterialTheme.colorScheme.primary
    Row(Modifier.size(16.dp),horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.Bottom) {
        listOf(.6f,1f,.4f).forEachIndexed { i,v ->
            Box(Modifier.width(3.dp).fillMaxHeight(if(still) v else if(i==1) height else (1.3f-height).coerceIn(.3f,1f)*v).background(c))
        }
    }
}
