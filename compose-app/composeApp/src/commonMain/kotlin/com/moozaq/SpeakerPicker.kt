package com.moozaq

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.moozaq.resources.*

private class AboveCastChip(private val gap:Int): PopupPositionProvider {
    override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset =
        IntOffset((anchorBounds.right-popupContentSize.width).coerceIn(8,(windowSize.width-popupContentSize.width-8).coerceAtLeast(8)),
            (anchorBounds.top-popupContentSize.height-gap).coerceAtLeast(8))
}
@Composable fun SpeakerPicker(store:PlayerStore,ui:PlayerUiState,open:Boolean,onOpenChange:(Boolean)->Unit,compact:Boolean=false) {
    val s=ui.snapshot;val r=s.receiver;val c=MaterialTheme.colorScheme
    val target=s.devices.find {it.id==s.target}
    val name=if(target?.temporary==true) "${requestedMembers(ui).size.coerceAtLeast(2)} speakers" else target?.name ?: r.device ?: "Choose speakers"
    val casting=r.connected && r.state in listOf("PLAYING","PAUSED","BUFFERING")
    val connection=if(s.connecting) "Connecting" else if(r.connected) "Connected" else "Disconnected"
    val density=androidx.compose.ui.platform.LocalDensity.current
    val windowSize=androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val popupWidth=with(density) {windowSize.width.toDp()-24.dp}.coerceAtMost(480.dp)
    val popupHeight=with(density) {windowSize.height.toDp()-80.dp}.coerceAtMost(600.dp)
    val chipFocus=remember {FocusRequester()}
    var wasOpen by remember {mutableStateOf(false)}
    LaunchedEffect(open) {
        if(!open && wasOpen) chipFocus.requestFocus()
        wasOpen=open
    }
    Box {
        OutlinedButton(onClick={onOpenChange(true)},modifier=Modifier.focusRequester(chipFocus).height(40.dp).widthIn(max=400.dp).semantics {contentDescription="Cast to $name · $connection"},
            border=BorderStroke(1.dp,c.outlineVariant),contentPadding=PaddingValues(horizontal=12.dp)) {
            if(s.connecting) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=c.onSurfaceVariant)
            else UiIcon(if(casting) Res.drawable.ui_cast_connected else Res.drawable.ui_cast,modifier=Modifier.size(20.dp),tint=if(casting) c.tertiary else c.onSurfaceVariant)
            Spacer(Modifier.width(8.dp));if(!compact) Text(if(casting) "Casting to " else "Cast to ",color=c.onSurfaceVariant)
            Text(name,color=c.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false))
            Spacer(Modifier.width(6.dp));UiIcon(Res.drawable.ui_expand_more,modifier=Modifier.size(18.dp),tint=c.onSurfaceVariant)
        }
        if(open) Popup(popupPositionProvider=remember(density) {AboveCastChip(with(density) {12.dp.roundToPx()})},onDismissRequest={onOpenChange(false)},properties=PopupProperties(focusable=true)) {
            val closeFocus=remember {FocusRequester()}
            LaunchedEffect(Unit) {closeFocus.requestFocus()}
            Column(horizontalAlignment=Alignment.End,modifier=Modifier.onPreviewKeyEvent {
                if(it.key==Key.Escape && it.type==KeyEventType.KeyDown) {onOpenChange(false);true} else false
            }) {
                Surface(Modifier.width(popupWidth).height(popupHeight),shape=RoundedCornerShape(24.dp),color=c.surfaceContainer,shadowElevation=24.dp) {
                    Column(Modifier.padding(start=16.dp,end=12.dp,top=12.dp,bottom=12.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("Cast to",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                            IconButton(onClick={onOpenChange(false)},modifier=Modifier.focusRequester(closeFocus).size(40.dp)) {UiIcon(Res.drawable.ui_close,"Close")}
                        }
                        val devices=pickerDevices(s)
                        Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState())) {
                            devices.forEach {device -> key(device.id) {
                                SpeakerRow(store,ui,device,compact)
                            } }
                            if(devices.isEmpty()) Text("Looking for speakers…",Modifier.padding(16.dp),color=c.onSurfaceVariant)
                        }
                        val failure=membershipError(ui)
                        if(failure!=null) {
                            Text(failure,style=MaterialTheme.typography.labelMedium,color=c.error,maxLines=2)
                            TextButton(onClick={store.dismissMembership()}) {Text("Read speakers")}
                        }
                        if(ui.destination?.failed==true) Text("Connection not confirmed. Select the destination to retry.",style=MaterialTheme.typography.labelMedium,color=c.error)
                    }
                }
                Canvas(Modifier.padding(end=122.dp).size(16.dp,8.dp)) {
                    drawPath(androidx.compose.ui.graphics.Path().apply {moveTo(0f,0f);lineTo(size.width,0f);lineTo(size.width/2,size.height);close()},c.surfaceContainer)
                }
            }
        }
    }
}
@Composable private fun SpeakerRow(store:PlayerStore,ui:PlayerUiState,device:Device,compact:Boolean) {
    val s=ui.snapshot;val c=MaterialTheme.colorScheme
    val output=s.outputs.find {it.id==device.id}
    val selected=s.target==device.id
    val destination=usesDestinationCheckbox(ui,device) || deviceKind(device) in listOf(DeviceKind.TV,DeviceKind.DISPLAY,DeviceKind.CAST)
    val request=ui.destination?.takeIf {it.id==device.id}
    val checked=if(destination) destinationChecked(ui,device.id) else device.id in requestedMembers(ui)
    val changingMember=membershipPending(ui) && checked!=(device.id in s.membership.members)
    val stopping=checked && ui.transport?.expected=="IDLE"
    val pending=request!=null || (selected&&s.connecting) || changingMember || stopping
    val failed=request?.failed==true || (changingMember&&membershipError(ui)!=null) || (stopping&&ui.transport?.failed==true)
    val tint=if(failed) c.error else if(pending) c.onSurfaceVariant else c.primary
    val foreground=if(checked) c.onSurface else c.onSurfaceVariant
    val groupPlaying=deviceKind(device)==DeviceKind.GROUP && selected && s.receiver.state in listOf("PLAYING","PAUSED","BUFFERING")
    val enabled=if(groupPlaying) s.receiver.connected&&!s.busy&&!s.connecting&&!membershipPending(ui)
        else if(destination) canSelectDestination(ui,device) else canToggleMembership(ui,device)
    val volume=if(selected) ui.mainVolume else ui.outputVolumes[device.id]
    val detail=when {
        !device.available -> "Unavailable"
        failed -> "Not confirmed"
        pending -> "Pending confirmation"
        volume?.failed==true -> "Volume not confirmed"
        volume!=null -> "Syncing volume…"
        destination&&checked -> "Selected destination"
        else -> outputActivity(output,s)
    }
    Row(Modifier.fillMaxWidth().height(52.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Checkbox(checked,onCheckedChange={
            if(groupPlaying) store.command("stop")
            else if(destination) store.selectDevice(device.id)
            else store.togglePlaybackDevice(device)
        },enabled=enabled,modifier=Modifier.size(40.dp).semantics {contentDescription="Cast to ${device.name}";stateDescription=detail},
            colors=CheckboxDefaults.colors(checkedColor=tint,disabledCheckedColor=tint,uncheckedColor=c.onSurfaceVariant,disabledUncheckedColor=c.outline,checkmarkColor=c.onPrimary))
        DeviceIcon(device,tint=foreground)
        Column(Modifier.width(if(compact) 112.dp else 148.dp).clickable(enabled=canSelectDestination(ui,device),onClickLabel="Control ${device.name}") {store.selectDevice(output?.control_id?:device.id)}) {
            Text(device.name,style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis,color=foreground)
            if(failed||pending||!device.available||volume!=null) Text(detail,style=MaterialTheme.typography.labelMedium,color=if(failed||volume?.failed==true) c.error else c.onSurfaceVariant,maxLines=1)
        }
        val level=if(selected) s.receiver.volume?.div(100.0) else output?.volume
        var drag by remember {mutableStateOf<Float?>(null)}
        Slider(value=drag ?: (volume?.value?:level?:0.0).toFloat().coerceIn(0f,1f),onValueChange={drag=it},
            onValueChangeFinished={drag?.let {if(selected) store.mainVolume(it.toDouble()) else store.outputVolume(device.id,it.toDouble())};drag=null},
            enabled=canAdjustOutputVolume(s,output,selected,volume)&&!(selected&&membershipPending(ui)),colors=pendingSliderColors(volume!=null,volume?.failed==true),
            modifier=Modifier.weight(1f).height(44.dp).semantics {contentDescription="${device.name} volume";stateDescription=if(volume?.failed==true) "Not confirmed; adjust to retry" else if(volume!=null) "Pending confirmation" else if(level==null) "Unavailable" else "Confirmed"})
    }
}
