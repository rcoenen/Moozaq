# Design

## Context

See proposal.md for the motivation. Today's layout:

- `compose-app/composeApp` holds the shared UI and `PlayerStore`. It talks to a `PlayerBackend` interface: `snapshot`, `selectDevice`, `play`, `command`, `outputVolume`, `memberVolume`, `setPlaybackDevices`, `dismissMembership`, `catalog`, `importChannel`.
- Desktop uses `PythonBackend`. It is a stdio JSON client for `compose-app/bridge/server.py`. The bridge wraps `Lab` from `mac-spike/engine.py` (PyChromecast discovery, connections, media commands, yt-dlp, an optional media relay) and adds its own modules: `membership`, `following`, `recovery`, `groups`, `outputs`, `volume`, `seeking`, `inventory`, and `catalog`.
- Android uses `AndroidBackend` (1,248 lines). It is built on `CastContext`, `RemoteMediaClient`, and MediaRouter. It also has a raw Cast v2 layer that is JVM-only and has no Android imports:
  - `CastStatusWire`: a hand-rolled protobuf `CastMessage` codec
  - `LanCastTransport`: TLS restricted to private addresses
  - `CastFrameTransport`: a scripted-test seam
  - `MembershipChannel`: multizone, receiver, and media reads, `SET_VOLUME`, `SET_PLAYBACK_DEVICES`, and media commands
- The rules are tested twice: about 124 Python bridge tests and about 65 Android unit tests. The UI confirmation rules are already shared in `commonMain` (about 62 tests).

The constraints come from AGENTS.md:
- no audible playback without explicit owner authorisation
- passive reconnects
- Stop takes priority
- no signed links in logs
- tests use mocks, never real playback

## Goals / Non-Goals

**Goals:**
- The protocol code and every rule in `specs/cast-client` live in one place, with one test suite that runs on a plain JVM against scripted frames.
- Each platform keeps a working app throughout. Cutover happens per platform, and the old backend can be switched back to until it is removed.
- The `PlayerBackend` contract and `PlayerStore` behaviour do not change, so the UI needs no rework.

**Non-Goals:**
- No media notification, MediaSession, lock-screen controls, hardware volume keys, or foreground playback service. Android has none of these today.
- No custom receiver. The Default Media Receiver (`CC1AD845`) stays.
- The desktop media relay is not ported. The Compose app uses the direct route only.
- The client does not support sender device authentication. It is not required today. See Risks.

## Decisions

### D1. New plain-JVM `cast` module, plus a shared JVM source set for the adapter

Add `compose-app/cast`, built with `kotlin("jvm")` and the dependencies kotlinx-coroutines, kotlinx-serialization, and an mDNS library. It contains:
- the wire codec and transport
- the connection actor
- discovery
- the rule engines: selection, media, membership, following/recovery, volume, inventory, expiry

It has no UI model.

In `composeApp`, add an intermediate `jvmShared` source set under `commonMain`. Both `desktopMain` and `androidMain` sit under it, and it depends on `:cast`. It holds `CastPlayerBackend : PlayerBackend`, which maps client state to `Snapshot`. Platform glue (lifecycle, multicast lock, sources, storage paths, diagnostics sink) is passed in through small interfaces.

*Alternatives:* everything in a `composeApp` source set was rejected, because rule tests would need the multiplatform build and the protocol would sit next to UI code. A separate Android library was rejected, because desktop could not use it.

### D2. One actor per speaker connection, and permits for operations

Each connection owns a single reader loop on `Dispatchers.IO`. It handles:
- CONNECT and heartbeat. A missed PONG means the connection is dead.
- `requestId` correlation
- a stream of unsolicited status updates

A dead or stopped connection is retired and replaced through the passive open path. That is the Kotlin counterpart of the bridge's `terminated_monitor_connection_is_replaced_passively`.

Operations sit above connections and carry generation tokens and permits. They grow out of `ConnectionEpoch`, `StatusRequestGate`, and `MembershipPermit`. That way, Stop, a newer selection, or a target change invalidates late completions in one place.

Clocks are injected so that deadlines are deterministic in tests. That generalises `MembershipClock`.

*Alternative:* the PyChromecast-style thread per connection with callbacks was rejected. It is what made late completions and passive status hard to guarantee.

### D3. The passive open path is the only way to connect

There is one `open(device)` function. It sends CONNECT to `receiver-0` and receiver `GET_STATUS`. It reads media status only if the Default Media Receiver is already running. Nothing on this path can produce LAUNCH or LOAD, so the "missing media channel launches an app" class of bug cannot exist.

LAUNCH and LOAD exist only inside the explicit Play operation, which needs a user action token.

### D4. Discovery: mDNS, then identity-checked hints, then saved groups from the receiver

Discovery browses `_googlecast._tcp.local` with a JVM mDNS library. It uses jmDNS by default, on both platforms. On Android the platform supplies a `WifiManager.MulticastLock` and the active Wi-Fi interface address.

Remembered addresses are probed directly, without waiting for mDNS. A hinted device counts only when its device info reports the remembered UUID.

The saved group list is read from `multizone.groups` in the receiver's eureka/device info, matching both current implementations. Temporary groups are classified against it. Device records whose last service has gone are treated as unavailable, never as present.

*Alternative:* Android `NsdManager`. It is kept as a fallback implementation behind the same interface, in case jmDNS proves unreliable on the Pixel. Older Android versions resolve one service at a time, which slows startup.

### D5. Endpoint handoff opens a connection, it does not adopt a route

After a membership reply returns an endpoint, the client validates the host, port, session, and media. It then opens a passive connection to that endpoint and moves the control binding there. That is what the bridge already does.

This replaces Android's `adoptRoute` / `handoffRouteSelected` / `shouldSeekMovedGroup` machinery, which existed only to make MediaRouter follow a move.

### D6. Port the rules test-first, from the bridge test names

For each bridge test file, write the Kotlin test cases first, using the same scenario names translated to camelCase, against scripted transports. Then port the rule. Android unit tests that cover the same rule are merged rather than duplicated.

`tasks.md` lists each Python test file next to its Kotlin target. Port completion is checked against that list, not by impression.

### D7. Desktop Python becomes a source service

`server.py` keeps only these operations: `catalog`, `import_channel`, `resolve(source, start)` (returns a link and its expiry), `refresh`, and `preview`. It stops importing PyChromecast and `Lab`'s Cast parts.

A smaller `SourceService` client replaces `PythonBackend`'s Cast operations. Android's `OnDeviceSources` / `BundledExtractor` implement the same `SourceProvider` interface. Expiry decisions (D6, `test_expiry`) move into the shared client, so Android gains the Mac's expiry guards.

### D8. Backend switch and parity checklist per platform

- Desktop selects the backend with `-Dmoozaq.backend=python|kotlin`.
- Android uses a build config flag, `CAST_BACKEND`. The SDK dependency stays until Android cutover is accepted.

Each platform must pass these before it switches by default:
1. the shared suite, plus that platform's build, lint, and unit tests
2. silent live checks: discovery of every known speaker, and a rejoin of an already-playing session with no LOAD, SET, or volume command (the transcript is checked)
3. an owner-authorised audible run: play, pause, resume, seek, stop, add and remove a member, group volume, and reopening the app mid-mix

Removal of the old backend is its own task, after acceptance.

## Risks / Trade-offs

- [Google starts requiring sender authentication] → Only the transport and the open path would change. Keep the SDK backend in git history and tagged, and record the risk in `google-cast-lessons.md`. Neither current stack handles this today.
- [jmDNS or Android multicast misses speakers] → Remembered-address probing does not depend on mDNS. D4 has the `NsdManager` fallback. Known speakers stay visible as unavailable instead of disappearing.
- [The unexplained SSL `BAD_LENGTH` / `BAD_RECORD_MAC` errors seen with PyChromecast reappear] → Treat them as connection loss and use passive replacement. Log the error class without payloads. Do not weaken TLS settings.
- [Behaviour drifts between the old and new backends during the port] → Freeze the old backends except for Stop and safety fixes. Any fix made there gets a matching shared test.
- [Large scope] → Every phase in `tasks.md` leaves both apps shippable. Phases 1–3 are useful on their own, because Android can use the shared protocol core before full cutover.
- [Losing the SDK's reconnection service and saved-session resume] → These are already disabled or worked around (`setResumeSavedSession(false)`, the 2152 failures). Read-only rejoin replaces them, and that rejoin is the behaviour already specified.

## Migration Plan

1. Build the `:cast` module and the shared tests, with no behaviour change in either app.
2. Move Android's raw layer (`CastStatusWire`, `LanCastTransport`, `MembershipChannel`) into `:cast`. `AndroidBackend` keeps working on top of it.
3. Build `CastPlayerBackend` and run it behind the desktop switch. Desktop cuts over first, because the bridge rules are the reference and the Mac is easiest to observe.
4. Run Android on `CastPlayerBackend` behind its flag. Accept it, then remove the SDK, MediaRouter, and `CastOptionsProvider`.
5. Reduce the Python bridge to the source service, then update the docs and the AGENTS.md map.

Rollback: before each removal step, flip the switch back. After removal, revert the removal commit.

## Open Questions

- Whether jmDNS is reliable enough on the Pixel, or the `NsdManager` fallback becomes the Android default. This is decided by measurement during phase 3. The interface does not change.
- Exact heartbeat interval and dead-connection timeout. Start from the Cast defaults (5 s ping, about 10 s timeout) and tune from diagnostics.
