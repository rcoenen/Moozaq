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

The Mac implementation is experimental protocol work, recovered from local Google Home inspection and validated against our receivers. It is **not a documented public Mac sender API or a guarantee for other devices/firmware**. Android's documented Output Switcher is a separate integration path; the shared Compose Android library does not implement that backend yet.

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
- **Not established:** repeated cold starts across devices/firmware, measured acoustic synchronization, leader failover, three-hour reliability, Android rejoining, or complete source coverage. Simulated recovery is not proof of every live migration case.
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
