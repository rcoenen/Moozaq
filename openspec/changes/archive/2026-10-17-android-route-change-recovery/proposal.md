# Proposal

## Why

Cast status 2155 maps to `CASTING_ROUTE_CHANGED`, but Android currently treats it as a generic disconnect. An interrupted first-time destination selection can therefore fail immediately and make the user retry, while diagnostics lose the reason needed to distinguish normal route transitions from an interrupted join.

## What Changes

- Record mapped Cast reason codes and route/session context in private diagnostics while keeping user-facing failures friendly.
- Keep an explicit destination pending through a bounded route-change reconciliation instead of failing on the first eligible event.
- Retry only the route join once, only when fresh target, generation, route, foreground, and SDK-state guards all pass; never retry media or volume operations.
- Ignore stale replaced-session events and never automatically reclaim an attached session or another controller's route.
- Add deterministic policy tests and a silent SDK reason-mapping instrumentation check.
- Validate deployment and existing-session recovery without forcing another failure or starting new audio.

## Capabilities

### New Capabilities

- `android-route-selection`: Reliable, reason-aware Android destination selection when Cast route transitions interrupt a pending join.

### Modified Capabilities

None.

## Impact

- Android session/route handling and focused policy code under `compose-app/androidApp`.
- Android JVM and instrumentation tests, shared destination-state tests, and the 0.3.4 / versionCode 6 debug build.
- Cast recovery documentation and evidence. No dependency-version changes or new playback paths.
