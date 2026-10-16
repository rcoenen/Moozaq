# Moozaq current direction

Updated October 16, 2026.

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
