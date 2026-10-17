# Proposal

## Why

The 0.3.3 recovery fallback can select a route while the Cast SDK is resuming a session, because the post-probe guard omits the resuming state checked before the probe. This race undermines the faster cold-rejoin experiment and should be removed before the candidate is installed for hardware validation.

## What Changes

- Prevent fallback route selection whenever the current SDK session is connected, connecting, resuming, or suspended, including when that state begins during the passive receiver probe.
- Add focused silent regression coverage for the recovery guard, especially a session that starts resuming while the probe is in flight.
- Rebuild the candidate and measure passive cold-process and warm foreground rejoin against an already-running explicitly authorized speaker session, without recovery issuing playback, seek, or volume commands. The initial target was Bedroom; when that session was unavailable, the owner explicitly authorized Office and audible setup there.
- Restore the verified 0.3.2 recovery options if the candidate rejoin is worse, while retaining independently validated safeguards.
- Leave broader session-callback ownership, unsynchronized Stop, probe/select atomicity, volume quantization, and Activity retention to separate changes.

## Capabilities

### New Capabilities

- `android-cast-recovery`: Safe Android rejoin behavior for an existing Cast receiver session during SDK lifecycle transitions and passive recovery.

### Modified Capabilities

None.

## Impact

- Android recovery and guard code under `compose-app/androidApp/src/main/java/com/moozaq/app/`.
- Focused Android JVM tests and the existing Android/shared validation suites.
- The uncommitted 0.3.3 candidate APK and, after observed validation, Android recovery documentation.
- No dependency-version changes, credentials, new automatic playback paths, or audible recovery retries. The Office mix used for acceptance was started only after explicit owner authorization.
