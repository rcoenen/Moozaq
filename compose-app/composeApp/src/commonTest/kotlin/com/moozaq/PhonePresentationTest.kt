package com.moozaq

import kotlin.test.*

class PhonePresentationTest {
    private val bedroom = Device("bedroom", "Bedroom speaker", cast_type = "audio")
    private val mix = catalog.first()
    private val other = catalog[1]
    private val library = listOf(mix, other)

    private fun playing() = Snapshot(
        devices = listOf(bedroom), target = "bedroom",
        receiver = Receiver(
            connected = true, device = "Bedroom speaker", title = mix.title, state = "PLAYING",
            source = mix.url, position = 60.0, duration = 7200.0, volume = 40,
        ),
    )

    @Test fun stoppedShowsNothingPlayingAndLastPlayed() {
        val ui = PlayerUiState(
            library = CatalogData(mixes = library),
            snapshot = Snapshot(devices = listOf(bedroom), target = "bedroom", receiver = Receiver(connected = true, state = "IDLE")),
            lastPlayed = Receiver(connected = true, state = "PLAYING", title = mix.title, source = mix.url),
        )
        val p = phonePlayback(ui, library)
        assertFalse(p.session)
        assertNull(p.title)
        assertEquals("Bedroom speaker", p.device)
        assertEquals("Last played: ${mix.title}", phoneIdleHint(ui, p.device))
        assertFalse(phoneCasting(p))
    }

    @Test fun firstLaunchInvitesAPickInsteadOfDebugText() {
        val ui = PlayerUiState(snapshot = Snapshot(devices = listOf(bedroom), target = "bedroom"))
        val p = phonePlayback(ui, library)
        assertFalse(p.session)
        assertEquals("Pick a mix to cast to Bedroom speaker", phoneIdleHint(ui, p.device))
    }

    @Test fun pausedKeepsTheSessionAndSaysPaused() {
        val ui = PlayerUiState(
            library = CatalogData(mixes = library),
            snapshot = playing().copy(receiver = playing().receiver.copy(state = "PAUSED")),
        )
        val p = phonePlayback(ui, library)
        assertTrue(p.session)
        assertTrue(p.paused)
        assertEquals("Paused", p.status)
        assertEquals("${mix.creator} · Paused", phoneNowStatus(p))
        assertEquals("Paused · Bedroom speaker", phoneMiniStatus(p))
        assertEquals(PhoneCardState.Paused, phoneCardState(ui, p, mix))
        assertEquals("Open player: ${mix.title}", phoneCardLabel(PhoneCardState.Paused, mix))
    }

    @Test fun castingUsesConfirmedStateOnly() {
        val ui = PlayerUiState(library = CatalogData(mixes = library), snapshot = playing())
        val p = phonePlayback(ui, library)
        assertEquals("Playing", p.status)
        assertEquals("${mix.creator} · Playing", phoneNowStatus(p))
        assertEquals("Casting · Bedroom speaker", phoneMiniStatus(p))
        assertTrue(phoneCasting(p))
        assertEquals(PhoneCardState.NowPlaying, phoneCardState(ui, p, mix))
        assertEquals(PhoneCardState.Idle, phoneCardState(ui, p, other))
        assertEquals("Play ${other.title}", phoneCardLabel(PhoneCardState.Idle, other))
    }

    @Test fun startingAMixShowsTheRequestNotTheOldTrack() {
        val ui = PlayerUiState(
            library = CatalogData(mixes = library),
            snapshot = playing(),
            transport = PendingTransport("PLAYING", "PLAYING", 0, other.url),
        )
        val p = phonePlayback(ui, library)
        assertEquals(other.title, p.title)
        assertEquals(other.creator, p.creator)
        assertEquals("Starting…", p.status)
        assertEquals("Starting… · Bedroom speaker", phoneMiniStatus(p))
        assertEquals(PhoneCardState.Starting, phoneCardState(ui, p, other))
        assertEquals("Starting ${other.title}", phoneCardLabel(PhoneCardState.Starting, other))
        assertEquals(PhoneCardState.NowPlaying, phoneCardState(ui, p, mix))
        assertFalse(phoneCasting(p), "A pending start is not yet confirmed casting")
    }

    @Test fun seekingAndPausingArePendingLabelsNotNewStates() {
        val seeking = PlayerUiState(
            library = CatalogData(mixes = library),
            snapshot = playing(),
            pendingSeek = PendingSeek(900.0, 0),
        )
        assertEquals("Seeking…", phonePlayback(seeking, library).status)
        assertEquals("${mix.creator} · Seeking…", phoneNowStatus(phonePlayback(seeking, library)))

        val pausing = PlayerUiState(
            library = CatalogData(mixes = library),
            snapshot = playing(),
            transport = PendingTransport("PAUSED", "PLAYING", 0),
        )
        val p = phonePlayback(pausing, library)
        assertEquals("Pausing…", p.status)
        assertEquals("Pausing… · Bedroom speaker", phoneMiniStatus(p))
        assertTrue(p.session, "The receiver still plays until it confirms the pause")
    }

    @Test fun failedRequestsStopClaimingThePendingLabel() {
        val ui = PlayerUiState(
            library = CatalogData(mixes = library),
            snapshot = playing(),
            transport = PendingTransport("PAUSED", "PLAYING", 0, failed = true),
        )
        val p = phonePlayback(ui, library)
        assertNull(p.awaiting)
        assertEquals("Playing", p.status)
        assertEquals(PhoneCardState.NowPlaying, phoneCardState(ui, p, mix))
    }

    @Test fun temporaryGroupUsesPlainSpeakerCount() {
        val dynamic = Device("dynamic", "Kitchen speaker + 1", cast_type = "group", temporary = true)
        val snapshot = Snapshot(
            devices = listOf(bedroom, dynamic), target = "dynamic",
            receiver = Receiver(connected = true, device = "Kitchen speaker + 1", title = mix.title, state = "PLAYING", source = mix.url),
            membership = PlaybackMembership(members = listOf("bedroom", "kitchen"), available = true, revision = 1, token = "t"),
        )
        val ui = PlayerUiState(library = CatalogData(mixes = library), snapshot = snapshot)
        assertEquals("2 speakers", phoneDeviceName(ui))
        assertEquals("Casting · 2 speakers", phoneMiniStatus(phonePlayback(ui, library)))
    }
}
