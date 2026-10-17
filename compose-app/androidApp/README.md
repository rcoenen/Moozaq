# Moozaq standalone Android app

The current Compose music browser runs on Android with native Google Cast control and an on-device yt-dlp/Python/QuickJS runtime. The APK pins upstream yt-dlp 2026.08.19 (the Mac reference version), overriding the native library’s old bundled resolver with checksum verification. No Mac server, ADB reverse, account, Premium subscription or browser cookies are used at runtime. ADB is only for development installation and tests. The Pixel build includes ARM64 native libraries.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./compose-app/gradlew -p compose-app :androidApp:assembleDebug :androidApp:lintDebug :androidApp:testDebugUnitTest
python3 compose-app/install-phone.py --serial <adb-serial>
```

Package: `com.moozaq.app`. APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Architecture

- `AndroidBackend`: native Cast session and AndroidX MediaRouter discovery. Device selection joins the Default Media Receiver without issuing a media LOAD. The current 0.3.3 configuration disables native saved-session restoration; after a 500 ms foreground grace, a bounded passive receiver-status check permits rejoining an already-running Default Media Receiver on the remembered device. Both pre- and post-probe guards yield to connected, connecting, resuming or suspended SDK sessions. Other receiver apps are not deliberately replaced, and no remembered volume or position is applied. On one explicitly authorized Office session, two cold reopens reached fresh PLAYING in 2.600 and 1.884 seconds and warm foregrounding received fresh media in 0.090 seconds, with unchanged source/session identity and no recovery playback commands. The verified 0.3.2 baseline took about 11 seconds; broader devices, network loss and long sessions remain unproven.
- Route-change hardening in 0.3.4: after the asynchronous Cast reason bundle initializes, 2155 is confirmed as `CASTING_ROUTE_CHANGED`; only that proven mapping is used as a fallback if the bundle is not ready during a callback. A pending first-time destination join holds pending UI and receives at most one delayed route-only reconciliation with fresh generation, target identity, default phone route and idle SDK guards. New selection, background, Stop, close and newer attachments cancel it. Attached sessions, other Cast routes, media, volume and source resolution are never automatically retried. The original transient has not been reproduced for hardware proof.
- Player status in 0.3.5: confirmed idle shows “Nothing playing”, with “Last played: …” when available and “Choose a mix” otherwise. Genuine connection work shows “Connecting…”. Raw receiver/protocol explanations remain in private diagnostics. The clean idle panel was visually verified on the phone.
- Explicit Play in 0.3.6 can connect the selected destination before starting: it shows Starting immediately, waits up to 15 seconds for fresh readiness, and starts once. Stop or a newer destination invalidates the wait; failures never cause late playback or an automatic retry. The real Office connect-and-play path reached receiver-confirmed PLAYING with one LOAD.
- Pending-mix presentation in 0.3.7 uses the requested title and artwork with Starting and hides the previous timeline until receiver confirmation. It no longer pairs a new start with the old track's metadata. This was visually verified during an Office source transition.
- `OnDeviceSources`: bundled public metadata catalog, private persistent metadata cache, channel import/refresh and audio-only HTTPS stream resolution on the phone. Only explicit Play resolves, verifies an ordinary bounded HTTP GET and hands a stream to Nest; the receiver fetches it directly. No audio file is downloaded or relayed through the phone.
- Shared `PlayerStore` and Compose UI: one-hour minimum, touch play affordances, current artwork, independent browsing, pending controls and a compact bottom player.
- Stop invalidates the operation fence and cancels extraction before sending STOP. Late resolver results cannot LOAD after Stop. Transport and seek success require fresh receiver state; volume requests require fresh native volume callbacks. Closing the activity detaches listeners and never stops Nest.
- Physical speaker membership uses native `MediaRouter.GroupRouteInfo` add/remove only where advertised. It never independently starts several speakers. Saved groups remain selectable destinations. Group behavior on Android is not yet audibly validated.
- Temporary groups are hidden using passive `dynamic_groups` metadata from discovered LAN Nest addresses, not friendly-name guessing. Nest's self-signed local setup certificate is accepted only in that bounded metadata request; Internet artwork and media retain normal HTTPS validation.

## Silent device checks

```sh
./compose-app/gradlew -p compose-app :androidApp:assembleDebugAndroidTest
adb -s <serial> install -r compose-app/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r com.moozaq.app.test/androidx.test.runner.AndroidJUnitRunner
```

These tests read the catalog, resolve sources and fetch a few media bytes, and discover devices. They never select a route or send LOAD, Play, Pause, Stop, seek, membership or volume commands. Real playback, phone-disconnect continuity and long-session/group reliability require separate authorized hardware testing.

Sources: [Google Cast Android integration](https://developers.google.com/cast/docs/android_sender/integrate), [AndroidX native group routes](https://developer.android.com/reference/androidx/mediarouter/media/MediaRouter.GroupRouteInfo), [youtubedl-android](https://github.com/yausername/youtubedl-android). Extraction remains the experimental playback source strategy; a technical success does not establish production permission. Third-party license information is bundled in `assets/licenses/`.

## Private Cast diagnostics

The development app records a bounded, rotating `files/cast-debug.log` in its
private storage and emits the same events under the `MoozaqCast` logcat tag.
It records connection changes, resolver outcomes, LOAD acknowledgement, receiver
player/idle states and command failures. Stream URLs are redacted; do not add
raw SDK payloads or signed URLs to logs. Read the file with
`adb -s <serial> shell run-as com.moozaq.app cat files/cast-debug.log`.
Uninstalling the app removes this log.

Existing-session recovery has a separate opt-in instrumentation check:

```sh
adb -s <serial> shell am instrument -w -r \
  -e class com.moozaq.app.ExistingSessionTest -e observe_existing_session true \
  com.moozaq.app.test/androidx.test.runner.AndroidJUnitRunner
```

Run it only when Bedroom already has the owner's mix playing. It checks receiver status, joins that remote, and verifies fresh media identity without LOAD or transport/volume commands. It is skipped in ordinary test runs. Actual cold-process recovery is checked separately by closing/reopening the installed app and comparing receiver session fingerprints and advancing position.
