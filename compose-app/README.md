# Moozaq Compose

The next Mac interface, built with Kotlin and Compose Multiplatform. The original Tk app remains the working reference in `mac-spike`.

## Run on this Mac

From the repository root:

```sh
./compose-app/Launch.command
```

Requires the existing `mac-spike/.venv` environment, Homebrew JDK 17, and Xcode with `swiftc` for the native local-audio helper. The launcher starts Gradle; its first run downloads dependencies. The app automatically joins the remembered speaker, reads its current session, and never starts audio on launch. Browse cards without interrupting playback; only the artwork’s **Play** button explicitly replaces the current session. Bottom controls target the selected output, either a Cast receiver or This Mac.

## Architecture

- `composeApp/src/commonMain`: shared catalog, artwork resources, player state and Compose UI. Below 600 dp the UI is the phone design from `docs/design-handoff/android/` (Browse, Now playing, Cast to, Nothing playing); above 600 dp it is the desktop screen.
- `composeApp/src/desktopMain`: desktop window and private JSON-lines subprocess adapter.
- `composeApp/src/desktopMain/swift/MoozaqAudioPlayer.swift`: native AVPlayer helper for local Mac playback, controlled through JSON-lines with fresh state for transport, position, volume and completion. It is compiled by the desktop build and bundled in `Contents/Resources`.
- `bridge/server.py`: command bridge to the existing Python Cast engine. No network control endpoint; signed media URLs stay inside Python. The desktop launcher injects the SQLite catalog and the optional local player; `start_bridge()` retains the legacy JSON default and no local output for Android.
- `bridge/local_player.py`: local helper process lifecycle, source expiry, source-position offsets and cancellation. Stop invalidates pending loads and confirms local IDLE; closing it never leaves an audio process behind.
- `bridge/local_relay.py`: a temporary loopback HLS remux with byte-range responses. It creates small segments rather than a complete download, and removes its process and files on stop, error or close.
- `androidApp/`: installable standalone Compose Android app. It runs the same Python bridge as the Mac in-process (Chaquopy), with on-device yt-dlp. No Mac dependency and no Google Cast SDK. See [`androidApp/README.md`](androidApp/README.md); `android-spike` remains a separate reference. The SQLite catalog, personal profile, discovery sections and GitHub updater in this version are Mac-only.

Cast uses direct playback (route A). Relay/cache experiments remain in the Python reference. This Mac resolves the audio-only direct URL, remuxes it through temporary local HLS for AVPlayer, and keeps the existing expiry guards. This avoids a progressive-MP4 buffering failure without introducing an external relay or requiring a complete download. Local seeks explicitly reload at the requested position with a source-position offset, preserving pause rather than guessing seekable ranges. Closing the Compose window detaches a Cast receiver without stopping it, but stops local Mac playback and its relay. Artwork and titles for the six starter mixes are bundled from the existing metadata cache; no refetch on selection. Unknown external sessions display their receiver title with a generic logo.

The generated `.app` embeds a JVM and the native audio helper but still depends on this checkout and its Python environment. It is a development package, not a portable distribution. The build records the checkout path; rebuild after moving it, or set `MOOZAQ_ROOT`.

The ad-hoc development signature retains hardened runtime and uses `macos/Moozaq.entitlements`: `disable-library-validation` for the bundled native libraries and `allow-jit` for the JVM. This is not Developer ID signing or notarization. A successful `codesign --verify` alone is not a launch test; open the packaged app as a smoke test.

## This Mac local playback

With no Cast destination selected, an explicit Play selects **This Mac** and starts only after fresh local readiness. A chosen but disconnected Cast speaker never silently falls back to the Mac. This Mac is also a destination in the output picker; selecting it alone never starts, stops or transfers audio. Switching routes during active or uncertain playback requires an explicit confirmed Stop first, preventing two uncontrolled sessions.

Local Play/pause, Stop, seek and volume use fresh AVPlayer state and the existing pending-confirmation rules. The first helper starts at 15% volume; subsequent loads use the current helper volume, never a persisted previous-app level. Local sessions stop on app close and are not reattached or resumed on restart. Android has no local output capability and retains its existing speaker-selection and Cast behavior.

## Silent checks

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./compose-app/gradlew -p compose-app :composeApp:desktopTest :composeApp:assembleDebug :composeApp:createDistributable
mac-spike/.venv/bin/python -m unittest discover -s compose-app/bridge -v
mac-spike/.venv/bin/python -m unittest discover -s mac-spike -v
mac-spike/.venv/bin/python -m unittest discover -s compose-app/androidApp/src/test/python -v
./compose-app/gradlew -p compose-app :androidApp:assembleDebug :androidApp:lintDebug
openspec validate --all --strict
```

Unit and build checks do not start audio. Live playback, Android Cast integration, and long-session reliability require separate owner-authorized validation; one explicitly authorized local HLS playback/control sequence was also completed.

## Speakers and live membership

Open the speaker control in the bottom player. A group checkbox selects that group as the destination for the next explicit Play; it connects passively and shows pending/confirmed connection state. Selecting a group never starts or transfers music. On desktop while idle, individual speaker checkboxes build an in-memory draft set for the next explicit Play, without connecting or forming a group. An empty draft disables Play rather than restoring an old target. Play validates the whole set, loads once on one coordinator, waits for fresh grouping eligibility, then sends one full-set membership command so the others join the same native Cast session. It never loads the speakers independently or retries LOAD/SET automatically. Stop cancels pending work; a failure after the coordinator starts is shown separately from playback confirmation. The draft is not persisted and ordinary phone/group Play does not consume it.

During playback, physical speaker checkboxes represent participants in the current music session. Clicking the full-height device icon/name area opens its remote without moving music; when live membership is unavailable, the desktop row says so beside the disabled checkbox. Supported audio sessions can add or remove speakers through native temporary Cast grouping. Unchecking the final participant invokes the verified Stop flow; it does not submit an empty group. The player also exposes Stop casting. Saved Google Home groups are not edited.

Membership changes retain the requested checkmarks with pending feedback until fresh receiver membership confirms the exact set. The bridge follows the returned session endpoint and observes delayed joins for up to 12 seconds; it never resends a failed mutation automatically. Errors remain explicit. Stop waits for the moved session to be located before claiming success.

This Mac path is experimental and depends on receiver-advertised grouping and transfer support. A session must already be playing or paused. TV/video sessions are not offered as individual audio participants. Silent startup/reconnection never adds speakers, starts media, or restores volume. Android playback remains a separate implementation and validation track; the catalog version below is Mac-only.

The remote remembers a physical group member for recovery. After a disconnected group has been absent from discovery for 15 seconds, it can reconnect to that member without recreating the group or changing music. Pending operations and healthy connections prevent fallback; legacy preferences without a known member require choosing another remote.

While the app is running, session recovery first searches passive output observations for the same stream, overlapping members and continuous position. A unique fresh match can move the remote to a replacement group or remaining speaker. The player retains its last track as unconfirmed during recovery; transport controls wait for fresh receiver evidence. Ambiguity does not select an arbitrary session, and missing network observations do not claim playback ended. Full application restarts retain the remote preference but not the in-memory session identity.

The last verified physical address also seeds passive host discovery when multicast announcements are missing. Device selection still uses the discovered UUID, so a cached IP cannot silently substitute another device. Startup does not restore cached volume.

A passive whole-home monitor shows current activity and volume on every discovered output, including separate music sessions. Group members resolve to their active group remote through fresh membership evidence. Checkboxes apply to the current mix; other sessions are labeled separately. Stale observations become unavailable/unconfirmed. Inline volume sliders use fresh receiver confirmation and preserve pending intent. Passive diagnostics write topology/member events to the ignored event log. Membership and PLAYING reports do not establish audible output or long-session reliability.

## Mac catalog and distribution

The desktop uses three separate files under ignored `mac-spike/data/`:

- `master.db` is the owner's authoritative catalog, writable only through the desktop curator workspace. It retains every discovered song, including short, hidden, unavailable and review-pending records, together with source evidence and editorial choices. It is never bundled or uploaded.
- `app-catalog.db` is the generated player catalog. It contains only approved, not hidden, not explicitly unavailable songs with a confirmed duration of at least one hour and the public fields the player needs. The player opens it with SQLite `mode=ro` and `PRAGMA query_only=ON`, never with master write access.
- `personal.db` contains local likes, qualified listening evidence and the last safe session observation. Ratings and reset do not change either catalog or playback. Personal data never enters a catalog, manifest or release.

On first desktop use, the original `channel-catalog.json` becomes migration input rather than the runtime catalog. Migration leaves it intact and creates `channel-catalog.backup.json`; missing legacy first-seen dates remain unknown. The ten owner-selected sources and six starter mixes are preserved. Channel aliases and video IDs are deduplicated.

Open **Channels** for the Catalog workspace. **Sources** adds/refreshes channels and selects automatic or review-first publication. **Songs** recommends, hides/restores, approves and assigns categories. **Add video** previews one public YouTube video before adding or recommending it, independently of following its channel. Individual preview reads public metadata only; channel imports use metadata-only yt-dlp. Neither resolves or plays audio. Due sources are attempted at most once every 24 hours while the curator is running; **Refresh all** and **Rebuild** do not publish anything to GitHub.

The app builds a complete candidate database, validates its schema, counts, integrity and public queries, then atomically activates it. `app-catalog.previous.db` is retained for local recovery; a failed build or update leaves the last-good catalog usable. Categories remain provisional title-keyword matches with channel defaults unless explicitly overridden. Artwork loads on demand into ignored `mac-spike/data/catalog-artwork/`. New sources still need explicit Play and successful source resolution before they can be heard.

On the Mac, **Recommended**, **Recently added**, and **For you**/**Discover** lead the mood shelves. Editorial recommendations persist until the owner changes them. Recently added shows up to 20 eligible songs by real first-seen date, newest first, and omits unknown legacy dates. Personal suggestions use local ratings and confirmed advancing receiver listening, never card taps alone. Visible discovery order stays frozen during polling and catalog replacement, changing only on a cold start, Browse re-entry or explicit discovery refresh. Search hides unrelated discovery shelves until cleared; the phone has no search or these new catalog controls.

### Updates, publication, and rollback

The Mac checks `catalog-manifest.json` on startup, hourly while open, and through **Check now**. Public readers need no GitHub account. The `latest` manifest alias can briefly point to an older release; the last-good catalog remains active while that happens. A newer compatible manifest leads to a size-bounded download from its immutable tag URL; byte size, SHA-256, embedded revision, schema, integrity and public queries are verified before activation. The Catalog dialog shows the latest observed revision, last check and any update error. Downloads never change playback or reorder visible cards.

Catalog releases belong to the dedicated public `rcoenen/Moozaq-catalog` repository, separate from application releases and the website/Cloudflare. Each `catalog-v<revision>` release contains only `app-catalog.db` and `catalog-manifest.json`, with revision, schema/app compatibility, generation time, immutable URL, byte size and SHA-256. **Publish release** first shows the exact revision, tag and both assets. Creating the repository and every release require explicit owner confirmation; the app then uses the authenticated `gh` client. Staged copies are revalidated so a concurrent build cannot silently replace the confirmed upload. A failed attempt can retry the identical bundle; changed content requires a new revision and confirmation. Refresh, curation, ratings, update checks and local generation never invoke `gh` or upload personal/master data.

For a local rollback, retain the previous generated database or explicitly launch the preserved legacy JSON path:

```sh
MOOZAQ_LEGACY_CATALOG=1 ./compose-app/Launch.command
```

For a published rollback, validate previous content and publish it under a **new higher revision**; clients never downgrade to an older manifest. Do not delete the master, JSON backup, personal database or previous app catalog during rollback. Android remains on its existing legacy JSON route, without this SQLite migration or GitHub updater.

Device glyphs use Google Material Design Icons (speaker, speaker_group, tv, smart_display, cast), Apache 2.0; license in `licenses/material-design-icons-LICENSE`. Icons follow discovered Cast type and known display models, not user-defined device names. Unknown types use the Cast fallback.

The in-app header uses the owner's neon wordmark from `assets/branding/Moozaq-Wordmark/`; originals are preserved alongside the icon handoff. The bundled transparent PNG is displayed at its original aspect ratio.

## Design handoff and silent visual preview

The October 17 handoff is implemented with shared theme tokens, compact cards, a 136 dp bottom player, an anchored Cast picker and a Channels dialog. Reference files and implementation notes live in [`docs/design-handoff`](../docs/design-handoff/implementation-notes.md). The normal app always uses real receiver confirmations.

For interactive visual QA without a Cast engine or network discovery:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./compose-app/gradlew -p compose-app :composeApp:designPreview
```

This separate test-source window is labeled **Mock data · No speakers**. Play, Pause, Stop, seek and volumes only update in-memory fixtures; its simulated acknowledgements are never packaged into the production app. Use Channels → Debug info → Reduce motion to suppress decorative card/equalizer animation. Roboto Flex is bundled under its OFL license in `licenses/`.

## Real-catalog UI acceptance

```sh
./compose-app/gradlew -p compose-app :composeApp:catalogAcceptance
```

This explicit test-source runner uses the real Python backend and catalog with the production Compose UI, rendered offscreen into `composeApp/build/previews/catalog-acceptance-browse.png` and `catalog-acceptance-management.png`. It waits for the normal due-source refresh and checks Recommended, truthful Recently added order, For you/Discover, and stability across receiver polls. A read-only wrapper refuses Play, receiver mutations and public releases. It does perform public metadata/artwork reads and passive receiver discovery; it is not a speaker or audible-playback test. Ordinary `desktopTest` does not start this real backend.

Set `MOOZAQ_ACCEPTANCE_CHECK=1` to exercise **Check now** through that real backend. `MOOZAQ_ACCEPTANCE_EXPECT_REVISION` can require a downloaded revision and `MOOZAQ_ACCEPTANCE_EXPECT_CATEGORY` can check its newly loaded VHS Dreams category while the visible discovery snapshot stays frozen. These are explicit network/update checks, not part of the default unit-test run.

For deterministic update tests, `MOOZAQ_CATALOG_AUTOMATIC_UPDATES=0` disables only the startup/hourly update thread; explicit **Check now** remains available. `MOOZAQ_ACCEPTANCE_UPDATE_GATE` names a new empty directory where the runner records the old snapshot before checking, and `MOOZAQ_ACCEPTANCE_EXPECT_INITIAL_CATEGORY` verifies its pre-update VHS Dreams category. Normal app startup keeps automatic updates enabled.
