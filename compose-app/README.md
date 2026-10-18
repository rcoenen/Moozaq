# Moozaq Compose

The next Mac interface, built with Kotlin and Compose Multiplatform. The original Tk app remains the working reference in `mac-spike`.

## Run on this Mac

From the repository root:

```sh
./compose-app/Launch.command
```

Requires the existing `mac-spike/.venv` environment and Homebrew JDK 17. The launcher starts Gradle; its first run downloads dependencies. The app automatically joins the remembered speaker, reads its current session, and never starts audio on launch. Browse cards without interrupting playback; only the artwork’s **Play** button explicitly replaces the current session. Bottom controls target the receiver session.

## Architecture

- `composeApp/src/commonMain`: shared catalog, artwork resources, player state and Compose UI. Below 600 dp the UI is the phone design from `docs/design-handoff/android/` (Browse, Now playing, Cast to, Nothing playing); above 600 dp it is the desktop screen.
- `composeApp/src/desktopMain`: desktop window and private JSON-lines subprocess adapter.
- `bridge/server.py`: command bridge to the existing Python Cast engine. No network control endpoint; signed media URLs stay inside Python.
- `androidApp/`: installable standalone Compose Android app. It runs the same Python bridge as the Mac in-process (Chaquopy), with on-device yt-dlp. No Mac dependency and no Google Cast SDK. See [`androidApp/README.md`](androidApp/README.md); `android-spike` remains a separate reference.

The Mac currently uses direct playback (route A). Relay/cache experiments remain in the Python reference. Closing the Compose window detaches the remote without stopping the receiver. Artwork and titles for the six starter mixes are bundled from the existing metadata cache; no refetch on selection. Unknown external sessions display their receiver title with a generic logo.

The generated `.app` embeds a JVM but still depends on this checkout and its Python environment. It is a development package, not a portable distribution. The build records the checkout path; rebuild after moving it, or set `MOOZAQ_ROOT`.

## Silent checks

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./compose-app/gradlew -p compose-app :composeApp:desktopTest :composeApp:assembleDebug :composeApp:createDistributable
mac-spike/.venv/bin/python -m unittest discover -s compose-app/bridge -v
mac-spike/.venv/bin/python -m unittest discover -s mac-spike -v
mac-spike/.venv/bin/python -m unittest discover -s compose-app/androidApp/src/test/python -v
./compose-app/gradlew -p compose-app :androidApp:assembleDebug :androidApp:lintDebug
```

No automated check starts audio. Live playback, Android Cast integration, and long-session reliability require separate validation.

## Speakers and live membership

Open the speaker control in the bottom player. A group checkbox selects that group as the destination for the next explicit Play; it connects passively and shows pending/confirmed connection state. Selecting a group never starts or transfers music. While idle, individual speaker checkboxes choose the next playback destination too, replacing any selected group. During playback, physical speaker checkboxes represent participants in the current music session. Clicking the device name opens its remote without moving music. Supported audio sessions can add or remove speakers through native temporary Cast grouping. Unchecking the final participant invokes the verified Stop flow; it does not submit an empty group. The player also exposes Stop casting. Saved Google Home groups are not edited.

Membership changes retain the requested checkmarks with pending feedback until fresh receiver membership confirms the exact set. The bridge follows the returned session endpoint and observes delayed joins for up to 12 seconds; it never resends a failed mutation automatically. Errors remain explicit. Stop waits for the moved session to be located before claiming success.

This Mac path is experimental and depends on receiver-advertised grouping and transfer support. A session must already be playing or paused. TV/video sessions are not offered as individual audio participants. Silent startup/reconnection never adds speakers, starts media, or restores volume. Android now uses its own native Google Cast/MediaRouter adapter; its group behavior requires separate hardware verification.

The remote remembers a physical group member for recovery. After a disconnected group has been absent from discovery for 15 seconds, it can reconnect to that member without recreating the group or changing music. Pending operations and healthy connections prevent fallback; legacy preferences without a known member require choosing another remote.

While the app is running, session recovery first searches passive output observations for the same stream, overlapping members and continuous position. A unique fresh match can move the remote to a replacement group or remaining speaker. The player retains its last track as unconfirmed during recovery; transport controls wait for fresh receiver evidence. Ambiguity does not select an arbitrary session, and missing network observations do not claim playback ended. Full application restarts retain the remote preference but not the in-memory session identity.

The last verified physical address also seeds passive host discovery when multicast announcements are missing. Device selection still uses the discovered UUID, so a cached IP cannot silently substitute another device. Startup does not restore cached volume.

A passive whole-home monitor shows current activity and volume on every discovered output, including separate music sessions. Group members resolve to their active group remote through fresh membership evidence. Checkboxes apply to the current mix; other sessions are labeled separately. Stale observations become unavailable/unconfirmed. Inline volume sliders use fresh receiver confirmation and preserve pending intent. Passive diagnostics write topology/member events to the ignored event log. Membership and PLAYING reports do not establish audible output or long-session reliability.

## Channel library

Open **Channels** to import a YouTube channel URL or refresh existing sources. **Refresh all** reads full public catalogs, not a fixed sample. The ten owner-selected sources are seeded in `bridge/channels.json`. Imports use metadata-only yt-dlp, run independently of playback, and persist atomically to ignored `mac-spike/data/channel-catalog.json`. Canonical channel IDs collapse aliases; video IDs deduplicate uploads across tabs. Refresh retains previously imported entries even if no longer returned; availability is not independently verified.

The browser presents horizontal mood shelves and only shows mixes with a known duration of at least one hour. There are no search or filter controls; hover over artwork for the play icon. Short and unknown-duration records remain in the metadata cache but are excluded from browsing. Artwork loads on demand into ignored `mac-spike/data/catalog-artwork/`. Categories are provisional title-keyword matches with channel defaults. Import/refresh never plays audio. New sources still need explicit Play and successful source resolution before they can be heard.

Device glyphs use Google Material Design Icons (speaker, speaker_group, tv, smart_display, cast), Apache 2.0; license in `licenses/material-design-icons-LICENSE`. Icons follow discovered Cast type and known display models, not user-defined device names. Unknown types use the Cast fallback.

The in-app header uses the owner's neon wordmark from `assets/branding/Moozaq-Wordmark/`; originals are preserved alongside the icon handoff. The bundled transparent PNG is displayed at its original aspect ratio.

## Design handoff and silent visual preview

The October 17 handoff is implemented with shared theme tokens, compact cards, a 136 dp bottom player, an anchored Cast picker and a Channels dialog. Reference files and implementation notes live in [`docs/design-handoff`](../docs/design-handoff/implementation-notes.md). The normal app always uses real receiver confirmations.

For interactive visual QA without a Cast engine or network discovery:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./compose-app/gradlew -p compose-app :composeApp:designPreview
```

This separate test-source window is labeled **Mock data · No speakers**. Play, Pause, Stop, seek and volumes only update in-memory fixtures; its simulated acknowledgements are never packaged into the production app. Use Channels → Debug info → Reduce motion to suppress decorative card/equalizer animation. Roboto Flex is bundled under its OFL license in `licenses/`.
