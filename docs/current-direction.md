# Moozaq current direction

Updated October 17, 2026.

## How to use the brief

The [v0.1 product brief](product-brief.md) is the original working baseline. The owner explicitly expects decisions to change. Treat suggested technologies, scope, priorities, and roadmap as provisional. Record subsequent decisions here and distinguish desired behavior from demonstrated capability.

## Initial focus

The owner's first priority is an Android app that lets them listen to the YouTube music mixes shown in the supplied screenshot on their own Google Nest music devices. This takes priority over the brief's initial mobile-web emphasis and its placement of native Android in P2. The implementation stack and playback route are still undecided.

Desired first flow: choose or share a YouTube mix, choose a Nest device, and listen. Validate this specific source-to-speaker experience before expanding the catalog, accounts, or commercial features. Casting an unrelated audio file alone would not demonstrate the requested YouTube use case.

## Preferred playback behavior

The owner prefers Nest to keep playing when the phone disconnects. The Android phone should act as a remote control, rather than carrying the audio throughout the session. This is the target experience, subject to feasibility for the chosen YouTube mixes; it has not yet been demonstrated.

The spike should start an actual mix, switch off the phone or disconnect it from the network, and verify that Nest continues playing. Reconnect the phone and verify that it can regain playback controls. Locking the screen alone does not demonstrate phone-independent playback. Follow with a full three-hour playback test on the viable route.

On reconnection, the phone should synchronize with the existing Nest playback session, showing the current mix, playback position, playing/paused state, and volume. Nest's current session is the source of truth; reconnecting must not restart the mix or overwrite it with stale phone state. The phone should regain volume up/down, stop, play/resume, pause, and seek controls where supported. Changes made while the phone is disconnected should be reflected when it returns. Validate these capabilities for the selected playback route rather than assuming they are available.

Phone-dependent audio casting may be useful as a comparison, but does not satisfy this preferred behavior. The owner has not yet accepted it as a fallback.

## Reference

Saved [user-provided YouTube screenshot](references/youtube-mixes.png). The main example is “No Rush. Just Coffee. – 3 Hours of Dark Vintage Noir Jazz for Slow Mornings & Quiet Starts” by The Worn Record. Other visible references include Afro house jazz, psychedelic desert rock, cozy vintage jazz, and Persian jazz. The owner has supplied [six starter video URLs](starter-videos.md) for the catalog and playback spike.

## Playback questions to validate

- Which Nest models and speaker groups are targeted?
- Are these exact mixes available through YouTube Music and castable there?
- Can the selected route keep playing independently of the phone and let the phone regain control after reconnecting?
- Which route supports the required long sessions and lock-screen behavior reliably?

Google documents casting music to Nest through YouTube Music, rather than the YouTube app: [Cast-enabled apps](https://support.google.com/googlehome/answer/7181830?hl=en).

Google also documents casting Android device audio: [Play Android audio on Google smart devices](https://support.google.com/googlehome/answer/7169790?hl=en-AU). This is a candidate validation route; it is not evidence that a custom app can initiate it or that these exact videos will play uninterrupted with the phone locked.

An ordinary YouTube watch URL is not a direct Cast audio stream. YouTube API policies restrict separating audio and video: [Developer policies](https://developers.google.com/youtube/terms/developer-policies). The prototype's playback architecture remains unresolved.

## Control reliability

Do not start or resume audio unless the owner explicitly asks. This includes testing, test tones, retries, and demonstrations. Building or testing the app does not itself authorize playback. Read-only receiver checks and mocked tests may proceed without sound.

The owner explicitly prioritizes tight, dependable Cast controls. Follow [playback control requirements](playback-control-requirements.md): fresh receiver state, confirmed command outcomes, Stop that cancels pending playback work, and reconnection without reloads. These are core acceptance requirements; the spike only implements part of them.

## No subscription requirement

The owner does not have or want YouTube Premium. The supplied inputs are ordinary YouTube video links. Do not substitute a paid YouTube Music/Premium workflow for this requirement. Research of extraction, direct URL handoff and a home-server relay is recorded in [playback options](playback-options.md); A short direct-URL playback test of the first mix succeeded on Bedroom speaker; see [spike results](spike-results.md). A production architecture and long-session reliability remain unvalidated.

## Speaker combinations

The owner wants selectable combinations of speakers, including a subset of those used in All speakers. Preserve saved group membership; prefer temporary synchronized Cast groups. See [speaker selection](speaker-selection.md) for supported platform paths and the current Mac limitation. Group playback still requires an explicit request.

## Unified player UI

The owner wants artwork/title and receiver controls combined into one player, with green Connected, orange Connecting, and red Disconnected labels, plus elapsed/total time and a progress bar. Technical receiver details belong in Diagnostics. The progress indicator follows Nest state; selecting a different mix remains a preview and never starts playback.

The mix dropdown belongs directly in the unified player and shows the six mix titles. An explicit user dropdown selection switches the connected speaker to that mix using direct playback and preserves receiver volume. Startup, receiver synchronization and programmatic dropdown updates never start playback. Disconnected selection previews only; choosing the already-loaded mix does not restart or resume it.

Updated selection behavior: choosing a dropdown mix only previews it; current playback must continue. A separate Play selected mix action explicitly switches streams. The existing session title remains visible above its progress bar. Transport Play resumes the existing paused session even when a different mix is previewed; Pause and Stop target that existing session. This supersedes the earlier immediate-switch-on-selection behavior.

## Separate browser and persistent player

The latest UI separates Browse (six available mixes, selected preview, explicit Play selected mix) above a persistent bottom Now playing bar. Browser and receiver artwork/state are independent. Browsing never interrupts playback; receiver changes never replace the browser selection. Bottom transport controls refer exclusively to the receiver session, and Play there only resumes that session. The selected mix starts through its browser Play action. This supersedes the earlier shared selection/player card.

## Automatic remote connection

Speaker selection automatically joins the selected receiver read-only. The last selected speaker ID is saved locally and restored on startup. Failed/dropped connections retry with bounded 5–30 second backoff, without replaying media, changing volume, or moving playback. Main UI Connect / rejoin and Disconnect buttons are removed. Connection changes wait until pending jobs complete; the speaker selector is disabled during jobs.

## Kotlin / Compose rebuild

The owner approved preserving the Python application as a working reference and building the next interface in Kotlin + Compose Multiplatform. `compose-app` shares the catalog, player state and UI between desktop and an Android library target. The initial Mac version uses a private stdio bridge to the proven Python engine and direct playback route A. It does not yet replace the Cast engine with Kotlin or provide an installable Compose Android app. Android will need its own native Cast adapter. Browsing remains silent and separate from the receiver player; only explicit Play actions load audio.

## Group observability

The Compose app now labels speaker groups and provides expandable group details with the current coordinator, membership and individual receiver connection/media/mute states. Passive monitoring logs topology and member-state changes. Group-level PLAYING is not proof of all-member audible output; keep that distinction visible. Network investigation and diagnostics are authorized, but do not infer permission to start or retry audio from general development approval.


## Confirmed group playback and lessons

On October 16 the owner audibly verified all four All speakers members after the passive-status fix: Office, Fridge, Bedroom, and Kitchen (isolated by setting the other members to zero). Direct route A worked in that session. The last explicit volume request restored each member to 50%, with individual receiver confirmation; do not restore those levels automatically on reconnect.

Status inspection must never use a path that implicitly launches receiver apps. Keep the guarded passive-status adapter on both main and member Cast connections. Group status, configured membership, individual volume, and audible output are distinct evidence. A successful session is not proof of long-term group reliability. See spike results for the experiment and remaining checks.

### Main app group controls (2026-10-16)

The Compose app uses the passive-status fix in the shared Python engine. Group details now exposes an individual volume slider for each discovered member, with receiver readback and no numeric percentage labels. Changes are explicit, bound to the selected group/member, and require a fresh receiver response; stale matching values cannot count as confirmation. Startup and rejoining do not restore member volumes. This remains the desktop bridge implementation; the shared Android library builds but does not yet supply an Android Cast backend.

### Product-wide pending UI contract (2026-10-16)

Owner decision: generalize the volume, seek and Play/Pause confirmation behaviour to all asynchronous UI controls, including future Android controls. User intent is shown immediately as pending and remains stable until authoritative confirmation; failures remain explicit. Feedback belongs to the affected control and must not imply unrelated playback interruption. The canonical contract is “Product-wide UI rule: intent → pending → confirmed” in `playback-control-requirements.md`, and AGENTS.md requires future work to follow it.

### Channel-based catalog sources (2026-10-16)

The owner may supply entire YouTube channels as well as individual videos. Treat channels as discovery sources for candidate mixes, classify videos by mood/genre, deduplicate by video ID, and retain actual artwork and metadata. Aim for at least six suitable mixes per category; this is a curation target, not automatic approval of every upload. First supplied channel: Sepia Lounge, recorded in `starter-videos.md`. Channel ingestion in the app is not yet implemented.

### Full-channel catalog implemented (2026-10-16)

Owner decision: import whole public channel catalogs, not six-video samples. Six remains a minimum curation target per category. The Compose desktop app now imports metadata from all available channel tabs returned by YouTube, deduplicates by video ID, caches it on disk, and exposes original thumbnail artwork, title, creator, duration and provisional category. Short videos remain stored; the default 1h+ view hides them and unknown-duration imported videos. Categories are title-based suggestions with channel fallbacks, not listening-verified editorial labels.

Channels opens source management: add a channel URL, refresh one, or refresh all. Refresh runs separately from playback work, merges new/updated entries and retains old entries on failure or disappearance. No automatic audio download or playback, no cookies, and no scheduled refresh. Artwork is fetched on demand and cached on desktop. The original six mixes remain alongside the imported catalog. This supersedes earlier notes saying channel ingestion is unimplemented.

### Minimum mix duration (2026-10-16)

Owner decision: only show tracks with a confirmed metadata duration of at least 3,600 seconds. Remove the optional 1h+ toggle and search bar. Short and unknown-duration entries are excluded from browsing and its counts, including starter mixes without duration metadata. Keep raw imported metadata for future refreshes and existing receiver identification. This supersedes the optional duration filter above.

### Minimal mood browsing (2026-10-16)

Owner decision: remove all catalog filter controls. Browse artwork in horizontal mood shelves, then explicitly play using the hover icon. Keep the one-hour minimum automatic; no search, channel filter, mood dropdown or duration toggle. Channel management remains separate from browsing. The persistent receiver player remains independent of browsing.

### Browse polish (2026-10-16)

Artwork placeholders use the logo in grayscale at 35% opacity; main branding stays in color. Routine engine completion messages are hidden from the main screen and available under Channels → Debug info; errors remain visible. Vertical browsing snaps to mood-row starts after scrolling settles, with trailing space to allow the final row to align at the top.

Owner correction: remove scroll snapping entirely. Mood browsing uses normal free scrolling, without automatic alignment or extra trailing snap space.

The browser heading shows the category of the top visible mood row instead of “Explore”, updating as the user scrolls freely.

Category headings now use native sticky headers: each pins at the top of the browser until the next category pushes it out. There is only one heading per category, no separate duplicate heading and no scroll snapping.

### Unified output control (2026-10-16)

One speaker control in the bottom player combines the selected output name and connection indicator. Its popover contains discovered speakers/saved groups and individual group volume sliders. Remove the duplicate top selector, separate Connected label and Group details section. Overall volume stays in the player. Selection connects the remote read-only; it does not transfer playback. Arbitrary group-member checkboxes remain unsupported by the Mac backend; use Google Home to change membership. Do not fake multiroom groups or present mute as membership selection.

Owner requested functional individual speaker selection checkboxes. Backend investigation found no verified membership mutation path in the current Mac Cast bridge. The feature remains blocked at the backend, not completed by the speaker popover. See speaker-selection.md for evidence and the supported Android Output Switcher path.

Speaker picker is now a centered 600×540 dp modal with a scrolling device area, fixed outer dimensions, blurred background and Done dismissal. Remove repeated connection subtitle and visual radio circles; confirmed connection is a checkmark on the selected row, with a spinner/text while connecting. Selected output gets the overall volume slider (including All speakers), while group members retain individual sliders. Existing pending-value confirmation logic is shared with the player volume.

Mac must also support the requested playback-membership checkmarks. The October 16 dynamic-group spike now has a passive before/after observation harness; saved-group playback and member volume support do not establish live membership mutation. Keep the production UI honest until the Mac mutation protocol and synchronized add/remove behavior are verified. See docs/speaker-selection.md.

Native Mac dynamic membership is now technically demonstrated (October 16): recovered SET_PLAYBACK_DEVICES over Cast multizone, with application session ID and full physical-device list. Mac removed Bedroom and added it back to the continuing Kitchen session, verified fresh group membership and PLAYING; no independent LOADs or saved-group edits. Earlier statements that Mac lacked a known mutation path are historical. Integrate experimental membership controls with request-bound pending state, fresh membership confirmation and receiver endpoint handoff. See spike-results.md for limitations and protocol evidence.

### Native Mac membership integrated (2026-10-16)

The Compose Speakers modal now uses physical-speaker checkboxes for the current session’s participants. A separate Control action switches the inspected remote without moving music. Checkboxes use intent → pending → confirmed, preserve requested selection on errors, and disable removing the final speaker in favor of Stop. Only receiver-advertised audio grouping/transfer sessions are eligible. Native temporary grouping follows the returned endpoint, checks fresh media identity and exact membership, and waits for delayed joins without retrying the mutation. This supersedes the earlier remote-only checkmark and Mac-blocked notes.

The integrated bridge removed Bedroom from Kitchen’s session and added it back successfully, with continuing receiver position and confirmed membership. The new interface builds; final visual verification is pending because the Mac was locked during validation. The phone is intentionally left awake while plugged in at the owner’s request during their absence.

### Whole-home activity and volume visibility (2026-10-16)

The speaker picker passively observes every discovered output, independently of the selected remote. Each row shows receiver-reported playback activity and current volume; stale evidence becomes unavailable/unconfirmed. Fresh temporary-group membership attributes the group session to its physical members without mistaking saved group membership for active playback. Checkboxes still belong to the current music session; other sessions are explicitly labeled Playing other music. Control on a member of another active group opens that group's remote without moving playback. Inline volume sliders work on observed outputs without changing the selected session, retain pending intent and require fresh confirmation. Numeric percentages remain hidden in the interface.

If a remembered temporary output disappears, the picker explicitly explains that it is unavailable and lets the owner select another remote. Do not infer a new playback target or restart audio. Individual output-volume controls depend on their own fresh receiver connection, not on whether the main remote is connected.

Automatic recovery now remembers a physical member alongside the selected group. If the group is absent from discovery and disconnected for at least 15 seconds, and no operation is pending, reconnect the remote to that discovered member. Preserve an earlier physical selection when it remains a member; otherwise remember the coordinator or another known member. This changes only the inspected remote, never playback, membership or volume. Unknown legacy group preferences cannot safely infer a member and still require an explicit remote selection.

Session-aware recovery takes precedence while the running app has observed active playback. Retain the last confirmed track and frozen position as unconfirmed while looking for a surviving session. Follow only one fresh candidate with the same stream fingerprint, overlapping participants and plausible position continuity, including group-to-group and group-to-single-speaker transitions. Never match by title alone. Fresh idle reports from every remembered participant, after a 15-second grace period, allow returning to an idle remote. At 60 seconds show playback unavailable rather than indefinite progress; after 120 seconds do not infer a transferred session. Manual Control selection resets recovery intent. Recovery never loads media, changes membership or restores volume. Session identity is held in memory; after a full app restart only the persisted remote/member fallback is available.

Persist the last verified physical speaker address as a discovery hint alongside its UUID. Seed PyChromecast's passive host discovery from that address to recover from missing multicast announcements. The host discovery response supplies the actual device UUID; the control connection must still match the selected UUID, never trust a remembered IP alone. Fresh independent output observations gate player controls and supply playback state; a connected socket alone does not count as synchronized.

Keep known physical speakers/displays visible across discovery gaps and app restarts. Persist their identities and names, not playback or volume. Mark missing devices unavailable and disable their selection/membership controls until rediscovered; temporary groups are not retained as permanent inventory. An unavailable selected remote must not prevent controlling another available output or adjusting its independently confirmed volume.

Configure known-host discovery hints before starting CastBrowser; adding them after its worker starts can delay the first probe by a full 30-second cycle. Treat a retained discovery record with no remaining services as unavailable, including for recovery and group following. Replace stopped passive-monitor connections on subsequent polls rather than retaining a dead socket. Every replacement must install passive status protection before connecting. These are discovery/control repairs, never reasons to launch a receiver or resume playback.

### Group destination selection (2026-10-17)

Owner clarified that selecting All speakers makes the saved group the playback destination. Group rows now offer a destination checkbox using the existing passive select/rejoin path; the next explicit Play targets that group. Selection alone does not load, resume or transfer music. Hold the desired group selection with pending feedback until the matching remote is synchronized; retain failed intent with retry feedback. A destination is exclusive and is replaced by choosing another output. Physical speaker checkboxes retain their separate current-session membership behavior; the picker explains this distinction.

Owner correction: while idle, individual speaker checkboxes also select the playback destination. Selecting a physical speaker replaces and unchecks a saved group, and selecting a group replaces the physical destination. Both use passive selection and the same pending confirmation. During an active/paused/buffering session, physical checkboxes retain live membership behavior. Pending membership changes must not silently turn into destination selection.


### Design handoff implementation (2026-10-17)

Owner requested implementation of `moozaq-design-handoff.zip`. The supplied Material 3 dark tokens, Roboto Flex typography, 240 dp artwork cards, 136 dp player, anchored speaker picker and Channels dialog supersede the earlier large player/centered speaker modal layout. Preserve the existing one-hour minimum, silent browsing and passive remote selection. The original handoff is archived under `docs/design-handoff/`.

The compact picker removes the separate Control buttons and explanatory footer. A device-name click still opens its remote; destination and live membership checkboxes retain their existing meanings. Unchecking the last active physical participant now invokes verified Stop instead of disabling the checkbox. Unchecking a currently playing selected saved group also invokes Stop. Neither action deletes a saved group. While idle, the exclusive destination remains available for the next explicit Play.

Stopped UI retains the last observed track for an explicit Play again action when it matches the catalog. This memory never resumes playback automatically and is not persisted as a session. Reconnection continues to show uncertain receiver evidence. Local pending colour and text remain tied to real confirmations, including Stop; prototype timer-based confirmations are limited to a separate test-only preview.


### Native Mac chrome (2026-10-17)

Owner rejected the white native title bar. Moozaq's native Mac window appearance should stay dark alongside the app theme, retaining native window controls. Configure the application appearance at JVM startup, without changing the owner's system-wide appearance.


### Speaker handoff continuity and picker (2026-10-17)

Adding a speaker must retain the current mix and frozen timeline while its remote moves. A verified membership handoff now supplies fresh, session-bound media evidence directly to the bridge, without waiting for multicast discovery and a second monitoring connection. Evidence expires after 12 seconds and passive status requests refresh it; session changes invalidate it. Unconfirmed controls remain disabled and Stop stays available. Do not present Play again merely because a handoff temporarily lacks receiver state.

Hide receiver-reported temporary groups from the destination list. The physical participant checkboxes represent them. Saved groups such as All speakers remain visible. Classify from the membership response or receiver dynamic_groups metadata, never from friendly-name suffixes. The Cast chip labels a classified temporary group by speaker count.


### Current Android companion deployed (2026-10-17)

Owner requested the current Moozaq design on the Pixel rather than the old Android Cast prototype. The first installable current build is `compose-app/androidApp`, package `com.moozaq.app`. It shares the Compose catalog, pending controls and group selection with desktop, with a touch-friendly compact layout.

This development build uses the existing Mac Bridge instance through an authenticated loopback-only HTTP transport over wireless/USB ADB reverse. This is explicitly a Mac companion, not yet a standalone native Android Cast sender. Keep the Mac app and debugging connection available for control; Nest continues to fetch route-A media directly, and phone lifecycle callbacks only poll state. Mac and phone therefore observe/control one engine rather than competing separate engines. Credentials are provisioned into private app files, excluded from backup, and never bundled in the APK. Closing or reopening the phone does not load media or restore volume.


### Standalone Android supersedes companion (2026-10-17)

The owner explicitly rejected the Mac companion architecture. Android must work independently of the Mac, including discovery, source resolution, catalog management and Cast controls. `androidApp` now uses native Google Cast/MediaRouter and a bundled on-device yt-dlp runtime. The loopback server, phone credentials and reverse-tunnel setup have been removed. The earlier companion was a development detour, not an accepted product direction.

Both apps share the Compose interface and state contracts; desktop retains its Python engine. The phone bundles public cached catalog metadata and can refresh channels itself. Explicit Play resolves on Android and sends the audio URL to the receiver; phone startup, browsing and reconnection never LOAD audio. Native Android group membership is gated on receiver-advertised support and still needs audible hardware validation.


### Standalone Android return to playback (2026-10-17)

Owner confirmed a mix plays on Bedroom from standalone Android and continues after closing the phone app. Reopening must automatically return to that speaker and show its actual current mix, artwork, playback state, position and volume. Android now persists the last Cast device identity and presentation name, never playback commands, signed streams, volume or timeline. Native saved-session restoration gets first chance; a bounded recovery path rediscovers the same device and checks for an existing Default Media Receiver before reattaching. A different receiver app or unavailable device is not replaced. Only fresh receiver state counts as Playing; startup and return never resolve or reload a mix.


### Android 0.3.3 recovery validated on Office (2026-10-17)

See [Android Cast hardening handoff](android-cast-hardening-handoff.md) for the verified 0.3.2 baseline and original candidate handoff. The phone now has 0.3.3 (code 5). Source review found that the post-probe recovery guard omitted `isResuming`; both recovery decision points now use a shared guard covering connected, connecting, resuming and suspended SDK sessions, with fresh state read after the probe. Assemble/lint pass with 30 Android and 34 shared tests.

Bedroom no longer had an active compatible receiver, so the owner explicitly authorized starting Midnight Fever Motel on Office and audibly confirmed its playback. Two cold-process reopens reached fresh PLAYING in 2.600 and 1.884 seconds; a warm foreground return received fresh playing media state in 0.090 seconds. Source and application-session fingerprints remained identical, position advanced, screenshots showed the correct Office player, and recovery emitted no LOAD, PLAY, seek, volume or source-resolution events. Keep the disabled saved-session restoration and 500 ms grace for this validated path. This is one source on one speaker, not phone-reboot, Wi-Fi-loss, group, all-source or long-session proof.

### Android 0.3.4 route-change hardening (2026-10-17)

The phone now has 0.3.4 (code 6). After waiting for the Cast reason table's asynchronous initialization, the on-device SDK check confirms 2155 maps to `CASTING_ROUTE_CHANGED`; an earlier zero result was test initialization timing, not a different meaning. A pending first-time destination join now remains pending through a one-second settling delay and gets at most one route-only reconciliation when fresh generation, target identity, default-route and SDK-state guards all pass. It never retries attached sessions, another Cast route, media, volume or source resolution. Cancellation covers new selection, background, Stop, close and newer attachment.

Validation passed with 35 Android and 34 shared tests plus the focused on-device reason mapping. If the reason bundle is not ready during a callback, only the already-proven 2155 mapping is used as a fallback; every other unknown status remains unchanged. The exact actor that returned Office to the phone route remains unknown, and no forced reproduction was attempted. A later cold-rejoin check found no active compatible Office receiver at 18:07 UTC, so hardware recovery and real-world retry effectiveness for this change remain unproven.

### Plain-language Android player status (2026-10-17)

Owner rejected the Android panel's debug-like “Checking playback…” and “No active Moozaq receiver…” presentation. Android 0.3.5 (code 7) now uses “Nothing playing” only for confirmed idle, shows “Last played: …” when an in-memory previous track exists, uses “Choose a mix” otherwise, and reserves “Connecting…” for genuine connection work. Raw receiver/protocol explanations remain in diagnostics. The phone screenshot visually confirms the clean idle panel; 35 Android and 35 shared tests pass.

### Explicit Play connects before starting (2026-10-17)

Owner found that mix Play clicks were silent when the selected speaker was disconnected. Android 0.3.6 (code 8) now treats Play as an explicit connect-and-start intent: show Starting immediately, select the current destination if needed, wait up to 15 seconds for fresh readiness, then start once. Stop or a newer destination invalidates the wait, and failures remain visible rather than becoming late playback or a silent no-op. Silent tests cover connect-then-play, Stop-before-ready and connection failure. With owner authorization, the real Midnight Fever Motel button connected Office and reached receiver-confirmed PLAYING with one LOAD; 35 Android and 38 shared tests pass.

### Pending start presents the requested mix (2026-10-17)

Owner identified that starting a second mix showed the old track's artwork, title and timeline beside a generic Starting label. Android 0.3.7 (code 9) now presents the requested mix's title and artwork with Starting and hides the old timeline until fresh receiver confirmation. A real Office transition from Just Being Myself to Nothing More Needed visually confirmed the corrected pending panel and then PLAYING for the requested source; 35 Android and 39 shared tests pass.

### Android phone UI rebuilt from the owner handoff (2026-10-17)

The owner supplied the Android design handoff and asked for the phone UI to follow it exactly; the archive lives at `docs/design-handoff/android/`. Android 0.4.0 (code 10) now uses the handoff's four screens instead of the previous compact bar: Browse with a 64 dp app bar, snapping 200 dp mix cards that start on tap, a 64 dp mini player, a full-screen Now playing controller, and a Cast to bottom sheet. Seek, volume and Stop casting live only in Now playing; the app-bar Cast button replaces the device chip; the X becomes a spelled-out Stop casting; cards no longer carry play buttons.

The phone path and the desktop path share catalog, `PlayerStore`, confirmation rules and tokens but not layout: above 600 dp the existing desktop screen is unchanged. Every pending state still ends on fresh receiver state, never on a simulated timer. Two honest deviations are recorded in `docs/spike-results.md`: the Cast button is our own vector opening the Cast to sheet rather than the SDK `MediaRouteButton`, because Moozaq owns and confirms routing, and a pending Stop keeps a "Stopping…" label the handoff does not specify.

The owner's open questions were left open rather than guessed: no Stop casting inside Cast to, no group-volume scaling decision, device order stays as the SDK returns it, and predictive back uses a plain collapse.

### Public website (2026-10-17)

The marketing site is live at https://moozaq.com. It is the Astro project in `website/`, deployed as the Cloudflare Worker `moozaq` with Workers Static Assets. It is not Cloudflare Pages, Netlify, or a Git-connected build. This repository has no GitHub remote, so the next update is `npm run deploy` from `website/`. That publishes a new version of the same Worker. The custom domain, the single proxied Worker DNS record, the absence of `www`, and Always Use HTTPS are already in place. A content change must not redo them.

The site must say that Moozaq is free, open source, and has no ads, accounts, or subscriptions. That supersedes the design brief's earlier ban on "free forever" language. Download buttons stay placeholders in `website/src/data/site.ts` until GitHub release URLs exist. `docs/website-design-brief.md` remains the visual reference. The commands and the things not to touch are in `website/README.md`.
