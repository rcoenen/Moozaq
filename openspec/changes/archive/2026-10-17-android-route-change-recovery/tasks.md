# Tasks

## 1. Reason and policy coverage

- [x] 1.1 Add a silent `CastReasonTest` instrumentation assertion for 2155 mapping to `CASTING_ROUTE_CHANGED`; verify it runs without route selection or media commands.
- [x] 1.2 Implement the pure route-change retry policy and JVM tests for eligible first retry, second-failure suppression, stale/attached sessions, alternate routes, changed targets, SDK activity, cancellation, and attachment-before-retry; verify all focused tests pass.

## 2. Backend integration

- [x] 2.1 Integrate mapped reason diagnostics and the bounded pending-join reconciliation in `AndroidBackend`, including fresh route/session guards and cancellation on selection, background, Stop, close, and attachment; verify focused tests and source inspection cover every guard and no media operation is called.
- [x] 2.2 Verify shared destination UI remains pending while reconciliation has no connection error and exposes manual retry after final failure; run the relevant shared destination tests.
- [x] 2.3 Bump Android to 0.3.4-standalone / versionCode 6 and the diagnostic version string; verify the built APK and `dumpsys` report those values after deployment.

## 3. Silent validation and deployment

- [x] 3.1 Run Android assemble, androidTest assemble, unit tests and lint plus shared desktop tests; verify success and record counts without audible tests.
- [x] 3.2 Reinstall with app data preserved, run only the focused reason-mapping instrumentation check, and verify code 6 / 0.3.4 without selecting a route or starting playback.

## 4. Recovery evidence

- [x] 4.1 If the existing Office session remains available, perform one passive cold rejoin and verify unchanged source/session, advancing position, and no recovery media commands; otherwise record the missing precondition without starting audio.
- [x] 4.2 Update Android README, current direction, Cast lessons, spike results, and OpenSpec with the established reason mapping, test evidence, remaining unknown route-change actor, and validation limits; verify `openspec validate android-route-change-recovery --strict` passes.
