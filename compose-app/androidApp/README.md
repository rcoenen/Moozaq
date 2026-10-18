# Moozaq Android app

The Android app runs the same Python Cast bridge as the Mac app, inside the app process. The Compose UI and `PlayerStore` are shared with desktop. Both platforms talk to the bridge through `BridgeBackend` (`composeApp/src/commonMain`). The Mac sends bridge requests over a subprocess's stdio; Android calls the embedded interpreter directly (`ChaquopyBackend`). No Mac server, account, Premium subscription or browser cookies are used at runtime.

The previous Google Cast SDK / MediaRouter app (0.5.0 and earlier, with its raw Cast v2 membership layer) is parked on branch `android-google-cast-sdk`.

```sh
./compose-app/gradlew -p compose-app :androidApp:assembleDebug :androidApp:lintDebug
mac-spike/.venv/bin/python -m unittest discover -s compose-app/androidApp/src/test/python -v
python3 compose-app/install-phone.py --serial <adb-serial>
```

Package: `com.moozaq.app`. APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (about 49 MB, arm64 only).

## How it fits together

- **Python runtime:** Chaquopy embeds Python 3.13. Packages are pinned in `build.gradle.kts` to the Mac's `mac-spike/requirements.txt`, and installed with `--no-deps` so nothing is fetched implicitly.
  - zeroconf ships as `wheels/zeroconf-0.151.5-py3-none-any.whl`. That is the same release with its optional Cython modules removed, made by `wheels/purewheel.py`, because those modules cannot be built for Android.
  - Pillow is Chaquopy's 11.0.0 Android build, plus its native image libraries.
- **Bridge sources:** a Gradle `Sync` task copies `compose-app/bridge/*.py` (no tests), `channels.json`, `mac-spike/engine.py` and `mac-spike/grouping.py` into assets. On first start after each install they are copied to `files/bridge`, so they run from real files as on the Mac. Bridge state lives in `files/bridge/data`.
- **Entry point:** `src/main/python/android_bridge.py` starts the bridge with `server.start_bridge()` and answers requests with `server.handle_line()`, the same functions the Mac's stdio loop uses. Its only substitution is process execution: `python -m yt_dlp` calls go to the bundled youtubedl-android runtime (`YtDlpRunner`, yt-dlp 2026.08.19, checksum-pinned by `BundledExtractor`). Desktop tools such as ffmpeg are refused.
- **Request transport:** requests run one at a time on a dedicated thread. A caller is released after 10 s even if Python is still busy, matching the Mac's stdio loop.
- **Environment:** Chaquopy's `PYTHONPATH` is cleared after the interpreter starts, so the yt-dlp child process uses its own Python.
- **Discovery:** a Wi-Fi multicast lock is held while the app is visible. Discovery, passive connections and recovery follow the Mac bridge rules unchanged. On a fresh install, with no remembered speaker, the bridge defaults to joining Bedroom speaker passively.

## Diagnostics

- `files/cast-debug.log` and logcat tag `MoozaqCast`: bridge start, failure and request timeouts. Stream URLs are masked.
- Python warnings and errors appear under logcat `python.stderr`.
- `files/bridge/data/events-*.jsonl`: the bridge's own event log, the same format as the Mac's.
- `adb -s <serial> shell run-as com.moozaq.app touch files/bridge/dump-stacks` writes every Python thread's stack, plus the URL-free UI snapshot, to `files/bridge/stacks.log` within a second.

## What has been tested

On the Pixel 6a, 2026-10-18, as test build `com.moozaq.app.py`, all on Bedroom speaker:
- Discovery of all five speakers.
- Play, pause, resume, seek and Stop, each confirmed from fresh receiver state.
- One audible play and Stop at 40%.

Not yet tested on this build: speaker membership, group and member volume, reopening mid-mix, background behaviour, link renewal and long sessions. See `docs/spike-results.md`.
