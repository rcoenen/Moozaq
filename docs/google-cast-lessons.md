# Google Cast and speaker groups: engineering lessons

Last reviewed: October 17, 2026. This is the practical reference for Cast work in Moozaq. Read [current direction](current-direction.md) for owner decisions, [control requirements](playback-control-requirements.md) for interaction rules, and [spike results](spike-results.md) for dated evidence. Earlier limitations in the chronological notes may have been superseded.

## Keep these concepts separate

| Concept | Meaning in Moozaq |
|---|---|
| Physical speaker | A discovered device identified by UUID, with its own volume and mute state. |
| Saved group | A persistent Google Home configuration, such as All speakers. It has its own Cast receiver identity and endpoint. |
| Temporary playback group | The participants of a live session. Expansion/contraction can create, replace or remove its advertised group identity. |
| Playback destination | Where the next explicit Play sends the mix. Selecting it joins a remote without starting or transferring music. |
| Controlled session | The receiver session the app is currently observing and controlling. Other speakers can be playing unrelated music. |
| Confirmed playback | Fresh receiver evidence of the expected stream and state. It does not establish audible output from every member. |

Never construct synchronized audio by loading the same stream independently on several speakers. Use the native Cast group/session mechanism. Do not treat muting as removing a member, or editing a saved group as changing temporary playback membership.

## The most important failure: monitoring can interrupt playback

**Install `install_passive_status(cast)` before starting every main, member, diagnostic or replacement connection.** In the inspected PyChromecast version, the normal media `update_status()` helper can launch the Default Media Receiver if the media channel is missing. Connection callbacks can use this helper too, so guarding only later polling is insufficient.

Our member monitoring accidentally launched individual receivers while group playback was starting. The group briefly reported PLAYING, then its session disappeared. The corrected passive helper sends guarded GET_STATUS only when connected with the required destination and media namespace; otherwise it sends nothing. Explicit Play remains the only path permitted to launch media.

After this fix, the owner audibly confirmed all four All speakers members in one direct-route session. That before/after result establishes this specific interference bug, not the cause of every earlier group failure.

Code: [engine.py](../mac-spike/engine.py), [passive status tests](../mac-spike/test_passive_status.py).

## Discovery is not connectivity, and connectivity is not synchronization

- An absent mDNS announcement does not prove a speaker is offline. Office answered device-info while Python discovery omitted it; macOS Bonjour also saw it during a separate check.
- Seed verified physical addresses through `CastBrowser(known_hosts=...)` **before** starting discovery. Adding the hint after startup can miss the first host scan and wait the full 30-second polling cycle. Our controlled measurement improved from 30.69 seconds to 0.51 seconds; the updated app connected in about one second in the observed run.
- An address is only a hint. Validate the discovered UUID against the requested device; DHCP can reassign addresses.
- PyChromecast retains discovery records after their last service disappears. Use `discovered_cast_infos()` to exclude empty-service records from availability, selection and recovery.
- Keep known physical devices visible as unavailable across discovery gaps. Do not preserve temporary groups as permanent inventory.
- A monitor socket created with `tries=1` can stop permanently after a failed connection. Retire stopped connections and create a new passively guarded connection on a later poll.
- Do not enable transport just because a control socket connected. Require fresh receiver/media evidence belonging to the selected target.

Group endpoints are discovery data: preserve the reported UUID, Cast type, service set, host and port. A group can use a dedicated port on its elected coordinator; never replace it with the physical speaker's port 8009 or hard-code the current leader.

Code: [engine.py](../mac-spike/engine.py), [recovery.py](../compose-app/bridge/recovery.py), [inventory.py](../compose-app/bridge/inventory.py), [outputs.py](../compose-app/bridge/outputs.py).

## Native temporary grouping on Mac

The Mac implementation is experimental protocol work, recovered from local Google Home inspection and validated against our receivers. It is **not a documented public Mac sender API or a guarantee for other devices/firmware**. Android 0.4.2 sends the same command on its own platform channel to `receiver-0`. `CastSession.sendMessage` still cannot address that channel. Output Switcher was checked first and does not drive Moozaq's checkboxes: the app has no `MediaTransferReceiver`, and the public Default Media Receiver is not a console Cast-for-Audio app. See [android-playback-membership-investigation.md](android-playback-membership-investigation.md). A passive read on 2026-10-17 found Bedroom speaker already playing on the Default Media Receiver with `appAllowsGrouping`, `streamTransferSupported`, and `isVideoContent=false`.

One authorized Kitchen add the same day, while that Bedroom session was playing, sent one `SET_PLAYBACK_DEVICES`. The receivers moved the mix onto the temporary group `Bedroom speaker + 1` and kept it playing (same session, two members). About ten seconds later the phone logged `membership.failed sent=true located=false` and stayed aimed at Bedroom, whose app was gone. Status polls then returned 2103. The confirmation read had already blocked for its socket timeout, and the clock slept that same budget again, so the phone gave up before it could see the group. Android 0.4.3 keeps reading until the deadline. After a sent command that never returns a location, it reads discovered groups and selects the one group whose session, media, and active player state match. That selection is one `route.select()` with `relaunchIfRunning` false. It does not send `SET_PLAYBACK_DEVICES` again and does not load the mix. The unfinished checkbox is cleared and status polls stop against the pre-change speaker while the location is still uncertain. That same-process path has not been re-run as another add or remove. Opening the app is a separate hole: 0.4.3 ran the follow only after a sent command, and a fresh open found no Default Media Receiver on Bedroom and stopped while the mix kept playing on the hidden group. Android 0.4.4 waits about eight seconds after that idle result, or after a connected speaker reports no media, then reads discovered groups and selects the one group playing that session. The session id comes from the Cast session when one is connected, and otherwise from a receiver `GET_STATUS` on the remembered speaker. The temporary group is not saved. One reopen on 2026-10-18 joined `Bedroom speaker + 1` with no LOAD and no second `SET_PLAYBACK_DEVICES`; the group was still playing afterward. Remove, a second add, and Stop or pause of the moved session remain unproven.

Observed protocol contract:

| Field/operation | Important detail |
|---|---|
| Namespace | `urn:x-cast:com.google.cast.multizone` |
| Query | `GET_STATUS`; inspect fresh membership and `playbackSession` capabilities. |
| Mutation | `SET_PLAYBACK_DEVICES`, sent once with a correlated `requestId`. |
| Session | Receiver **application** `sessionId`, not numeric media `mediaSessionId`. |
| Desired participants | Full `deviceList` of physical device IDs, addresses and ports; not a list of group UUIDs. Wire IDs omit UUID hyphens. |
| Eligibility | Require `appAllowsGrouping=true`, `streamTransferSupported=true`, and `isVideoContent=false` from the actual session. |
| Response | `PLAYBACK_SESSION_UPDATED`; inspect `failedDevices` and optional `sessionEndpoint`. |

Rules learned from live changes:

1. Bind the operation to the current receiver, application session, media fingerprint, membership revision and cancellation generation. Recheck capabilities and membership immediately before sending.
2. Do not automatically retry the mutation after a timeout. The first command may already have moved playback.
3. A returned endpoint requires validation against requested local participants before connecting. Adopt it only after confirming fresh media identity and session continuity.
4. **An omitted `sessionEndpoint` is not automatically a failure.** Fridge joined an existing temporary group without an endpoint handoff. Confirm the original connection still owns the same application session and media, then read its membership.
5. **Membership converges after playback becomes visible.** An initial PLAYING response can contain only the coordinator. The implementation waits up to 12 seconds for fresh exact membership rather than failing immediately or sending SET again.
6. A partial response, stale membership or changed media is not full success. Preserve uncertainty and requested UI state; offer an explicit state read rather than silent rollback.
7. Stop before mutation invalidates it. Stop after mutation must first locate the possibly moved session, then stop that session. Never claim success by stopping only the obsolete endpoint.
8. Keep the saved All speakers configuration unchanged. Do not substitute persistent `join_group`/`disband_group` setup operations.

Google Home itself displayed a connection error during an expansion that produced a working temporary group. Use fresh topology/session evidence and listening confirmation to assess the result; neither a success toast nor an error toast is conclusive by itself.

Code: [grouping.py](../mac-spike/grouping.py), [membership.py](../compose-app/bridge/membership.py), [following.py](../compose-app/bridge/following.py). Regression coverage: `test_grouping_protocol.py`, `test_membership.py`, `test_following.py`.

## Observe all outputs without inventing playback state

Individual media state can become UNKNOWN when the authoritative session moves to a group. Attribute playback to physical members only from fresh group membership and a fresh active group session. A saved group's configured members alone do not prove they are playing.

Preserve independent sessions: Kitchen and Bedroom were playing the same mix while Office played different jazz. Inspecting one must not stop, resume or reload the other. Label other music separately. Ambiguous group ownership stays unconfirmed.

When a temporary group vanishes, retain the last track as unconfirmed while searching. Follow only a unique fresh candidate with the same media fingerprint, overlapping physical membership and plausible position continuity; never match by title alone. Missing discovery is not evidence that playback stopped. Across a full app restart, only remote/member preferences persist; in-memory session continuity does not.

## Controls and UI

Use **intent → pending → confirmed** for destination selection, membership, volume, seek and transport:

- Hold the requested checkbox/slider value while pending; use local colour plus text. Stale polls must not move it back and forth.
- Confirm from fresh state for the correct target/session. A cached matching value is insufficient.
- Keep failure visible; do not silently restore the old value or resend the command.
- Volume and membership work must not replace the playback label with a generic “updating” message.
- Stop stays available during pending work; late extraction/load completions must not restart music.
- Seek must preserve paused state. The inspected library convenience method forced playback start, so Moozaq uses an explicit state-preserving SEEK.

Current selection semantics: while idle, physical speakers and groups are exclusive destinations for the next Play. Choosing a physical speaker clears All speakers and vice versa. During active/paused/buffering playback, physical checkboxes represent live participants; group selection still chooses the destination for Play. Do not let pending membership operations silently change meaning. These semantics are owner decisions, not Cast protocol requirements.

Group volume is not confirmation of identical per-member volume. Read each member independently when validating requested levels. The same percentage can sound different across rooms and hardware. Reconnection must never restore remembered volumes, positions or playback.

## Troubleshooting sequence

Start read-only. Building, connecting, inspecting and testing do not authorize audible playback, test tones or automatic retries.

1. Inspect discovery and verify device UUID/type/endpoint. Compare native Bonjour with Python discovery if a device disappears.
2. Check direct reachability and fresh device identity. Separate a discovery delay from a control connection failure.
3. Read receiver app/session, media identity/state and fresh topology. Distinguish idle, unknown, disconnected and a session moved to a temporary group.
4. Check stale service entries, stopped monitor threads, and passive-status installation order before blaming firmware or the router.
5. For a previously authorized membership operation, inspect the correlated reply, endpoint handoff, exact membership convergence and session continuity. Never issue another mutation merely because confirmation was slow.
6. Only with explicit playback authorization, compare actual audible output in the relevant rooms. A group PLAYING report or connection chime is insufficient.

Existing read-only tools, run from the repository root:

```sh
mac-spike/.venv/bin/python mac-spike/tools/inspect_groups.py
mac-spike/.venv/bin/python mac-spike/tools/grouping_probe.py --label baseline
```

Keep reports in ignored `mac-spike/data/`. Never log credentials or signed media URLs. Preserve sanitized evidence before restarting anything; router changes, receiver resets and firmware changes are not default debugging steps.

## Evidence and remaining limits

- **Audible:** all four saved-group members worked after the passive-status fix in one session. Kitchen/Bedroom shared a temporary session; the owner later heard Office join the existing temporary mix through the Mac checkbox.
- **Receiver-state verified:** native Mac expansion/contraction, in-place expansion without an endpoint, exact membership convergence, and independent-session preservation in the recorded scenarios.
- **Silent tests:** stale evidence, cancellation, pending state, endpoint validation, discovery race, monitor recovery and session-following decisions. See the evidence log for test counts at each change.
- **Not established:** repeated cold starts across devices/firmware, measured acoustic synchronization, leader failover, three-hour reliability, Android rejoining beyond the limited 0.3.3 Office validation below, or complete source coverage. Simulated recovery is not proof of every live migration case.
- **Unresolved:** historical SSL BAD_LENGTH/BAD_RECORD_MAC failures. A similar upstream report exists, but does not prove our cause or justify weakening TLS settings.
- **Separate question:** successful direct YouTube audio playback without Premium does not establish production distribution or music rights permission.

## Primary references

These were consulted during the investigation; local behavior and version-specific findings are recorded above, not promised by the references.

- [Google Cast discovery troubleshooting](https://developers.google.com/cast/docs/discovery): sender/receiver/network separation and service inspection; YouTube and Netflix use specialized discovery and are unsuitable baseline discovery tests.
- [Google Home multiroom controls](https://support.google.com/googlehome/answer/10012636?hl=en): user-facing multiroom behavior.
- [Android Cast Output Switcher](https://developers.google.com/cast/docs/android_sender/output_switcher): documented Android integration path.
- [PyChromecast](https://github.com/home-assistant-libs/pychromecast), [discovery source](https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/discovery.py), [multizone source](https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/controllers/multizone.py): distinguish monitoring registration methods from remote membership mutations. Our discovery inspection used installed version 14.0.10; upstream master can change.
- [Home Assistant Cast integration](https://www.home-assistant.io/integrations/cast/): multicast discovery and known-host fallback.
- [Home Assistant issue 141619](https://github.com/home-assistant/core/issues/141619): similar SSL BAD_LENGTH report, closed without an established fix.

When a new issue is understood, update the relevant rule here and append the dated experiment, observed outcome and limitations to [spike-results.md](spike-results.md). Keep failed hypotheses as historical evidence rather than presenting them as current implementation limits.

## Android source compatibility and transient errors

A successful tiny Range request is not sufficient to validate a direct receiver
URL. On October 17 the old Android resolver produced AAC URLs with HTTP 206 for
a ranged request but HTTP 403 for an ordinary GET. Pinning the same upstream
resolver version as Mac changed the phone’s ordinary fetch to HTTP 200. Test
ordinary header-free access before LOAD, read only a bounded amount, and keep
the URL private. Check cancellation and session binding again after preflight.
A successful phone fetch still does not establish Nest playback.

Google’s LOAD acknowledgement only establishes acceptance of the request. Keep
watching for the expected content in PLAYING, and latch a subsequent explicit
ERROR while that attempt is active; empty later statuses must not turn it into
an unexplained timeout. Ignore failures belonging to previously observed media.
See [Google’s media status definitions](https://developers.google.com/android/reference/com/google/android/gms/cast/MediaStatus)
and the dated Android investigation in [spike results](spike-results.md).

On Android, discovery gaps should mark remembered device rows unavailable, not
remove/reinsert them or resize the picker. Preserve sorting and row identity.
An explicit SDK connection failure should release the pending state immediately
and allow a manual retry; never infer PLAYING or send LOAD as part of recovery.


### Android cold-start recovery and SDK error 2152 (2026-10-17)

The owner's Android log confirmed PLAYING, followed by app close, a cold start with no current session, `onSessionResuming`, and a failed resume with 2152 ten seconds later. The old backend then had neither an attached session nor a remembered destination. Do not interpret that as Nothing playing: the owner could still hear Bedroom.

A [report in Google's sample project](https://github.com/googlecast/CastVideos-android/issues/144) reproduces error 2152 after killing/reopening Cast SDK 22.x apps. This is corroborating evidence, not a definitive diagnosis of our SDK internals. [Google's SessionManager documentation](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/SessionManager) states that failed resume unselects the route; enabling saved-session resume alone therefore does not guarantee recovery.

Android 0.3.2 remembers stable Cast device ID plus route/name/type, keeps an unavailable placeholder until discovery, and lets native restoration run first. After failure it checks only that remembered LAN endpoint using a bounded Cast receiver GET_STATUS probe. It selects the rediscovered route only when the Default Media Receiver is already running, with `LaunchOptions.relaunchIfRunning=false`. No LOAD/PLAY/seek/volume or extraction runs in recovery. A different receiver app, missing device or failed probe remains unconfirmed; checks back off from 5 to 30 seconds. Manual selection and backgrounding invalidate pending probes. This fallback is a status check followed by SDK selection, not an atomic join-only API; a receiver-app change between those steps remains a race to investigate.

Reconstruct media identity from receiver-returned Moozaq customData and its content hash, not a title match or remembered Playing flag. Persist no signed URL and no authoritative media state. Read current position and volume only after attaching.

### Android 0.3.3 post-probe guard and faster rejoin (2026-10-17)

Recheck the current SDK session after every asynchronous passive probe, not only before it. Fallback route selection must yield to connected, connecting, resuming and suspended sessions at both decision points; sharing one predicate avoids the observed omission of `isResuming` after the probe. This still does not make the subsequent route selection atomic with the earlier receiver-status response.

With native saved-session restoration disabled and a 500 ms foreground grace, two Office cold-process reopens reached fresh PLAYING in 2.600 and 1.884 seconds, and warm foregrounding received fresh media in 0.090 seconds. Source and application-session fingerprints stayed unchanged and recovery emitted no media/source/volume commands. This supports the faster configuration for one explicitly authorized Office session; it does not prove other devices, network transitions or long sessions. See `spike-results.md` for the authorized playback setup and evidence boundaries.

### Android 2155 and route-change reconciliation (2026-10-17)

`CastContext.getCastReasonCodeForCastStatusCode()` depends on an asynchronously loaded reason bundle. Do not interpret an immediate zero as `UNKNOWN_REASON` proof: wait for `MAP_CAST_STATUS_CODES_TO_CAST_REASON_CODES` to initialize before asserting a mapping. On the Pixel's SDK 22.3.1 runtime, the confirmed mapping is **2155 → `CASTING_ROUTE_CHANGED`**. This code describes a route transition, not a network or receiver diagnosis. If the bundle is not ready during a callback, only that proven 2155 mapping may be used as a fallback; do not guess any other unknown status.

Classify route events by session role. A stale replaced-session event must not tear down its replacement; an attached session must not be reclaimed automatically. Only an explicit pending first-time join may hold pending UI and receive one delayed route-only reconciliation, and only after fresh generation, target identity, default phone route and idle SDK checks pass. Cancel it on newer selection, background, Stop, close or attachment; never retry LOAD/PLAY/seek/volume/source resolution, and never select over another Cast route. A second failure remains explicit and manual.

The original Office route-reversal actor is still unknown. Version 0.3.4 passed 35 Android and 34 shared tests plus the focused on-device mapping check, but no natural recurrence or active-session cold rejoin was available for hardware validation of this build.
