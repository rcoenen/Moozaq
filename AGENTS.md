# Moozaq agent guide

## Product and decisions

Moozaq explores long-form YouTube music mixes on Google Nest speakers, with Android as the intended remote control and a Mac app for feasibility experiments. Read `docs/current-direction.md` first. The original `docs/product-brief.md` is a starting point, not a fixed specification; later explicit owner decisions take precedence.

- Inputs are ordinary YouTube video links. Do not require YouTube Premium or substitute a paid YouTube Music workflow.
- Target independent receiver playback: the phone can leave and later synchronize without restarting the mix.
- Dependable controls are a defining feature. Follow `docs/playback-control-requirements.md`: confirmed actions, reliable Stop, clean reconnection, responsive sliders, honest status, and external-change synchronization.
- Distinguish technical feasibility, audible confirmation, long-session reliability and production permission. A short extraction/playback success does not prove all four.

## Playback authorization

**Do not start or resume audio unless the owner explicitly asks.** This includes speaker groups, test tones, automated tests, retries and demonstrations. A request to build, fix, connect, inspect or test the app does not authorize audible playback. Use mocks and read-only state checks instead. App startup and metadata fetching must never play audio.

Use Bedroom speaker for playback only when explicitly requested. Do not use Office speaker or the All speakers group without specific instruction. A Stop request takes priority over other work: cancel pending loads, stop the intended session and verify receiver state. Never report stopping as successful merely because a command was sent.

## Repository map

- `compose-app/`: next Kotlin / Compose Multiplatform UI, shared Android library target, desktop app and private Python engine bridge. See its README for build commands; preserve `mac-spike` as the working reference.

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

## Development and validation

Run from repository root unless stated otherwise.

Mac prerequisites: Python with Tk, Node.js for yt-dlp JavaScript support, ffmpeg for the generated-tone feature. Use `mac-spike/.venv`, not global package installs. Dependencies are pinned in `mac-spike/requirements.txt`.

```sh
mac-spike/.venv/bin/python -m unittest discover -s mac-spike -v
mac-spike/.venv/bin/python -m py_compile mac-spike/app.py mac-spike/engine.py
./mac-spike/Launch.command
```

Android uses JDK 17, SDK 35 and the checked-in Gradle wrapper:

```sh
./android-spike/gradlew -p android-spike assembleDebug lintDebug
```

The APK is `android-spike/app/build/outputs/apk/debug/app-debug.apk`. Keep SDK paths in ignored `local.properties`. Discover the current ADB endpoint with `adb devices -l`; Wi-Fi ports can change. Use an explicit device selector if both IP and mDNS transports appear.

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

A short direct-URL test of the first mix played audibly on Bedroom speaker without Premium, cookies or a Mac media relay. Mac relay playback and seeking were observed in receiver state. Complete cache playback, all-six-source coverage, three-hour reliability and Android rejoining are not yet established. A stop/readiness failure prompted regression coverage and a receiver-shutdown fallback. Consult `docs/spike-results.md` for details rather than treating this summary as a permanent guarantee.


### Group playback follow-up (October 16, 2026)

All four All speakers members were audibly confirmed after fixing implicit receiver launches from diagnostic status requests. Always install `install_passive_status` before starting main/member Cast connections: the library's normal `update_status()` can launch an app when the media channel is missing. Keep reconnect/monitoring read-only. Last individually confirmed volumes were 50% for all four, but never apply remembered volumes on reconnect. This is one successful direct-route session, not repeatability or three-hour proof; see `docs/spike-results.md`.
