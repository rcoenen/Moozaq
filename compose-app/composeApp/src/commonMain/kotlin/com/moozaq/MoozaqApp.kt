package com.moozaq

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import com.moozaq.resources.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun MoozaqApp(store: PlayerStore) {
    val ui by store.state.collectAsState()
    val library=remember(ui.library.mixes) { mergedCatalog(ui.library.mixes) }
    var manualReducedMotion by remember { mutableStateOf(false) }
    MoozaqTheme(manualReducedMotion || systemReducedMotion()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            if(maxWidth < 600.dp) PhoneApp(store,ui,library,manualReducedMotion) { manualReducedMotion=it }
            else DesktopApp(store,ui,library,manualReducedMotion) { manualReducedMotion=it }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun DesktopApp(store:PlayerStore,ui:PlayerUiState,library:List<Mix>,reducedMotion:Boolean,onReducedMotion:(Boolean)->Unit) {
    val eligible=remember(library) { library.filter { (it.duration ?: 0.0)>=3600.0 } }
    val moods=remember(eligible) { eligible.groupBy { it.category }.toSortedMap() }
    var speakersOpen by remember { mutableStateOf(false) }
    var channelsOpen by remember { mutableStateOf(false) }
    val browse=rememberLazyListState()
    val c=MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(),color=c.background) {
        Column(Modifier.padding(24.dp).blur(if(channelsOpen || speakersOpen) 8.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Image(painterResource(Res.drawable.wordmark),"Moozaq",Modifier.width(220.dp).height(56.dp),contentScale=ContentScale.Fit)
                Text("Find your mood. Let it run.",Modifier.weight(1f),color=c.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge,maxLines=1)
                TextButton(onClick={channelsOpen=true}) { Text("Channels") }
            }
            if(eligible.isEmpty()) Text("Your hour-long mixes will appear here.",color=c.onSurfaceVariant)
            LazyColumn(state=browse,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                moods.forEach { (name,mixes) ->
                    stickyHeader(key="heading:$name") {
                        Text(name,style=MaterialTheme.typography.titleLarge,modifier=Modifier.fillMaxWidth().background(c.surface).padding(top=8.dp,bottom=8.dp))
                    }
                    item(key="shelf:$name") {
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.padding(bottom=8.dp)) {
                            items(mixes,key={it.id}) { MixCard(it,ui,store,false) }
                        }
                    }
                }
            }
            PlayerBar(store,ui,library,speakersOpen,{speakersOpen=it})
            val error=ui.error ?: ui.snapshot.message.takeIf { it.startsWith("Failed") || it.startsWith("Stop unconfirmed") }
            if(error!=null) Text(error,color=c.error,maxLines=2,style=MaterialTheme.typography.labelMedium)
        }
        if(speakersOpen) Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha=.45f)).clickable(interactionSource=remember {MutableInteractionSource()},indication=null) {speakersOpen=false})
        if(channelsOpen) ChannelsDialog(store,ui,reducedMotion,onReducedMotion,{channelsOpen=false})
    }
}
