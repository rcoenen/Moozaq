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
