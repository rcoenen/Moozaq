package com.moozaq

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moozaq.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ChannelsDialog(store:PlayerStore,ui:PlayerUiState,reducedMotion:Boolean,onReducedMotion:(Boolean)->Unit,onClose:()->Unit) {
    val c=MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var debug by remember { mutableStateOf(false) }
    val s=ui.snapshot
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.padding(12.dp).widthIn(max=560.dp).fillMaxWidth().heightIn(max=678.dp),shape=RoundedCornerShape(28.dp),color=c.surfaceContainer) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Your channels",style=MaterialTheme.typography.headlineSmall)
                Text(if(s.importing) "Refreshing ${s.refreshing_channels.size} channels…" else "${ui.library.channels.size} channels · ${if(ui.library.channels.any {it.error!=null}) "some imports need attention" else "catalog up to date"}",style=MaterialTheme.typography.bodyMedium,color=c.onSurfaceVariant)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ui.library.channels.forEach { channel ->
                        val busy=channel.url in s.refreshing_channels
                        Row(Modifier.fillMaxWidth().height(52.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(channel.name,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(if(busy) "Refreshing…" else channel.error ?: "${channel.count} videos · ${channel.refreshed?.take(10) ?: "Not imported"}",style=MaterialTheme.typography.labelMedium,color=if(channel.error!=null) c.error else c.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            IconButton(onClick={store.importChannel(channel.url)},enabled=!s.importing,modifier=Modifier.size(40.dp).semantics {contentDescription="Refresh ${channel.name}"}) {
                                if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else UiIcon(Res.drawable.ui_refresh,modifier=Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if(adding) {
                    OutlinedTextField(input,{input=it},Modifier.fillMaxWidth(),placeholder={Text("YouTube channel link")},singleLine=true)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Only mixes of an hour or longer show up. Importing never starts playback.",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=c.onSurfaceVariant)
                        Button(onClick={store.importChannel(input.trim());input="";adding=false},enabled=input.isNotBlank()&&!s.importing) {Text("Import")}
                    }
                }
                if(debug) {
                    Text(s.message,style=MaterialTheme.typography.labelSmall,maxLines=3,color=c.onSurfaceVariant)
                    Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(reducedMotion,onReducedMotion);Text("Reduce motion",style=MaterialTheme.typography.bodyMedium) }
                }
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={debug=!debug},contentPadding=PaddingValues(4.dp)) {Text("Debug info",color=c.outline,style=MaterialTheme.typography.labelMedium)}
                    TextButton(onClick={adding=!adding}) {Text(if(adding) "Cancel" else "Add channel")}
                    TextButton(onClick={store.importChannel()},enabled=!s.importing) {Text("Refresh all")}
                    Button(onClick=onClose) {Text("Done")}
                }
            }
        }
    }
}
