# Tasks

No task in this list starts audible playback. Tasks marked **(owner-authorised)** need the owner's explicit go-ahead for that run, a named speaker, and a Stop check afterwards.

## 0. Preconditions

- [ ] 0.1 Archive `android-live-membership` (`openspec archive android-live-membership`) so its delta specs reach `openspec/specs/`. Verify with `openspec validate --all --strict`.
- [ ] 0.2 Record the decision in `docs/current-direction.md` (shared Kotlin client, desktop first, Python kept only as a source service) and the sender-authentication risk in `docs/google-cast-lessons.md`. Verify the two entries exist.

## 1. Shared protocol core (no behaviour change)

- [ ] 1.1 Add the `compose-app/cast` plain-JVM module and the `jvmShared` source set in `composeApp` (D1). Verify that `./gradlew :cast:test :composeApp:desktopTest :androidApp:assembleDebug` succeeds.
- [ ] 1.2 Move `CastStatusWire`, `CastFrameTransport`, `LanCastTransport`, and their tests from `androidApp` into `:cast`. `AndroidBackend` keeps using them. Verify that the existing `PassiveReceiverProbeTest` and `MembershipChannelTest` pass from `:cast` and that the Android unit tests stay green.
- [ ] 1.3 Implement the connection actor (D2): CONNECT, heartbeat, `requestId` correlation, unsolicited status, dead-connection detection, injected clock. Verify with scripted tests for a missed PONG, an out-of-order reply, a wrong request id, an oversized frame, and close during a wait.
- [ ] 1.4 Implement the passive open path (D3). Verify with a scripted test that no frame other than CONNECT, PING/PONG, and `GET_STATUS` is ever sent on open, reconnect, or poll, including when the media channel is missing (`poll_installs_passive_guard_before_connection_and_never_mutates`, `snapshot_never_loads`).

## 2. Discovery and inventory

- [ ] 2.1 Implement the discovery interface with jmDNS and identity-checked address hints (D4). Port `test_inventory.py` and the hint cases from `test_bridge.py` (`entrypoint_passes_saved_address_before_lab_starts_discovery`, `discovery_hint_is_only_an_address_not_a_selected_identity`). Verify that the ported tests pass.
- [ ] 2.2 Read the saved group list and classify temporary groups. Port `temporary_classification_uses_receiver_topology_not_group_name` and `unknown_group_is_temporary_once_saved_groups_are_known`, and merge `GroupTopologyTest`. Verify that the tests pass.
- [ ] 2.3 Run a desktop discovery smoke test from a test harness: list every known speaker, its availability, and the saved groups, sending reads only. Verify that the transcript has no LAUNCH, LOAD, SET, or volume frame. Record the result in `docs/spike-results.md`.

## 3. Rules, ported test-first (D6)

Each task translates the listed Python tests into Kotlin cases with the same names before porting the rule. Done means every listed test name has a passing Kotlin counterpart.

- [ ] 3.1 Destination selection and connection freshness: the selection cases in `test_bridge.py` and `test_groups.py` (`socket_without_fresh_evidence_does_not_enable_controls`, `individual_selection_does_not_attach_group_controller`, `stale_group_monitor_cannot_replace_live_controller`, `only_explicit_remote_selection_clears_uncertain_context`), plus `RejoinPolicyTest`.
- [ ] 3.2 Transport confirmation: `test_seeking.py` and the pause/resume cases (`pause_requires_fresh_confirmation`, `resume_requires_matching_state`, `stale_pause_does_not_confirm`, `resume_confirmed`), plus `CastGuardsTest`.
- [ ] 3.3 Stop and cancellation: `stop_blocks_new_load_until_confirmation`, `stop_revision_waits_for_verified_receiver_result`, `failed_stop_never_acknowledges_or_retries`, `fallback_after_stop_sends_nothing`, plus `PlaybackFenceTest`.
- [ ] 3.4 Play and load: the LOAD operation with a user action token, `LoadObservationTest`, `DirectStreamProbeTest`, and `ReceiverDeadlineTest`. Verify that LOAD can only be reached from the Play operation.
- [ ] 3.5 Membership: all of `test_membership.py`, merged with `MembershipChannelTest` and `AdoptionTest`. Use the D5 endpoint connection instead of route adoption.
- [ ] 3.6 Following and recovery: all of `test_following.py` and `test_recovery.py`, plus `RouteChangeRecoveryTest` cases that still apply without the SDK.
- [ ] 3.7 Outputs monitor: all of `test_outputs.py` and `test_groups.py` (`simultaneous_group_and_individual_music`, `stale_media_and_volume_are_hidden`, `terminated_monitor_connection_is_replaced_passively`, …).
- [ ] 3.8 Volume: `test_volume.py`, the group-volume cases (`scaling_preserves_member_ratio`, `average_waits_for_every_member`, `incomplete_group_is_not_written`, `cached_matching_volume_is_not_confirmation`), plus `MemberVolumeTest`. Reuse `GroupVolume.kt`.
- [ ] 3.9 Source expiry: all of `test_expiry.py` against a fake `SourceProvider`.
- [ ] 3.10 Cross-check: produce a table in `openspec/changes/kotlin-cast-client/port-map.md` that maps every Python and Android test name to its Kotlin test, or gives a reason it was dropped (for example, a PyChromecast-only workaround). Verify that no name is unmapped.

## 4. Shared backend and desktop cutover

- [ ] 4.1 Implement `CastPlayerBackend : PlayerBackend` in `jvmShared`, mapping client state to `Snapshot`. Verify with the existing `commonTest` suites run against it through a scripted client (`PlayerTest`, `MembershipTest`, `VolumeSyncTest`, `TransportSyncTest`, `SeekSyncTest`, `StoppedPlaybackTest`).
- [ ] 4.2 Reduce `compose-app/bridge/server.py` to the source service (`catalog`, `import_channel`, `resolve`, `refresh`, `preview`) behind a new flag, with no PyChromecast import on that path. Add `SourceService` on desktop. Verify the bridge source tests and that `python -c "import server"` in source mode does not import `pychromecast`.
- [ ] 4.3 Add the `-Dmoozaq.backend=python|kotlin` switch (D8), defaulting to `python`. Verify that `createDistributable` succeeds and that both modes launch silently.
- [ ] 4.4 Run the silent live desktop check on `kotlin`: discovery, and a rejoin of an already-playing session with no LOAD, SET, or volume. Verify from the frame transcript. Record it in `docs/spike-results.md`.
- [ ] 4.5 **(owner-authorised)** Run the audible desktop parity checklist on the speaker the owner names: play, pause, resume, seek, Stop, add and remove a member, group volume, reopening mid-mix. Record each result separately in `docs/spike-results.md`.
- [ ] 4.6 Switch the desktop default to `kotlin` after 4.5 passes. Verify that a fresh launch uses the Kotlin client, from its diagnostics.

## 5. Android cutover

- [ ] 5.1 Add Android platform glue: multicast lock, Wi-Fi interface, storage, diagnostics sink, and `OnDeviceSources` as `SourceProvider`. Add a `CAST_BACKEND` build flag, defaulting to `sdk`. Verify that `assembleDebug lintDebug` passes with both flag values.
- [ ] 5.2 Measure jmDNS discovery on the Pixel against the SDK's speaker list (read-only). If speakers are missed, switch the Android default to the `NsdManager` implementation. Record the numbers in `docs/spike-results.md`.
- [ ] 5.3 Run the silent live Android check on the `kotlin` flag: cold open, warm return, and rejoin of a playing session with no LOAD, SET, or volume, verified from diagnostics.
- [ ] 5.4 **(owner-authorised)** Run the audible Android parity checklist, the same list as 4.5. Record it separately.
- [ ] 5.5 Remove `play-services-cast-framework`, `mediarouter`, `CastOptionsProvider`, its manifest entry, and the SDK paths in `AndroidBackend`. Bump the app version. Verify that `assembleDebug lintDebug` and all tests pass, and that `./gradlew :androidApp:dependencies` lists neither library.

## 6. Cleanup and documentation

- [ ] 6.1 Remove `PythonBackend`'s Cast operations and the bridge's Cast modules (`membership`, `following`, `recovery`, `groups`, `outputs`, `volume`, `seeking`) once 4.6 has held. Leave `mac-spike/` untouched as the reference. Verify that the bridge source tests and desktop tests pass.
- [ ] 6.2 Update `AGENTS.md` (repository map and validation commands), `compose-app/README.md`, `docs/current-direction.md`, and `docs/google-cast-lessons.md`, removing PyChromecast-specific rules from the active guidance. Verify with `openspec validate --all --strict`.
