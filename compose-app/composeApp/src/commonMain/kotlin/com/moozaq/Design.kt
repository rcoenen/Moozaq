package com.moozaq

import androidx.compose.animation.core.FiniteAnimationSpec

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import com.moozaq.resources.*

val LocalReducedMotion = compositionLocalOf { false }
val MoozaqColors = darkColorScheme(
    primary=Color(0xFFFFB872), onPrimary=Color(0xFF4A2800),
    primaryContainer=Color(0xFF6A3C00), onPrimaryContainer=Color(0xFFFFDCBE),
    secondary=Color(0xFFBFC7D9), secondaryContainer=Color(0xFF59422D), onSecondaryContainer=Color(0xFFFFDCBF),
    tertiary=Color(0xFFA2D2A3), background=Color(0xFF10131A), onBackground=Color(0xFFE0E2EC),
    surface=Color(0xFF10131A), surfaceContainerLowest=Color(0xFF0B0E15), surfaceContainerLow=Color(0xFF181C22),
    surfaceContainer=Color(0xFF1C2026), surfaceContainerHigh=Color(0xFF272A31), surfaceContainerHighest=Color(0xFF31353C),
    onSurface=Color(0xFFE0E2EC), onSurfaceVariant=Color(0xFFBFC7D9),
    outline=Color(0xFF8991A2), outlineVariant=Color(0xFF3F4756), error=Color(0xFFFFB4AB), scrim=Color.Black,
)
/**
 * M3 Expressive motion physics as Material publishes the spring tokens: spatial springs overshoot
 * slightly for bounce; effects springs (colour, opacity) settle without it. Compose's own
 * MotionScheme API is internal in this release, so Moozaq's custom components read these directly.
 * Reduced motion makes every spring critically damped.
 */
class ExpressiveMotion(private val reduced: Boolean) {
    private fun <T> spring(damping: Float, stiffness: Float): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.spring(if (reduced) 1f else damping, stiffness)
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(.8f, 380f)
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(.6f, 800f)
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(.8f, 200f)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(1f, 1600f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(1f, 3800f)
}
val LocalExpressiveMotion = staticCompositionLocalOf { ExpressiveMotion(reduced = false) }

@Composable fun MoozaqTheme(reducedMotion: Boolean, content: @Composable () -> Unit) {
    val family=FontFamily(Font(Res.font.roboto_flex))
    fun style(size:Int,line:Int,weight:FontWeight,spacing:Double=0.0)=TextStyle(fontFamily=family,fontSize=size.sp,lineHeight=line.sp,fontWeight=weight,letterSpacing=spacing.sp)
    MaterialTheme(colorScheme=MoozaqColors, typography=Typography(
        titleLarge=style(22,28,FontWeight.Normal), headlineSmall=style(24,32,FontWeight.Normal),
        titleMedium=style(16,24,FontWeight.SemiBold,.15), titleSmall=style(14,20,FontWeight.SemiBold,.1),
        bodyLarge=style(16,24,FontWeight.Normal,.5), bodyMedium=style(14,20,FontWeight.Normal,.25),
        bodySmall=style(12,16,FontWeight.Normal,.4),
        labelLarge=style(14,20,FontWeight.Medium,.1), labelMedium=style(12,16,FontWeight.Medium,.5),
        labelSmall=style(12,16,FontWeight.Medium,.5),
    )) { CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalExpressiveMotion provides remember(reducedMotion) { ExpressiveMotion(reducedMotion) }, content=content) }
}

/** Now playing title: titleLarge at 600 per the handoff type table. */
@Composable fun playerTitleStyle(): TextStyle = MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.SemiBold)

@Composable fun UiIcon(resource: DrawableResource, description:String?=null, modifier:Modifier=Modifier.size(24.dp), tint:Color=LocalContentColor.current) {
    Icon(painterResource(resource),description,modifier,tint=tint)
}
@Composable fun pendingSliderColors(pending:Boolean, failed:Boolean=false): SliderColors {
    val c=MaterialTheme.colorScheme
    val tint=if(failed) c.error else if(pending) c.onSurfaceVariant else c.primary
    return SliderDefaults.colors(thumbColor=tint,activeTrackColor=tint,inactiveTrackColor=c.secondaryContainer,
        disabledThumbColor=if(pending) tint else c.outline,
        disabledActiveTrackColor=if(pending) tint else c.outline,
        disabledInactiveTrackColor=c.outlineVariant.copy(alpha=.5f))
}
