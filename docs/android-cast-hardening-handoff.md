# Android Cast hardening handoff

Prepared October 17, 2026. Repository: `/Users/rcoenen/Dev/Moozaq`.

## Assignment and stopping point

The owner wants dependable, friendly Cast behavior: “it simply works.” The immediate motivating problem was Android reopening disconnected and displaying Nothing playing while Bedroom continued the mix started by Moozaq.

**The owner has now requested a documentation handoff to another agent to conserve credits. Implementation and deployment have stopped.** This document tells the next agent what exists, what is demonstrated, what is only proposed or unit-tested, and what to do next. Do not treat an unfinished hardening pass as a completed reliability guarantee.

Read `AGENTS.md`, `docs/current-direction.md`, and `docs/playback-control-requirements.md` before changing code. Later explicit owner decisions supersede older historical sections.

## Exact state at handoff

| Item | State |
|---|---|
| Installed phone app | `com.moozaq.app`, **0.3.2-standalone**, versionCode **4** |
| How installed version was checked | Read-only `adb shell dumpsys package com.moozaq.app` after the interrupted deployment |
| Source and newly built APK | **0.3.3-standalone**, versionCode **5** |
| Candidate build/lint | Passed |
| Candidate Android unit tests | **28 passed**, no failures/errors/skips |
| Shared desktop tests | **34 passed**, no failures/errors/skips |
| Candidate hardware validation | **Not performed** |
| Candidate installation | **Not completed**; do not assume it reached the phone |
| Verified baseline recovery | 0.3.2 passed two cold-process reopen checks against existing Bedroom playback |
| Commits | No commit made during these debugging/hardening turns |
| Current audible state | Not rechecked at this handoff; the earlier verified session was playing on Bedroom |

The attempted install tool call was interrupted. The later package inspection definitively still showed code 4 / 0.3.2. The APK at the normal build output path is now the **candidate**, not a preserved baseline APK.

**Working-tree caution:** this repository contains substantial pre-existing modifications and untracked files from the larger implementation. The entire `compose-app/androidApp/` directory is currently untracked. There are also changes to the Mac bridge, shared UI, resources, design handoff, and documentation. Do not reset, clean, overwrite, or blanket-commit the tree. A Git checkout does not reconstruct the tested Android baseline because these files are untracked.

## Product and operational constraints

- Android must be standalone: discovery, catalog, YouTube resolution, Cast control all run on the phone. No Mac runtime, relay, ADB reverse tunnel, paid subscription, Premium or implicit cookies.
- Mac remains a working reference; keep its Python implementation intact.
- Receiver playback should continue when the remote disappears. Rejoining reads what the speaker is doing; it must not replay the mix, restore old volume, or seek to cached position.
- **No assistant-initiated start/resume/audio test without a new explicit owner request.** General permission to debug/build/test/harden does not authorize sound. Existing-session read-only inspection is allowed. Do not transfer old historical playback authorizations into a new test.
- Prefer Bedroom for any separately authorized playback. Do not start Office or All speakers without explicit instruction.
- Use intent → pending → confirmed for asynchronous controls. Keep requested slider values stable; confirm from fresh receiver evidence. A transport/socket acknowledgment is not playback confirmation.
- Preserve playback status while unrelated volume operations are pending. Show uncertainty and failures, with deliberate retry; never automatic audible retry.
- Native Cast grouping only; do not independently LOAD several speakers and call that synchronized playback.
- Keep credentials and signed media URLs out of logs, reports and commits.
- No subagents were requested for this work. The current instruction is to hand work to another agent, not to spawn one automatically.

## Architecture and code map

### Android

`compose-app/androidApp/src/main/java/com/moozaq/app/`:

- `AndroidBackend.kt`: native Cast framework / AndroidX MediaRouter, session lifecycle, state snapshots, commands, local discovery, recovery, group membership.
- `CastOptionsProvider.kt`: Default Media Receiver, receiver lifecycle and native SDK recovery settings. **This file contains the unverified 0.3.3 recovery behavior change.**
- `OnDeviceSources.kt`: catalog and on-phone source resolution.
- `BundledExtractor.kt`: checksum-verified pinned yt-dlp runtime installation.
- `DirectStreamProbe.kt`: ordinary bounded HTTP GET before LOAD, rejecting unusable streams without replacing current playback.
- `AndroidDiagnostics.kt`: private rotating, URL-redacted Cast log.
- `PlaybackFence.kt`: cancellation generation and content-bound Moozaq source recognition.
- `LoadObservation.kt`: latch brief receiver ERROR for the active requested LOAD.
- `ReceiverDeadline.kt`: distinguish visible receiver timeout from coroutine cancellation.
- `SpeakerInventory.kt`: retain unavailable rows across discovery gaps.
- `RememberedSpeaker.kt`: private identity preferences and recovery backoff/generation.
- `PassiveReceiverProbe.kt`: bounded local TLS Cast receiver GET_STATUS probe, plus small protobuf envelope codec; no media commands.
- `CastGuards.kt`: **new in candidate** attachment epochs, status-poll gate, media identity and seek-state confirmation checks.
- `MainActivity.kt`: creates backend/store, foreground polling, cleanup. Still recreated on Activity recreation; no retained ViewModel was implemented.

Tests are in `androidApp/src/test/` and `androidApp/src/androidTest/`. Build/deploy usage is in `compose-app/androidApp/README.md` and `compose-app/install-phone.py`.

### Shared UI

`compose-app/composeApp/src/commonMain/kotlin/com/moozaq/`:

- `Player.kt`: serialized snapshot/receiver models, PlayerStore, pending reconciliation and remembered presentation.
- `PlayerBar.kt`: desktop/mobile playback presentation. Android recovery now says Checking playback rather than falsely claiming Nothing playing while state is unknown.
- `Outputs.kt`: output volume and destination reconciliation, immediate explicit connection failure.
- `SpeakerPicker.kt`: output UI; fixed modal height prevents shrinking/growing as discovery changes.

Android uses Google Cast framework **22.3.1**, MediaRouter **1.8.0-beta01**, youtubedl-android wrapper **0.18.1**, JDK 17 and SDK 35. Do not change dependency versions speculatively while investigating a focused regression.

## What was found and demonstrated

### 1. Initial Android source failure

The owner's first attempts selected Bedroom and received a chime, but Play spun then timed out. Logs showed LOAD accepted, LOADING, then IDLE/ERROR roughly 120 ms later. Subsequent empty media status erased that brief failure. Waiting for PLAYING therefore ran until the full timeout.

A silent HTTP comparison found:

| Source resolver | Ranged request | Ordinary GET | Source |
|---|---|---|---|
| Old Android bundled yt-dlp 2025.11.12, ANDROID client | 206 | **403** | AAC/MP4, format 140 |
| Mac yt-dlp 2026.08.19, VISIONOS client | 206 | **200** | Same format |
| Updated Android yt-dlp 2026.08.19 | 206 | **200** | Same format |

A range-only smoke test had missed the issue. The phone was on Wi-Fi without VPN; address hashes matched Mac. Forcing IPv4 did not solve the old URL behavior. No cookies or credentials were used.

The app now bundles upstream yt-dlp **2026.08.19** at `androidApp/src/main/res/raw/ytdlp`, SHA-256:

`1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6`

It is verified and atomically installed into the wrapper runtime. License/source details are in Android assets/licenses. It is not downloaded as executable code on app startup. Before explicit LOAD, a normal GET reads only a bounded amount and closes; a failed preflight leaves the existing mix alone. Cancellation and receiver binding are checked again after resolution/preflight.

**Owner subsequently confirmed audible standalone playback works.** Logs independently showed `Xdho5SVxEVI` reaching PLAYING on Bedroom. This establishes a successful short session, not all-source coverage or long-session reliability. The precise HTTP request issued inside Nest was not captured, so do not overstate the network-level causal proof.

### 2. Cold-start reconnection failure

Original 0.3.1 sequence:

1. Playing on Bedroom.
2. App closes; receiver keeps playing (owner confirmation).
3. Reopen: no current native session; SDK begins saved-session resume.
4. Around ten seconds later: `onSessionResumeFailed`, error **2152**.
5. Backend has no persistent destination; app stays disconnected/empty.

A separate earlier initial-selection error **2155** was also observed. Its underlying meaning/cause remains unestablished. Do not call it a network failure or receiver crash without evidence.

Primary references:

- [Google sample issue #144: SDK resume failure 2152 after app restart](https://github.com/googlecast/CastVideos-android/issues/144). Corroborates a similar SDK 22.x symptom, not a definitive diagnosis of our internals.
- [SessionManager](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/SessionManager): failed session resume unselects the route.
- [CastOptions.Builder](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/CastOptions.Builder): saved-session restoration and reconnection options.
- [LaunchOptions.Builder](https://developers.google.com/android/reference/com/google/android/gms/cast/LaunchOptions.Builder): relaunch-if-running option.
- [Cast SDK release notes](https://developers.google.com/cast/docs/release-notes): checked during hardening; no verified remedy for this observed failure was established from the notes.
- [MediaStatus](https://developers.google.com/android/reference/com/google/android/gms/cast/MediaStatus): receiver idle ERROR is distinct from INTERRUPTED.

### 3. Verified recovery in 0.3.2

0.3.2 saved the last stable Cast device ID, route ID, display name/type/model in private preferences. It did **not** persist playback commands, signed URLs, volume, position or an authoritative Playing flag.

Recovery:

- Let native SDK saved-session resume run first.
- Rediscover the remembered device by identity, not friendly name.
- After native failure, use a bounded receiver GET_STATUS against its discovered LAN endpoint.
- Only if Default Media Receiver is already running, select that same route with relaunch disabled.
- Attach and request current receiver media state.
- Recognize Moozaq source via receiver customData + content hash; use catalog artwork.
- Retry read-only checks with 5–30 second backoff; never retry LOAD or Play.

Validation against already-running Bedroom:

- Opt-in instrumentation read Playing, Midnight Fever Motel, source identity recognized, position 1,852 s.
- First cold restart: 15:43:36 → 15:43:47 UTC. Rejoined at 1,884 s, same application-session fingerprint.
- Second normal-app cold restart: 15:45:27 → 15:45:38 UTC. Rejoined at 1,996 s, same fingerprint/source.
- No source resolution, LOAD, PLAY, seek or volume command in those recovery cycles.
- Screenshot verified artwork, highlighted matching mix, Playing/Pause, Bedroom and advancing timeline.

This is the installed and verified baseline. It is still slow because of the native ten-second failure first.

## Candidate 0.3.3: changes already written, not hardware-validated

The previous implementation turn was interrupted just before deployment. Do not redo these changes blindly; review what is present.

1. **Faster cold-start experiment**
   - `CastOptionsProvider` changes `setResumeSavedSession(true)` to **false**.
   - `recover()` foreground grace changes **2,500 ms → 500 ms**.
   - Intended effect: skip the observed broken native saved-session attempt, use guarded discovery/status/rejoin directly.
   - Reconnection service stays enabled; already-connected SDK sessions are still attached.
   - **No claim of speed improvement yet.** Background/suspended behavior with these options needs validation.

2. **Attachment-bound callbacks**
   - Each attach/detach advances `ConnectionEpoch`.
   - Media and volume listeners are created per attachment and check captured session/client/epoch.
   - Old callbacks and old status acknowledgments should no longer make a replacement connection look fresh.
   - Attach verifies current SDK session, connected state and explicit destination identity.
   - `joiningSession`, `explicitDeviceId`, and `sessionFailed()` attempt to reject late failures/end events from replaced sessions.
   - Unit tests cover the epoch primitive, **not every real SDK callback ordering**. Inspect the integration carefully, especially callbacks with no CastDevice and group handoff.

3. **Bounded status polling**
   - `StatusRequestGate` allows one status request at a time, with a six-second expiry.
   - Late acknowledgments cannot complete a newer poll or refresh media state.
   - Synchronous status-request exceptions are caught and logged; they do not kill polling.
   - Actual fresh media callbacks remain an independent source of receiver truth.

4. **Recovery/lifecycle fencing**
   - Backgrounding, closing, manual selection and Stop invalidate pending recovery as appropriate.
   - Backoff is preserved on lifecycle invalidation.
   - Closed backend callbacks are ignored; close is idempotent.
   - Failed route selection in a recovery coroutine becomes explicit recoverable state instead of an uncaught coroutine exception.
   - Suspended/connecting SDK sessions are not raced by a second selection attempt.

5. **Transport confirmation**
   - Pause/resume/seek/stop confirmations reject an unexpected replacement content ID.
   - Seek requires a settled playing or paused session and verifies both requested position and original paused/playing state after the command.
   - Seeking preserves SDK RESUME_STATE_UNCHANGED.
   - Empty content is permitted in the shared identity check for Stop; each command still has its own state predicate.
   - Do not mistake these checks for atomic receiver-side compare-and-swap or perfect identification of a restarted identical stream.

6. **Destination cleanup**
   - Manual selection cancels pending volume waits, clears joining reference, binds desired device ID and invalidates old connection listeners.
   - Rediscovering the same Cast device under a new route ID removes its old remembered inventory row to avoid a duplicate unavailable entry.

Candidate validation so far:

```text
:androidApp:assembleDebug
:androidApp:assembleDebugAndroidTest
:androidApp:testDebugUnitTest
:androidApp:lintDebug
:composeApp:desktopTest
BUILD SUCCESSFUL
28 Android tests; 34 shared desktop tests; no failures
```

New eight tests are `CastGuardsTest.kt`: old attachment callbacks, poll overlap/expiry, late ACKs, detach clearing, seek paused-state preservation, replacement-content rejection, empty stopped content, and background probe invalidation. These are focused JVM checks, not end-to-end hardware tests.

## Next agent: recommended order

### Priority 1 — review and validate the candidate before expanding scope

1. Read the current Android files and inspect the exact 0.3.3 changes above. Preserve unrelated work.
2. In particular review the session-event ownership logic and interaction between disabled native saved resume and still-enabled reconnection service. Do not assert that null device metadata reliably identifies the intended session.
3. Install code 5 only after that review. Recheck the ADB transport first. The owner has authorized continuing app fixes/deployment, but **not new audible playback tests**.
4. If Bedroom still has an existing mix playing, test passive cold rejoin only. Verify identical source/session fingerprint, advancing position, unchanged playback and no LOAD/PLAY/volume/seek commands. Do not start music to set up this test without asking.
5. Verify at least two cold process starts plus warm background/foreground return. Record actual elapsed times. Confirm UI shows Checking playback until fresh state and then correct artwork/Playing/Pause/position.
6. If candidate recovery is worse, restore the 0.3.2 recovery configuration (`setResumeSavedSession(true)`, 2,500-ms grace), retaining only independently validated safeguards. Rebuild; do not claim the old Git commit is an Android baseline.
7. Update current direction, lessons, README and spike results with what is actually observed. **Current docs still describe native saved-session recovery first, which is correct for installed 0.3.2 but no longer matches candidate options.** Resolve that only once the candidate approach is accepted.

### Priority 2 — close the highest-risk remaining gaps with mocks first

- **Probe/select race:** passive GET_STATUS followed by SDK route selection is not atomic. If another app replaces the receiver between those steps, route selection could launch/replace it despite relaunch-if-running=false. Find a supported join-only/session-bound route, or tighten and explicitly document the guarantee. Never market the current guard as impossible to race.
- **Stuck SDK suspension:** recovery currently yields while SDK reports suspended/connecting. Determine how to bound a session that never leaves those states without ending receiver playback or racing another selection.
- **Stop while unsynchronized:** Stop invalidates pending work, but `transport()` still requires a fresh connected session. During a lost connection it may fail rather than reconnect and verify Stop. Implement a bounded, session-bound strategy; never silently stop unrelated replacement music. Keep Stop available and never claim success on command dispatch alone.
- **Real callback-order tests:** add injected/fake session-client tests for late old `onSessionEnded`, late attach, null device metadata during selection, a session changing during command confirmation, and same SDK object reattached under a new epoch. Existing primitive tests do not cover all of that integration.
- **Status freshness:** ensure no stale cached native state or late callback is promoted to fresh evidence. Test missing replies, acknowledgments without media state and network recovery.
- **Group operations:** membership changes are not the same as ordinary transport jobs. Audit binding/cancellation when Stop, device change, or group endpoint changes occur mid-operation. Android synchronized grouping and its recovery remain unproven.

### Priority 3 — user-friendly resilience

- **Volume quantization, identified by inspection but not changed:** Android route volume is integer steps (`volumeMax`). Backend rounds the requested fraction to a step, while shared output reconciliation requires error < .01. A coarse device can truthfully acknowledge its rounded step yet UI waits/fails. Carry supported step resolution or acknowledged intent through the state model; require a fresh revision and an appropriate half-step tolerance. Keep requested thumb stable until confirmation. Do not silently treat any nearby stale volume as success.
- **Activity recreation, not changed:** MainActivity creates backend/store in onCreate and closes onDestroy. Rotation/configuration changes rebuild state and can cancel in-progress work. Consider a retained ViewModel owning backend/store; lifecycle foreground discovery must still be correct. No ViewModel changes were written.
- **Play with no ready destination:** current `canStart` gates playback. A click that does nothing is confusing; consider opening destination selection and clearly indicating what is needed. Never turn reconnect/startup into implicit playback, and do not replay a stale queued tap after a long failure.
- Preserve the currently observed mix during uncertainty, clearly marked as unconfirmed, without borrowing the wrong device's remembered title.
- Preserve stable device rows through temporary discovery gaps. Avoid false “Not playing” where playback is merely unknown.
- Keep errors understandable and local to the affected control; routine diagnostics belong in debug information.

### Broader acceptance still outstanding

Do not claim these are solved: full three-hour playback, all-six starter source coverage, arbitrary imported source reliability, URL expiry recovery, phone reboot, actual Wi-Fi loss/rejoin, Android temporary-group endpoint changes, repeated synchronized member add/remove, other-controller changes, and latency/reliability percentiles. Some Mac group behavior was audibly demonstrated previously; it is not Android proof.

Use a small, explicit acceptance matrix and record command intent, acknowledgment, fresh observation and audible confirmation separately. Prefer deterministic mocks and targeted tests over broad repeated builds or repeated requests for the owner to listen.

## Environment and commands

Current development phone: Pixel 6a. Last observed ADB transport:

`adb-2A251JEGR08539-V8FfyR._adb-tls-connect._tcp`

Rediscover it; Wi-Fi endpoints change. Use explicit `-s` if multiple transports are listed.

```sh
adb devices -l
adb -s <serial> shell dumpsys package com.moozaq.app

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./compose-app/gradlew -p compose-app \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest \
  :androidApp:testDebugUnitTest :androidApp:lintDebug :composeApp:desktopTest

adb -s <serial> install -r compose-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s <serial> shell am start -n com.moozaq.app/.MainActivity

adb -s <serial> shell run-as com.moozaq.app cat files/cast-debug.log
```

Private diagnostics rotate, redact URLs, and also emit under logcat tag `MoozaqCast`. Useful events: backend.created, session.resuming/disconnected/attach, recovery.rejoin, snapshot, source.preflight, media.load, play.confirmed, command.request, status.failed. Snapshots include source video ID, position and hashed application-session ID, not the raw signed URL.

For a separately justified **existing** Bedroom session check:

```sh
adb -s <serial> install -r compose-app/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r \
  -e class com.moozaq.app.ExistingSessionTest -e observe_existing_session true \
  com.moozaq.app.test/androidx.test.runner.AndroidJUnitRunner
```

This test requires an already-running Default Media Receiver before joining and never calls play/transport/volume. It is skipped without explicit opt-in. It is not a substitute for verifying process-death recovery from the normal Activity. The general StandaloneTest includes source/network checks; run only the relevant methods rather than repeatedly resolving every source.

Gradle/ADB/network commands have required sandbox escalation in this environment; task-related requests were approved by automatic review. No phone credentials or global security-setting changes are needed. Do not unlock by bypassing security.

## Evidence locations

Transient files may disappear; durable findings are recorded in repository docs.

- `/tmp/moozaq-hardening-build.log`: candidate 0.3.3 successful build/lint/tests.
- `androidApp/build/test-results/testDebugUnitTest/`: current 28-test XML results.
- `composeApp/build/test-results/desktopTest/`: current 34-test results.
- `/tmp/moozaq-existing-session-test.log`: 0.3.2 opt-in existing-session observation.
- `/tmp/moozaq-rejoin-build.log`, `/tmp/moozaq-rejoin-build2.log`: baseline recovery build checks.
- `/private/tmp/moozaq-rejoin-032/screen.png`: baseline cold-rejoin visual verification.
- `/tmp/moozaq-031-phone-tests.log`: previous silent source checks.
- `/tmp/moozaq-range-probe.log`: old Android ordinary-GET 403 evidence.
- `docs/spike-results.md`: chronological observed evidence and its limits.
- `docs/google-cast-lessons.md`: passive-monitoring pitfalls, group lessons, source-fetch checks, recovery findings.

REA, discussed by the owner, is the developer plugin **Reverse Engineer Anything**. It was used for ADB screenshots. It is not shipped in Moozaq or required for runtime operation. Standard ADB is sufficient for debugging; no reverse engineering of third-party apps is needed for this handoff.

## Suggested next-agent opening task

> Read docs/android-cast-hardening-handoff.md and AGENTS.md. Review the uncommitted 0.3.3 Android Cast hardening candidate, then validate its faster cold rejoin against an already-running Bedroom session without issuing playback or volume commands. The phone still has verified 0.3.2. Preserve the dirty worktree. If the candidate is sound, install it and record measured recovery results; otherwise restore the verified recovery configuration. Then address the highest-risk callback, cancellation and session-identity gaps with focused regression tests. Never start/resume audio without a new explicit request. Report what is tested separately from what remains uncertain.
