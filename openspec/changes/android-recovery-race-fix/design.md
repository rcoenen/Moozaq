# Design

## Context

See `proposal.md` for motivation and `specs/android-cast-recovery/spec.md` for the behavior contract. `AndroidBackend.recover()` currently duplicates its SDK-state checks before and after the passive probe, and the two checks have diverged. The probe is asynchronous relative to SDK callbacks, so the post-probe check must evaluate fresh session state. The Android source is untracked and the working tree contains unrelated work that must be preserved.

## Goals / Non-Goals

**Goals:**

- Make the pre-probe and post-probe SDK-state decisions consistent by construction.
- Keep the correction small and deterministically testable without real Cast hardware or audio.
- Preserve the 0.3.3 faster-recovery experiment long enough to measure it safely.

**Non-Goals:**

- Introduce a broad Cast SDK abstraction or rewrite session-callback ownership in this change.
- Claim that the passive probe and subsequent SDK route selection are atomic.
- Change Cast, MediaRouter, or other dependency versions.

## Decisions

### Use one pure predicate for SDK states that block fallback selection

Represent the current session's connected, connecting, resuming, and suspended flags in a small immutable value and evaluate one shared predicate at both recovery decision points. Read the current SDK session again after the probe rather than reusing the earlier state.

This removes the duplicated condition lists that allowed `isResuming` to disappear from the post-probe guard. Duplicating the same four checks in both places is simpler initially but leaves the same drift risk in place.

### Test the guard directly, not through a new SDK fake framework

Add a focused JVM state-matrix test covering each blocking state, an idle/disconnected state, and the specific transition from not resuming before the probe to resuming afterward. This change does not require mocking final SDK classes or introducing a test dependency.

A fake session-client integration layer remains valuable for late callbacks and null device metadata, but it belongs to the follow-up session-ownership change rather than this localized recovery correction.

### Validate the experiment before expanding recovery behavior

After the guard tests and existing Android/shared suites pass, install the reviewed candidate and measure two cold-process reopens plus a warm background/foreground return. Use only an already-running explicitly authorized speaker session and compare source identity, application-session fingerprint, advancing receiver position, and command logs. The initial Bedroom session was unavailable, so final acceptance used Office after explicit owner authorization for audible setup there.

If faster recovery is slower or less reliable than the verified baseline, restore `setResumeSavedSession(true)` and the 2,500 ms foreground grace, rebuild, and repeat the relevant passive checks. Retain safeguards whose tests remain valid. Do not use Git checkout or cleaning as a rollback mechanism for the untracked Android source.

## Risks / Trade-offs

- [A session can transition after the final guard but before route selection] → Document the remaining probe/select race and address supported join-only behavior separately; do not claim this change makes selection atomic.
- [A session can remain resuming, connecting, or suspended indefinitely] → Recovery continues to yield rather than forcing an end or competing selection; bounding that condition is separate work.
- [Null device metadata and stale SDK callbacks remain ambiguous] → Leave current handling unchanged in this focused patch and cover it in the session-ownership design with fake callback-order tests.
- [Passive receiver evidence does not prove audible output or long-session reliability] → Report only the measured rejoin behavior and keep audible/long-session acceptance separate.

## Migration Plan

There is no persistent-data or API migration. Deploy the corrected candidate with the normal debug reinstall only after silent checks pass. Rollback is a source-level restoration of the two verified 0.3.2 recovery options followed by a rebuild, not a tree reset or an assumed historical APK.
