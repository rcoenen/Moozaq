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


## Design handoff implementation — October 17, 2026

Implemented the supplied design handoff in Compose: shared dark theme and font, compact artwork shelves, 136 dp persistent player, anchored Cast picker with inline volumes, and Channels dialog. Archived the source handoff and integration notes in `docs/design-handoff/`.

Validation: 29 desktop tests and 101 Python bridge tests pass. Android shared-library assembly and Mac distributable build pass. New regressions cover last-participant Stop routing, Stop revision only after successful verification, failed Stop without retry, remembered last-track state and refresh-progress cleanup after a channel failure. No engine playback algorithm was changed beyond exposing verified Stop acknowledgement to the UI.

Visual QA on the actual Mac checked the library layout, idle/connecting player, anchored speaker panel, background blur/scrim and Channels list. An isolated test-only backend also rendered the active player and now-playing card. Native UI inspection initially could not identify the temporary mock bundle until its ad-hoc signing identifier matched its Info.plist; this affected the QA wrapper, not the production app. The Mac locked before Pause/Stop/Add-channel/Escape click-through could be completed. Explicit Escape/focus-return handling compiles but still needs a manual keyboard check. Unit tests validate the transport state transitions, not native keyboard behavior.

No speaker playback was started or resumed, and no speaker volumes or memberships were changed by these checks. The existing app was allowed to rejoin passively; startup still uses current receiver evidence. The interactive fixture is in desktop test sources and has no Cast backend. It must not be used as evidence of audible or long-session reliability.


### Handoff continuity repair — October 17

Code inspection identified a second synchronization gate: membership verified and adopted the returned endpoint, but Bridge.snapshot still required a separate OutputMonitor observation, which depended on discovery. A fresh handoff listener now feeds session recovery and UI evidence directly, with session identity, socket, media and membership freshness checks. The UI retains the known track/timeline during uncertainty without enabling stale transport commands. Temporary endpoints are hidden in the picker using protocol metadata.

Validation: 104 Python bridge tests and 32 Kotlin desktop tests pass; Compose Android library and Mac distribution builds pass. New regressions cover pre-discovery handoff evidence, expiration/session changes, temporary group classification, preserved timeline and disabled unconfirmed controls. No real membership changes or audio starts were performed. The Mac app was relaunched passively; final UI inspection was blocked because the UI tool reported a locked Mac.

Android deployment preparation: Pixel 6a was reachable over wireless ADB. The existing android-spike APK builds and passes lint, but is the older Cast experiment, not the Compose product. Installation is awaiting the owner's choice between that prototype and building an installable current Compose Android app. No APK installed in this step.


### Current Android interface on Pixel 6a — October 17

Built and installed the current Compose Android companion (`com.moozaq.app`) over the authorized wireless debugging connection. It is separate from `com.moozaq.spike`. The Mac bridge uses an authenticated localhost-only endpoint, reached via ADB reverse, with the same Bridge object as desktop. No public LAN control port was opened.

A device screenshot verified the actual channel-derived mood shelves/artwork, phone-sized player, current mix, advancing elapsed time, three-speaker label, volume and transport controls. Existing playback was read, not restarted. No Play, Pause, Stop, seek, volume or membership commands were sent during deployment. Native standalone Android Cast playback, phone-off duration and live Android control actions remain untested. The Android build currently requires the Mac engine and an active debugging tunnel.

Silent verification: authenticated transport tests cover unauthorized/browser request rejection, private credentials, loopback binding, no startup commands, read-only state and explicit Stop dispatch into a mock. Shared-state regression covers connection loss retaining frozen unconfirmed playback, disabling controls and recovery without media/volume/membership commands.

### Standalone Android build on Pixel (2026-10-17)

Owner rejected the first Mac companion build. Replaced its backend with native Google Cast/MediaRouter plus on-device yt-dlp 0.18.1, including bundled Python/QuickJS. Removed the private phone HTTP server and ADB reverse provisioning. The installer removes the old Moozaq tunnel; the Android app deletes its obsolete private bridge token. Public catalog metadata (1,481 entries; 1,374 at least one hour) ships with the APK and persists/refreshes on the phone.

Silent Pixel 6a checks passed with the Mac reverse tunnel removed:

- Native discovery found All speakers, Bedroom TV, Bedroom, Fridge, Kitchen and Office, plus the existing temporary Fridge + 2 group. No route was selected by the checks.
- Passive local receiver metadata classified Fridge + 2 as temporary and kept All speakers visible. This does not prove member audibility.
- The phone resolved starter `7vy0H3ERRiA` to an audio-only HTTPS MP4 stream, with its title and duration. A bounded HTTP range fetch returned media bytes directly to the phone. No cookies or Premium credentials were supplied. No signed URLs were printed or saved.
- The catalog loaded locally, without an RPC endpoint. Native source extraction ran on the Pixel, not on Mac.

Validation: Android assemble and lint pass; 4 Android unit checks cover Stop/late preparation, destination invalidation and content-bound Mac/Android identity; 33 shared desktop tests and 104 Python bridge tests pass. Two Android instrumentation checks cover discovery/topology and source resolution/media fetch.

Not tested in this build: audible Android-initiated playback, all-source coverage, native Android live group mutation, phone disconnection during playback, full-session reliability, or a complete channel refresh on Android. No audio was started/resumed for validation. These remain separate hardware acceptance checks. The native adapter never reloads on reconnect or restores cached volume/seek position, and uses the same content-hash-bound `moozaq` source tag as desktop for rejoining identification.

Final installation succeeded as `com.moozaq.app` version `0.3-standalone`; the process is running and the ADB reverse list is empty. Final launch produced no AndroidRuntime crash. The phone was dozing during the final screenshot, so that capture could not verify the visible standalone UI; the earlier compact-layout screenshot was from the companion build. Keep those visual checks distinct.

### Android Bedroom playback investigation — 2026-10-17

Owner-reproduced failure on the standalone Android app, source `Xdho5SVxEVI`:
Bedroom selection attached a connected Cast session. On explicit Play, on-device
resolution returned `audio/mp4` after about five seconds. LOAD was issued, but the
receiver stayed idle and then lost its media status; no PLAYING confirmation
arrived before the 20-second timeout. The owner heard no music. This does not
establish the receiver's underlying rejection reason.

A silent instrumented check on the Pixel resolved the same source with default
network settings and with `--force-ipv4`. Both URLs were IPv4-bound, and both
returned HTTP 206 with readable audio bytes on the phone. The suspected IPv6
binding mismatch was not reproduced; no production IPv4 change was made.
Successful phone fetching does not demonstrate Nest access or decoding.

Fixed Android command deadlines being treated as ordinary coroutine cancellation,
which hid the timeout error. Deadlines now surface explicit failures while Stop
and caller cancellation remain cancellation. Added private, bounded, URL-redacted
Cast diagnostics including LOAD acknowledgement, player state and idle reason.
No automatic retry, audio start or receiver-volume change was introduced.

Validation: updated APK assemble and lint passed; all seven Android unit tests
passed, including timeout-versus-cancellation regressions. The one silent network
binding diagnostic passed on the Pixel. Reinstalled and opened the standalone
app after the owner deleted both apps. No playback command was sent by the agent;
receiver-side failure diagnosis awaits another explicit owner playback attempt.

### Android direct-stream compatibility fix (2026-10-17)

Owner retried Bedroom and two mixes. First selection failed inside the SDK
(session ended code 2155); second selection connected. Both explicit LOADs were
acknowledged, entered LOADING, then reached IDLE with reason 4 (ERROR) within
about 120 ms. The SDK’s next empty media status erased the transient error,
leaving the app waiting for its full 20-second confirmation deadline. The
connection failure code’s underlying cause is not established.

Silent source comparison for `Xdho5SVxEVI` found a reproducible HTTP difference:

| Resolver/source | Ranged request | Ordinary GET | Format |
|---|---|---|---|
| Android library’s bundled yt-dlp 2025.11.12 / ANDROID client | 206, audio bytes | 403 Forbidden | 140 / AAC / MP4 |
| Mac yt-dlp 2026.08.19 / VISIONOS client | 206, audio bytes | 200, audio bytes | 140 / AAC / MP4 |
| Updated Android yt-dlp 2026.08.19 / VISIONOS client | 206, audio bytes | 200, audio bytes | 140 / AAC / MP4 |

The phone was on Wi-Fi without VPN, and the signed-address hashes matched Mac.
Tests read at most a few bytes and never played audio. A range-only smoke test
was insufficient: it missed a source which rejected ordinary fetching. This
explains a concrete source incompatibility; the receiver’s internal HTTP request
was not captured, so the causal link to its generic ERROR remains to be verified
with an authorized playback attempt.

The APK now ships the official 2026.08.19 Python zipapp, verified against its
release SHA-256 (recorded in Android assets/licenses/NOTICE.txt). Initialization
atomically migrates the old private runtime before invoking it. No executable
updates, credentials or Mac services are fetched at app startup. Direct playback
performs an ordinary bounded GET before LOAD and still checks cancellation and
receiver identity afterwards. A failed preflight leaves existing playback alone.

Additional fixes: latch a receiver ERROR after observing the requested LOAD so
subsequent empty status cannot hide it; retain discovered speaker rows as
unavailable across gaps; keep the picker height fixed; surface explicit
connection failures immediately so destination retry is available. A successful
LOAD acknowledgement is still not PLAYING confirmation.

Validation: assemble/lint passed; 13 Android unit tests and 34 shared desktop
tests passed. Two silent Pixel checks passed, covering the failing mix with
range and ordinary Cast-style requests, and the starter mix with production
ordinary-GET preflight. Installed version 0.3.1-standalone (code 3) and reopened
it. Visually checked the browser and six-row speaker picker; no route was
selected and no audio was started. Audible Nest playback after this update,
repeatable first-connect behavior and long-form reliability remain unverified.


### Android playback confirmed; cold-start rejoin (2026-10-17)

The owner confirmed that standalone 0.3.1 now plays a mix on Bedroom, and that it continues after closing the Android app. The phone log independently shows source `Xdho5SVxEVI` reaching PLAYING at 15:34:16 UTC after the new ordinary-GET preflight. This validates that short playback attempt, not all sources, groups or three-hour reliability.

The remaining bug was reproducible: reopen at 15:34:45 UTC started native session restoration, which failed with 2152 ten seconds later. Moozaq had no persistent destination and stayed disconnected/empty while Bedroom continued.

Android 0.3.2 (versionCode 4) adds persisted device identity, bounded guarded rejoining and a Checking playback state. It retains native SDK restoration, then verifies the existing Default Media Receiver with a passive LAN status probe before selecting that same discovered device with relaunch disabled. Fresh remote media customData restores the catalog source, artwork and current playback position; no remembered volume or position is applied. A different receiver app is left alone. See google-cast-lessons.md for the non-atomic probe/selection limitation.

Validation:
- APK build and Android lint passed; 20 Android unit tests and 34 shared desktop tests passed. New tests cover recovery backoff, superseded recovery probes, unavailable remembered devices, passive receiver eligibility, and malformed Cast frames.
- Installed 0.3.2 on the Pixel. An explicit existing-session instrumentation test verified Bedroom already had the Default Media Receiver, joined its remote without a LOAD, and read PLAYING for “Dark Jazz & Cinematic Trip-Hop // Midnight Fever Motel // Dawn Is Still Hours Away”, source recognized, at 1,852 seconds. The test is opt-in and skipped in normal suites.
- Force-stopped the phone process and reopened the activity. Native resume again failed with 2152; the fallback joined the already-running receiver in about 11 seconds total. At 15:43:47 UTC it reported PLAYING at 1,884 seconds with the same application-session fingerprint as before closing. No LOAD, Play/resume, seek, volume, or source resolution occurred during recovery.
- Phone screenshot verified artwork, the highlighted matching catalog card, Playing, Pause, Bedroom speaker, and advancing progress (32:01 of 1:32:47). This is visual/protocol evidence; the owner supplied the audible confirmation of the preceding playback.

This does not yet prove phone reboot, Wi-Fi loss, native dynamic-group reattachment or long-session reliability. The native initial restore timeout still adds roughly ten seconds before fallback recovery.

A second cold restart of the normal installed activity also recovered automatically: 15:45:27 → 15:45:38 UTC, same application-session fingerprint and source, PLAYING at 1,996 seconds. No playback commands were issued. The app was left open and synchronized to Bedroom.

### Android 0.3.3 recovery guard; acceptance pending (2026-10-17)

Source review found that the pre-probe recovery guard yielded to a resuming SDK session but the post-probe guard omitted that state. The candidate now uses one immutable session-activity predicate at both decision points and reads the current SDK session again after the passive probe. Two focused JVM cases cover the active-state matrix and a session that starts resuming during the probe.

Silent validation passed: Android assemble, androidTest assemble and lint, plus 30 Android unit tests and 34 shared desktop tests with no failures/errors/skips. The phone still had verified 0.3.2 code 4 before deployment; reinstall preserved app data and `dumpsys` then verified 0.3.3 code 5.

A cold reopen at 16:59:34 UTC remained foreground and performed only passive recovery. Bedroom reported no active compatible Moozaq receiver at 16:59:36; an earlier passive check at 16:58:54 had the same result. The screenshot showed Checking playback with the no-active-receiver explanation. The earlier playing session was therefore unavailable for source/session continuity testing. No LOAD, Play/resume, seek, volume or source-resolution event occurred, and no audio was started to establish the missing precondition.

Two cold rejoin measurements and the warm return remain unperformed. This run establishes deployment and idle/no-active-receiver behavior only, not faster recovery, existing-session continuity, audible output or long-session reliability. Evidence captures are under ignored `compose-app/androidApp/build/validation/`.

### Android 0.3.3 faster rejoin validated on Office (2026-10-17)

After the Bedroom session was unavailable, the owner explicitly authorized starting Midnight Fever Motel on Office. The first read-only Office destination selection failed with SDK error 2155; a deliberate selection retry connected to an idle receiver without media commands. This adds one observed 2155 occurrence but does not establish its cause.

Explicit Play resolved and preflighted source `Xdho5SVxEVI`, LOAD was acknowledged, and fresh receiver state confirmed PLAYING at 17:18:16 UTC. The owner then audibly confirmed jazz playing on Office. The application-session fingerprint was `5b3da0b68d62`.

Recovery validation against that existing session:

- Cold reopen 1: backend created at 17:19:12.385, first fresh connected PLAYING snapshot at 17:19:14.985 (**2.600 seconds**), source unchanged, position 56 seconds, same application-session fingerprint.
- Cold reopen 2: backend created at 17:19:53.771, first fresh connected PLAYING snapshot at 17:19:55.655 (**1.884 seconds**), source unchanged, position 97 seconds, same application-session fingerprint.
- Warm background/foreground: foreground at 17:20:35.582, fresh playing media callback at 17:20:35.672 (**0.090 seconds**), with no route reselection.
- Screenshots after all three runs showed the matching artwork, Now playing/Playing, Pause, Office speaker and advancing timeline. A machine check of each recovery log slice found zero LOAD, Play/resume, seek, volume, source-resolution or play-confirmation events.

The 0.3.2 baseline took about 11 seconds on its two cold reopens, so the disabled saved-session restoration and 500 ms grace are retained for this validated path. This establishes one explicitly authorized Office source/session, two cold starts and one warm return. It does not establish audible continuity after each reopen, phone reboot, Wi-Fi loss, other speakers/groups/sources, or three-hour reliability. Evidence is under ignored `compose-app/androidApp/build/validation/office-*`.

### Android 0.3.4 route-change diagnosis and hardening (2026-10-17)

The older Bedroom 2155 sequence occurred during an intended Bedroom TV to Bedroom speaker route switch and attached successfully. The Office 2155 sequence instead returned to the phone route without attaching; a later manual selection succeeded. The exact Office route-change actor is not established and current system logs do not identify it.

An on-device `CastReasonTest` initially returned zero for all codes because `CastContext` loads `MAP_CAST_STATUS_CODES_TO_CAST_REASON_CODES` asynchronously after construction. Waiting for that bundle produced the authoritative mapping: **2155 → `CASTING_ROUTE_CHANGED`**, with 2152 → `SESSION_RESUME_FAILED`. The final bounded instrumentation test asserts the mapping and passes without selecting routes or sending media commands.

Version 0.3.4 / code 6 now logs mapped reasons and session/route context. For a pending first-time destination join, it holds pending UI through a one-second settling delay and allows one route-only reconciliation only with fresh generation, target identity, default phone route and idle SDK guards. It cancels on new selection, background, Stop, close or newer attachment, never automatically retries attached sessions or another Cast route, and surfaces manual retry after a second failure. If the reason bundle is not ready during a callback, only the already-proven 2155 mapping is used as a fallback; other unknown statuses remain unchanged.

Validation: Android assemble, androidTest assemble and lint passed; **35 Android unit tests and 34 shared desktop tests** passed with no failures/errors/skips. `dumpsys` verified code 6 / 0.3.4, and the focused on-device reason-mapping test passed. A cold reopen at 18:07:45 UTC found no active compatible Office receiver, so no existing-session continuity result was available for this build. No new audio or forced 2155 reproduction was attempted. Deterministic tests cover the retry guards, but real-world effectiveness against the original transient Office route reversal remains unproven. Evidence is under ignored `compose-app/androidApp/build/validation/034-*`.

### Android 0.3.5 plain-language idle panel (2026-10-17)

The owner rejected the debug-like Android playback status. The mobile player had been replacing the title with “Checking playback…” for any recovery message and then repeating raw receiver detail underneath. Android 0.3.5 / code 7 changes confirmed no-active-receiver recovery to a settled IDLE state, removes the raw “No active Moozaq receiver” presentation, and uses “Connecting…” only for actual connection work. The title now follows the Mac model: “Nothing playing” when settled idle, with “Last played: …” when a previous in-memory track exists and “Choose a mix” otherwise.

Validation: Android assemble, androidTest assemble and lint passed; **35 Android unit tests and 35 shared desktop tests** passed with no failures/errors/skips. `dumpsys` verified code 7 / 0.3.5. A fresh phone screenshot at 18:22 UTC shows “Nothing playing” and “Choose a mix” with no checking/receiver debug text. The shared presentation test separately verifies the “Last played: Our mix” label. No playback was started for this UI validation. Evidence: ignored `compose-app/androidApp/build/validation/035-idle.png`.

### Android 0.3.6 connect-and-play (2026-10-17)

The owner reported that mix Play clicks did nothing while Office was selected but disconnected. No `play.request` appeared in diagnostics: the shared store rejected the click because `canStart` required an already synchronized receiver. Android 0.3.6 / code 8 now allows an explicit Play request with a selected destination, shows Starting immediately, connects if needed, waits up to 15 seconds for fresh readiness and then starts once. Stop or a newer destination invalidates the wait; timeout/connection failure is explicit and never causes a late automatic retry.

Silent store tests cover connect-then-play, Stop-before-ready preventing a late Play, and visible selection failure. Full validation passed with **35 Android unit tests and 38 shared desktop tests**, Android assemble, androidTest assemble and lint. `dumpsys` verified code 8 / 0.3.6.

With explicit owner authorization, the real Midnight Fever Motel button was used. Logs show one Office destination request, one `play.request` for `Xdho5SVxEVI`, one preflight and one LOAD, followed by receiver-confirmed PLAYING at 18:41:49 UTC. The UI showed the matching artwork, Now playing/Playing, Pause and Office connected. This verifies the actual connect-and-play path on Office, not all sources or long-session reliability. Evidence: ignored `compose-app/androidApp/build/validation/036-*`.

### Android 0.3.7 pending-mix presentation (2026-10-17)

The owner identified a second presentation contradiction: after starting another mix, the bottom panel showed the old track's title, artwork and timeline beside Starting. Android 0.3.7 / code 9 now uses the pending transport's requested source for the title/artwork and hides the old timeline until receiver confirmation. Desktop and mobile share the same requested-mix behavior.

Validation: Android assemble, androidTest assemble and lint passed; **35 Android unit tests and 39 shared desktop tests** passed with no failures/errors/skips. `dumpsys` verified code 9 / 0.3.7. With explicit owner authorization, Office switched from Just Being Myself to Nothing More Needed. The pending screenshot shows Nothing More Needed's title/artwork with Starting and no old timeline. Logs show one `play.request` for `GzUPBC6j3ms`, one preflight and one LOAD, with receiver-confirmed PLAYING at 18:59:23 UTC. The final screenshot shows the same requested track Playing. Evidence: ignored `compose-app/androidApp/build/validation/037-pending.png` and `037-final.png`.
