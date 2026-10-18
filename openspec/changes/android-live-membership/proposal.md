# Proposal

## Why

A playing or paused mix on one Nest speaker cannot gain or lose another speaker from the Cast to checkboxes. The Android session is a plain route, and the Cast SDK only exposes add and remove on a dynamic group route. Google's Output Switcher is a system chooser, not a command those checkboxes can send, and this app is not a Cast Developer Console audio receiver that can opt into that chooser. The Mac already changes this membership with one platform `SET_PLAYBACK_DEVICES`.

## What Changes

- Send that same multizone command from Android on a platform channel to `receiver-0`, once, for an explicit checkbox change during a playing or paused session.
- Gate it on fresh `appAllowsGrouping`, `streamTransferSupported`, and `isVideoContent=false`. Preserve the original playing or paused state. Do not retry.
- Adopt a returned endpoint only after the same session and media are confirmed. Hide temporary groups. Keep saved speaker groups unchanged.
- Follow the moved session for Stop and later transport commands. Automatic recovery must not reselect the old speaker while that handoff is the playback location.
- Leave the audible add/remove trial unauthorized by this change. Silent tests cover the protocol failures before any speaker is mutated.

## Capabilities

### New Capabilities

- `android-live-membership`: Add or remove one physical speaker on a playing or paused Android Cast session, with confirmation, cancellation, and endpoint handoff.

### Modified Capabilities

- `android-cast-recovery`: Automatic rejoin must not select the previous speaker while a membership handoff owns the playback location.

## Impact

- `compose-app/androidApp` membership path, Cast framing, and JVM tests.
- Phone Cast to checkboxes, which already call `setPlaybackDevices` when membership is available.
- No new dependency, no manifest receiver, no media LOAD on the membership path, and no change to saved Home groups.
