package com.moozaq

import androidx.compose.ui.unit.dp

/**
 * Phone tokens from the Android design handoff (`Moozaq-Android-design-handoff.pdf`).
 * The reference frame is a 412 x 915 dp phone; values are dp unless stated.
 */
object Dimens {
    val ScreenMargin = 16.dp
    val TopAppBarHeight = 64.dp
    val TouchTarget = 48.dp

    val LogoWidth = 148.dp
    val LogoHeight = 40.dp
    val ChannelsButtonHeight = 40.dp

    val CardWidth = 200.dp
    val CardCoverHeight = 112.dp
    val CardCorner = 12.dp
    val CardGap = 12.dp
    val CardPadding = 12.dp
    val CardTitleHeight = 40.dp
    val ShelfHeadingTop = 16.dp
    val ShelfHeadingBottom = 8.dp

    val MiniPlayerHeight = 64.dp
    val MiniPlayerCorner = 16.dp
    val MiniPlayerSideInset = 8.dp
    val MiniPlayerBottomInset = 16.dp
    val MiniPlayerCover = 48.dp
    val MiniPlayerCoverCorner = 8.dp
    val MiniPlayerProgress = 3.dp
    val MiniPlayerFadeHeight = 168.dp
    val MiniPlayerShadow = 8.dp
    val PlayAgainSize = 40.dp
    val ListBottomPadding = 96.dp

    val PlayerMargin = 24.dp
    val PlayerCoverCorner = 16.dp
    val PlayerCoverAspect = 364f / 205f
    val PlayPauseSize = 96.dp
    val PlayPauseCorner = 28.dp
    val StopCastingHeight = 48.dp
    val StopCastingCorner = 24.dp

    val SheetCorner = 28.dp
    /** Browse shows above the player panel by this much, like the Cast panel. */
    val PlayerPanelTopGap = 56.dp
    val SheetMaxHeight = 820.dp
    val SheetHandleWidth = 32.dp
    val SheetHandleHeight = 4.dp
    val DeviceCardCorner = 20.dp
    val DeviceCardGap = 8.dp

    val SliderHitHeight = 44.dp
    val SeekTrack = 16.dp
    val VolumeTrack = 12.dp
    val SliderThumbWidth = 4.dp
    val VolumeThumbHeight = 32.dp
}

/** Motion values from the handoff. Prototype delays are simulated; production ends on receiver state. */
object Motion {
    const val EqualizerMs = 900
    const val EqualizerStaggerMs = 300
    const val SpinnerMs = 1000
    const val CoverZoomMs = 300
    const val CoverScrimMs = 200
    const val FadeMs = 120
    const val PendingPulseMs = 1400
}
