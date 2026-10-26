# Source URL expiry

Status: **Mac app guards implemented 2026-10-17; silent tests only; hardware behavior assumed, untested.** Android guards not implemented (separate agent). No receiver has yet been observed failing on an expired link; see *Unproven* below. Hardware validation is deferred as low priority (owner decision 2026-10-17); see *Deferred hardware test plan*.

Practical limit: a mix played straight through is expected to be safe up to the link lifetime (about 6 h measured), counted from when the link was fetched, **including pauses**. Since 2026-10-17, Resume in the Mac app renews the link before it runs short, so a long pause no longer depends on the speaker failing first. Resume by voice or the Google Home app bypasses Moozaq and still uses the old link.

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

The speaker's own media status carries the signed URL as `content_id`, so `Lab.status()` derives `expires_at`, `source_expired` and `refresh_needed` (the link will not outlast the rest of the mix plus `EXPIRY_MARGIN`) from authoritative receiver state. This works after reconnecting and for sessions started by an earlier app run, without persisting anything. The URL never leaves the engine; the bridge exposes only `source_expired`, `refresh_needed` and `expires_in` (seconds). Relay/cache routes have local URLs and report unknown expiry.

The last confirmed PLAYING/PAUSED position is kept in memory per content hash (`Lab.last_position`) so a failed session still knows where it was.

### 3. Honest "stream link expired" state — implemented (Mac)

When the receiver is `IDLE` with `idleReason=ERROR` **and** its link is past expiry, the bridge presents state `EXPIRED` with the mix's title, source, duration and last position. Stop (`CANCELLED`) and a finished mix (`FINISHED`) are never presented as expired. The desktop player keeps the mix visible, shows *"Stream link expired · Resume reloads from 1:10:00"* in the error colour, and enables **Resume**. The seek slider is disabled in this state.

Known delay: session recovery treats the sudden IDLE as a possible handoff and shows "Reconnecting…" for up to ~15 s before the expired state appears.

### 4. Renew the link on explicit Resume or seek — implemented (Mac)

Owner decision 2026-10-17: renew **before** the link runs out, not only after it has expired. The owner proposed refreshing on every Resume after a pause longer than 30 s; adopted instead is a lifetime rule, because a refresh costs a YouTube lookup plus rebuffering (several seconds) and most pauses are short while the link still has hours left.

- **Rule:** on Resume or seek, refresh when `refresh_needed`: the link has expired, or it will not last the rest of the mix (`duration − position`) plus 10 minutes. Otherwise Resume and seek are the ordinary instant commands.
- **Resume** with `refresh_needed` (state `EXPIRED`, or `PAUSED`) calls `Lab.refresh_source()`: re-resolve, then LOAD at the last position with autoplay. `play_selection` applies the same rule to a paused session.
- **Seek** with `refresh_needed` on a `PLAYING`/`PAUSED` session reloads at the seek target, keeping pause (`autoplay=false`, confirmed as `PAUSED`). Seek on an `EXPIRED` failure is refused; press Resume first.
- **Fallback:** if the new link cannot be fetched but the old link has not expired, `RefreshUnavailable` is raised before any LOAD and the app does the ordinary Resume or seek instead. With an already-expired link, the failure is shown.
- **Stop always wins:** a Stop during the lookup never falls back to Resume, and the fallback carries the generation of the original request, so a Stop in between sends nothing.
- Each refresh is a single attempt. Success is confirmed from fresh receiver state (`PLAYING` or `PAUSED` for the new content). Failure is shown, never retried automatically. The Resume button reads "Refreshing…" while pending.
- The current receiver volume is used, never a remembered one.
- Not covered: Resume by voice or from the Google Home app goes straight to the speaker. If that fails on an expired link, guard 3 presents it.

### Constraints from existing rules

- Reconnection must remain read-only; it must not load media. Guards 3 and 4 reload only after an explicit user action (Resume, seek or tap).
- Automatically swapping the URL mid-play is **not approved**. It would also cause a short audible gap. Needs a separate owner decision.
- Pending → confirmed rules in `playback-control-requirements.md` apply to the refresh-and-resume action: show pending, confirm from fresh receiver state, surface failure.
- Android is out of scope for this work: another agent is editing `AndroidBackend.kt` and `MembershipChannel.kt`. Coordinate with that work before applying the guards on Android.

### Local AVPlayer route (2026-10-26)

This Mac resolves the direct audio-only URL and remuxes it through a temporary loopback HLS relay. The signed source URL remains in memory and is used by ffmpeg; AVPlayer receives only the local playlist URL, never as a process argument or log field. The relay uses small segments rather than a complete download and removes its process and files on stop, error or close. Local expiry is derived from the in-memory resolution and current source position, not from a receiver or a persisted signed URL.

Local Resume and seek use the same remaining-lifetime rule and ten-minute margin. A refresh is a single explicit load at the current/target position with a source-position offset, preserving pause for a paused seek. If a still-valid old link cannot be refreshed, ordinary Resume/seek is allowed; Stop or an expired link prevents that fallback. There is no automatic mid-play URL swap and no reattachment to a dead local helper. A fresh explicit Play is required after process death or app restart. HLS seeks explicitly reload at the requested position rather than guessing unavailable ranges.

The local path has protocol, cancellation, expiry, relay and lifecycle tests with a scripted helper, plus real no-media AVPlayer start/stop and bridge selection checks. The installed helper also completed one real YouTube/HLS sequence: PLAYING with advancing position, PAUSED, paused seek to 5.0, PLAYING again, volume changes to 10% and 15%, and IDLE on Stop with helper exit 0. This confirms one source and one control sequence, not expiry behavior, long-session reliability or independent audible assessment.

### Fallback that removes expiry

Cache/relay (route B/C): download the complete audio (~170 MB for 3 h at 128 kbps) and serve it from the Mac. No expiry, but the Mac must stay on, which conflicts with the phone-leaves goal. Keep as fallback, not default.

### Silent test coverage

- `mac-spike/test_source_expiry.py` (25): expiry parsing and host checks, cache reuse vs re-resolve, late-start lifetime, refusal of an already-expiring link, long-mix risk logging, receiver-derived expiry without URL exposure, `refresh_needed` for short and long links, last-position recovery, paused/playing refresh, lookup failure fallback vs error, Stop during lookup and before LOAD, plain Resume for long links, LOAD `currentTime`/`autoplay`/expected state.
- `compose-app/bridge/test_expiry.py` (14): Resume/seek routing for expired and short links, plain fallback when no new link, normal transport when the link lasts, refused seek on a failed session, single attempt on failure, fallback sends nothing after Stop, `EXPIRED` presentation, Stop not shown as expiry, other-session expiry ignored, no load from snapshots.
- `composeApp/.../ExpiryPresentationTest.kt` (6): Resume availability, labels, busy/synchronizing guards, decoding older snapshots.

All mocked. No resolve, Cast command or audio during tests.

## Unproven, needs owner-authorized hardware test

- What the Nest receiver does when its in-flight fetch outlives `expire`.
- What happens on Resume after a pause longer than the URL lifetime (expected: `IDLE`/`ERROR`).
- Seek near the end of a mix after expiry.
- Whether the lifetime varies by video, format or network.
- That the Default Media Receiver honours `currentTime` with `autoplay=false` and reports `PAUSED` (assumed by the paused-seek refresh).
- How long a Resume-with-refresh takes in practice (lookup plus rebuffer), and whether the reload position is accurate to the second.
- The real `idleReason` the Nest reports for an expired link (the guard assumes `ERROR`).

Each requires audible playback and must not be run without an explicit owner request.

Until tested, the guards assume: the Nest reports `IDLE`/`ERROR` for an expired link, a running fetch continues past `expire`, and a paused reload reports `PAUSED`. If any assumption is wrong, guard 3 may not trigger and the player shows a generic stream error instead.

## Deferred hardware test plan (low priority)

Owner decision 2026-10-17: low priority, because uninterrupted playback up to ~6 h is expected to work. Nothing below is built or run yet.

YouTube links cannot be shortened (`expire` is covered by the signature; editing it breaks the link), so the test stands in for YouTube with a local fake media server.

**Harness** (planned `mac-spike/tools/`):
- Fake media server on the Mac serving a long, quiet, ffmpeg-generated test tone (original audio, not a YouTube mix) with byte-range support. The URL mimics YouTube's, with `expire` = now + 5 minutes.
- Two expiry behaviours, since YouTube's is unknown: *reject new* (downloads in progress continue; new requests after expiry get 403) and *cut* (downloads in progress are also dropped at expiry).
- Throttled sending, so the speaker must keep requesting data as it would for a 3-hour mix.
- A log of every speaker request: time, byte range, response.
- A stand-in for the YouTube lookup that issues a new 5-minute link, so the app's real Resume and seek refresh code runs end to end.
- A test-only switch so `url_expiry()` also accepts the fake host. Off by default, set by an environment variable the packaged app never sets.

**Scenarios** (each about 5–8 minutes, Bedroom speaker, low volume, explicit owner go-ahead):
1. Play straight through expiry (*reject new*): does playback continue, and when does the speaker request more data?
2. Same with *cut*: which `idleReason` does the Nest report?
3. Pause before expiry, wait past it, Resume in the app: does the refresh reload at the right position?
4. Seek after expiry, once while playing and once while paused.
5. Reload with `autoplay=false`: does the Nest report `PAUSED`?

**Not covered by the harness:** YouTube's own server behaviour (whether it drops a running download at `expire`, and whether 6 h is typical). One real check covers that: pause a real mix overnight (longer than 6 h), then Resume in the app.
