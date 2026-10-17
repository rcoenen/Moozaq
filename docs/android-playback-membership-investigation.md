# Android playback membership: investigation

Date: 2026-10-17. Trigger: the owner tried to add Kitchen speaker to a playing Office
session on the Pixel and found every speaker checkbox in Cast to locked. This records what
was verified, what was assumed, what was wrong, and what an Android implementation would
take.

Status: investigation only. No protocol code was written and no speaker was mutated.

## The question

Can the Android app add a second speaker to a session that is already playing on one
speaker — ideally by quietly forming a temporary group — the way Google Home and our own
Mac/desktop path can?

## What was checked, and what it showed

### Android behaviour (facts from this build)

- Diagnostics for every selection in this session record a plain route:
  `route.selected name=Office speaker type=RouteInfo`. No `GroupRouteInfo` has ever
  appeared.
- `AndroidBackend.snapshot()` reports membership as available only when the selected route
  is a `GroupRouteInfo`:
  `available = group != null && fresh && client?.hasMediaSession() == true`
  (`compose-app/androidApp/src/main/java/com/moozaq/app/AndroidBackend.kt:408`).
- `AndroidBackend.setPlaybackDevices()` refuses anything else outright:
  "This receiver does not offer native live grouping. Select a saved speaker group instead."
  (`AndroidBackend.kt:540`), and otherwise uses `GroupRouteInfo.addRoute/removeRoute`
  (`AndroidBackend.kt:552`).
- Temporary groups are deliberately hidden from the picker
  (`temporary` at `AndroidBackend.kt:352`, filtered in `pickerDevices()`).

So on Android the checkboxes were correctly locked: the code only knows one grouping API,
and that API needs a group route the Cast provider never publishes.

### The Cast SDK cannot create a group (verified by inspecting the shipped jars)

- `androidx.mediarouter:mediarouter:1.8.0-beta01` contains the abstractions:
  `MediaRouter.GroupRouteInfo.addRoute/removeRoute/isGroupable`,
  `MediaRouteProvider.DynamicGroupRouteController`, and
  `MediaRouterParams.Builder.setDialogType(MediaRouterParams.DIALOG_TYPE_DYNAMIC_GROUP)`.
- `com.google.android.gms:play-services-cast-framework:22.3.1` and
  `play-services-cast:22.3.1` contain **no reference** to `DynamicGroupRouteController`
  and no dynamic-group provider class. Grepping both extracted jars returns only the
  mediarouter files.
- Consequence: `DIALOG_TYPE_DYNAMIC_GROUP` only selects which system dialog is shown. It
  cannot make a provider that does not implement grouping start publishing group routes.
  Through the SDK, a sender cannot compose a group.

### But the protocol can, and this project already does it (facts)

The Mac/desktop path does not go through the Cast SDK's route model. It speaks the
receiver's platform channel directly:

- `mac-spike/grouping.py:10` — namespace `urn:x-cast:com.google.cast.multizone`.
- `mac-spike/grouping.py:21` — controller registered with `target_platform=True`, i.e.
  messages addressed to the receiver platform (`receiver-0`), not the running app.
- `mac-spike/grouping.py:48-66` — `SET_PLAYBACK_DEVICES` with `requestId`, the receiver
  **application** `sessionId`, and a `deviceList` of physical speakers
  (`deviceId` without hyphens, `ipAddress`, `port`).
- `compose-app/bridge/membership.py` — the complete confirmed implementation: capability
  gates (`appAllowsGrouping`/`isVideoContent`/`streamTransferSupported`,
  `membership.py:127-129` and `:216-217`), single-shot send with "never retry after a
  timeout" (`:227`), optional `sessionEndpoint` handoff (`:233`), and a 12 s membership
  convergence loop (`:302`, `:320`).

Hardware evidence that this works on our receivers:

- `docs/spike-results.md:160` — recovered wire contract.
- `docs/spike-results.md:166` — a native Mac change on Kitchen's individual session
  produced `failedDevices=[]` **and a new temporary group UUID
  `302fdeb3-90ee-46ca-a23b-9c1ee486485e` at the Kitchen host port 32004**, with the
  application session ID unchanged and exactly Kitchen+Bedroom playing the same mix.
- `docs/spike-results.md` (same section, earlier) — the owner confirmed audibly that
  Kitchen and Bedroom played the same music after a Google Home expansion.
- `docs/google-cast-lessons.md` ("Native temporary grouping on Mac") already records the
  full contract and eight rules learned from live changes.

## Correction

During the initial diagnosis I told the owner that "a sender cannot create a group" and
that "silent temp groups are not possible". That was wrong. It is true only of the Android
Cast SDK's route API. The receiver protocol supports it, this repository implements it on
the Mac side, and it has been exercised on the same hardware. The correct statement is:
**Android lacks the platform-channel client, not the capability.**

The lessons document already anticipated this exact gap: "Android's documented Output
Switcher is a separate integration path; the shared Compose Android library does not
implement that backend yet."

## Why Android is the odd one out

| Path | Session transport | Can form a group? |
| --- | --- | --- |
| Mac / desktop (Python) | pychromecast + own `BaseController` on the platform namespace | Yes — proven |
| Android (current) | `CastSession` / `MediaRouter` from the Cast SDK | No — no platform send, no dynamic group provider |
| Google Home | Google's own platform client | Yes — creates `Kitchen speaker + 1` |

`CastSession.sendMessage()` addresses the running receiver application. The multizone
channel belongs to the platform (`receiver-0`). The SDK keeps that socket internal, so
Android would need its own CASTV2 connection for this one operation.

## What an Android implementation takes

Porting the proven design, not inventing one:

1. **Platform channel client.** TLS to `ip:8009` with a trust scoped to that discovered LAN
   host (the same pattern already used for the Nest setup endpoint in
   `ReceiverTopology.dynamicGroups`), 4-byte big-endian length-prefixed `CastMessage`
   frames, `source_id="sender-0"`, `destination_id="receiver-0"`. Only
   protocol version, namespace and `payload_utf8` are needed, so hand-encoding is ~30
   lines with no new dependency.
2. **Eligibility read.** `GET_STATUS`, then require exactly what Python requires:
   `playbackSession.appAllowsGrouping == true`, `streamTransferSupported == true`,
   `isVideoContent == false`, plus fresh session identity and matching current members.
3. **Single-shot mutation.** `SET_PLAYBACK_DEVICES` with `requestId`, the receiver
   application `sessionId` (already available: diagnostics log `sessionHash=…`), and the
   desired full physical device list.
4. **Correlated reply.** Await `PLAYBACK_SESSION_UPDATED` for that `requestId`; treat
   `failedDevices` as failure; never retry after a timeout.
5. **Endpoint adoption.** When a new group is formed, the session moves to the returned
   `sessionEndpoint` (group UUID on the coordinator's port). Android must follow it: select
   the new route and — this is the invasive part — **stop hiding temporary groups**, since
   the new group is one. An omitted `sessionEndpoint` is not failure (rule 4 in the lessons
   doc): confirm the original connection still owns the same session and media.
6. **Convergence and confirmation.** Poll membership until it matches, up to 12 s, then
   require fresh PLAYING on the moved session. Keep the pending checkmarks on failure;
   never silently roll back. Stop must first locate the possibly moved session, then stop
   that session.

Reused safety rules from `docs/google-cast-lessons.md`: bind to session/media
fingerprint/generation, recheck immediately before sending, one send only, no automatic
retry, honest uncertainty, and keep the saved "All speakers" definition untouched.

## Risks and unknowns

- **Endpoint handoff** is the hard part. During migration the old endpoint can report
  UNKNOWN while the group reports PLAYING; a monitor that only watches the original
  endpoint will look like it lost playback. Our route-change recovery (0.3.4, status 2155)
  is adjacent but was written for single-speaker selection, not group migration.
- **Our own visibility rule inverts.** Temporary groups are currently hidden on purpose. A
  formed group must be visible and selectable, which also means stale temporary groups may
  appear later.
- **Two senders, one device.** The Cast SDK keeps its session while we open a second
  connection for the platform channel. That should be normal (multiple senders per device),
  but it is unproven with this firmware from the same app.
- **Capability gate may be false on our sessions.** `appAllowsGrouping` was true for the
  Mac's Default Media Receiver sessions; the Android app uses the same receiver app, but
  this has not been read on an Android session.
- **Testing is audible.** Forming a group chimes on the speakers and moves audio. This
  cannot be validated silently.

## Test plan

Silent, deterministic (no speaker):

- Frame encode/decode for `CastMessage`, including length prefix and large payloads.
- Eligibility gates: each capability false, stale session, changed members, mismatch
  between requested and current.
- Reply correlation: wrong `requestId`, `failedDevices`, timeout with no reply, and a late
  reply after cancellation.
- Endpoint adoption: absent endpoint vs changed endpoint vs endpoint on an unrequested host.
- Stop during mutation: must locate the moved session before claiming success, never retry
  the mutation.

Authorized hardware run (one, explicitly instructed):

1. Confirm Office is playing and read eligibility.
2. Add Kitchen; verify `failedDevices` empty, membership converges to exactly Office +
   Kitchen, and both report PLAYING the same source and session.
3. Confirm audibly that both speakers play the same music.
4. Remove Kitchen again and confirm Office alone still plays.
5. Record source/session identity, timings, and the exact commands sent.

## Recommendation

Implement it as an OpenSpec change (`android-playback-membership`) with the six steps above
and the silent test list first, then a single authorized audible run. Until that lands, the
0.4.1 behaviour stands: locked speakers explain themselves and the saved-group move is
confirmed rather than silent.

Related: `docs/google-cast-lessons.md`, `docs/spike-results.md` (Oct 16 membership
sections), `docs/speaker-selection.md`.
