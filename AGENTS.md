# Moozaq agent guide

## Product and decisions

Moozaq explores long-form YouTube music mixes on Google Nest speakers, with Android as the intended remote control and a Mac app for feasibility experiments. Read `docs/current-direction.md` first. The original `docs/product-brief.md` is a starting point, not a fixed specification; later explicit owner decisions take precedence.

- Inputs are ordinary YouTube video links. Do not require YouTube Premium or substitute a paid YouTube Music workflow.
- Target independent receiver playback: the phone can leave and later synchronize without restarting the mix.
- Before changing Cast discovery, group membership or recovery, read `docs/google-cast-lessons.md` for known failure modes and implementation rules.
- Dependable controls are a defining feature. Follow `docs/playback-control-requirements.md`: confirmed actions, reliable Stop, clean reconnection, responsive sliders, honest status, and external-change synchronization.
- Distinguish technical feasibility, audible confirmation, long-session reliability and production permission. A short extraction/playback success does not prove all four.

## Playback authorization

**Do not start or resume audio unless the owner explicitly asks.** This includes speaker groups, test tones, automated tests, retries and demonstrations. A request to build, fix, connect, inspect or test the app does not authorize audible playback. Use mocks and read-only state checks instead. App startup and metadata fetching must never play audio.

Use Bedroom speaker for playback only when explicitly requested. Do not use Office speaker or the All speakers group without specific instruction. A Stop request takes priority over other work: cancel pending loads, stop the intended session and verify receiver state. Never report stopping as successful merely because a command was sent.

## Repository map

- `compose-app/`: Kotlin / Compose Multiplatform UI, desktop app, Android app and the Python Cast bridge (`bridge/`). Both apps run that same bridge: the Mac as a subprocess, Android in-process through Chaquopy (see `compose-app/androidApp/README.md`). The bridge still imports `mac-spike/engine.py`, so changes there reach both apps. The former Google Cast SDK Android app is parked on branch `android-google-cast-sdk`. See its README for build commands; preserve `mac-spike` as the working reference.
- `compose-app/bridge/local_player.py`, `compose-app/bridge/local_relay.py` and `compose-app/composeApp/src/desktopMain/swift/MoozaqAudioPlayer.swift`: Mac-only local playback and its native AVPlayer helper plus a temporary loopback HLS relay. With no Cast target, explicit Play selects This Mac; a selected Cast destination never silently falls back. Local selection is passive, active route changes require a confirmed Stop, and app close stops local playback without reattachment. The helper starts at 15% volume and is bundled in `Contents/Resources`; Android has no local output. HLS seeks explicitly reload at the requested source position with an offset and preserve pause; they never guess an unavailable range.

- `mac-spike/app.py`: native Tk user interface; UI updates belong on the main thread.
- `mac-spike/engine.py`: Cast discovery, media state, extraction jobs and local media delivery.
- `mac-spike/test_engine.py`: silent protocol, input and control-readiness regression tests.
- `mac-spike/Launch.command`: launches the Mac app with its project-local Python environment.
- `mac-spike/Moozaq Cast Lab.app`: development app bundle; keep beside source files, not a standalone distribution.
- `android-spike/`: Java/Android Views prototype, Google Cast framework, Gradle wrapper.
- `assets/branding/Moozaq-AppIcon/`: original owner-supplied icon handoff, including SVG masters and mobile assets. Preserve originals; add platform-specific derivatives separately.
- `docs/starter-videos.md`: six owner-selected sources.
- `docs/spike-results.md`: observed outcomes and unresolved tests.
- `docs/playback-options.md`: source/architecture research.
- `docs/source-url-expiry.md`: signed YouTube audio URL lifetime, IP binding and expiry guards (Mac implemented, Android pending). Read before changing source resolution, Resume or seek.
- `website/`: public marketing site for https://moozaq.com. Static Astro, deployed as the Cloudflare Worker `moozaq`. Read `website/README.md` before changing or deploying it.
- `openspec/specs/`: durable behavior contracts for the Android Cast client; `openspec/changes/archive/` holds completed change records. Validate with `openspec validate --all --strict`.

## Mac catalog and distribution

The SQLite catalog version is desktop-only. Android retains its legacy JSON catalog path; do not imply that the Mac curator, discovery, profile or updater has shipped on the phone.

- `mac-spike/data/master.db` is the owner-managed authoritative catalog. It retains every discovered record, including short, hidden, unavailable and review items. Only the desktop curator writes it; never bundle or upload it.
- `mac-spike/data/app-catalog.db` is the validated public projection: approved, not hidden, not explicitly unavailable and at least 3,600 seconds. The player opens it read-only (`mode=ro`, `query_only=ON`). Build and validate a complete candidate before atomic activation; retain `app-catalog.previous.db`.
- `mac-spike/data/personal.db` holds local likes, qualified listening evidence and safe session observations. It never enters either catalog database, a manifest or a release. Ratings and reset do not edit the catalog or control playback.
- The original `channel-catalog.json` is migration/rollback input, not the Mac runtime catalog. Migration preserves the original and creates `channel-catalog.backup.json`. An explicit local fallback is `MOOZAQ_LEGACY_CATALOG=1 ./compose-app/Launch.command`; retain the JSON and private databases during rollback.
- The Mac Catalog workspace handles individual-video review separately from following a channel, source refresh, editorial choices and Rebuild. Due sources are attempted at most once per 24 hours while running. Recommendations persist without a daily deadline; Recently added uses real first-seen dates, not migration or source-publication dates.
- Recommended, Recently added and For you/Discover lead desktop browsing. Background polls, local generation and downloaded database activation do not reorder visible cards; only a cold start, Browse re-entry or explicit discovery refresh takes a new snapshot.
- Catalog distribution uses GitHub Releases in the separate public `rcoenen/Moozaq-catalog` repository, never the website, Cloudflare or the application-release channel. Repository creation and every public release require separate explicit owner approval. Refresh, curation, ratings and local Rebuild never invoke `gh` or upload data.
- A release contains only the validated `app-catalog.db` and `catalog-manifest.json`. The manifest supplies revision, schema/app compatibility, immutable tag URL, byte size and SHA-256. The Mac checks on startup, hourly and via Check now without requiring a GitHub account, and activates only a newer compatible, fully validated download while preserving last-good data. GitHub's latest-manifest alias can be delayed; never substitute it for the immutable artifact URL. Public rollback republishes previous content under a new higher revision.

See `compose-app/README.md` for the operating and rollback flow. Validate with the three Python suites, desktop tests/package checks and `openspec validate --all --strict`. Visual, metadata-network, GitHub-network and live-hardware checks are separate evidence; catalog work never authorizes playback.

## Development and validation

Run from repository root unless stated otherwise.

Mac prerequisites: Python with Tk, Node.js for yt-dlp JavaScript support, ffmpeg for the generated-tone feature, and Xcode with `swiftc` for the native local-audio helper (available on this Mac). Use `mac-spike/.venv`, not global package installs. Dependencies are pinned in `mac-spike/requirements.txt`.

```sh
mac-spike/.venv/bin/python -m unittest discover -s mac-spike -v
mac-spike/.venv/bin/python -m py_compile mac-spike/app.py mac-spike/engine.py
./mac-spike/Launch.command
```

The Android app (`compose-app/androidApp`) runs the Python bridge in-process:

```sh
./compose-app/gradlew -p compose-app :androidApp:assembleDebug :androidApp:lintDebug
mac-spike/.venv/bin/python -m unittest discover -s compose-app/androidApp/src/test/python -v
```

The older `android-spike` prototype uses JDK 17, SDK 35 and the checked-in Gradle wrapper:

```sh
./android-spike/gradlew -p android-spike assembleDebug lintDebug
```

The APK is `android-spike/app/build/outputs/apk/debug/app-debug.apk`. Keep SDK paths in ignored `local.properties`. Discover the current ADB endpoint with `adb devices -l`; Wi-Fi ports can change. Use an explicit device selector if both IP and mDNS transports appear.

The public site is updated from `website/`, not the repository root:

```sh
cd website && npm install
cd website && npm run deploy
```

`npm run deploy` builds and publishes a new version of the existing `moozaq` Worker. Confirm https://moozaq.com afterward. The domain, DNS record, and HTTPS redirect are already set. Do not reattach them or add `www`. Details are in `website/README.md`.

Use tests appropriate to the change. Mock race conditions, timeouts, stale state and cancellation; do not write tests that start real playback. Visual QA and live hardware checks must be reported separately from builds/unit tests.

## Implementation standards

- Apply the product-wide intent → pending → confirmed UI rule in `docs/playback-control-requirements.md` to all asynchronous controls: hold requested values, show local pending colour plus text, confirm from fresh authoritative state, and surface failure without silent rollback. Keep unrelated playback status unchanged.

- Keep blocking extraction, downloads, discovery and receiver operations off the UI thread.
- Reconnection reads receiver state; it must not load media, restore old volume or seek to cached phone position.
- A connected control socket is not a synchronized media session. Confirm actual state changes and surface uncertainty/timeouts.
- Stop stays available during pending work. Invalidate late completions so cancelled jobs cannot restart sound. Bind actions to the intended device/session.
- Do not fake synchronized multiroom audio by independently starting several speakers. Prefer native Cast group mechanisms and validate supported APIs.
- Keep credentials and signed media URLs out of logs and reports. Do not import browser cookies or credentials implicitly.
- Cache audio, transient reports and environments under ignored directories. Do not commit `.venv`, `data`, `build`, `.gradle`, `local.properties`, pairing codes or temporary signed URLs.
- Record new decisions in current direction and evidence in spike results. Say exactly what was and was not tested.

## Current evidence

A short direct-URL test of the first mix played audibly on Bedroom speaker without Premium, cookies or a Mac media relay. Mac relay playback and seeking were observed in receiver state. Android 0.3.3 also completed two cold-process reopens and one warm return on one explicitly authorized Office session with unchanged receiver source/session identity; this is limited rejoin evidence, not broad reliability. Complete cache playback, all-six-source coverage, three-hour reliability, phone reboot and actual Wi-Fi loss/rejoin are not established. A stop/readiness failure prompted regression coverage and a receiver-shutdown fallback. Consult `docs/spike-results.md` for details rather than treating this summary as a permanent guarantee.


### Group playback follow-up (October 16, 2026)

All four All speakers members were audibly confirmed after fixing implicit receiver launches from diagnostic status requests. Always install `install_passive_status` before starting main/member Cast connections: the library's normal `update_status()` can launch an app when the media channel is missing. Keep reconnect/monitoring read-only. Last individually confirmed volumes were 50% for all four, but never apply remembered volumes on reconnect. This is one successful direct-route session, not repeatability or three-hour proof; see `docs/spike-results.md`.

### Android group follow (October 17, 2026, Google Cast SDK app, now on branch `android-google-cast-sdk`)

One authorized Kitchen add while Bedroom was playing moved the mix onto the temporary group "Bedroom speaker + 1" and kept it playing. Android 0.4.2 then slept its confirmation timeout a second time, left the Kitchen check in flight, and kept polling Bedroom after that speaker's app was gone (status 2103). The 0.4.3 source fix keeps reading until the deadline and may select the one discovered group whose session, media, and active state match. It does not send the membership command again and does not load media. That path has not been re-run as another add or remove. Opening 0.4.3 did not look for the group. Android 0.4.4 (code 14) is installed on the Pixel. On open it joined the already-playing temporary group once, without LOAD or another membership command, and did not save that group as the remembered speaker. See `docs/spike-results.md`.

### Local Mac output (October 26, 2026)

This Mac is now a desktop-only output when no Cast destination is selected. The native AVPlayer helper starts at 15% volume; explicit local Play is required, active or uncertain Cast/local route changes require a confirmed Stop, and app close stops local playback without reattachment. A temporary loopback HLS relay supplies small segments and local seeks explicitly reload at the requested source position with an offset, preserving pause. Silent protocol, cancellation, expiry, routing, relay and UI tests passed, plus a real no-media helper start/stop and bridge selection check. The final installed helper also completed one real YouTube/HLS playback and control sequence: PLAYING with advancing position, PAUSED, paused seek to 5.0, PLAYING again, volume changes to 10% and 15%, and IDLE on Stop with helper exit 0. This confirms one source and one local control sequence, not long-session reliability or independent audible assessment. The development bundle's ad-hoc/hardened-runtime signing includes `disable-library-validation` for bundled native libraries and `allow-jit` for the JVM; it is not Developer ID signing or notarization. Both signature verification and a real installed-window launch passed; no native-window Play automation is claimed. The final build was installed after separate owner approval.
