# Design

## Context

Cast to already toggles membership through `PlayerStore.setPlaybackDevices` when `membership.available` is true. On Android that flag is true only for a `GroupRouteInfo`, and every observed phone session has been a plain `RouteInfo`. The Mac sends one `SET_PLAYBACK_DEVICES` on `urn:x-cast:com.google.cast.multizone` to platform destination `receiver-0`. `CastSession.sendMessage` cannot address that destination. `CastStatusWire` already frames passive receiver reads.

Gate 1 compared the app with Google's Output Switcher guide. The Pixel is Android 17 and the installed app is 0.4.1. The manifest has no `MediaTransferReceiver`, there is no media notification, and the receiver is the public Default Media Receiver rather than a console app with Google Cast for Audio enabled. Gate 2 found no documented API that lets our checkboxes add a speaker. The system chooser is a different product. This change takes the platform path for that reason.

## Goals / Non-Goals

**Goals:**

- Make one eligible checkbox add or remove send the Mac command, once, with the Mac confirmation rules.
- Keep the decision and the socket conversation testable without a speaker or the Cast SDK.
- Preserve pause, follow Stop across an endpoint change, and stop recovery from grabbing the old speaker.
- Hide temporary groups and leave saved groups alone.

**Non-Goals:**

- Declare `MediaTransferReceiver` or open Google's Output Switcher.
- Run the audible add/remove, start a mix, or change speaker volume as part of this change.
- Retry a membership command, or turn a failed join into separate per-speaker loads.
- Replace the SDK for ordinary single-speaker playback.

## Decisions

### Platform channel beside the SDK session

Read and mutate through a short-lived TLS connection to the current session's LAN address, using the existing Cast framing with destination `receiver-0`. Trust is limited to that site-local or link-local socket, as in the passive probe. The connection sends CONNECT, GET_STATUS, the one membership command, heartbeat replies, and the media commands needed to confirm or correct state. It does not send LAUNCH or LOAD.

`membership.available` becomes true only from a fresh eligible read tied to the current application session and media. Until that read exists, checkboxes stay locked. A `GroupRouteInfo` that is not platform-eligible keeps the existing add and remove calls.

### Pure conversation, injected transport

`MembershipChannel` speaks the exchange against a `CastFrameTransport`. Tests use a scripted transport and a manual clock. The phone uses a LAN socket. Request ids are a counter starting at 1000, never a hash of the session. A reply counts only when its request id matches.

An omitted endpoint is confirmed on the same connection. A present endpoint is checked for a literal private address belonging to a requested speaker, then confirmed on a new connection to that host and port. A new group id is marked temporary so the picker hides it. If discovery later shows a route with that device id and port, the backend selects that route once. `relaunchIfRunning` stays false, and no LOAD follows.

### Stop, uncertainty, and recovery

A permit records whether the command has been written. Stop before the write cancels it. Stop after the write lets the reply and adoption finish, then stops the confirmed session. Direct pause, resume, seek, volume, and stop are used only when the SDK session is not that adopted session. Resume sends PLAY only when the receiver is paused. A paused session observed playing receives one PAUSE and no PLAY.

A timeout or unmatched reply sets an uncertain location, clears availability, and refuses another membership command until a fresh read confirms the session. The 2026-10-17 Kitchen add showed why the wait itself matters: the follow-up read blocked until its socket timeout, then the clock slept that same budget again, so the phone stopped at about ten seconds with no location while the speakers had already moved the mix onto a temporary group and kept playing. A transport that waits inside `receive` must not sleep that budget again. A scripted transport still advances the manual clock by the full remaining time.

When that unlocated reply follows a sent command, the unfinished checkbox request is cleared and status polls stop against the pre-change speaker. The phone then reads discovered group routes with receiver and media `GET_STATUS` only. It selects a route once when exactly one group reports the same session, the same media, and an active player state. That selection uses the existing once-only `route.select()` and does not load media or send `SET_PLAYBACK_DEVICES` again. Zero matches and two matches leave the route unchanged. `recover()` returns immediately while a command is in flight, an adopted endpoint is current, or the location is uncertain, so it cannot reselect the old speaker. An SDK session end during that window is recorded and does not wipe the adopted observation.

Opening the app is not that path. The follow used to run only after a sent command in the same process, and `recover()` returns when a Cast session is already connected. On a fresh open, recovery finds no Default Media Receiver on the remembered speaker and stops, while the mix is on a hidden temporary group. After about eight seconds of that idle result, or after a connected speaker has reported no media for about eight seconds, the phone reads discovered groups the same way. The session id is the Cast session id when one is connected. Otherwise it is read with receiver `GET_STATUS` from the remembered speaker, including when that speaker is running a different app. Exactly one active match is selected once. The temporary group is not saved as the remembered speaker. No membership command and no LOAD are sent. A missing session id, or two matching groups, leaves the route alone.

### What stays unchanged

Saved All speakers, the confirmed move-to-group path, and single-speaker LOAD playback stay as they are. Diagnostics record request id, reply type, failed count, and whether an endpoint existed. They do not record stream URLs or raw payloads.

## Risks

Endpoint adoption and direct control after a move are unproven on these speakers. One silent implementation does not establish that a second speaker will be heard. The next hardware step is a read of the three flags on an already playing session, then a separately authorized add and remove.
