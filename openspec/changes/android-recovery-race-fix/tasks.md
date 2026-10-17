# Tasks

## 1. Recovery guard

- [x] 1.1 Add a shared immutable SDK-session activity predicate and focused JVM state-matrix tests covering idle, connected, connecting, resuming, and suspended states; verify the tests include a session that becomes resuming during the probe and pass with the new guard.
- [x] 1.2 Use freshly read current-session state and the shared predicate at both recovery decision points; verify the regression tests pass and both call sites prevent fallback route selection for every blocked state without duplicating condition lists.

## 2. Silent build and deployment

- [x] 2.1 Run `:androidApp:assembleDebug`, `:androidApp:assembleDebugAndroidTest`, `:androidApp:testDebugUnitTest`, `:androidApp:lintDebug`, and `:composeApp:desktopTest`; verify all complete successfully and record test counts without running audible tests.
- [x] 2.2 Rediscover the ADB transport, inspect the installed package before deployment, reinstall the reviewed candidate, and verify with `dumpsys` that code 5 / 0.3.3 is installed; preserve unrelated worktree changes and do not start playback.

## 3. Passive recovery acceptance

- [x] 3.1 When Bedroom no longer had an active session, use the explicitly owner-authorized Office session for two normal-app cold-process reopens; verify matching source and application-session fingerprints, advancing receiver position, correct fresh UI state, and no LOAD, PLAY, seek, or volume recovery events, and record both elapsed recovery times.
- [x] 3.2 Perform a warm background/foreground return under the same explicitly authorized Office session; verify fresh receiver state, unchanged source/session identity, and absence of playback or volume commands, and record elapsed recovery time.
- [x] 3.3 Compare observed recovery with the verified 0.3.2 baseline; if worse, restore saved-session resume and the 2,500 ms grace, then verify the rollback with the silent suites and relevant passive rejoin check, otherwise retain the faster options and record the evidence and limitations.
- [x] 3.4 Update the Android README, current direction, Cast lessons, and spike results with the accepted configuration and actual measurements; verify the documentation distinguishes source review, unit tests, receiver-state observation, visual checks, and unproven audible/long-session reliability.
