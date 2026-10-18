# Android playback membership: investigation

Date: 2026-10-17. Trigger: the owner tried to add Kitchen speaker to a playing Office
session on the Pixel and found every speaker checkbox in Cast to locked. This records what
was verified, what was assumed, what was wrong, and what an Android implementation would
take.

Status: Gates 1 and 2 are decided. Android 0.4.2 implements the silent platform client.
A passive read found Bedroom speaker already playing on the Default Media Receiver,
with all three eligibility flags true. That read did not change membership.
One later authorized Kitchen add moved that session onto a temporary group and kept it
playing; 0.4.2 left the phone on Bedroom. Android 0.4.3 is the source fix for that
confirmation failure and has not been re-run as another add or remove. Android 0.4.4
joins the already-playing temporary group on open. One such join is recorded in spike results.
The handoff for the next pass, including the open volume failure, is
[android-agent-handoff.md](android-agent-handoff.md).

## Decision after Gates 1 and 2 (2026-10-17)

The custom checkboxes are the required control. Google's Output Switcher cannot send
that command. It is a system sheet opened from a media notification or the volume panel,
and the guide's remote-to-remote expansion is defined for a receiver that enables
Google Cast for Audio in the Cast Developer Console. This app uses the public Default
Media Receiver, has no console registration, posts no media notification, and does not
declare `MediaTransferReceiver`. `setSessionTransferEnabled(true)` does not turn the
sheet on by itself. There is no documented call that lets Cast to add or remove a speaker.

The Pixel 6a is Android 17 and the installed app at the time of the audit was 0.4.1,
code 11. The Cast SDK in this build is `play-services-cast-framework` 22.3.1.
`CastSession.sendMessage` still addresses the running receiver app, not `receiver-0`.

That is the reason for the platform client, not the earlier jar search. The client
follows the Mac command: one `SET_PLAYBACK_DEVICES`, the three session flags, no retry,
endpoint checks, pause preservation, and Stop after adoption. Automatic recovery must
not reselect the old speaker while that handoff is the playback location. An audible
add and remove remains a separate authorization.

The flag read was made the same day, after the client existed, against a mix that was
already playing. Bedroom speaker reported `appAllowsGrouping=true`,
`streamTransferSupported=true`, and `isVideoContent=false`, with one multizone member
and player state `PLAYING` on app `CC1AD845`. Fridge speaker, Kitchen speaker, and
All speakers had no Default Media Receiver running. Office speaker was not discovered
in that pass. The probe sent CONNECT, receiver `GET_STATUS`, and, only because the
app was already running, one multizone `GET_STATUS` and one media `GET_STATUS`.
It did not send LAUNCH, LOAD, `SET_PLAYBACK_DEVICES`, volume, or seek. The installed
phone build was still 0.4.1 / code 11, so this does not show the new client on the phone.

## Handoff summary and revised recommendation

**Investigate Google's supported Output Switcher stream-expansion path before committing
to a custom CASTV2 membership client.** The earlier conclusion that the Android SDK cannot
form groups was too broad. Google documents dynamic expansion through its system UI for
eligible audio apps. Whether that path works with Moozaq's Default Media Receiver and
whether our custom checkboxes can invoke it are separate, unresolved questions.

The Mac protocol remains a demonstrated fallback on these speakers, not yet an Android
implementation. Temporary groups should stay hidden from the user-facing picker while
remaining available internally for routing. Preserve the original playing or paused
state; a membership change must not implicitly resume a paused mix.

This update supersedes the original recommendation to immediately port the raw protocol.
The next agent should work through the investigation gates below, record evidence, and
then propose an implementation. It does not authorize audible tests.

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
and that API needs a group route that has not appeared in the observed sessions. This is
not proof that the provider can never publish one or that system stream expansion is unavailable.

### What the earlier jar inspection does and does not establish

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
  This does **not** establish that all Cast platform grouping is unavailable. Inspecting
  these jars does not exhaust system UI or Google Play services behaviour. The prior
  blanket conclusion is withdrawn; the supported Output Switcher path needs evaluation.

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

Two overstatements have now been corrected:

1. The receivers cannot form a temporary group around an individual session: false;
   the Mac implementation and recorded hardware results demonstrate this.
2. The Android SDK/platform cannot do it, so we must write a raw client: not established;
   Google's Output Switcher documentation explicitly describes dynamic stream expansion.

The precise finding is that **Moozaq's current custom Android membership implementation
cannot handle the observed plain route**. A raw platform client is one candidate solution,
not yet the only solution.

## Why Android is the odd one out

| Path | Session transport | Can form a group? |
| --- | --- | --- |
| Mac / desktop (Python) | pychromecast + own `BaseController` on the platform namespace | Yes — proven |
| Android custom picker (current) | `GroupRouteInfo.addRoute/removeRoute` | Not available on observed plain routes |
| Android system Output Switcher | Cast platform stream expansion | Documented for eligible audio apps; not tested with Moozaq |
| Google Home | Google's own platform client | Yes — creates `Kitchen speaker + 1` |

`CastSession.sendMessage()` addresses the running receiver application. The multizone
channel belongs to the platform (`receiver-0`). The documented `sendMessage()` API sends to the connected application; it does not expose
an arbitrary destination ID. A direct port of the Mac platform operation would therefore
need a suitable platform transport. This does not rule out the separate system Output
Switcher path.

## Raw protocol fallback if the supported path is insufficient

Port the Mac behaviour only after the investigation below establishes why it is needed.
These are design requirements, not a completed or fully validated Android plan:

1. **Platform channel client.** Reuse/review `PassiveReceiverProbe.kt` and
   `CastStatusWire`, which already implement a bounded LAN TLS connection, Cast protobuf
   framing, CONNECT and heartbeat replies. They are not a membership client. Extend only
   after checking sender identity, namespace/destination validation, cancellation,
   malformed/oversized frames, timeouts and connection ownership. Use the discovered or
   validated returned port; do not hard-code 8009 for temporary groups. A short encoder
   does not make the complete transport a 30-line task. Keep local certificate handling
   scoped to verified/discovered LAN endpoints, never a global trust override.
2. **Eligibility read.** `GET_STATUS`, then require exactly what Python requires:
   `playbackSession.appAllowsGrouping == true`, `streamTransferSupported == true`,
   `isVideoContent == false`, plus fresh session identity and matching current members.
3. **Single-shot mutation.** `SET_PLAYBACK_DEVICES` with `requestId`, the receiver
   application `sessionId` read from the live session, and the
   desired full physical device list. Diagnostic `sessionHash` is only a fingerprint;
   it is not the wire session ID and must never be sent as one.
4. **Correlated reply.** Await `PLAYBACK_SESSION_UPDATED` for that `requestId`; treat
   `failedDevices` as failure; never retry after a timeout.
5. **Endpoint adoption.** If `sessionEndpoint` is returned, validate its host, port,
   identity and relationship to requested physical participants. Establish fresh session
   and media continuity before adoption. It is unproven that the SDK will publish a
   selectable route promptly or follow an arbitrary returned endpoint without launching
   or reloading media. Investigate SDK handoff versus retaining direct protocol control.
   Keep temporary routes internally accessible but hidden from the visible picker.
   An omitted endpoint is not failure: confirm the original connection still owns the
   intended session and read fresh membership there.
6. **Convergence and confirmation.** Wait for exact fresh membership with a bounded
   deadline (the Mac implementation allows 12 seconds for convergence). Confirm media
   identity, plausible position and the original playing/paused state; do not require
   PLAYING when the session was paused. Buffering/unknown transitions need an explicit
   settling policy. Preserve requested checkmarks with local failure/uncertainty feedback.
   Stop must locate the possibly moved session before claiming it stopped. A partial
   failure may still have moved playback: process endpoint evidence even when
   `failedDevices` is nonempty. Never retry the mutation automatically.

Reused safety rules from `docs/google-cast-lessons.md`: bind to session/media
fingerprint/generation, recheck immediately before sending, one send only, no automatic
retry, honest uncertainty, and keep the saved "All speakers" definition untouched.

## Risks and unknowns

- **Endpoint handoff** is the hard part. During migration the old endpoint can report
  UNKNOWN while the group reports PLAYING; a monitor that only watches the original
  endpoint will look like it lost playback. Our route-change recovery (0.3.4, status 2155)
  is adjacent but was written for single-speaker selection, not group migration.
- **Internal routing versus visible inventory.** A temporary endpoint must be internally
  controllable without reintroducing duplicate rows such as “Fridge speaker + 1”. Hiding
  it in presentation must not remove it from discovery or handoff processing.
- **Two senders, one device.** The Cast SDK keeps its session while we open a second
  connection for the platform channel. That should be normal (multiple senders per device),
  but it is unproven with this firmware from the same app.
- **Capability gate may be false on our sessions.** `appAllowsGrouping` was true for the
  Mac's Default Media Receiver sessions; the Android app uses the same receiver app, but
  this has not been read on an Android session.
- **Hardware membership changes may be audible.** They can chime or move existing music.
  Only mocks and passive reads are silent validation. A paused-session test is not an
  exemption from explicit authorization.

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
2. Add Kitchen once through the chosen path. For the raw path, inspect the correlated
   reply and `failedDevices`. Verify exact Office + Kitchen membership and fresh group
   media/session continuity. Physical members may report UNKNOWN after migration; do not
   demand independent PLAYING reports where the group is authoritative.
3. Confirm audibly that both speakers play the same music.
4. Remove Kitchen again and confirm Office alone still plays.
5. Record source/session identity, timings, and the exact commands sent.

## Documentation and source review findings

Primary references checked October 17, 2026:

- [Google Output Switcher guide](https://developers.google.com/cast/docs/android_sender/output_switcher),
  especially “Enable Output Switcher in AndroidManifest.xml,” “Remote-to-remote” and
  “Testing remote-to-remote.” It describes expansion and removal through system UI,
  requires the relevant audio receiver configuration, and describes updating the group
  name via `Cast.Listener.onDeviceNameChanged`. This is not documentation of a public
  membership command for our custom checkboxes.
- [CastSession reference](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/CastSession):
  `sendMessage(namespace, message)` targets the connected application.

Local source findings from this review:

- `androidApp/src/main/AndroidManifest.xml` does not declare
  `androidx.mediarouter.media.MediaTransferReceiver`. The inspected merged-manifest
  outputs also had no match. Recheck the actual installed/build variant before treating
  this as definitive runtime configuration. Google requires this declaration for Output
  Switcher integration. Adding it alone is not a demonstrated fix.
- `CastOptionsProvider.kt` uses the Default Media Receiver and already enables session
  transfer. That setting alone does not demonstrate stream-expansion eligibility.
- No `SessionTransferCallback` integration was found in the app source search. Determine
  which lifecycle hooks are required for the remote-to-remote path rather than copying
  local-playback sample behaviour into this remote-only product.
- Android already contains passive CASTV2 transport primitives; the original handoff
  overstated how much transport was absent, and understated robust transport complexity.
- The Python implementation adopts/locates the returned endpoint before reporting
  `failedDevices`, so partial failure does not leave Stop aimed at the obsolete receiver.

### Confidence boundaries

| Finding | Confidence and basis |
| --- | --- |
| Current plain-route gate explains locked checkboxes | High: inspected code plus recorded route diagnostics |
| Receivers can form temporary playback groups | High for recorded Mac/Google Home sessions; not universal firmware proof |
| Google documents system stream expansion | High: official guide |
| Moozaq currently has complete Output Switcher integration | Not established; concrete manifest gap found |
| Default Media Receiver qualifies on this Pixel | Unknown; must inspect/test, not infer from the ability to play audio |
| A public API can drive our custom checkboxes | Unknown; system UI support does not prove this |
| Raw Android mutation and subsequent control will work | Plausible from Mac evidence; not hardware-validated on Android |
| Paused membership changes preserve pause | Required behaviour; not demonstrated by the playing-only Mac examples |

## Next agent investigation sequence

### Gate 1 — Audit the supported integration without changing speakers

1. Read current direction, Cast lessons and recovery/route-selection OpenSpec contracts.
   Check current source and installed version; 0.4.1 is the last recorded baseline in this
   investigation, not a fresh device-version assertion.
2. Compare the current manifest, merged manifest, media notification/session setup,
   SDK/MediaRouter versions, Android version and transfer listeners with Google's guide.
   Inspect dependencies as well as app code; a missing app class does not prove the SDK
   supplies no equivalent behaviour.
3. Establish whether the Default Media Receiver meets the guide's audio-app condition.
   Check authoritative receiver documentation/configuration and passive session
   capabilities where available. Do not register/change receiver configuration silently.
4. Identify how to open the actual system Output Switcher. Do not confuse it with
   `DIALOG_TYPE_DYNAMIC_GROUP` or our Compose picker.
5. Record how the app receives expanded device identity, membership, media state and
   control readiness. Check foreground/background lifetime and existing recovery guards:
   intentional session migration must not be mistaken for an unsolicited route failure.

**Deliverable:** a short gap list distinguishing documented prerequisites, locally missing
integration and runtime unknowns. If integration changes are needed, propose them as a
bounded OpenSpec change before implementation.

### Gate 2 — Determine whether the official path meets our UI requirements

Identify documented APIs, if any, for initiating member changes from Moozaq's checkbox UI.
Separate the ability to open Google's UI from the ability to implement our own controls.
Do not interpret a renamed Cast device as proof of exact participant membership.

If only the system UI works, present that as a product tradeoff: supported expansion via
Google's chooser versus custom controls backed by a private protocol. Do not silently
replace the requested UI or claim that the two are equivalent.

### Gate 3 — Authorized hardware evaluation of the supported path

Only after explicit authorization naming the intended speakers, test one add and one
remove during an existing session. Obtain approval separately for any new audio start.
Record original and resulting session/source fingerprints, membership, position, playback
state, SDK events and timing. Check that Moozaq retains control without LOAD, PLAY or
remembered volume changes. Confirm audibility with the owner when testing playing audio.

A separate authorized paused-session run must establish that adding/removing a participant
does not resume it. A successful playing test does not cover this case. If the supported
path fails, record the exact eligibility or integration failure before choosing a fallback.

### Gate 4 — Raw protocol fallback only with a clear reason

If the official path cannot deliver the agreed experience, design the Mac protocol port.
Resolve endpoint adoption first: how Android will control the returned group before route
discovery catches up, and how the SDK and direct socket avoid competing recovery actions.
Reuse passive framing only after hardening it for this longer transaction.

Prove cancellation and partial-failure handling with mocks before hardware. Include:

- Old/changed session or media between eligibility read and send.
- Wrong namespace, wrong request ID, malformed frame and unexpected endpoint.
- Missing endpoint, delayed discovery and delayed exact membership.
- Partial join with a moved endpoint; Stop must follow actual playback location.
- Timeout with unknown outcome; no duplicate mutation or automatic playback retry.
- Stop before send, Stop after send, new target, background and process lifecycle changes.
- Playing and paused sessions, external voice controls and stale SDK callbacks.
- Temporary groups retained internally but absent from visible device choices.

Then conduct a specifically authorized bounded hardware trial; keep Android transport
success, owner audible confirmation and long-session reliability as separate evidence.

## Implementation decision and completion criteria

Do not choose the raw protocol solely because the jar search found no provider class.
Choose the supported path if it meets the required experience; otherwise document the
precise gap and the maintenance cost of the private protocol before proposing the port.

Completion means a user can add/remove a supported physical speaker with confirmed
membership, stable pending UI, preserved media/state and continuing controls. Stop must
work across migration. Saved groups must remain unchanged, temporary group rows hidden,
and no mutation may silently turn into independent speaker LOADs. One successful hardware
run is feasibility evidence, not a claim of general reliability.

No runtime changes, builds, installations or hardware membership tests were performed for
this handoff update. The previous 0.4.1 tests and limitations remain historical evidence.

## Suggested next agent instruction

> Read docs/current-direction.md, docs/google-cast-lessons.md and
> docs/android-playback-membership-investigation.md. Start with the supported Output
> Switcher audit and receiver eligibility check. Do not assume raw CASTV2 is necessary,
> and do not assume system UI grouping exposes APIs for our own checkboxes. Report the
> evidence and propose a bounded OpenSpec change for the chosen path. Preserve hidden
> temporary groups, paused state, session-bound pending controls and Stop across handoff.
> No audible playback or membership changes are authorized by this investigation request.

Related: `docs/google-cast-lessons.md`, `docs/spike-results.md` (Oct 16 membership
sections), `docs/speaker-selection.md`.
