# Moozaq Cast Lab for Mac

A native Python/Tk test app for the six starter YouTube mixes and Google Cast audio receivers. No Premium login, cookies, browser session or API key is used. Extraction uses yt-dlp and remains an unofficial, changeable source path.

## Open

Double-click **Moozaq Cast Lab.app** in this directory, or run `./Launch.command`. Keep the app bundle beside the source files; it is a local development launcher, not a standalone signed distribution. Python with Tk, Node.js and ffmpeg are required and available on this Mac. Dependencies are isolated in `.venv`; the launcher installs the pinned requirements if that environment is absent.

## Use

1. Let discovery find **Bedroom speaker**, then click **Connect / rejoin**. Connecting reads state; it does not load audio or replace the current receiver.
2. Pick one of the six starter URLs in the Music tab. **Resolve audio only** checks extraction without playing.
3. Choose a test:
   - **A · Direct to Nest:** send the resolved audio URL to the Default Media Receiver. Nest fetches upstream directly. No file is downloaded to the Mac. The Mac can disconnect once playback starts, subject to receiver access and stream expiry.
   - **B · Relay through Mac:** serve a tokenized local HTTP endpoint that forwards audio with the extractor's request headers. Keep the Mac awake and this app open. Upstream failures are logged; this version does not refresh expiring URLs midstream.
   - **C · Download, then cast:** wait for the complete audio-only file, then serve it with byte-range support. Keep the Mac awake and this app open. Completed files are reused; downloads have a 30-minute timeout and can be cancelled. No full video download or transcoding is performed.
   - **Original test tone:** generate and serve a quiet three-minute tone to check delivery independently of YouTube.
4. Observe state and advancing position. Confirm sound yourself. A `cast-load` event is a request, not proof of playback.
5. Test pause, resume, seeking and volume. Each new playback test uses the chosen volume (default 15%) and replaces the current receiver playback.
6. **Disconnect remote** closes the control connection without sending Stop. For B/C/tone, the Mac HTTP server continues. **Connect / rejoin** should read existing receiver state without restarting audio.
7. Record what you heard and export a JSON report. Export before closing; timestamped events also persist in `data/`.

The source selector is selection-only; the dropdown contains all six original links. No default action targets Office speaker. Choosing a different device is explicit.

## Important test distinctions

- Direct mode continuing after the Mac app exits is stronger evidence than a still-running relay test.
- Mac control reconnection is not proof that the Android app rejoins correctly. Test that separately.
- The phone is not involved in these playback paths. Powering it off should not interrupt them, but physical phone-off behavior still needs observation.
- Short playback success does not establish three-hour reliability, seeking near the end, URL lifetime, all-six-video coverage, or group support.
- The original source is a YouTube watch URL. The actual media URL is kept in memory and omitted from reports/logs. No cookies are imported. Audio files and logs remain in ignored `data/` until manually removed.
- The HTTP server exposes only random-token media URLs on the LAN; no control API or directory listing is exposed. Local HTTP is for this spike, not a production hosting design.
- A completed file cache is more predictable than upstream relay, but does not establish downloading/redistribution rights. See [research notes](../docs/playback-options.md).

## Verify

```sh
.venv/bin/python -m unittest discover -v
.venv/bin/python -m py_compile app.py engine.py
```

Tests cover source validation, byte-range seeking, HEAD/full responses, restricted routes, and relay range propagation. Hardware observations are in [spike results](../docs/spike-results.md).

## Implementation

`app.py` is the Tk interface. `engine.py` handles Cast discovery/control, cancellable extractor processes and media delivery. Network work runs off the UI thread. Only explicit playback tests launch the Default Media Receiver; reconnect never reloads media. This matters because generic Cast media commands can otherwise target a lingering YouTube Music receiver.

References: [PyChromecast](https://github.com/home-assistant-libs/pychromecast), [yt-dlp](https://github.com/yt-dlp/yt-dlp), [Cast media support](https://developers.google.com/cast/docs/media).

## Control reliability

Connect/rejoin requests fresh receiver media state before media commands are allowed. STOP remains available during acquisition; it cancels the process and invalidates pending loads. If normal media Stop cannot be confirmed with a fresh IDLE state, the app ends the receiver app and waits for confirmation that it is gone. An unconfirmed stop is shown as a failure, never success. Regression tests exercise stale media state, fallback, failed confirmation, and already-idle behavior without playing sound.

Native UI automation was unavailable in this session, so the window has been launched but its visual layout has not been independently inspected. Compilation, protocol tests and the live Cast backend were checked.

## Source previews and groups

Selecting a starter link loads its public YouTube title, channel and thumbnail. Previews persist in `data/metadata/` and are reused across launches without a network request. Refresh artwork / title explicitly updates the cache; a failed refresh keeps the saved preview and shows a notice. Invalid cache entries are fetched again. Signed audio URLs are never stored in this metadata cache. The preview also loads on startup. This does not resolve audio, connect to a receiver, or play anything. A generation counter discards late results from previously selected sources.

The speaker picker includes existing saved groups such as All speakers. Arbitrary subgroup selection is not implemented in the Mac library. See [speaker selection](../docs/speaker-selection.md) for the Google Home/Android path; no independent multi-speaker streams are presented as synchronized groups.

## Window layout

The header shows the supplied Moozaq logo. Music, Playback tests, and Diagnostics have separate tabs. Speaker selection, receiver controls, Stop, and job status remain visible across tabs. Music and test content scroll in smaller windows; diagnostics has a scrollable log. The default window is 1000 × 760 with an 800 × 680 minimum.

## Receiver-driven music selection

On rejoin and receiver track changes, the Music card follows the receiver. New Moozaq loads carry a source URL and route in Cast custom data bound to the exact media content hash. Matching starter mixes select their URL and use the cached preview. External or unrecognized sessions clear the URL selector and display the receiver title/artist (or app name). Older untagged sessions are matched by an exact, unique title in the saved starter catalog; ambiguous titles remain unselected. This compatibility fallback recognizes content, not which app originally started it. Receiver-provided artwork is displayed for external tracks, with a unique cached-title artwork fallback. Browsing a different saved mix remains possible without playback; unchanged status updates do not overwrite that choice. A subsequent receiver track change synchronizes the card again. Rejoining never loads or resumes media.

`./Launch.command --rejoin-speaker 'Office speaker'` opens the app and joins the specified receiver when discovered, without loading media or setting volume.

## Unified player

Artwork, title, connection indicator, elapsed/total time, progress, transport and volume controls share one always-visible player. Green Connected, orange Connecting and red Disconnected include text labels. Playback tests contain the source chooser and manual seek; Diagnostics contains raw receiver details and preview refresh. The progress bar is read-only and follows receiver time; unknown durations show a dash instead of invented progress. Receiver volume is shown separately from the editable volume command. No UI refresh sends playback commands.

The six-mix selector now sits at the top of the player and displays cached titles. Choosing another mix explicitly switches the connected speaker via the direct route while preserving its current volume. The selector is disabled during pending jobs; Stop remains available. While disconnected, choosing a mix only previews it. Programmatic selection updates and rejoining never initiate playback.

Play starts the selected mix when no usable receiver session exists, resumes the same paused mix, and leaves an already-playing mix alone. It checks receiver state first; an unsynchronized existing session is not silently replaced. Starting a selection preserves receiver volume. These paths are covered with silent mocks, including Stop during synchronization.

Transport buttons follow receiver state after rejoining and external changes: while PLAYING the Play button reads Playing and is disabled, with Pause enabled; while PAUSED only Play is enabled. Idle playback requires a selected mix. Connecting, buffering, disconnection and pending operations disable Play/Pause. Stop remains available during pending operations.

### Preview before switching (current behavior)

Dropdown selection now only previews artwork/title and never starts or interrupts audio. Play selected mix explicitly switches to the previewed selection. The current receiver title stays above the progress bar. The regular Play control resumes the current paused session rather than starting a different preview. This supersedes the earlier immediate-switch behavior described above.

### Browser above, player below (current layout)

Browse lists all six mixes with cached artwork and titles; clicking a mix previews it. Play selected mix is the explicit start/switch action. An always-visible bottom player independently shows receiver artwork, title, connection state, progress and transport controls across all tabs. Its Play button resumes the current session only. Browser previews and receiver artwork use separate queues and generation checks, so browsing, late artwork responses and external receiver changes cannot overwrite each other.

Browse now contains only six compact cards with separate image labels, wrapped titles and explicit Play buttons. It no longer repeats a large selected-track preview beneath the catalog. Source inspection remains under Diagnostics. Image labels are not embedded in native macOS buttons. Card Play buttons preserve the existing explicit-start behavior; the active mix is labelled Playing and disabled, while paused matching media offers Resume. Layout checks verify that thumbnails, titles and buttons do not overlap at 1000×760 and 800×680.

### Automatic connection

Select a speaker to connect; no Connect or Disconnect buttons are needed. The last selected receiver UUID is saved in ignored `data/preferences.json` and restored when discovered. `--rejoin-speaker` overrides it for that launch. If a saved speaker is absent, the app waits rather than connecting to a different device. With no saved preference it selects Bedroom when discovered. The Cast library gets five seconds to recover a dropped connection before an explicit read-only rejoin; failures back off up to thirty seconds. No connection/retry path loads media, resumes audio, changes volume or transfers playback.
