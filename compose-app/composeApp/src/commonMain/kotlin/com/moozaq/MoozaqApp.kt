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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import com.moozaq.resources.*

/** Build shown beside the wordmark, e.g. "v0.6.1 (20)"; null hides it. */
val LocalBuildLabel = staticCompositionLocalOf<String?> { null }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun MoozaqApp(store: PlayerStore, buildLabel: String? = null, initialChannelsOpen: Boolean = false, initialSearchQuery: String = "") = CompositionLocalProvider(LocalBuildLabel provides buildLabel) {
    val ui by store.state.collectAsState()
    val library=remember(ui.library.mixes) { mergedCatalog(ui.library.mixes) }
    var manualReducedMotion by remember { mutableStateOf(false) }
    MoozaqTheme(manualReducedMotion || systemReducedMotion()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            if(maxWidth < 600.dp) PhoneApp(store,ui,library,manualReducedMotion) { manualReducedMotion=it }
            else DesktopApp(store,ui,library,manualReducedMotion,initialChannelsOpen,initialSearchQuery) { manualReducedMotion=it }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun DesktopApp(store:PlayerStore,ui:PlayerUiState,library:List<Mix>,reducedMotion:Boolean,initialChannelsOpen:Boolean,initialSearchQuery:String,onReducedMotion:(Boolean)->Unit) {
    val browseMixes=ui.discovery?.catalog?.mixes ?: library
    val eligible=remember(browseMixes) { browseMixes.filter { (it.duration ?: 0.0)>=3600.0 } }
    var query by remember { mutableStateOf(initialSearchQuery) }
    val moods=remember(eligible,query) { searchMixes(eligible,query).groupBy { it.category }.toSortedMap() }
    val discovery=ui.discovery
    val sections=if(discovery==null) emptyList() else visibleDiscoverySections(discovery,query)
    var speakersOpen by remember { mutableStateOf(false) }
    var channelsOpen by remember { mutableStateOf(initialChannelsOpen) }
    val browse=rememberLazyListState()
    val c=MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(),color=c.background) {
        Column(Modifier.padding(24.dp).blur(if(channelsOpen || speakersOpen) 8.dp else 0.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                Image(painterResource(Res.drawable.wordmark),"Moozaq",Modifier.width(220.dp).height(56.dp),contentScale=ContentScale.Fit)
                Text("Find your mood. Let it run.",Modifier.weight(1f),color=c.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge,maxLines=1)
                SearchField(query,{query=it})
                IconButton(onClick={store.refreshDiscovery()},enabled=ui.catalogAction==null,modifier=Modifier.semantics { contentDescription="Refresh discoveries" }) {
                    UiIcon(Res.drawable.ui_refresh,"Refresh discoveries",Modifier.size(20.dp))
                }
                TextButton(onClick={channelsOpen=true}) { Text("Channels") }
            }
            LaunchedEffect(query) { if(browse.layoutInfo.totalItemsCount>0) browse.scrollToItem(0) }
            if(eligible.isEmpty()) Text("Your hour-long mixes will appear here.",color=c.onSurfaceVariant)
            else if(moods.isEmpty() && sections.isEmpty()) Text("No matches",color=c.onSurfaceVariant)
            LazyColumn(state=browse,modifier=Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(sections.isNotEmpty()) {
                    stickyHeader(key="heading:discovery") {
                        Text("Discover",style=MaterialTheme.typography.titleLarge,modifier=Modifier.fillMaxWidth().background(c.surface).padding(top=8.dp,bottom=8.dp))
                    }
                    item(key="shelf:discovery") {
                        val cards=discoveryCards(sections)
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.padding(bottom=8.dp)) {
                            items(cards,key={"${it.section.kind}:${it.mix.id}"}) { card ->
                                DiscoveryMixCard(card.mix,ui,store,card.section.title,discovery?.reasons?.get(card.mix.id))
                            }
                        }
                    }
                }
                moods.forEach { (name,mixes) ->
                    stickyHeader(key="heading:mood:$name") {
                        Text(name,style=MaterialTheme.typography.titleLarge,modifier=Modifier.fillMaxWidth().background(c.surface).padding(top=8.dp,bottom=8.dp))
                    }
                    item(key="shelf:mood:$name") {
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.padding(bottom=8.dp)) {
                            items(mixes,key={it.id}) { MixCard(it,ui,store,false) }
                        }
                    }
                }
            }
            PlayerBar(store,ui,library,speakersOpen,{speakersOpen=it})
            val error=ui.error ?: ui.snapshot.message.takeIf { it.startsWith("Failed") || it.startsWith("Stop unconfirmed") }
            if(error!=null) Text(error,color=c.error,maxLines=2,style=MaterialTheme.typography.labelMedium)
            if(ui.catalogError!=null) Text(ui.catalogError,color=c.error,maxLines=2,style=MaterialTheme.typography.labelMedium)
        }
        if(speakersOpen) Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha=.45f)).clickable(interactionSource=remember {MutableInteractionSource()},indication=null) {speakersOpen=false})
        if(channelsOpen) ChannelsDialog(store,ui,reducedMotion,onReducedMotion,{channelsOpen=false})
    }
}

@Composable private fun DiscoveryMixCard(mix:Mix,ui:PlayerUiState,store:PlayerStore,label:String,reason:String?) {
    Column(Modifier.width(240.dp)) {
        Text(label,Modifier.padding(start=12.dp,bottom=4.dp),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium,maxLines=1)
        MixCard(mix,ui,store,false)
        if(reason!=null) Text(reason,Modifier.padding(start=12.dp,top=4.dp,end=12.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
    }
}

@Composable private fun SearchField(query:String,onQuery:(String)->Unit) {
    OutlinedTextField(query,onQuery,
        Modifier.width(360.dp).onPreviewKeyEvent { if(it.key==Key.Escape && it.type==KeyEventType.KeyDown && query.isNotEmpty()) { onQuery("");true } else false },
        placeholder={Text("Search")},singleLine=true,shape=RoundedCornerShape(28.dp),
        leadingIcon={UiIcon(Res.drawable.ui_search,modifier=Modifier.size(20.dp))},
        trailingIcon={ if(query.isNotEmpty()) IconButton(onClick={onQuery("")}) { UiIcon(Res.drawable.ui_close,"Clear search",Modifier.size(20.dp)) } })
}
