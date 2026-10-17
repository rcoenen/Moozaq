# Playback spike observations

## October 16, 2026

- Debug APK builds successfully; Android lint passes with non-blocking dependency/localization warnings.
- Local generated-MP3 server verified for full responses, bounded/open-ended/suffix byte ranges, invalid range rejection, and directory isolation.
- Installed and launched successfully on the owner's Pixel 6a over wireless ADB.
- Owner reports that YouTube Music does not show the Nest devices. YouTube playback eligibility and session persistence remain untested.
- Phone is connected to `New Amsterdam-5G`. YouTube Music and Google Home are installed.
- A six-second Cast mDNS browse on the Mac discovered two Nest Audio advertisements, two Google Home Mini advertisements, one Chromecast Ultra and one Cast group. This proves discovery from the Mac, not from the phone or YouTube Music.

Next diagnostic: compare the Moozaq native Cast picker with YouTube Music on the same phone. Do not select a device yet if another session must be preserved. If Moozaq lists the speakers, investigate the YouTube Music path; if it does not, investigate phone-side discovery and network reachability. No speaker playback has been verified yet.

## First YouTube Music playback attempt

- Devices subsequently appeared in YouTube Music, per owner report and the Cast picker observed over ADB.
- Opened exact starter video `7vy0H3ERRiA`; YouTube Music displayed its title and approximately 3:02:47 duration.
- Initially selected Office speaker, then switched to Bedroom speaker at the owner's request. Use Bedroom speaker for subsequent audible tests; do not use Office speaker.
- UI reported connected to Bedroom speaker, but playback remained at 0:00 with a Play control.
- Owner heard the speaker say “sorry something went wrong”. This is a failed playback attempt, not a successful stream.
- Tapping the audio/headphones mode opened a YouTube Premium offer with “Not now” and “Get YouTube Premium”. Dismissed without subscribing. This does not establish the cause of the receiver playback failure or prove the account's subscription status.
- Phone screen timeout temporarily changed from 30 seconds to 30 minutes at the owner's request. Original value recorded in `android-spike/build/phone-test-settings.txt` for restoration after testing.

Next: test another supplied mix on Bedroom speaker to distinguish a source-specific failure from a broader YouTube Music/Cast issue. Independent direct-audio testing remains available. Phone-independent playback and reconnection are not yet validated.

## Follow-up source attempt

Opening `MpoIuYCfEvQ` while connected to Bedroom speaker produced an explicit YouTube Music message: “Upgrade to play all content from YouTube Music on your speaker”. The player continued displaying the first mix; the second mix was not demonstrated to load. The official app is gating this attempt behind an upgrade for the currently active account/session. No purchase or account changes were made. This provides stronger evidence of an account/content entitlement blocker, but does not prove that upgrading would resolve every playback failure.


## Mac Cast Lab results

- Built a native Mac test app with six source links, Cast discovery, receiver state, playback controls, exportable reports, direct URL handoff, Mac relay, complete-file caching, and a generated-tone baseline.
- yt-dlp resolved `7vy0H3ERRiA` to progressive HTTPS audio-only M4A/AAC (format 140, roughly 3:02:47), without Premium, cookies, or account credentials.
- Found an implementation issue: generic media loading initially targeted the still-active YouTube Music receiver. Changed explicit playback tests to launch the Default Media Receiver first. Reconnection does not launch or load anything.
- **Direct route:** Bedroom speaker reported PLAYING with advancing position. Pause was confirmed. At the owner's requested 70% volume, the owner confirmed “yes it works”. This establishes short audible playback of the first regular YouTube mix via an extracted URL, with no Mac media relay or download.
- **Relay route:** Bedroom speaker reported PLAYING; seeking to 120 seconds produced playback around 122 seconds. Audible output for this particular route was not separately confirmed.
- **Cache route:** acquisition was started but interrupted to prioritize the owner's listening/volume requests. A partial file remains in ignored local data; complete-file Cast playback is not yet verified.
- **Reconnect:** the same media session was observed on rejoin, but it was paused. The owner reported their wife had said “stop” during testing. Continuous disconnected playback was therefore not established by that run.
- **Stop failure:** a new control connection sent media Stop before receiving a media session ID; it failed. Ending the receiver app succeeded, and the receiver reported no active app. Music is stopped at the owner's request.
- Hardened the app after that failure: fresh media state before play/pause/seek, STOP available during work, acquisition cancellation and stale-load invalidation, verified IDLE status or receiver-app shutdown fallback, and explicit failure if stopping cannot be confirmed. Covered these cases with regression tests; no further audible playback was started for the fixes.
- Native Mac UI inspection could not run because the computer-use service failed to start. The Python/Tk app launched without reported startup errors; visual QA remains outstanding.

Still unverified: all other source videos, full three-hour playback, direct URL expiry, phone-off persistence, Android rejoining this session, speaker groups, and complete cache playback. The short success does not remove the unofficial extraction/production-permission constraint.

## Office speaker — October 16, 2026

Owner explicitly requested switching to Office speaker and testing. Using the Mac engine directly (native UI automation was unavailable), connected to Office speaker and loaded the first starter mix, “No Rush. Just Coffee.”, with the direct route at 15% volume. Receiver reported PLAYING with position advancing through 2.7, 7.9 and 12.9 seconds. Owner confirmed hearing the connection chime and music. The test remote then disconnected without sending Stop. Continued playback after disconnect has not yet been separately confirmed. The GUI's speaker selection was not changed by this backend test. This establishes short audible playback on Office speaker, not long-session reliability.

## Failed track switch and confirmation fix — October 16, 2026

Owner reported silence after selecting #3. Events at 14:04–14:05 show an earlier load of #1 returned stale PLAYING status for #2, then #1 became IDLE/ERROR. The subsequent #3 source resolved successfully, but its Cast operation failed with “is connecting”; no #3 playback confirmation exists. Changes: reuse Default Media Receiver when already active; wait for socket, receiver destination and media namespace before loading; preflight direct URL reachability with a one-byte streamed range request before receiver mutation; confirm the exact requested content ID reaches PLAYING instead of accepting previous media state. Errors and timeouts do not trigger automatic playback retries. Mac preflight cannot prove Nest access. All 54 silent tests passed; no live playback retry was performed, so #3 remains unverified.

## Compose desktop rebuild — October 16, 2026

Built and opened the Kotlin/Compose development `.app`. Shared Android library compilation and desktop packaging passed. Four Kotlin state tests, three mocked bridge safety tests and the existing 59 Python regressions passed. The Python HTTP tests required normal local socket access outside the sandbox; none starts speaker audio.

Visual inspection confirmed the six-card browser, persistent player, artwork, speaker selection, connection status and controls. The new app automatically rejoined the existing Office speaker session and displayed Focus Music Deep Work, approximately 14 minutes elapsed and 30% receiver volume. Clicking the cat mix card left that session progressing. No load, resume, pause, stop, seek or volume command was sent during this validation. Audible switching and transport commands from this new UI remain untested. Android is a shared library build only, not a new installable app. The Mac still depends on the existing Python engine/environment and checkout.

## Transient INTERRUPTED during direct switch

The Compose session log at 14:25:05 shows an owner-triggered direct load of starter mix #3. Nest reported IDLE/INTERRUPTED at 14:25:06, BUFFERING at 14:25:07 and PLAYING at 14:25:08. The app had incorrectly terminated load confirmation on INTERRUPTED, leaving a stale failure message despite playback. Updated confirmation to keep observing that same load through INTERRUPTED, require the requested content ID in PLAYING, retain the timeout, and honor Stop cancellation. No retry is sent. Added mocked transition, persistent-interruption timeout and cancellation regressions. All 62 engine/UI-reference tests and three bridge tests pass. No audio was started or resumed during this fix; the already-running #3 session was observed read-only.

## Group diagnostics implementation — October 16, 2026

Added passive Compose bridge group monitoring, a group label in the speaker picker, and expandable coordinator/member details. The monitor was intended to subscribe to member media status and read group membership without receiver mutations. Later testing exposed an implicit-launch side effect in its status queries; see the interference fix below. Target changes suppress old group snapshots. Topology/member changes are logged, and main engine media/load events now include the selected target UUID.

Validation: 62 reference regressions, eight bridge/monitor tests and four Kotlin tests passed. Desktop packaging and shared Android compilation passed. Reopened the app, selected All speakers without playback, and visually verified Kitchen as coordinator plus all four member names. Individual receiver states were UNKNOWN, correctly not claimed as audible output. No group load, retry, pause, stop, seek, volume or membership mutation was performed. The intermittent partial-playback cause remains unresolved; the new diagnostics are ready to capture an owner-triggered attempt.

## Authorized group attempt — October 16, 2026, 14:49 EDT

Owner explicitly authorized starting playback. Clicked first mix Play in Compose with All speakers selected, retaining reported group volume (26%). Load targeted the group UUID. Receiver reported BUFFERING then matching PLAYING at 14:49:30 and load confirmation succeeded, immediately followed by UNKNOWN and the UI returning to Idle. No automatic retry was sent. Member diagnostics did not report a corresponding PLAYING transition in the inspected log. Audible outcome requested from the owner; not yet confirmed. This captures a transient successful load followed by session loss, not a successful group listening test.

## Group playback interference fixed — October 16, 2026

Owner authorized repeated controlled playback investigation at 50%. A baseline retry again showed receiver app creation, replacement and disappearance immediately after matching PLAYING. Inspection found that PyChromecast MediaController.update_status() calls BaseController.send_message(), which can auto-launch the Default Media Receiver when the media namespace is unavailable. The newly introduced member monitor called update_status when any app was active, so diagnostic queries could launch individual apps and disrupt the group. Existing engine sync/Stop status reads also used this auto-launch-capable helper.

Added install_passive_status(cast), installed before cast.wait() on main and member connections. It replaces status reads (including channel_connected callbacks) with guarded GET_STATUS via send_message_nocheck, only when connected with a destination and media namespace. Missing channels send nothing; no launch/retry is possible through this path. Explicit Play retains the existing launch behavior. Three regressions cover available, missing and disconnected channels; all 65 reference tests and eight bridge tests pass.

After reloading the Python bridge, repeated the same first-mix direct load on All speakers at 50%. At 14:56:12–13 EDT the group transitioned through buffering into playing and remained active beyond the prior immediate-disappearance window. Audible confirmation requested from owner, pending at this entry. This is strong evidence for correcting the diagnostic interference, not proof of all-member output or a diagnosis of all group failures that predated the monitor. Playback left running for the owner's listening check; no independent per-speaker loads were sent.


## Audible group success and volume checks — October 16, 2026

Following the passive-status fix, the owner confirmed audible playback of the first mix on Office, Fridge and Bedroom. Kitchen was initially uncertain. At the owner's request, set each of the four members to 50%, confirming each receiver's level. Then set Office, Fridge and Bedroom to 0%, leaving Kitchen at 50%, without reloading media or changing group membership. The owner subsequently confirmed it was working, establishing Kitchen output in isolation. Finally restored all four members to 50%, individually confirmed, and the owner reported everything working.

**Outcome:** all four members of All speakers were audibly verified in this session using direct route A, with Kitchen as the observed coordinator. Volume adjustments were separate per-device operations, not four independent playback loads. The latest requested levels were Office 50%, Fridge 50%, Bedroom 50%, Kitchen 50%; these are recorded observations, not startup defaults to restore automatically.

**What we learned:**

- Our newly added diagnostic polling could disrupt playback: PyChromecast's ordinary media status helper can implicitly launch a receiver when its channel is unavailable. Guarded, non-launching GET_STATUS requests fixed that interference in the observed before/after experiment. This does not establish the cause of all earlier group problems.
- A momentary group PLAYING response is insufficient evidence of sustained playback or audible output from every member. The failing attempt briefly reported success before the app/session vanished; the corrected attempt stayed active and was checked by listening.
- A group volume setting must not be interpreted as confirmation that every individual member has that same level. Explicit per-member writes and readback were needed to establish equal 50% settings. Identical percentages also need not sound equally loud across different speaker models and rooms.
- Isolating one member through volume made Kitchen's audible result unambiguous without changing membership, coordinator, stream, or playback position.

Validation at the fix: 65 reference regressions and eight bridge tests passed. All-member audible output is now demonstrated for this one session; repeated cold starts, source switches, leader failover, synchronized timing measurements, three-hour reliability, and Android rejoining remain unvalidated. No reset, firmware update or router configuration change was required for this successful run.

### Product integration: group member volume controls (2026-10-16)

Moved the previously scripted member-volume capability into the Compose app's Group details panel. The bridge validates current membership and connection, sends only a receiver volume command, waits for fresh matching receiver state, and cancels confirmation if Stop changes the generation or the selected group changes. No playback fallback or automatic retry is used.

Validation: 65 reference-engine tests and 14 bridge tests passed; desktop tests, Android shared-library assembleDebug and desktop createDistributable succeeded. New silent tests cover confirmation, stale readback, unknown member, changed group, Stop cancellation and invalid volume. Live volume changes were not repeated for this integration.

Visual integration check: the rebuilt app rejoined the existing first-mix group session and exposed four member sliders. A live adjustment surfaced an incorrect `receiver_controller` attribute path; corrected it to `socket_client.receiver_controller`, constrained the test double to the actual Chromecast surface, and reran all 14 bridge tests successfully. Reopened the app with the corrected bridge. No new media load was issued during this integration; post-correction hardware volume confirmation remains to be checked.

### Stable member-volume slider feedback

Individual sliders now retain their requested position across stale polls and panel reopening. A backend confirmation revision plus matching volume releases the pending state; no confirmation within ten seconds leaves the requested position visibly marked unconfirmed. Added three Kotlin regression tests for stale matching/nonmatching polls, confirmed readback and timeout position retention. Desktop tests, Android shared-library build, desktop packaging and all 14 bridge tests passed. Reopened the app; no live volume adjustments were issued for this UI change.

### Main volume confirmation feedback

Applied held-position pending colour and timeout indication to the main volume slider. Added passive receiver-status confirmation in the Compose bridge, with target and cancellation guards. All 19 bridge tests passed; main-slider Kotlin coverage includes stale readback, matching confirmation and timeout retention. No live speaker volume changes were used for validation.

### Seek slider confirmation feedback

Added held seek position, pending colour and explicit timeout indication. The Compose bridge now confirms fresh media status bound to the same session. It sends an explicit SEEK preserving paused state because PyChromecast's convenience seek forces PLAYBACK_START. Silent tests cover confirmed paused seeking, stale matching status, Stop, session change, unsupported seek, and UI timeout/confirmation. All 24 bridge tests passed. No live seek or playback command was sent for this change.

### Play/Pause pending feedback

Added pending transport buttons and preserved prior playback label until confirmation. Resume/Pause now use session-bound fresh receiver-state confirmation through the Compose bridge. Added silent stale-status, wrong-state, confirmed pause/resume and UI completion/failure tests. All 28 bridge tests passed. No hardware Play/Pause commands were issued during validation.

### Full-channel import and browser

Imported 1,481 distinct public video entries from the ten owner-supplied channels; with the six original mixes, the UI displays 1,487 total and 1,380 under its default long-form filter. Counts reflect this import, not a guarantee that YouTube exposes every historical/private/deleted upload. Channel counts: Sepia Lounge 99, Noir Tiki Lounge 65, Jazz Haze 124, Safehouse Lounge 58, Afterhours Club ’70 21, Futuristic Sound 146, Old Tape Jazz 693, We Are Jazz 156, Chill Palace 81, Static & Dust 38.

Validation: 33 bridge tests passed, including nested-tab flattening, video deduplication, short-video retention, refresh updates, persistent cache preservation on failure, channel alias deduplication and URL validation. Compose desktop tests, Android shared-library build and desktop packaging passed. Visually verified original YouTube artwork, catalog counts, channel management and live metadata-only refresh; existing playback rejoined independently. No imported mix playback or audio download was tested. Desktop artwork is cached on demand, not prefetched for all 1,481 entries. Android still lacks the complete runtime Cast/catalog backend.

### UI packaging and scroll check (2026-10-16)

After disk space became available, desktop tests and createDistributable succeeded. Opened the rebuilt app and scrolled the mood browser: the settled screenshot showed Dark Jazz / Trip-Hop aligned at the top with its artwork fully visible. Routine footer status text was absent and a hover play icon was visible. Existing All speakers playback was passively rejoined; no playback or volume commands were issued. Grayscale fallback compiled and packaged but was not visually exercised with a forced image failure. Trackpad fling feel across different window sizes remains for manual evaluation.

### Unified speaker popover (2026-10-16)

Desktop tests and distributable build passed. Relaunched and inspected the bottom speaker popover: discovered outputs, connection status, and all four group-member volume sliders appeared. Duplicate top selector and standalone group details/status section were removed. No output selection, volume adjustment or playback command was issued during this UI check. Existing playback was passively rejoined. Dynamic group membership remains unsupported; the UI directs users to Google Home rather than offering nonfunctional checkboxes.

Centered speaker modal build passed and app was relaunched. UI inspection confirmed centered dimensions, no repeated subtitle/radio circles, selected-output volume and connecting indicator. No volume or playback commands issued; the previously selected Bedroom TV was restored read-only. Group-volume action uses the existing confirmed main-volume path; no new live volume test performed.

### Mac temporary-group observation spike (October 16, 2026)

Built and ran `tools/grouping_probe.py` with no playback mutation. Six targets discovered; Kitchen independently PLAYING, saved All speakers four members, no temporary groups reported. Added baseline/comparison reports in ignored data/grouping-spike. Corrected legacy inspection tool to install passive status before connection. Three new silent probe regression tests pass; both probe modules compile. Dynamic add/remove from Mac remains unverified and unimplemented; this is the observation harness for that experiment, not a completed checkbox backend. See speaker-selection.md for sources and acceptance procedure.

### Authorized temporary expansion via Google Home — October 16, 2026

Owner authorized adding Bedroom to the existing Kitchen session and unlocked the phone. Using Google Home's active-stream Cast popup, selected Bedroom while Kitchen remained selected. Google Home briefly displayed Selecting, then “Can’t connect. Tap to retry”. No retry was sent.

Despite that UI error, passive Mac snapshots observed a new `Kitchen speaker + 1` temporary group (UUID `9f321b07-b584-4b23-b3da-72a241fc1eb4`) containing exactly Kitchen and Bedroom. Both physical receivers listed this in dynamic_groups. The saved All speakers group retained the same four members. Group status reported Default Media Receiver, the same PORTLAND SPY RING title and media session ID 2; position advanced from Kitchen 592.47s before expansion to group 622.74s, then 655.17s on an independent reconnect ~32 seconds later. This supports timeline continuity, not proof of uninterrupted audio or acoustic synchronization. Audible Bedroom confirmation is pending.

Physical Kitchen/Bedroom media controllers now reported UNKNOWN without media sessions, while the temporary group reported PLAYING. Thus a UI monitoring only the original Kitchen endpoint would falsely appear to have lost playback. Mac must follow the temporary group's discovered UUID/endpoint and reconcile membership. No Mac membership mutation command has yet been identified; the successful observed mutation was issued through Google Home. Office's separate existing session continued reporting PLAYING; no Office action was sent. No volume changes or saved-group edits were made.

Reports: ignored data/grouping-spike/*5996220032000-baseline.json, *6027148710000-expanded.json, *6059617195000-rejoined.json. Fixed comparison to ignore ordering of member UUIDs and group records; four silent probe tests pass.

Owner follow-up: Bedroom is audibly playing after expansion. Owner could not identify whether it matched the Kitchen mix and reported jazz independently playing on Office. Record Bedroom output as audible, but matching Kitchen content and acoustic synchronization remain unverified. The observed expanded session title was PORTLAND SPY RING | Pacific Northwest Spy Surf & Rainy Noir Lounge. Office was not part of this temporary group and was not controlled during this experiment.

Owner subsequently confirmed Kitchen and Bedroom both play the same music, different from Office. This establishes audible matching content on both members of the temporary group created through Google Home, with Office continuing its separate session. Precise acoustic synchronization, long-session reliability, removal behavior, and Mac-originated membership mutation remain unverified.

### Native Mac membership protocol recovered — October 16, 2026

Used the reverse-engineer-anything skill and REA to pull Google Home's installed APK set (evidence ev_18f7b956d9daa6fc0051c5790adf431a1f50ecfb4d9abc97bdee28d40d9fd761). Base APK SHA256 405359eeec8022025ee5d1a789e7d84adedbfc5b7314ea0b2cdf03bb66ac4836. REA Android decompiler integration lacked its configured JAR; used existing local JADX 1.5.6 CLI instead. Decompiled static observations in defpackage/pid.h, phv.a and pig.c established the command construction and reply parsing; decompilation had errors elsewhere, so no broad completeness claim. Copies and decompiled third-party code stay in ignored data, never shipped.

Recovered wire contract: platform namespace urn:x-cast:com.google.cast.multizone, SET_PLAYBACK_DEVICES with requestId, receiver application sessionId (not numeric mediaSessionId), deviceList of deviceId (physical UUID without hyphens), ipAddress, port and optional sessionName. Response PLAYBACK_SESSION_UPDATED contains failedDevices and sessionEndpoint. GET_STATUS reports playbackSession.appAllowsGrouping, streamTransferSupported, isVideoContent and current devices. This is distinct from persistent setup join_group/disband_group.

Implemented experimental mac-spike/grouping.py and explicit tools/set_playback_devices.py; CLI defaults read-only and --apply is required for mutation. Three initial silent tests cover payload, response correlation and invalid destinations. A native Mac contraction removed Bedroom from Kitchen+1 with failedDevices=[], returning Kitchen:8009 and the same application session ID. Fresh passive snapshot confirmed Kitchen PLAYING the same mix at 1275s, Bedroom no app/no temporary group, and temporary group gone. Office's independent stream continued. Saved All speakers unchanged. No LOAD, volume writes or persistent group setup commands used.

Phone stay-awake temporarily enabled at owner's request while plugged in: original stay_on_while_plugged_in=0, temporary=15. PIN/security settings unchanged. Restore original after live investigation.

Reverse native Mac operation also succeeded: SET_PLAYBACK_DEVICES on Kitchen's individual session with Kitchen+Bedroom returned failedDevices=[] and a new temporary group UUID 302fdeb3-90ee-46ca-a23b-9c1ee486485e at the discovered Kitchen host, port32004. Application session ID remained unchanged. Passive fresh snapshot confirmed exactly Kitchen+Bedroom as members, PLAYING the same mix, and both speakers reporting the new dynamic group. Office continued its separate session; saved All speakers membership unchanged. The owner was away, so this native remove/re-add cycle is protocol/state confirmation; the preceding Google Home expansion had audible same-content confirmation on both speakers. No new acoustic confirmation is claimed for this cycle.

### Integrated membership validation and convergence (October 16, 2026)

The first integrated bridge expansion revealed that the returned endpoint can already report PLAYING while its initial membership contains only Kitchen. Immediate equality checking falsely failed before Bedroom joined. Fixed this with a bounded 12-second fresh-status convergence loop; no SET retry or media LOAD. Stop skips further membership convergence once the new endpoint is verified and adopted.

The follow-up live bridge run succeeded in both directions: Kitchen+Bedroom → Kitchen (1719.3 → 1719.8 seconds), then Kitchen → Kitchen+Bedroom (1721.8 → 1722.9 seconds). Each finished PLAYING, exact requested membership, pending null and error null. Title/session continuity was checked internally without logging signed media URLs. This is protocol/state evidence; no new audible report was available while the owner was away. Their earlier audible Kitchen+Bedroom confirmation remains separate evidence.

Validation: 62 bridge tests pass, including delayed membership convergence, deadline failure, stale intent, endpoint changes and Stop races. Desktop tests and createDistributable pass; the earlier engine/protocol suite passed 72 tests. New checkbox visual QA remains pending because the Mac is locked.

Phone follow-up: ADB reports Awake, powered=true and stayOn=true. The temporary stay-awake-while-plugged-in setting remains 15 at the owner’s explicit request while away (original 0). Restore 0 when the owner no longer needs it; PIN/security were not changed.

After silently relaunching the rebuilt app, a final passive snapshot confirmed Kitchen speaker + 1 still PLAYING with exactly Kitchen and Bedroom, Office still PLAYING its separate jazz title, and All speakers retaining its four saved members. The Mac app and private backend processes are running; the app restores the owner's saved Office remote on startup, without moving either session. To inspect the Kitchen/Bedroom session, use Control on its temporary group.

### Whole-home output monitor verification (October 16, 2026)

Added a passive output monitor with guarded Cast connections, fresh receiver/media status, fresh group membership, and stale-value expiry. Group cast-status callbacks now also publish volume updates immediately without waiting for media changes. Seven new silent bridge tests cover simultaneous independent/group playback, idle saved groups, ambiguous membership, stale evidence, external volume receipts, passive connection ordering and volume target binding; two shared UI tests cover activity labels and pending-volume confirmation. Bridge suite: 69 passing. Desktop tests and packaged build succeeded.

Read-only hardware validation reported Kitchen and Bedroom PLAYING PORTLAND SPY RING through Kitchen speaker + 1; Office PLAYING its separate This Suits Me jazz session; Fridge IDLE; saved All speakers IDLE. All four physical speaker volumes were 20%, as requested earlier. Bedroom TV's media status remained unconfirmed and was not fabricated as idle. This check issued no playback, membership or volume commands.

Relaunched the rebuilt app and visually verified the centered picker: per-output status, title of other music, inline live volume sliders, and separate Control actions. Accessibility state confirmed Office Playing this music, Kitchen/Bedroom Playing other music with their shared title, Fridge Not playing, and each physical speaker slider at 0.20. Prior new-picker visual-QA limitation is resolved.

### Fridge checkbox end-to-end test (October 16, 2026)

Owner explicitly requested adding Fridge to Kitchen/Bedroom at 20%, keeping Office on its independent music. Used the actual Compose UI: Control on Bedroom correctly joined Kitchen speaker + 1; clicked Fridge's checkbox once. Fridge displayed Adding, then Playing this music. Passive hardware snapshot confirmed Kitchen speaker + 2 PLAYING PORTLAND SPY RING with precisely Kitchen, Bedroom and Fridge; Office remained PLAYING This Suits Me. All four physical volumes stayed at 20%. No volume restoration, independent LOAD or saved-group mutation was sent. No new audible confirmation was obtained.

The successful expansion exposed a confirmation bug: MembershipService rejected a reply lacking sessionEndpoint. Google Home's locally inspected parser treats that field as optional; the existing temporary group retained its endpoint during the observed expansion. Fixed the in-place path to require a correlated PLAYBACK_SESSION_UPDATED reply, fresh media with the original application session/content identity, and fresh exact member convergence on the existing connection. It never retries SET automatically. Two new silent tests cover no-handoff success and changed-media rejection; 71 bridge tests pass. Reloaded the fixed backend silently and read the actual group anew; did not replay the live membership mutation solely to test this correction.

### Office joins through checkbox (October 16, 2026)

Owner explicitly requested Office join Kitchen/Bedroom/Fridge at 20%, replacing its separate jazz session so they could listen in Office. Clicked Office's checkbox once in the running Compose picker. Adding transitioned to Playing this music without the earlier false error; group name became Kitchen speaker + 3, the PORTLAND SPY RING timeline advanced from 58:16 to 58:28, and controls returned enabled. Passive snapshot confirmed exactly Kitchen, Bedroom, Fridge and Office as group members; every physical speaker remained at 20%. Office now reports temporary-group participation instead of its previous independent media session. This also live-validates the optional-endpoint confirmation fix. The owner subsequently confirmed hearing the new mix in Office. This establishes audible Office joining through the actual Mac checkbox at 20%; it does not independently confirm every room in this run or long-session reliability. Saved All speakers was not changed.

### Reported app hang and expired output (October 16, 2026)

At inspection neither the packaged Moozaq process nor its private backend was running; no Moozaq/Java crash report was found. Reopened the app and verified that opening the speaker picker responded. Discovery showed no temporary Kitchen group and all physical speakers idle; preferences still referenced the former temporary group UUID. This explains the disconnected main controls, but does not establish the cause of a possible earlier window freeze. A system CPU sample also showed heavy WindowServer/browser GPU activity, which is contextual evidence rather than a root-cause finding. No audio or volume commands were sent.

Adjusted the picker to explain that the previous output is unavailable. Independent output-volume controls no longer depend on the selected main remote's connection; they require their own fresh receiver state. Added a shared UI regression test for stale temporary targets and independent-volume readiness.

The expired-output UI regression tests and createDistributable completed successfully (6m46s under system load). Relaunched the updated package. Final visual verification was blocked by the Mac being locked; the earlier reopened build had responded to picker interactions. No conclusion about the original reported whole-window hang is established; the user's referenced screenshot has not arrived in chat.

### Connection loss clarified and recovered (October 16, 2026)

The owner supplied the screenshot and clarified that the window responds; the Cast connection is missing. Preferences referenced the expired temporary group, rather than an available physical speaker. All four speaker Cast ports were reachable. One 12-second discovery attempt missed Office, while the next discovered all six outputs by its 11-second sample; this is evidence of transient discovery delay, not a persistent network outage.

Added persisted physical-member fallback and a 15-second absence grace period. Recovery only opens a passive remote when the old group is disconnected and absent, the fallback is discovered, and no operation is pending. Healthy sessions, rediscovered groups, offline homes and unknown legacy targets do not trigger a guessed replacement. Six new silent tests bring bridge coverage to 77 passing tests, including a maintenance-loop check that recovery connects without play, control or Stop calls.

For the existing legacy preference, explicitly restored Office as the remote and relaunched the packaged app. Its own backend event at 23:01:42 confirms “Office speaker; joined existing receiver, no media loaded”; both app and backend processes remained running. No playback or volume commands were sent. The vanished-group recovery transition is mock-tested, not yet observed through another live group disappearance. Final visual verification remains pending because the Mac is locked.

### Session-aware recovery follow-up

Implemented recovery from a lost observed session using the passive whole-home monitor. A replacement must be the sole fresh candidate sharing the exact media fingerprint, overlapping physical membership, and plausible forward position continuity. Supports replacement groups and contraction to an individual speaker. The UI holds the previous title/artwork/position as unconfirmed, shows Reconnecting, and disables transport until fresh state is available. Missing observations alone cannot claim playback ended. All remembered participants freshly idle after the grace period allow an idle remote; ambiguity stays unresolved. Identity continuity is kept in memory, not persisted across app restarts.

Silent validation: 93 Python bridge tests pass, including replacement groups, group contraction, unrelated streams, duplicate matches, stale media/membership, restart/rewind rejection, long outages, late candidate changes, idle convergence, Stop/busy exclusion, snapshot synchronization and address-hint validation. Desktop tests and createDistributable pass. No audio, seek, volume, membership or receiver-launch commands were used in live validation.

Live discovery separately missed Office while its Cast port was reachable and the other physical speakers appeared. Read-only device-info confirmed Office's expected UUID at its known address. Added that verified physical address to persisted discovery hints; after relaunch the app's own backend logged a passive Office connection. Visually verified the final app shows Office speaker Connected, Idle, Resume disabled, and the current observed volume (27%, not restored to an earlier value). Full live migration/recovery remains untested; the group transitions were simulated silently. This resolves the earlier pending visual verification of reconnection.

### Known speakers remain visible

Owner supplied another screenshot where Office had disappeared. A read-only device-info query again returned its expected UUID and name; disappearance from the picker was not proof the device was offline. The previous UI listed only live discovery entries. Added a persistent physical-device inventory, with unavailable rows during discovery gaps and disabled controls until rediscovery. Temporary groups are deliberately not retained. Also allowed independent output volume and remote selection while the selected remote is waiting for discovery. The current Office identity was saved from verified device info; no playback/volume commands were sent. Silent validation: 96 bridge tests, desktop tests and createDistributable pass, including inventory disappearance/reappearance/restart and temporary-group exclusion.

Relaunched and visually verified Office remains in the picker as “Unavailable · looking for speaker…” while discovery omits it. Other available speaker controls remain usable. This fixes the disappearing row; this inspection did not confirm that the intermittent discovery problem itself was resolved or that Office had reconnected.

### Discovery startup race and passive monitor recovery (October 17, 2026)

Investigated Office disappearance using read-only device-info, Cast discovery, macOS Bonjour and installed PyChromecast 14.0.10 source. Office device-info responded in 0.20 seconds and its multizone query in 0.24 seconds; Kitchen device-info responded in 0.18 seconds. A 12-second native `dns-sd` browse immediately included Office and the other three physical speakers plus All speakers. A separate Python discovery run initially omitted Office and later found it. These observations distinguish a discovery gap from an unreachable speaker; they do not rule out intermittent network behavior.

Confirmed a startup race in our address-hint implementation. Lab started CastBrowser before Bridge seeded the remembered address, allowing HostBrowser to enter its 30-second wait after an empty first poll. Controlled host-only discovery measured Office at 30.69 seconds with late seeding versus 0.51 seconds with constructor-provided known_hosts. Now load validated preferences before Lab creation and pass known_hosts before discovery starts. Device identity must still match the selected UUID.

Two additional recovery defects were fixed: PyChromecast retains device records after their final service disappears, so all availability/recovery/group lookups now exclude empty-service records; passive monitor connections created with tries=1 can permanently stop after a failure, so subsequent polls retire and replace stopped connections with passive status protection installed first. Known physical inventory rows remain visible as unavailable when no usable discovery service exists.

Validation: 98 silent bridge tests and 74 silent engine tests pass, including pre-start seeding, expired service rejection and stopped-monitor replacement. Python compilation and diff whitespace checks pass. Relaunched the packaged Compose app without playback: its own event log changed from ready 00:09:25 / Office connected 00:09:56 in the previous run to ready 00:16:38 / Office connected 00:16:39 in the updated run. UI verification shows Office Connected, Idle, visible in Speakers with its observed 27% volume and Resume disabled. No audio, volume, seek or membership commands were sent. This verifies startup recovery in this run, not long-duration or repeated group playback reliability.

Research cross-check:

- [Google Cast discovery troubleshooting](https://developers.google.com/cast/docs/discovery) distinguishes sender, receiver and network causes, recommends inspecting `_googlecast._tcp.local`, and warns that YouTube/Netflix use special discovery mechanisms and are not suitable baseline discovery tests.
- [Home Assistant Cast documentation](https://www.home-assistant.io/integrations/cast/) documents multicast discovery failures and known-host fallback. [PyChromecast's README](https://github.com/home-assistant-libs/pychromecast/blob/master/README.rst) also supports known hosts; its [discovery implementation](https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/discovery.py) explains the host polling behavior verified locally.
- Historical engine logs also contain SSL BAD_LENGTH/BAD_RECORD_MAC errors. [Home Assistant issue 141619](https://github.com/home-assistant/core/issues/141619) reports a similar BAD_LENGTH failure, but was closed as stale without an established fix. This is related evidence, not proof of the same root cause. No certificate/cipher weakening, router changes or device reboots were performed. The SSL failure cause remains unresolved.

### Saved-group destination checkbox (October 17, 2026)

Added destination checkboxes for discovered Cast groups, including All speakers, through the existing passive remote-selection operation. The desired selection stays visible while connecting, confirms only for the matching synchronized receiver, and retains failure with retry feedback. Selecting another destination replaces the previous selection; individual checkboxes still represent current-session membership. Play and membership mutation are guarded while destination selection is unresolved. No saved-group changes or automatic media transfer are involved.

Validation: 25 shared desktop tests pass and createDistributable succeeds. New tests cover selecting an idle group without membership support, offline rejection, pending intent, exact destination/connection confirmation and failure retention. Relaunched the package; during UI verification the owner interacted with the app. Fresh accessibility state and screenshot then confirmed All speakers checked, Selected destination / Not playing, and All speakers Connected in the player. No audible playback test or volume changes were performed by the agent.

### Idle individual destination selection (October 17, 2026)

Fixed the idle picker so physical speaker checkboxes choose the playback destination, replacing a selected saved group. The same pending/confirmed destination state is used for both row types. Active/paused/buffering sessions retain physical membership controls, and pending membership operations cannot change checkbox meaning. Added regressions for idle group-to-individual selection, exclusive pending/confirmed checkmarks and preservation of active membership behavior.

All 27 desktop tests and createDistributable pass. Relaunched and verified every available physical speaker checkbox is enabled while All speakers is idle/selected. The owner interacted during verification; the final observed UI shows Fridge checked and Connected/Idle, All speakers unchecked and selectable, with all other individual destinations available. No audio, volume or membership mutation was initiated by the agent.
