# Tasks

## 1. Decision record

- [x] 1.1 Record the Gate 1–2 result and the platform-client decision in the investigation note, current direction, Cast lessons, and spike results.

## 2. Silent membership client

- [x] 2.1 Add the pure eligibility, endpoint, permit, and conversation rules, plus scripted tests for one send, stale session, bad frames, wrong request ids, omitted and foreign endpoints, partial failure, pause preservation, Stop before send, Stop after send, and timeout without a second send.
- [x] 2.2 Report platform eligibility and members from `AndroidBackend`, send the checkbox change through the platform channel, and keep the group-route fallback only when the platform session is not eligible.
- [x] 2.3 Follow an adopted endpoint for Stop and transport, hide temporary groups, and make automatic recovery skip the previous speaker during handoff.
- [x] 2.4 Bump the Android app to 0.4.2 / code 12 and describe the locked checkbox as a session limit.

## 3. Verification

- [x] 3.1 Run Android unit tests, lint, and shared desktop tests. Confirm they pass without a speaker mutation.
- [x] 3.2 If a mix is already playing, read the three eligibility flags without loading, moving, or resuming anything. Otherwise record that no session was available.

## 4. Kitchen confirmation hang

- [x] 4.1 Keep a real socket reading until the confirmation deadline, and keep scripted misses on the manual clock. After an unlocated send, clear the in-flight checkbox, stop status polls of the pre-change speaker, and select one discovered group only when a read-only status check shows the same session, the same media, and an active player state. Record why in the code comment, Cast lessons, current direction, and spike results. Do not install or send another membership command as part of this fix.

## 5. Reopen follow

- [x] 5.1 When open has no media, either because a connected speaker reports none or because recovery settled with no Default Media Receiver, wait about eight seconds and select the one discovered group already playing that session. Read the session id from the Cast session or, if none is connected, from a receiver `GET_STATUS` on the remembered speaker. Do not send `SET_PLAYBACK_DEVICES` or LOAD. Do not save the temporary group. Record why, and do not archive this change.
