# Proposal

## Why

Moozaq has two Cast stacks, and each one implements the same control rules separately. The Mac app runs a Python bridge (`compose-app/bridge/`). Its core Cast engine is still `Lab` in `mac-spike/engine.py`, which is built on PyChromecast. The Android app runs the Google Cast SDK and MediaRouter, plus a hand-rolled Cast v2 layer for what the SDK does not expose.

The Mac path changed speaker membership almost at once. Android needed eight builds (0.4.2 to 0.5.0) to reach the same point. Nearly every Android defect sat where the SDK's route-bound session had to be reconciled with a session that moved to a new temporary-group endpoint. The Python path has its own workarounds for library behaviour:
- passive status, so that `update_status()` cannot launch an app
- a known-host seeding order, to avoid a 30-second discovery wait
- filtering of empty-service device records
- replacement of connections that stop permanently

Owning the Cast protocol in one shared Kotlin client removes both kinds of friction and the duplicate implementation.

## What Changes

- Add one Kotlin Cast v2 client in shared JVM code. It does TLS framing, CONNECT, heartbeat, and the receiver, media, and multizone namespaces. It grows out of the existing `MembershipChannel.kt`, `PassiveReceiverProbe.kt`, and `CastStatusWire`.
- Implement `PlayerBackend` once on top of that client, for both the desktop app and the Android app. The rules move over from the Python bridge and from `AndroidBackend` together with their tests: passive connections, fresh confirmation, membership, following, recovery, member and group volume, inventory, seeking, and expiry handling.
- Discover speakers with `_googlecast._tcp` mDNS in the client, plus remembered address hints that must match the device identity.
- **BREAKING (Android internals):** remove `play-services-cast-framework` and the MediaRouter session model from Android. Route selection, SDK session callbacks, the `CASTING_ROUTE_CHANGED` reconciliation, and SDK saved-session behaviour go away. User-visible controls and confirmation rules stay the same.
- **BREAKING (desktop internals):** remove PyChromecast and the `Lab` Cast engine from the desktop path. Python stays only as a source service: yt-dlp resolution, link refresh, the channel catalog, and previews.
- Cut over one platform at a time behind a backend switch. The old backend stays available until that platform passes its parity checklist.
- Out of scope:
  - media notification, lock-screen controls, and hardware volume keys (the app has none today)
  - any new receiver app
  - audible validation, which needs explicit owner authorisation per run

## Capabilities

### New Capabilities

- `cast-client`: Shared Cast control behaviour that both apps follow through one Kotlin client. It covers passive connections, discovery and inventory, explicit destination selection, confirmed media and volume commands, live membership, session following and recovery, and source handoff from a platform source provider.

### Modified Capabilities

- `android-cast-recovery`: Recovery yields to the shared client's active connection states instead of Cast SDK session states. The no-playback-change rule stays.
- `android-route-selection`: Its requirements are about reconciling SDK `CASTING_ROUTE_CHANGED` events, so they are removed. Explicit destination selection moves to `cast-client`.

## Impact

- New shared JVM module or source set under `compose-app/` for the Cast client and the shared backend. It is used by `composeApp` desktop and by `androidApp`.
- Android:
  - `AndroidBackend.kt` shrinks to platform glue: lifecycle, multicast lock, on-device sources, diagnostics.
  - `CastOptionsProvider.kt` is removed.
  - The Gradle dependencies on `play-services-cast-framework` and `mediarouter` are dropped.
- Desktop:
  - `PythonBackend.kt` is replaced by the shared backend and a smaller source-service client.
  - `compose-app/bridge/` keeps only source and catalog duties.
  - The Cast parts of `mac-spike/engine.py` are no longer used by the Compose app. `mac-spike/` itself stays as the reference AGENTS.md asks for.
- Tests: the ~124 bridge tests and ~65 Android unit tests become one shared Kotlin suite, run against scripted Cast frame transports, with no speaker.
- Sequencing: archive `android-live-membership` before this change. Its membership rules are restated in `cast-client` for both platforms.
- Docs: `docs/current-direction.md`, `docs/google-cast-lessons.md`, `AGENTS.md` repository map, `compose-app/README.md`.
