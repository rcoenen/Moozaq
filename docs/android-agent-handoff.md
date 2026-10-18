# Android session handoff

Date: 2026-10-18 (phone clock 21:15, US Eastern). Written so another agent can continue without this chat.

Read [current-direction.md](current-direction.md), [google-cast-lessons.md](google-cast-lessons.md), [playback-control-requirements.md](playback-control-requirements.md), and [spike-results.md](spike-results.md) before changing Cast code. This note is the narrative. Those files are the durable record. Do not archive the OpenSpec change `android-live-membership`.

## Rules that still bind

- Do not start or resume audio unless the owner explicitly asks. Install, open, inspect, and unit tests do not authorize playback.
- Do not add or remove a speaker unless the owner names that speaker. The only authorized membership change so far is one Kitchen add on 2026-10-17.
- Do not use Office speaker or the All speakers group unless the owner names them. Bedroom was the original playback speaker. Kitchen is in the current temporary group because of that one add.
- Do not send a second `SET_PLAYBACK_DEVICES` to "finish" an old command. Empty membership is Stop, not an empty SET.
- Stop outranks other work. Confirm the receiver actually stopped. A sent command is not success.
- Reconnect and status reads must not load media, seek, or restore a remembered volume.
- A temporary group is where a moved mix plays. Do not save it as the remembered speaker.
- Do not disable the lock screen or type the phone password. UI automation only when `isKeyguardShowing=false`. If the phone is locked, ask the owner to unlock it.
- Keep stream URLs and credentials out of logs and this note.

## What the owner wants

An Android remote for long YouTube mixes on the owner's Nest speakers. Ordinary YouTube links. No YouTube Premium. The phone may leave. The Nest keeps the audio. Coming back synchronizes. It must not restart the mix or push an old volume or position.

Custom Cast-to checkboxes are the required control. Google Output Switcher cannot drive them. The app uses the public Default Media Receiver (`CC1AD845`). There is no Cast Developer Console registration and no `MediaTransferReceiver`. `CastSession.sendMessage` cannot address `receiver-0`. The phone therefore speaks CASTV2 itself, the same multizone command the Mac app already uses.

## Devices and build

- Pixel 6a (`bluejay`), Android 17, ADB serial `adb-2A251JEGR08539-V8FfyR._adb-tls-connect._tcp`. Re-check `adb devices -l`. The transport id changes.
- Package `com.moozaq.app`. Installed build is **0.5.0-standalone / versionCode 18**. It replaced 0.4.7 on 2026-10-18.
- APK: `compose-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- Build from `compose-app`, not the repo root. `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.
- Install: `python3 compose-app/install-phone.py --serial '<serial>'`. That runs `adb install -r`, removes `adb reverse tcp:38765`, and starts `MainActivity`. It sends no playback command. `install -r` keeps preferences.
- Log: `adb shell run-as com.moozaq.app tail files/cast-debug.log`. Logcat tag `MoozaqCast`. Preferences: `shared_prefs/cast-destination.xml`.
- Speakers seen: Bedroom, Kitchen, Fridge, Office, Bedroom TV, saved group All speakers, and temporary group `Bedroom speaker + 1`.
- Remembered speaker after the 0.4.4 join: Bedroom speaker, device `9818a09f20775ca679d79f88c7aca98f`, kind `audio`. The temporary group was not saved. That is intentional.

## Timeline

### 0.4.2 — one Kitchen add, phone left behind

Gates 1 and 2 closed. Output Switcher stays out. 0.4.2 (code 12) sends one `SET_PLAYBACK_DEVICES` on `urn:x-cast:com.google.cast.multizone` to `receiver-0` when a fresh session has `appAllowsGrouping`, `streamTransferSupported`, and `isVideoContent=false`. No automatic retry. Pause is preserved with one `PAUSE`. Stop before the write cancels it. Stop after the write adopts, then stops that session.

The owner named Kitchen. One add while Bedroom was playing:

- `membership.request speakers=2` at 2026-10-18T00:10:07.966Z.
- `membership.failed sent=true located=false` about 10.4s later.
- UI: "Speaker selection was not confirmed." Kitchen stayed checked because the in-flight request was not cleared.
- The speakers had already moved the same session onto `Bedroom speaker + 1` (Default Media Receiver, two members, `PLAYING`, session hash `efb0f27b3183`).
- The phone stayed on Bedroom: `connected=false`, status 2103, frozen position. Bedroom's own app was no longer the Default Media Receiver.

`located=false` means the outcome was null. It was not a converge failure. The real socket blocked for the whole read timeout, then `MembershipClock` slept that same budget again. The phone gave up at about ten seconds.

### 0.4.3 — confirmation fix, same process only

Installed as code 13. Three parts, all still in the source:

1. `LanCastTransport.waitsInsideReceive()` is true. `await()` does not sleep a real socket timeout a second time. Scripted tests still advance the manual clock.
2. After a sent `MembershipRejected` with `outcome == null`, clear `pendingMembers`, set `uncertain`, and `followUnlocated()` once. `chooseMovedGroup` selects a route only when exactly one discovered group is not the current route, has the same session, the same media when the content id is known, an active player (`PLAYING` / `PAUSED` / `BUFFERING`), and a literal private host. Then one `route.select()` with `relaunchIfRunning` false. No second SET. No LOAD. Zero or two matches leave the route alone. All speakers is not selected unless that read is the moved session.
3. The checkbox returns to the last confirmed members and keeps the error text. Status polls stop while `uncertain` or `useDirect()`.

Unit tests for that fix passed before install (50 Android tests at the time, lint, 56 desktop tests). It was **not** re-run as another add or remove. Opening 0.4.3 did not use this path. `followUnlocated` ran only after a sent command in that same process. `recover()` returns immediately when a Cast SDK session is already connected. On a fresh open it probed Bedroom, found no Default Media Receiver, reported nothing playing, and stopped. The mix kept playing on the hidden group. The Cast-to sheet is a destination picker when the phone is not on the media session. Tapping a speaker there is `selectDevice`, not a membership SET. The temporary group is filtered out of the picker, so the owner cannot tap it.

### 0.4.4 — reopen joins the group that is already playing

This is installed and was checked once.

`shouldSeekMovedGroup`: connected, no media, blind for at least 8 seconds, not already adopted. A status failure with no media sets `blindSince` and further `requestStatus()` polls stop.

`shouldSeekIdleGroup`: not connected, recovery has settled on `IDLE` with no error, idle for at least 8 seconds, not adopted. This is the restart case. 0.4.3 never reached it.

`seekMovedGroup` then peeks discovered group routes (`CONNECT` + receiver `GET_STATUS`, and media `GET_STATUS` only when the Default Media Receiver is already running). Session id comes from the Cast session when one is connected. Otherwise `receiverSessionId()` reads it from the remembered speaker, including when that speaker is running a different app. Bedroom was doing that: app `531A4F84`, same session hash as the group. Exactly one match is adopted. `route.select()` once. No SET. No LOAD. If the route disappears before select, the adoption is dropped so a later pass can look again. A temporary group is not written to `cast-destination.xml`.

Hardware, 2026-10-18T01:05Z, mix already playing, no new mix started by this install:

- Before: `Bedroom speaker + 1` app `CC1AD845`, `PLAYING`, 2 members, session `efb0f27b3183`, position 3557. Bedroom app `531A4F84`, same session hash, no mix. Kitchen a different app and session `b369ab0aabcf`. Fridge and All speakers had no receiver app.
- New process: `backend.created version=0.4.4-standalone currentSession=false`, then `IDLE`.
- 2026-10-18T01:05:51Z: `membership.follow matched=true groups=2`, `membership.adopt route=Bedroom speaker + 1`.
- Attach: `media.status` playing, same session hash, position 3603 then 3619.
- Log after `backend.created` had no `media.load`, `play.request`, `command.request`, or `membership.request`.
- After: same group still `PLAYING`, 2 members, same session, position 3658. Office was discovered with no app and was not selected.
- `groups=2` was All speakers plus the temporary group. Only the temporary group matched.

Android unit tests after the 0.4.4 code: 51 tests, 0 failures. `openspec validate android-live-membership --strict` passed. Lint was not re-run for 0.4.4.

## Code map

| Piece | Where |
|---|---|
| Multizone conversation, peek, group choice | `compose-app/androidApp/src/main/java/com/moozaq/app/MembershipChannel.kt` |
| Discovery, recovery, follow, volume, route select | `compose-app/androidApp/src/main/java/com/moozaq/app/AndroidBackend.kt` |
| Member-volume rules and receiver-only path | `compose-app/androidApp/src/main/java/com/moozaq/app/MemberVolume.kt` |
| Stale adopted-endpoint grace rule | `compose-app/androidApp/src/main/java/com/moozaq/app/Adoption.kt` |
| Receiver `SET_VOLUME` / `GET_STATUS` volume primitives | `compose-app/androidApp/src/main/java/com/moozaq/app/MembershipChannel.kt` (`setVolume`, `receiverVolume`) |
| Cast options: no saved-session resume, `relaunchIfRunning` false, do not stop the receiver when the session ends | `compose-app/androidApp/src/main/java/com/moozaq/app/CastOptionsProvider.kt` |
| Passive probe used by recovery. Receiver `GET_STATUS` only | `compose-app/androidApp/src/main/java/com/moozaq/app/PassiveReceiverProbe.kt` |
| Cast-to sheet | `compose-app/composeApp/src/commonMain/kotlin/com/moozaq/PhoneCastTo.kt` |
| Slider enable rule | `canAdjustOutputVolume` in `compose-app/composeApp/src/commonMain/kotlin/com/moozaq/Outputs.kt` |
| Picker hides temporary groups | `pickerDevices` in `compose-app/composeApp/src/commonMain/kotlin/com/moozaq/Player.kt` |
| Sheet header "Casting to N speakers" | `PhoneModel.kt` when the target device is `temporary` |
| Mac member volume that actually works | `set_member_volume` in `compose-app/bridge/groups.py` |
| OpenSpec (still open) | `openspec/changes/android-live-membership/` |

`pickerDevices` is `devices.filterNot { it.temporary }` plus any group member that is missing from discovery. `Device.temporary` is true when the canonical id is in `dynamicIds`, or the route is a `GroupRouteInfo` that is not in `router.routes`. `followUnlocated` adds the adopted group id to `dynamicIds` before `route.select()`, so the next attach does not remember it.

## Open problem: volume does not change

The owner confirmed both speakers are audible. The Cast-to sheet shows "Casting to 2 speakers", Bedroom and Kitchen checked, and the other rows unchecked. The volume tracks on that sheet are the disabled colour. Enabled slider tint is `colors.primary` (the same orange as a checked box). Disabled tint is `colors.outline`. The screenshot matches disabled.

### What the log shows after the successful join

The 0.4.4 process later did this, from the phone UI, not from the install:

- 2026-10-18T01:15:11Z `play.request video=nO1BgJFMT2o device=Bedroom speaker + 1`
- 2026-10-18T01:15:16Z `media.load` on that group, acknowledged at 01:15:17Z
- Snapshot then shows that video id, `PLAYING`, position 0 and climbing (15, 30, 46)
- 2026-10-18T01:15:31Z and 01:16:16Z `command.request volume`
- No `command.failed` after either volume request
- 2026-10-18T01:16:19Z `lifecycle.foreground false`. The log file ended there. The phone was on the keyguard when last checked.

The earlier join was a different video id (`Xdho5SVxEVI`) around position 3600. This later load replaced that timeline. Do not reload it. Do not treat it as part of the reopen fix.

### Why the sheet sliders stay disabled

`CastToRow` treats a row as selected only when `snapshot.target == device.id`. The target is the temporary group, which is hidden. Bedroom and Kitchen are members, so they are not selected.

For an unselected row, `canAdjustOutputVolume` requires `output.connected` and `output.volume != null`. `AndroidBackend.snapshot` sets `OutputState.volume` only when that MediaRouter route has `volumeMax > 0` and `PLAYBACK_VOLUME_VARIABLE`. Otherwise the volume is null and the slider is disabled.

One `dumpsys media_router` line during this session was `volume(current=0, max=0, handling=FIXED(0))`. The filter was too coarse to assign that line to a named route. The next pass needs each route's name, `volumeMax`, and handling. The likely case is that member routes are fixed or volumeless while the selected route is the group, so every visible slider fails the null check.

`outputVolume` is a different command from the one in the log. It calls `MediaRouter.RouteInfo.requestSetVolume` and does not log `command.request`. The two logged volume commands are `command("volume")`, which is the Now playing session slider (`store.mainVolume`). The sheet would only call that for a row whose id is the target. The visible rows are not the group.

### Why the session volume command can look like it did nothing

`AndroidBackend.command` for `"volume"` is outside `transport()`. A failure does **not** write `command.failed`. Absence of that line is not success.

The wait is:

```kotlin
s.volume = level
receiverDeadline(10_000, "The speaker did not confirm the new volume.") {
    while (session === s && (volumeRevision <= revision || abs(s.volume - level) > .015)) delay(100)
}
```

`volumeRevision` increments only in `Cast.Listener.onVolumeChanged`. Both a new revision and a matching level are required. If the callback never arrives, this times out after 10 seconds even when the setter already changed the local value. The thrown message is "The speaker did not confirm the new volume." `reconcileMainVolume` keeps the requested thumb for 10 seconds, then marks it failed. It does not by itself prove the Nest volume changed.

A second volume request cancels the in-flight `volumeJobs` entry. The first request can die as cancellation, which also is not logged as `command.failed`.

On a temporary group, `CastSession.volume` is the group session volume. It is not each speaker's volume. The Mac path that has been shown to change a member is `set_member_volume` in `compose-app/bridge/groups.py`: `set_volume` on that member's own receiver, then a fresh receiver status, and only then a revision bump. A group-level write must not be treated as proof that Bedroom and Kitchen each changed. See "Individual group volume feedback" in `docs/playback-control-requirements.md`.

`MembershipChannel.exec(MediaCommand.Volume)` already sends receiver `SET_VOLUME`, but it then calls `readMedia` and requires the adopted session's content id. That is the direct-control path for when the SDK is no longer on the session. It is not what the Cast-to row calls, and it is the wrong confirm for a member whose media session lives on the group. Bedroom individually was not running `CC1AD845`.

### What not to do while fixing volume

- Do not send `SET_PLAYBACK_DEVICES`, LOAD, PLAY, or seek.
- Do not add Fridge, Office, or All speakers. Do not remove Kitchen unless the owner asks.
- Do not change a speaker's volume from the agent until the owner asks for that check. A silent unit test can fake the route and the receiver reply.
- A live check, if the owner asks, should name the speaker, set one level, and confirm from that speaker's own fresh volume, not from "the command was sent".
- Keep the pending-thumb rule: hold the requested value, show pending colour and text, confirm from a new revision, and on timeout say it was not confirmed.

### A fix that matches the existing Mac behaviour

1. Dump MediaRouter volume handling per named route so the null-volume claim is tied to Bedroom and Kitchen, not to an unnamed system route.
2. For a speaker that is a member of the playing group, publish a real volume and enable the sheet slider even when that member's MediaRouter route is not `PLAYBACK_VOLUME_VARIABLE`.
3. Send the change to that speaker's receiver with `SET_VOLUME`, then confirm with receiver `GET_STATUS` only. Do not open the media channel and do not launch an app. `CONNECT` + receiver `GET_STATUS` is the probe that has not launched idle speakers.
4. Bump that member's `volume_revision` only after the fresh level matches. `reconcileOutputVolumes` already clears the pending state on that condition.
5. Decide explicitly what the Now playing slider does on a temporary group. One group `CastSession.setVolume` is not a substitute for the two member sliders. If it stays, its timeout must be logged, and success must be a fresh session volume, not the local setter.
6. Add a scripted test that a fixed MediaRouter route still produces an enabled member slider and that the volume write is one `SET_VOLUME` with no LOAD and no multizone SET.

### 0.4.5 implements that member path (installed and live-confirmed, 2026-10-18)

Source is now `0.4.5-standalone` / code 15. It was installed on the Pixel while the `Bedroom speaker + 1` group was playing; a fresh process reopened it with no load and no membership command.

Live member volume, 2026-10-18T01:53Z:

- Foreground read: Bedroom and Kitchen are the group members; the group route is `variable` at max 50, while both member routes report `volumeMax=50` with non-variable handling. Their own sliders were enabled (the screenshot showed a bright Kitchen thumb), unlike the disabled tracks in 0.4.4.
- Owner dragged Bedroom twice and Kitchen four times. Log: `volume.member route=Bedroom speaker confirmed=true` twice, then `volume.member route=Kitchen speaker confirmed=true` four times.
- After the writes: Bedroom 18/50 and Kitchen 32/50 in `router.volume`. A passive read then showed Bedroom's receiver at level 0.3555 with the Default Media Receiver `PLAYING`, and Kitchen at 0.6493 with no app. These are each speaker's own receiver levels, not the group session volume.
- The mix stayed `PLAYING` on the group during the volume writes; no LOAD, PLAY, seek or membership command was sent by the volume path.

The same live run also produced a separate, unresolved result. At 01:53:25Z an unchecked speaker caused `membership.request speakers=1`. The temporary group's receiver ended (`2055 RECEIVER_APP_NOT_RUNNING`), the app detached, and its target became null. The removal itself left the mix playing on Bedroom (passive read: `PLAYING`, position 2413). The app did not rejoin; it kept a stale adopted group endpoint, so `recover()` stayed out and the Cast-to sheet showed "Kitchen speaker cannot join live" for the lost session. The add-back was refused locally and never reached the wire. This is the 0.4.5 defect fixed in 0.4.6.

### 0.4.6 drops a dead adopted group endpoint (2026-10-18)

0.4.5 kept `adoptedEndpoint` after the group's receiver ended. `useDirect()` stayed true, `recover()` returned early, and the phone could not rejoin the remaining Bedroom session until the process restarted. That is why the owner's add-back produced no membership request: the app had no live session to add to.

0.4.6 (code 16) adds two read-only guards. `watchAdoption()` drops an adopted endpoint whose discovered route has been gone for 4 seconds, but never while `adopting` is true. `platformChange` also drops the dead endpoint right after a sent change ends without a confirmed location. Dropping it sends no command; the next snapshot's `recover()` probes the remembered speaker and reattaches with `relaunchIfRunning` false. No SET, LOAD, PLAY or seek is involved.

Installed 2026-10-18. The stuck 0.4.5 session was replaced by a fresh 0.4.6 process, which logged `recovery.rejoin device=Bedroom speaker existingReceiver=true`, selected Bedroom, and attached `PLAYING` to the same session `efb0f27b3183` at position 2651, advancing to 2666. No load or membership command was in that log.

The owner then retried Kitchen. At 02:06:19Z the phone logged `membership.request speakers=2`. The speakers moved the session onto `Bedroom speaker + 1`; at 02:06:25Z the phone logged `membership.follow matched=true groups=1` and `membership.adopt route=Bedroom speaker + 1`, then attached `PLAYING` to the same session at position 3067, advancing to 3068. No `media.load` was sent. The owner confirmed Kitchen was audible. This is the first completed remove-then-add cycle, one attempt only.

The UI caught up a few seconds after the audio: the requested checkmarks held while pending, then the confirmed member list came from the group's periodic multizone read. That is the intent → pending → confirmed rule, not a lost join. By the next `router.volume` reading both Bedroom and Kitchen were `member=true` and both sliders were enabled. A shorter post-change refresh would reduce that lag but would not change what counts as confirmation. Unit tests: 58 Android tests (one new adoption-grace test), `assembleDebug` and `lintDebug` pass.

0.4.6 was then live-exercised as another remove/add toggle. Both removes dropped the dead group endpoint (`membership.adoption.dropped`) and reattached Bedroom within about a second, so the stale-adoption fix works. But each remove still ended with `membership.failed sent=true located=false` and the red "Speaker selection was not confirmed" text, even though playback was on Bedroom. The remove succeeded; the error was a false negative because `followUnlocated` only looks for groups. The add-back after that did work.

### 0.4.7 confirms the remaining speaker and refreshes membership faster (2026-10-18)

Two changes, both read-only on the wire:

1. **No false removal error.** When a sent change has no location and `followUnlocated` finds no group, a removal that leaves exactly one desired speaker now reads that speaker's own receiver and media status (`CONNECT` + receiver/media `GET_STATUS` only). If it reports the same session, content and an active player, the removal is confirmed from fresh state and that speaker is adopted for recovery. No LOAD, PLAY, SET or seek is sent. A real failure still shows the error.
2. **Faster confirmed checkmarks.** A membership change starts a 20 s window of 1.5 s membership reads instead of the normal 8 s cadence; a successful network follow uses 15 s. The fast poll stops as soon as the receiver reports exactly the requested canonical members. Pending checkmarks still hold the request; nothing is shown as confirmed before the group reports it.

Source and installed APK are 0.4.7 / code 17. On a fresh open it rejoined Bedroom with the same session and no load. The live toggle has not been retried on 0.4.7 yet. Unit tests: 59 Android tests (two new refresh/grace rules), `assembleDebug` and `lintDebug` pass. `openspec validate --all --strict` still passes.

### 0.5.0 main volume is the confirmed member average (2026-10-18)

The owner asked Mac and Android to reflect the true state of what is playing where and at what volume, and to follow the Sonos model. Sonos treats player volume as the stored truth: group volume is the average of the players, and a group change scales each player proportionally. Moozaq now does the same on both clients.

- **Android:** the Now playing slider for a multi-member group is the average of each member's own confirmed receiver level. Changing it reads the current member levels, computes `scaledGroupVolumes` (1% steps, ratio preserved, clamped), writes one receiver `SET_VOLUME` per member, and confirms each from that member's own fresh `GET_STATUS`. No media channel, LOAD, LAUNCH, multizone SET or group-session volume write. The main revision (`groupVolumeRevision`) bumps only after every member confirms, so an interim write cannot release the thumb early. Member rows continue to use their own levels and mute. The main slider is disabled while any current member's volume is unknown. Member volumes are re-read every 3 s (plus the 1.5 s membership burst). If a member fails, confirmed members keep their new levels, `volume.group.failed` is logged, and the main request ends unconfirmed.
- **Mac bridge:** `GroupMonitor.set_group_volume` reads the current member levels, scales them with the same rule, and calls the existing confirmed `set_member_volume` per member under a volume lock. `server.py` sends `volume` to that transaction when the selected cast is a group; the old `VolumeTracker` remains for a single speaker. `receiver.volume` for a group is the member average, or null until every member is readable. `GroupMonitor.average_volume` merges the latest per-member states, so an individual trim immediately moves the average.
- **Shared:** `GroupVolume.kt` holds `scaledGroupVolumes` and `groupAverageVolume`; both clients use the same arithmetic. Mute is read per member and is separate from volume.
- **Tests:** Android 60, desktop/common 60, bridge 123. New tests cover ratio-preserving scale, zero-average equalisation, clamped/unreachable targets, incomplete-member refusal, both member writes for a group change, and the bridge dispatching group volume to the member transaction rather than `VolumeTracker`. `assembleDebug` and `lintDebug` pass.
- **Installed:** 0.5.0 / code 18 on the Pixel. The phone was on the keyguard when installed, so no app open, no slider move and no live group-volume check yet. The Mac app was not relaunched; its bridge change applies on the next launch.

A follow-up in the same build fixes a single-speaker adoption blocking a later group move. `shouldSeekMovedGroup` no longer requires `!adopted`; `handoffRouteSelected` clears once the adopted device attaches; `adoptRoute` can select a new group route even after a previous route was adopted; and `receiveMedia` starts the 8 s blind timer when a connected session reports no media, not only after a status failure. On a sent membership change with an omitted endpoint, the phone reads the current endpoint’s own media; if that endpoint no longer has the mix, it follows the discovered group playing the same session read-only. An in-place endpoint that still has media is trusted. `membership.outcome` now records sent/endpoint/members/error. A fresh open rejoined `Bedroom speaker + 1` with no SET or LOAD; the in-process failed-add follow still needs a live retest. Android tests 64, desktop/common 60, bridge 124.

The group-volume transaction now waits for membership to settle: the main volume sliders are disabled while `membershipPending(ui)` (Now playing, desktop player bar, selected speaker-picker row, selected Cast-to row). When a transaction is already running, it compares the current member set to the set it started with; a redundant membership read with the same set no longer cancels it, while a real addition/removal does. The abort message is now “Speakers changed while adjusting volume. Move the slider again.” The pending thumb remains and fails locally. Tests: Android 65, desktop/common 62, bridge 124. After the latest install the app reopened onto `Bedroom speaker + 2` with no SET/LOAD; the volume-conflict path has not been retried live.

UI follow-up in the same 0.5.0 build:

- The Cast-to sheet is a fixed 88% height with a scrolling list instead of content-height sizing, so a membership or status change no longer resizes the sheet.
- The “adding a speaker live is not available” explanation moved below the device list into the status area. It can appear or disappear without shifting the rows above.
- A pending checkbox slowly fades between `onSurfaceVariant` and `primary` over 1.4 s and holds until the membership request clears. Reduced motion keeps the static pending grey.
- Deselecting a confirmed active member now marks that row as leaving immediately: the whole row fades to 0.45–0.75 alpha over 1.4 s, its slider is disabled, and it returns to normal only when membership confirms or fails. Reduced motion uses a static 0.5 alpha. The last-speaker Stop path uses the same dim/pulse. The pure `leavingMember` rule covers pending + confirmed + not requested.
- The temporary-group filter no longer waits for `dynamic_groups` to catch up. The receiver’s `multizone.groups` array is the saved Google Home group list; once it is known, any group route whose canonical id is not in it is hidden immediately. `dynamicIds` remains a second signal, and missing topology falls back to the old classification rather than hiding saved groups. A small `topology.groups` diagnostic logs `dynamic`, `configured`, `configuredKnown` and `savedKnown` counts. The live endpoint uses `groups` (not `configured_groups`); the count was `configured=1` with All speakers. The transient `Bedroom speaker + 1` row from the owner’s add/remove test should therefore be hidden from the first frame after topology loads; that add/remove has not been re-run since this build.
- Visual check: the sheet showed Bedroom + Fridge checked, Kitchen and Office unchecked, the temporary `Bedroom speaker + 1` row hidden, and the Now playing header “Casting to 2 speakers”. The main volume showed the member average. The pending pulse and leaving dim were not triggered because no new speaker was added or removed during the check.

- `MemberVolume.kt` holds two pure rules: a group member's row prefers its receiver level and falls back to a variable MediaRouter level; a group member takes the receiver path instead of `requestSetVolume`.
- `MembershipChannel.receiverVolume()` is `CONNECT` + receiver `GET_STATUS` only.
- `MembershipChannel.setVolume(level)` sends one receiver `SET_VOLUME`, then repeats `GET_STATUS` (0.15 s apart, 6 s deadline) until the fresh level matches. It never opens the media channel, never sends LOAD/LAUNCH, and never sends a multizone SET. An unchanged level is not success.
- `AndroidBackend.refreshMemberVolumes` reads each current member's receiver volume at most every 3 s and publishes it in `OutputState.volume`, so a fixed route still gets an enabled slider. A write that completes first cannot be overwritten by a slower read that started earlier.
- `outputVolume` routes a current group member to that member's receiver and bumps only that member's `routeVolumeRevision` after a matching fresh level. `reconcileOutputVolumes` clears the pending thumb on that evidence. Failures log `volume.member.failed` and keep the pending-failed text.
- A 30-second `router.volume` event names every route with `volumeMax`, handling, value, `member`, `selected` and `enabled`. That is the dump needed to tie a fixed route to Bedroom and Kitchen.
- The Now playing slider still targets the observed Cast session. On a temporary group that is a group-level value; it is not treated as per-member proof. Its timeout now logs `volume.main.failed` (and direct mode logs `volume.direct.failed`) instead of leaving no trace.

Validation: 57 Android unit tests (6 new), `assembleDebug`, `lintDebug` all pass. The new tests assert one `SET_VOLUME`, no `NS_MEDIA` traffic, no LOAD/LAUNCH/multizone SET, and that an unchanged level fails. Live 0.4.5 confirmed both member sliders and both member volumes as described above. The `router.volume` label prints `handling=muted` for the value 0 reported by these Cast member routes; that value is the non-variable case the earlier `dumpsys` printed as `FIXED(0)`, and those routes had nonzero, unmuted levels. Treat 0 as non-variable, not as mute evidence.

## Still unproven

- The 0.4.3 path as another add or remove. The double-sleep fix and the same-process follow have not been repeated on hardware. Do not invent a Fridge, Office, or All speakers change to test it.
- Stop and pause of the moved group session.
- Removing Kitchen, or adding a third speaker.
- That a phone reboot, process death, or real Wi-Fi loss rejoins the same way 0.4.4 did once.
- Three-hour playback, source-URL expiry on Android, and seeking the group.
- Per-speaker volume and group session volume, as above.
- Office was not part of this group and must stay that way unless the owner names it.

## Useful commands

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd compose-app && ./gradlew :androidApp:testDebugUnitTest
cd compose-app && ./gradlew :androidApp:assembleDebug
python3 compose-app/install-phone.py --serial 'adb-2A251JEGR08539-V8FfyR._adb-tls-connect._tcp'
adb devices -l
adb -s '<serial>' shell dumpsys window | awk '/mCurrentFocus|isKeyguardShowing/'
adb -s '<serial>' shell run-as com.moozaq.app tail -n 80 files/cast-debug.log
```

A read-only speaker check must install `install_passive_status` before any pychromecast `wait()` or `update_status()`. The library's normal media status helper can launch the Default Media Receiver. Raw `CONNECT` + receiver `GET_STATUS` did not launch idle speakers. Do not print titles or stream URLs. Do not write `mac-spike/data/group-diagnostics.json` unless that report is the task. `openspec validate android-live-membership --strict` from the repo root.
