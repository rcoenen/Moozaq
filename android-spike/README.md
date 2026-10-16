# Moozaq Android playback spike

An installable experiment with two independent paths: the six starter videos opened in YouTube Music, and native Google Cast controls for an authorized direct audio stream. This is not yet a YouTube-to-Nest player.

## Build and install

Requires JDK 17 and Android SDK 35. Set `sdk.dir` in your ignored `local.properties`, or set `ANDROID_HOME` to your SDK directory.

```sh
./gradlew assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.moozaq.spike/.MainActivity
```

Android 11+ wireless installation: enable Developer options and Wireless debugging on the phone, use “Pair device with pairing code”, and run `adb pair IP:PAIRING_PORT`. Enter the displayed pairing code. If the phone does not appear in `adb devices`, run `adb connect IP:CONNECTION_PORT` using the main Wireless debugging screen. Both devices must share a suitable Wi-Fi network. See [Android hardware debugging](https://developer.android.com/studio/run/device).

## Run the experiment

1. Record phone model, Android version, Nest models, group name if applicable, and YouTube subscription tier in Notes.
2. Open a starter mix in YouTube Music. A link opening successfully does not prove that the mix is playable. Check the exact video and cast using YouTube Music's own controls.
3. Check volume, pause/resume, seek and stop. Restart the mix for the persistence test.
4. Turn the phone off for ten minutes. Confirm by listening that Nest continues playing. Reboot and reopen YouTube Music; check current position and controls without restarting the mix.
5. Record the video ID and results. Repeat for the other mixes; note missing or substituted content.
6. Finish that session before using the native Cast experiment: selecting the Default Media Receiver may replace YouTube Music's receiver session.
7. In the native experiment, select Nest using the Cast icon, enter a directly accessible HTTPS URL to audio you are authorized to stream, choose its MIME type (MP3: `audio/mpeg`), then Load. The receiver fetches the URL itself. The source must not depend on the phone or its authentication cookies.
8. Repeat the controls, phone-off and cold-start reconnection tests. If automatic reconnection fails, use the Cast button to select the same device. Do not press Load again. Record automatic and manual reconnection separately.
9. Change volume using Nest while the phone is away; verify the phone reads the new state on return. Confirm that position advances and the mix does not restart.
10. Test a speaker group and a three-hour audio source. Test phone-network interruption separately from Nest-network interruption. Record recovery behavior rather than assuming uninterrupted playback.
11. Mark each checklist item and export the report. The share sheet opens for you to choose the destination; no reports are uploaded automatically.

The app stores the checklist, notes, URL and a bounded timestamped event log locally. Test results are manual observations, not automatically asserted successes. Use notes to distinguish each mix/device/run; checklist values summarize the current run. Export before overwriting a run. URLs are not included in Cast event logs; YouTube reference IDs are logged. Notes and exports may contain device names you enter.

## Questions this answers

- Which exact videos are available and castable in YouTube Music?
- Does that official route survive a phone shutdown and reconnect correctly?
- Can our Android sender discover Nest and groups, stream direct audio and regain control without reloading?
- Which controls and state fields work on the actual devices?
- What breaks during long playback and network interruptions?

A successful YouTube Music test does not prove a third-party app can start or take over its session. A successful direct-audio test does not prove arbitrary YouTube videos can be streamed by Moozaq. Combining those capabilities remains a separate architecture question.

## Implementation

Java/Android Views keep this spike small. Google Cast uses the Default Media Receiver, with saved-session resumption enabled. No audio is loaded from connection callbacks. Receiver status drives the UI, and Cast command result codes are logged. No backend, extraction service or account system is included.

References: [Android Cast integration](https://developers.google.com/cast/docs/android_sender/integrate), [Cast setup](https://developers.google.com/cast/docs/android_sender), [supported media](https://developers.google.com/cast/docs/media).

## Self-contained audio source

To avoid relying on third-party test media, generate an original quiet 220 Hz test tone and serve it from this Mac:

```sh
python3 tools/serve_test_audio.py
```

Requires Python 3 and ffmpeg. The default generates three hours of MP3 audio in the ignored build directory. For a quick test use `--seconds 60`. Enter the printed URL in the app, replacing `<Mac-LAN-IP>` with the Mac’s Wi-Fi IP address (not localhost). Set MIME type to `audio/mpeg`. This local lab route uses HTTP; production media should use HTTPS. The server supports byte ranges and serves only the generated file. Stop it with Ctrl-C after testing.

Keep the Mac awake and on the network; Nest fetches from the Mac, not the phone. Start at a low speaker volume. A synthetic tone tests transport persistence, not music quality, YouTube eligibility, or production CDN behavior.
