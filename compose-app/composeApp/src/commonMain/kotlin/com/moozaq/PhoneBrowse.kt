package com.moozaq

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moozaq.resources.*
import org.jetbrains.compose.resources.painterResource

/**
 * Browse: 64 dp top app bar, snapping mix shelves, and a 64 dp mini player floating 16 dp
 * above the bottom edge with content fading out underneath it (handoff "Browse").
 */
@Composable
fun PhoneBrowse(
    store: PlayerStore,
    ui: PlayerUiState,
    library: List<Mix>,
    playback: PhonePlayback,
    reducedMotion: Boolean,
    onOpenPlayer: () -> Unit,
    onOpenCast: () -> Unit,
    onOpenChannels: () -> Unit,
    miniPlayerGestures: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shelves = remember(library) {
        library.filter { (it.duration ?: 0.0) >= 3600.0 }.groupBy { it.category }.toSortedMap()
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.fillMaxSize()) {
            PhoneTopBar(playback, onOpenCast, onOpenChannels)
            if (shelves.isEmpty()) {
                Text(
                    "Your hour-long mixes will appear here.",
                    Modifier.padding(Dimens.ScreenMargin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = Dimens.ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shelves.forEach { (name, mixes) ->
                    item(key = "heading:$name") { ShelfHeading(name) }
                    item(key = "shelf:$name") { ShelfRow(store, ui, playback, mixes, reducedMotion, onOpenPlayer) }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Dimens.MiniPlayerFadeHeight)
                .background(Brush.verticalGradient(
                    0f to colors.surface.copy(alpha = 0f),
                    .45f to colors.surface.copy(alpha = .85f),
                    .75f to colors.surface,
                ))
        )
        PhoneMiniPlayer(
            store, ui, library, playback,
            onOpenPlayer = onOpenPlayer,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = Dimens.MiniPlayerSideInset, vertical = Dimens.MiniPlayerBottomInset)
                // Swiping the mini player up pulls the player open, while something is playing.
                .then(if (playback.session) miniPlayerGestures else Modifier),
        )
    }
}

@Composable
private fun PhoneTopBar(playback: PhonePlayback, onOpenCast: () -> Unit, onOpenChannels: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val casting = phoneCasting(playback)
    val castLabel = if (casting) "Casting to ${playback.device}. Choose devices" else "Cast to ${playback.device}"
    Row(
        Modifier.fillMaxWidth().height(Dimens.TopAppBarHeight).padding(start = Dimens.ScreenMargin, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painterResource(Res.drawable.wordmark), "Moozaq",
            Modifier.width(Dimens.LogoWidth).height(Dimens.LogoHeight),
            contentScale = ContentScale.Fit,
        )
        LocalBuildLabel.current?.let {
            Text(it, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onOpenCast,
            modifier = Modifier.size(Dimens.TouchTarget).semantics { contentDescription = castLabel },
        ) {
            UiIcon(
                if (casting) Res.drawable.ui_cast_connected else Res.drawable.ui_cast,
                modifier = Modifier.size(24.dp),
                tint = if (casting) colors.tertiary else colors.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onOpenChannels,
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(Dimens.ChannelsButtonHeight).defaultMinSize(minWidth = 0.dp),
        ) {
            Text("Channels", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

@Composable
private fun ShelfHeading(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.ScreenMargin, end = Dimens.ScreenMargin, top = Dimens.ShelfHeadingTop, bottom = Dimens.ShelfHeadingBottom),
    )
}

@Composable
private fun ShelfRow(
    store: PlayerStore,
    ui: PlayerUiState,
    playback: PhonePlayback,
    mixes: List<Mix>,
    reducedMotion: Boolean,
    onOpenPlayer: () -> Unit,
) {
    val rowState = rememberLazyListState()
    val starting = ui.transport?.takeIf { !it.failed }?.expected == "PLAYING"
    LazyRow(
        state = rowState,
        flingBehavior = rememberSnapFlingBehavior(rowState),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
    ) {
        items(mixes, key = { it.id }) { mix ->
            val state = phoneCardState(ui, playback, mix)
            PhoneMixCard(
                mix = mix,
                state = state,
                reducedMotion = reducedMotion,
                // Cards stay tappable while another mix starts, but never start a second mix.
                onPlay = { if (!starting) store.play(mix) },
                onOpenPlayer = onOpenPlayer,
            )
        }
    }
}

@Composable
private fun PhoneMixCard(
    mix: Mix,
    state: PhoneCardState,
    reducedMotion: Boolean,
    onPlay: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.CardCorner)
    val current = state == PhoneCardState.NowPlaying || state == PhoneCardState.Paused
    Box(
        Modifier
            .width(Dimens.CardWidth)
            .clip(shape)
            .background(colors.surfaceContainer)
            .then(if (current) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .clickable(
                enabled = state != PhoneCardState.Starting,
                onClickLabel = phoneCardLabel(state, mix),
                onClick = { if (current) onOpenPlayer() else onPlay() },
            )
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(Dimens.CardCoverHeight)) {
                MixCover(mix, Modifier.fillMaxSize())
                when (state) {
                    PhoneCardState.NowPlaying, PhoneCardState.Paused -> {
                        Box(Modifier.fillMaxSize().background(colors.surface.copy(alpha = .35f)))
                        NowPlayingBadge(paused = state == PhoneCardState.Paused, reducedMotion = reducedMotion)
                    }
                    PhoneCardState.Starting -> {
                        Box(Modifier.fillMaxSize().background(colors.surface.copy(alpha = .35f)))
                        Box(
                            Modifier.align(Alignment.Center).size(48.dp)
                                .clip(RoundedCornerShape(24.dp)).background(colors.surface.copy(alpha = .85f)),
                            contentAlignment = Alignment.Center,
                        ) { MzSpinner(size = 24.dp, color = colors.primary) }
                    }
                    PhoneCardState.Idle -> Unit
                }
            }
            Column(Modifier.padding(Dimens.CardPadding)) {
                Text(
                    mix.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 2,
                    minLines = 2,
                    modifier = Modifier.height(Dimens.CardTitleHeight),
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    mix.creator,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.NowPlayingBadge(paused: Boolean, reducedMotion: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.align(Alignment.TopStart).padding(8.dp).height(24.dp)
            .clip(RoundedCornerShape(12.dp)).background(colors.surface.copy(alpha = .85f))
            .padding(start = 8.dp, end = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MzEqualizer(animated = !paused && !reducedMotion, modifier = Modifier.size(12.dp))
            Text(
                if (paused) "Paused" else "Now playing",
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
            )
        }
    }
}

/**
 * Mini player states: Casting, Paused, Pausing…/Resuming…, Stopped and First launch.
 * The content area opens Now playing; the end button plays or pauses.
 */
@Composable
private fun PhoneMiniPlayer(
    store: PlayerStore,
    ui: PlayerUiState,
    library: List<Mix>,
    playback: PhonePlayback,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.MiniPlayerCorner)
    val snapshot = ui.snapshot
    val awaiting = playback.awaiting
    val lastMix = ui.lastPlayed?.let { last -> library.find { it.url == last.source } }
    Box(
        modifier.fillMaxWidth().height(Dimens.MiniPlayerHeight)
            .shadow(Dimens.MiniPlayerShadow, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape).background(colors.surfaceContainerHighest),
    ) {
        if (playback.session) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(onClickLabel = "Open player: ${playback.title ?: ""}") { onOpenPlayer() }
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(Dimens.MiniPlayerCover).clip(RoundedCornerShape(Dimens.MiniPlayerCoverCorner)),
                        contentAlignment = Alignment.Center,
                    ) {
                        playback.mix?.let { MixCover(it, Modifier.fillMaxSize()) }
                            ?: UiIcon(Res.drawable.ui_music_note, "Artwork unavailable", Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text(
                            playback.title ?: "Connecting…",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            UiIcon(
                                Res.drawable.ui_cast_connected,
                                modifier = Modifier.size(14.dp),
                                tint = colors.tertiary,
                            )
                            Text(
                                phoneMiniStatus(playback),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { store.command(if (canPause(snapshot)) "pause" else "resume") },
                    enabled = awaiting == null && (canPause(snapshot) || canResume(snapshot)),
                    modifier = Modifier.size(Dimens.TouchTarget).padding(end = 4.dp)
                        .semantics { contentDescription = if (canPause(snapshot)) "Pause" else "Resume" },
                ) {
                    if (awaiting != null) {
                        MzSpinner(size = 24.dp, color = colors.onSurfaceVariant)
                    } else {
                        UiIcon(
                            if (canPause(snapshot)) Res.drawable.ui_pause else Res.drawable.ui_play_arrow,
                            modifier = Modifier.size(28.dp),
                            tint = colors.onSurface,
                        )
                    }
                }
            }
            MiniProgress(ui, playback)
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(Dimens.MiniPlayerCover).clip(RoundedCornerShape(Dimens.MiniPlayerCoverCorner))
                            .background(colors.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (lastMix != null) MixCover(lastMix, Modifier.fillMaxSize().alpha(.7f))
                        else UiIcon(Res.drawable.ui_music_note, modifier = Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Nothing playing",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            phoneIdleHint(ui, playback.device),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (lastMix != null) {
                    val busy = ui.transport?.takeIf { !it.failed } != null || snapshot.busy
                    Box(
                        Modifier.padding(end = 8.dp).size(Dimens.PlayAgainSize)
                            .clip(RoundedCornerShape(Dimens.PlayAgainSize / 2))
                            .background(colors.primary)
                            .clickable(
                                enabled = !busy && ui.destination == null && canRequestPlay(snapshot, lastMix),
                                onClickLabel = "Play again: ${lastMix.title}",
                            ) { store.play(lastMix) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy) MzSpinner(size = 20.dp, color = colors.onPrimary)
                        else UiIcon(Res.drawable.ui_play_arrow, modifier = Modifier.size(22.dp), tint = colors.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MiniProgress(ui: PlayerUiState, playback: PhonePlayback) {
    val colors = MaterialTheme.colorScheme
    val r = presentedReceiver(ui)
    val fraction = if (playback.starting || r.duration == null || (r.duration ?: 0.0) <= 0.0) 0f
    else ((r.position ?: 0.0) / r.duration!!).toFloat().coerceIn(0f, 1f)
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Dimens.MiniPlayerProgress)
            .background(colors.secondaryContainer)
    ) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.primary))
        }
    }
}
