# Design

## Context

See `proposal.md` — Why, and the two capability deltas for the external contract. The completed Mac change supplies the public SQLite format, validator, ordered editorial/recent inputs, personal store, listening tracker, shared Kotlin ranking, and GitHub release channel. This design reuses those contracts; it does not design another catalog.

Android currently calls `server.start_bridge()` without a catalog injection from `android_bridge.py:102-122`. That selects the legacy JSON implementation in `server.py:49`. `ChaquopyBackend.kt:48-65` retains `files/bridge/data` across source replacement and seeds `channel-catalog.json` only when absent. The active APK is 0.6.9, while `CatalogUpdater` defaults to 0.6.0 (`catalog_update.py:25-27`). These are explicit adapter inputs, not reasons to change the Cast engine.

The public `AppCatalog` already supplies read-only SQL, recognition, ordered Recommended/Recently added IDs, and replacement detection. `DesktopCatalog` is deliberately unsuitable for Android because it constructs the master, ingest/curation, local generation, personal profile, and updater together (`desktop_catalog.py:15-47`). Android needs a thin composition of the reusable client-side pieces instead.

The following observed constraints shape the design:

- `CatalogUpdater.check()` currently holds the same lock used by `status()` throughout network and activation work (`catalog_update.py:45-111`). Android polls through one serialized request lane with a 10-second caller timeout (`ChaquopyBackend.kt:68-87`), so the existing lock cannot simply be exposed to phone updates.
- Highest accepted revision is currently derived from the active app at updater construction (`catalog_update.py:41`). Android requires an explicit persisted update state so a delayed older manifest cannot downgrade it after a restart.
- `activate_app_catalog()` copies an existing active file to the previous slot without first validating that active file (`catalog_publish.py:121-143`). Recovery must not overwrite a good fallback with a corrupt active file.
- `MainActivity.kt:17-27` creates/closes a backend per Activity, while `android_bridge.py` uses global bridge/runner state and asynchronous close. Catalog/profile/updater resources must be instance-bound so late lifecycle work cannot affect a replacement instance.
- Phone Browse still groups only moods (`PhoneBrowse.kt:48-72`), and the non-curatable `ChannelsDialog` exposes legacy Add/Refresh controls (`ChannelsDialog.kt:19-65`). The compact UI needs its own read-only catalog panel and discovery/rating presentation.
- Android has no `src/androidTest` suite at planning time. Existing AndroidX runner/JUnit dependencies support instrumentation; compact Compose rendering already has desktop test infrastructure. There is no WorkManager or Room dependency to reuse.
- Private files and preferences are already excluded from backup/device transfer (`data_extraction_rules.xml`). HTTPS is required by the network security configuration, compatible with GitHub and the existing artwork hosts.

## Goals / Non-Goals

**Goals:**

- Compose existing public-catalog/profile components in a narrow Android adapter, with no Mac owner workspace in the APK.
- Provide a reproducible offline starting snapshot, durable upgrades, validated last-good recovery, and a foreground-aware updater that cannot stall receiver state.
- Reuse the common discovery/profile models in the phone layout without changing Cast behavior or the desktop owner experience.
- Establish real Chaquopy/SQLite evidence on Android separately from host and rendering tests.

**Non-Goals:**

- No second writable catalog, phone master, cross-device profile, account, telemetry, or cloud taste service.
- No exact background schedule, always-running service, WorkManager port, or claim that a killed process keeps checking.
- No new GitHub repository or required catalog/app release for implementation and acceptance. Existing public releases and local fault injectors are sufficient.
- No Cast discovery, source-resolution, receiver-command, group, volume, recovery, or playback redesign.
- No Room or other runtime database dependency; Chaquopy's own `sqlite3` is the database under test.

## Decisions

### 1. Add a thin Android composition, never `DesktopCatalog`

Add `compose-app/bridge/android_catalog.py` with these collaborators:

```text
validated packaged/public download --> AppCatalog (mode=ro + query_only)
                                            |
                                            v
                                   AndroidCatalog facade
                                    |       |        |
                              PersonalStore  |  CatalogUpdater
                                    |        |   (public HTTPS only)
                              ListeningTracker
                                    |
                         files/bridge/data/personal.db
```

- `AndroidCatalog` exposes public `snapshot()`, `status()`, `recognize()`, personal operations, observation, manual update requests, foreground transitions, and close. It has no master, curator, generation, source-ingest, or release-publishing interface.
- `android_bridge.start()` receives the actual app version from `ChaquopyBackend` and calls `server.start_bridge(catalog=android_catalog)` explicitly. Existing `start_bridge()` remains the legacy default for desktop rollback and its regression tests; Android no longer relies on that default.
- `DesktopCatalog` remains the only owner composition on Mac. The Android facade never imports it.
- Add a server capability guard for `import_channel` so non-legacy read-only clients reject it even though legacy Android previously used that operation. Existing guards for master, curator, rebuild, and publish remain enforced. Hidden phone controls are not the security boundary.
- Route ratings through the catalog facade when it owns the profile. New positive/negative ratings require a currently recognized public song; unknown receiver sessions cannot be rated through a crafted request. Clearing an existing rating and full reset remain local operations.

**Alternative considered:** reuse `DesktopCatalog` with flags. Rejected because even disabled flags leave master creation/migration and owner-only construction in the phone path, violating the single-master boundary.

### 2. Package only the client-side Python boundary and a public bootstrap bundle

`syncBridgeAssets` currently copies every non-test `bridge/*.py`, including desktop-only curation and publication tools. Replace that broad inclusion with a narrow allowlist plus the two `mac-spike` engine files.

Extract shared, platform-neutral pieces where necessary:

- public app schema/validation/atomic activation from `catalog_publish.py`;
- video-ID/channel/time helpers needed by both platforms from `catalog_sqlite.py` and legacy `catalog.py`.

Keep `DesktopCatalog`, `MasterCatalog`, source ingest/curation, local generation, individual-video metadata, and `gh` publication modules out of the Android assets. Keep the public database format unchanged and run all Mac tests after moving shared helpers. Lazy-load the legacy catalog fallback so its presence is not an Android runtime requirement.

For first launch, version an intentional Android resource bundle under `androidApp/src/main/assets/catalog-bootstrap/`:

- `app-catalog.db`: the exact validated derived database from an existing immutable public release;
- `catalog-manifest.json`: the matching public manifest with revision, immutable URL, size, and hash.

An explicit `androidApp/prepare-catalog.py --tag catalog-v<revision>` refreshes those resources from the public repository without credentials or `gh`, then verifies hash/size and the existing public validator. Normal Gradle builds verify the local bundle but do not contact GitHub or use `latest`. Only these public resources are versioned; runtime `data/`, master, and personal files are never copied or committed. The implementation selects an existing validated release and records its exact tag/hash; no new release is needed.

**Alternative considered:** download `latest` on every clean build or copy the Mac's ignored active `data/app-catalog.db`. Rejected because the first is non-reproducible/network-dependent and the second depends on one machine and risks copying the wrong file. The public bootstrap resource is regenerable, inspectable, and works in an offline build.

### 3. Separate bootstrap, active, previous, and personal files

Use app-private persistent storage, never `cacheDir`, for databases:

```text
APK assets/catalog-bootstrap/       public, read-only source bytes
files/bridge/bootstrap/             validated packaged recovery copy
files/bridge/data/app-catalog.db    active public catalog, opened read-only
files/bridge/data/app-catalog.previous.db
files/bridge/data/catalog-update.json
files/bridge/data/personal.db       private, never a catalog input
files/bridge/data/channel-catalog.json  untouched legacy evidence only
```

Kotlin copies the APK bundle to a candidate location, not over the active catalog. The Python bootstrap validates its descriptor and database before using or retaining it. On first start it activates a copy and opens `AppCatalog`; on APK updates it may replace the packaged recovery copy only with another validated bundle, never the active, previous, update-state, or personal files.

Once the derived catalog is active, legacy JSON is not an alternative writable runtime and is not merged into the public projection. Preserve the old file untouched for private recovery/owner review. Missing legacy-only candidates must be reviewed on the Mac deliberately; Android does not upload or merge them automatically. Do not infer first-seen dates from migration.

The derived Kotlin path must not call `mergedCatalog()` to append hardcoded starters to the public database. Legacy fallback remains only for legacy mode; receiver titles can still be shown without making unknown sessions rateable.

If the active database fails validation, quarantine it without copying it over the previous validated database, then try validated previous and packaged copies. Recovery uses a fresh temporary copy and atomic replacement; it is a local recovery action, not acceptance of an older remote manifest. Preserve highest-accepted update state and report the recovered revision. If no valid copy exists, expose an empty read-only catalog with an explicit error rather than failing into legacy JSON; the receiver bridge and a later valid download remain available.

### 4. Make update state durable and update work non-blocking

Refactor `CatalogUpdater` without changing the manifest/artifact contract:

- A short-lived state lock protects status fields. A separate one-flight check lock/runner owns network, validation, and activation. Routine `status()` never waits on the network lock.
- An optional small atomic `catalog-update.json` stores highest accepted revision, last attempted check, last outcome, and public manifest metadata/ETag when supported. It contains no personal data or credentials. Initialize highest revision as the maximum of durable state and valid active revision; persist a newly accepted revision after successful activation. An active higher revision still wins if a process dies between activation and state persistence.
- Inject `BuildConfig.VERSION_NAME` as `current_version`; never use the stale 0.6.0 default on Android. Schema and minimum-app-version gates run before database download.
- Only the dedicated public manifest and its immutable tag-specific database URL are accepted. Keep the existing byte caps, SHA-256, public schema, integrity, foreign-key, and public-query checks. HTTPS/GitHub trust is unchanged; SHA-256 is integrity, not a new signing claim.
- Preserve `check()` as the synchronous Mac operation where needed. Add an asynchronous `request_check()` for Android that returns immediately with an accepted check ID. Repeated requests join/report the in-flight check rather than competing downloads.
- Expose `update_checking`, accepted/completed check IDs, and a structured last outcome through backward-compatible snapshot fields. Kotlin holds **Checking…** until the corresponding fresh outcome and activated revision or error arrive; a caller timeout is not reported as cancellation of work that may still finish.
- Close sets a cancelled/closed generation. A worker that finishes late discards its candidate before state mutation or activation; activation and close are serialized over the short final critical section. A replacement backend has separate collaborators and cannot be closed by an old instance.

On every foreground entry, request one manifest check (joining any in-flight check) and resume the while-visible timer. On background, stop scheduling new checks and let an in-flight public request finish or be discarded under the close/generation rules. Do not use Android exact alarms or promise execution while killed. A subsequent foreground entry requests a fresh check even when the hourly interval has not elapsed; manual **Check now** is always available while visible.

**Alternative considered:** keep synchronous checks and increase the Android 10-second timeout. Rejected because it would still stall the single bridge lane and make Cast controls hostage to network conditions.

### 5. Bind Android lifecycle to instances and flush listening conservatively

Return an instance handle from the Android Python start path rather than relying on one global `_bridge` for every request/close. Bind the yt-dlp runner to its owning Lab/bridge as well. Make close idempotent, serialize it behind earlier requests, reject later calls, and ensure an old Activity's close cannot close a new bridge, profile, or updater. This is resource ownership only; receiver detach/close still never stops playback.

Forward the existing `ChaquopyBackend.foreground(active)` signal to the Android catalog on the request lane:

- foreground starts due update scheduling and enables listening observation;
- background stops new scheduled checks, flushes bounded pending listening evidence, and clears the observation baseline;
- close flushes only if not reset, stops updater/listener activity, and prevents late writes.

Keep the existing fresh-source/session/position plausibility checks and five-minute qualification. Do not credit the entire background interval on return: a fresh foreground baseline must be established from read-only receiver evidence. Keep high-frequency position baselines in memory and persist safe observations/aggregates on bounded flushes rather than writing SQLite on every 700 ms poll. A force-stop can lose at most the most recent bounded flush interval; this is preferable to fabricated listening history.

Reset shares the listener lock, clears pending seconds and in-memory/persisted observations, and advances the personal revision. Profile UI generations invalidate late rating/reset responses so an older completion cannot repaint or restore pre-reset intent. Reset is disabled or serialized while a rating write is pending, and its confirmation is read back from the cleared profile.

### 6. Reuse discovery, but give Android its own compact presentation and snapshot boundaries

Reuse `CatalogData`, `DiscoverySnapshot`, `buildDiscovery()`, `visibleDiscoverySections()`, and `PersonalProfile`; do not fork the recommendation algorithm in Android code.

Refactor `PlayerStore` into three distinct flows:

1. **Latest library/profile:** fetched when authoritative revisions change, also used to identify the receiver song;
2. **Visible discovery:** rebuilt on cold store creation, deliberate collapse of full-screen Now playing to Browse, or explicit discovery refresh;
3. **Explicit profile edits:** after confirmed rating/reset, update affected personalized content using the existing exploration seed rather than an unrelated random rotation; Recommended and Recently added stay tied to their ordered catalog inputs.

`PhoneApp` owns the Browse-visit signal. Opening/closing Cast or catalog dialogs is not a new Browse visit. A background catalog activation can update `ui.library` and receiver identification but cannot replace `ui.discovery`. Preserve existing `LazyColumn`/`LazyRow` state and stable item keys.

`PhoneBrowse` uses `discoveryCards(visibleDiscoverySections(...))` in one shared horizontal **Discover** row followed by separate mood shelves. Every card carries its small Recommended, Recently added, For you, or Discover source label, so one recommendation never claims a mostly empty row of its own. Keep the handoff's compact card/mini-player behavior and snapping. A discovery-refresh action uses the shared pending contract and does not interrupt scrolling except for the intentional new snapshot.

Add `PhoneCatalogDialog` for read-only sources, active/latest-observed revisions, last attempted check, Checking/current/app-update-required/error status, **Check now**, explicit discovery refresh, reduced-motion setting, and a confirmed **Clear personal data** action. Do not route Android to `LegacyChannelsDialog`; do not show master revision, rebuild, curator, source refresh, or publication actions. A local bundled/stale catalog is labeled distinctly from a confirmed current online revision.

Add phone rating controls with at least 48 dp touch targets to compact cards and the identified current song in `PhoneNowPlaying`. Nested rating taps must be consumed by the rating control, never bubble into card Play. Hold requested rating values in a per-song pending map with pending colour/text and an explicit not-saved state on failure; a failed request must not silently look confirmed. Other songs remain usable while independent profile writes serialize in the store/backend. Unknown sessions and absent public songs are not rateable. For-you reasons appear in a short two-line card caption without claiming human attention beyond receiver evidence.

The desktop layout, search, curator, and publication flows remain intact; common-model changes require the existing desktop tests and Mac acceptance command.

### 7. Test the real Android runtime, not only host Python

Use three separate evidence layers:

1. **Host Python/common Kotlin:** adapter construction, no master/curation path, schema/bootstrap/migration/recovery, update races/durability/timeout/close, rating/reset/listening, deterministic discovery and phone UI state. Preserve the Mac suites.
2. **Silent rendering and Android instrumentation:** render the production compact `MoozaqApp` with a fake `PlayerBackend` through its existing store constructor; make every receiver mutation throw. Reuse desktop Compose rendering for phone-width interaction screenshots. Add `androidApp/src/androidTest` with the already-declared AndroidX runner/JUnit for real Chaquopy `sqlite3`, read-only/query-only behavior, WAL personal writes, private-file persistence, asset/bootstrap validation, foreground/close and Activity recreation. Use a test-only Python entry point that loads the extracted bridge modules without constructing Lab, discovery, or receiver connections. If Compose Android UI automation is needed, first resolve the test-only artifact against the existing Compose 1.9.1 setup; do not add an unverified version or a production-only test dependency.
3. **Real Android acceptance:** use an isolated test application ID on an arm64 emulator or Pixel so the owner's installed app/profile are not erased. Exercise fresh offline install, existing-JSON upgrade, APK reinstall preserving newer catalog/profile, process/Activity recreation, actual GitHub manifest/redirect/download without credentials, corrupt/hash/old/incompatible candidates, no-network last-good behavior, ratings/reset, and update-without-card-reordering. Existing public revisions can supply a real older-to-current update; local fixtures supply faults and incompatible manifests without a new release.

No test may press Play or issue receiver mutations. Read-only real receiver observation is separately optional and is not evidence of audible reliability. Record host, instrumentation, visual, public-network, and any hardware evidence separately; unrun physical Wi-Fi, long-session, group, and playback checks remain explicitly unproven.

## Risks / Trade-offs

- **Public bootstrap grows the source/APK and can age** → Refresh it deliberately from an immutable public release at app-build boundaries, verify it, and let normal updates deliver newer revisions; never label the snapshot as an online freshness check.
- **Binary bootstrap could accidentally contain private data** → Use only an already published derived asset and matching manifest, enforce public-schema allowlists, inspect the two packaged files, and never copy runtime/master/personal paths.
- **An APK update replaces code while data remains** → Make bootstrap and schema migrations forward-compatible, never blanket-copy assets over `data/`, and test fresh, legacy-upgrade, and newer-data upgrade paths.
- **A network request outlives its caller or Activity** → Return accepted check IDs immediately, separate status/network locks, cancel by generation, discard late candidates, and test close/recreation with a blocked fake response.
- **A corrupt active file destroys the only good fallback** → Validate before any previous-slot replacement, quarantine corrupt files, and recover from a separate validated copy.
- **Foreground scheduling is less frequent than a server scheduler** → Be honest about the boundary and check again on return; do not add a battery-expensive always-on service for catalog freshness.
- **Receiver playback is not proof of human preference** → Require conservative qualified progression, clear the baseline on background, retain truthful explanations, and provide complete local reset.
- **A force-stop can lose unflushed listening credit** → Bound the interval and flush on background/close; never compensate by guessing elapsed listening.
- **Ratings/reset and lifecycle completions can race** → Serialize store writes, use UI generations, synchronize listener reset, and test that no late completion restores erased data.
- **Host tests cannot prove Android SQLite, filesystem, or TLS behavior** → Require real Chaquopy instrumentation and account-free public-network acceptance before marking those parts complete.
- **Shared helper/updater changes can regress the Mac** → Keep the public format and synchronous Mac operations compatible, run all Mac/bridge tests and the real Mac catalog acceptance, and make no Mac release during this change.

## Migration Plan

1. Extract and test shared validation/helpers, narrow the Android Python asset allowlist, and prepare/verify the public bootstrap bundle from an existing immutable release. Confirm that no master/personal/curation artifact enters the APK.
2. Add the Android catalog composition, private bootstrap/recovery and legacy-preserving migration. Inject it explicitly with the actual app version. Verify fresh and upgraded installs before enabling the new phone UI.
3. Add durable, non-blocking update state and foreground/manual scheduling, followed by instance-bound lifecycle/close handling. Exercise slow downloads, older manifests, invalid candidates, and late completion without any receiver calls.
4. Enable compact discovery, read-only catalog information, rating/reset and conservative listening. Verify stable visible shelves and silent interactions with production common UI and fake receiver controls.
5. Run host, desktop/common, Android instrumentation, packaging, and lint checks. Perform isolated real-Android upgrade/network/visual acceptance and record exactly what was proven. Keep the existing validated previous catalog and untouched legacy JSON for local recovery; public rollback remains a new higher release, which is not part of this change.
6. Update Android/user/developer documentation and evidence notes. Do not publish a new catalog, deploy an app/website, alter DNS, or perform audible playback as part of this migration. Any later release requires separate explicit owner approval.
