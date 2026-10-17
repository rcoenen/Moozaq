# Source URL expiry

Status: **Mac app guards implemented 2026-10-17; silent tests only.** Android guards not implemented (separate agent). No receiver has yet been observed failing on an expired link; see *Unproven* below.

## How playback reaches the speaker

Moozaq never casts video. yt-dlp asks YouTube for an ordinary video's formats and picks an audio-only stream (`bestaudio[ext=m4a]/bestaudio`, normally format 140, m4a/AAC). The Nest speaker receives that URL with `audio/mp4` or `audio/webm` through the Default Media Receiver and fetches the audio from YouTube itself (route A, direct). The phone or Mac does not relay it.

- Mac: `mac-spike/engine.py` `resolve()` / `play_test()`
- Android: `compose-app/androidApp/.../OnDeviceSources.kt` `resolve()`, called from `AndroidBackend.kt` on each explicit Play

## What was measured (2026-10-17)

One silent metadata-only resolution of starter `7vy0H3ERRiA` (3.05 h mix). No audio, no Cast command, URL not printed or stored.

| Observation | Value |
|---|---|
| Format | 140, m4a, `mp4a.40.2` |
| `expire` minus resolve time | **6.0 hours** |
| Signed parameters present | `expire`, `ip`, `dur`, `lmt`, `mn`, `ms` |
| IP-bound | **Yes** (`ip=` present) |

This is one sample from one network. Other videos, formats and days may differ; re-measure before relying on the 6-hour figure.

## What this means

- A fresh URL outlives a single uninterrupted 3-hour mix by about 3 hours.
- The risk is any **new request to YouTube after `expire`**: a long pause then Resume, a seek late in a session, a Wi-Fi/receiver reconnect that restarts the HTTP fetch, or loading a URL resolved earlier.
- An HTTP fetch already streaming probably continues past `expire`. That is an expectation, **not tested**.
- The URL is bound to the resolving public IP. Phone and speaker share the home NAT, so this normally works. A phone that resolves on mobile data and then casts on home Wi-Fi would likely get a URL the speaker cannot use.
- Before the guards, Mac `ensure_source()` reused a resolved URL for up to 600 seconds with no lifetime check. Android resolves again on each explicit Play.

## Guards

Scope: the **Mac app** (`mac-spike/engine.py`, `compose-app/bridge/server.py`, desktop `Player.kt`/`PlayerBar.kt`). Android is owned by a separate agent; the same four guards still need applying there.

### 1. Lifetime check before every load — implemented (Mac)

`url_expiry()` reads only the signed `expire` parameter of a `*.googlevideo.com` HTTPS URL; anything else is "unknown". `ensure_source(value, start)` reuses a cached resolution only when it is the same video, under 10 minutes old, **and** its link outlives the media still to be fetched (`duration − start`) by `EXPIRY_MARGIN` (600 s). Otherwise it resolves again.

Deviation from the original plan, deliberate: a *fresh* link shorter than the mix does **not** refuse the load. Mixes longer than ~6 h would otherwise be unplayable, a running fetch probably continues past expiry, and guards 3–4 recover. The risk is logged as `source-expiry-risk`. A fresh link with less than the margin left is refused with no load.

### 2. Expiry from the receiver, not stored URLs — implemented (Mac)

The speaker's own media status carries the signed URL as `content_id`, so `Lab.status()` derives `expires_at` / `source_expired` from authoritative receiver state. This works after reconnecting and for sessions started by an earlier app run, without persisting anything. The URL never leaves the engine; the bridge exposes only `source_expired` and `expires_in` (seconds). Relay/cache routes have local URLs and report unknown expiry.

The last confirmed PLAYING/PAUSED position is kept in memory per content hash (`Lab.last_position`) so a failed session still knows where it was.

### 3. Honest "stream link expired" state — implemented (Mac)

When the receiver is `IDLE` with `idleReason=ERROR` **and** its link is past expiry, the bridge presents state `EXPIRED` with the mix's title, source, duration and last position. Stop (`CANCELLED`) and a finished mix (`FINISHED`) are never presented as expired. The desktop player keeps the mix visible, shows *"Stream link expired · Resume reloads from 1:10:00"* in the error colour, and enables **Resume**. The seek slider is disabled in this state.

Known delay: session recovery treats the sudden IDLE as a possible handoff and shows "Reconnecting…" for up to ~15 s before the expired state appears.

### 4. Refresh on explicit Resume or seek — implemented (Mac)

- **Resume** when the link has expired (state `EXPIRED`, or `PAUSED` past expiry) calls `Lab.refresh_expired()`: re-resolve, then LOAD at the last position with autoplay. `play_selection` applies the same rule for a paused expired session.
- **Seek** past expiry on a `PLAYING`/`PAUSED` session reloads at the seek target, keeping pause (`autoplay=false`, confirmed as `PAUSED`). Seek on an `EXPIRED` failure is refused; press Resume first.
- Each refresh is a single attempt bound to the Stop generation; Stop before the LOAD prevents sound. Success is confirmed from fresh receiver state (`PLAYING` or `PAUSED` for the new content). Failure is shown, never retried automatically. The Resume button reads "Refreshing…" while pending.
- The current receiver volume is used, never a remembered one.

### Constraints from existing rules

- Reconnection must remain read-only; it must not load media. Guards 3 and 4 reload only after an explicit user action (Resume, seek or tap).
- Automatically swapping the URL mid-play is **not approved**. It would also cause a short audible gap. Needs a separate owner decision.
- Pending → confirmed rules in `playback-control-requirements.md` apply to the refresh-and-resume action: show pending, confirm from fresh receiver state, surface failure.
- Android is out of scope for this work: another agent is editing `AndroidBackend.kt` and `MembershipChannel.kt`. Coordinate with that work before applying the guards on Android.

### Fallback that removes expiry

Cache/relay (route B/C): download the complete audio (~170 MB for 3 h at 128 kbps) and serve it from the Mac. No expiry, but the Mac must stay on, which conflicts with the phone-leaves goal. Keep as fallback, not default.

### Silent test coverage

- `mac-spike/test_source_expiry.py` (18): expiry parsing and host checks, cache reuse vs re-resolve, late-start lifetime, refusal of an already-expiring link, long-mix risk logging, receiver-derived expiry without URL exposure, last-position recovery, paused/playing refresh, Stop before refresh, LOAD `currentTime`/`autoplay`/expected state.
- `compose-app/bridge/test_expiry.py` (10): Resume/seek routing, normal transport when the link is live, refused seek on a failed session, single attempt on failure, `EXPIRED` presentation, Stop not shown as expiry, other-session expiry ignored, no load from snapshots.
- `composeApp/.../ExpiryPresentationTest.kt` (6): Resume availability, labels, busy/synchronizing guards, decoding older snapshots.

All mocked. No resolve, Cast command or audio during tests.

## Unproven, needs owner-authorized hardware test

- What the Nest receiver does when its in-flight fetch outlives `expire`.
- What happens on Resume after a pause longer than the URL lifetime (expected: `IDLE`/`ERROR`).
- Seek near the end of a mix after expiry.
- Whether the lifetime varies by video, format or network.
- That the Default Media Receiver honours `currentTime` with `autoplay=false` and reports `PAUSED` (assumed by the paused-seek refresh).
- The real `idleReason` the Nest reports for an expired link (the guard assumes `ERROR`).

Each requires audible playback and must not be run without an explicit owner request.
