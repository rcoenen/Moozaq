package com.moozaq

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import com.moozaq.resources.*

enum class DeviceKind(val label: String) {
    GROUP("Speaker group"), SPEAKER("Speaker"), TV("TV / Cast display"), DISPLAY("Smart display"), CAST("Cast device")
}

fun deviceKind(device: Device): DeviceKind {
    val model = device.model.lowercase()
    return when {
        device.cast_type == "group" || model == "google cast group" -> DeviceKind.GROUP
        model.contains("nest hub") || model.contains("home hub") -> DeviceKind.DISPLAY
        device.cast_type == "audio" || model in listOf("nest audio", "google nest audio", "google home", "google home mini", "google home max", "google nest mini", "chromecast audio") -> DeviceKind.SPEAKER
        device.cast_type == "cast" -> DeviceKind.TV
        else -> DeviceKind.CAST
    }
}

@Composable
fun DeviceIcon(device: Device, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val kind = deviceKind(device)
    val resource = when(kind) {
        DeviceKind.GROUP -> Res.drawable.device_speaker_group
        DeviceKind.SPEAKER -> Res.drawable.device_speaker
        DeviceKind.TV -> Res.drawable.device_tv
        DeviceKind.DISPLAY -> Res.drawable.device_smart_display
        DeviceKind.CAST -> Res.drawable.device_cast
    }
    Icon(painterResource(resource), kind.label, Modifier.size(24.dp), tint=tint)
}
