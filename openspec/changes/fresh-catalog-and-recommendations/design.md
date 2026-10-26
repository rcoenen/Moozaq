# Design

## Context

See `proposal.md` — Why. The current Python `Catalog` stores one merged JSON document per device. The Mac desktop launcher runs `compose-app/bridge/server.py`; Android calls `start_bridge()` through `android_bridge.py`. This change targets the Mac player and updater while leaving the existing Android catalog path functional without a Chaquopy migration.

Mac Python already provides `sqlite3` (SQLite 3.50.4 in the project environment), so no new database package is required. The existing catalog JSON becomes migration input rather than the runtime source of truth. The stdio bridge may continue using JSON-lines messages; that transport is distinct from catalog persistence.

The source repository `rcoenen/Moozaq` is public, the authenticated `gh` client is available, and it currently has no releases. A separate public `Moozaq-catalog` repository will be proposed for catalog artifacts so future application releases cannot interfere with the catalog's `/releases/latest` channel. Repository creation and public releases require explicit owner approval.

## Goals / Non-Goals

**Goals:**

- Give the owner one authoritative `master.db` containing every discovered song and all curator state.
- Produce a separate, validated `app-catalog.db` that the ordinary Mac player opens read-only.
- Distribute derived revisions through GitHub Releases with a small version manifest and verified atomic updates.
- Keep ratings and listening evidence in a third, private `personal.db`.
- Use SQL transactions, constraints, and indexes for catalog management and date-ordered sections.
- Preserve the legacy Android catalog path and add no Android, website, Cloudflare, or deployment work.

**Non-Goals:**

- No shared writable database, network database, Cloudflare D1, or direct SQLite synchronization between devices.
- No Android/Chaquopy implementation or phone UI acceptance in this version.
- No master database in GitHub, no listener accounts, multi-curator model, or cloud taste profiles.
- No required GitHub Actions/self-hosted runner in the first version; the owner's Mac publishes with `gh`.
- No daily editorial expiry, fabricated addition dates, automatic audio download/playback, or exception to the 3,600-second app-inclusion rule.
- No Cast protocol, source-resolution, membership, volume, or playback-state redesign.

## Decisions

### 1. Separate the three database roles

Use three files under the ignored Mac data directory:

```text
YouTube metadata --> master.db (owner read/write, all songs)
                         |
                         | validated projection
                         v
                     app-catalog.db (Mac player read-only)
                         |
                         | optional explicit public release
                         v
                     GitHub Releases --> validated Mac download

Likes/listening ----> personal.db (private, never a catalog source)
```

- `mac-spike/data/master.db` is the only authoritative catalog and source-management store. It includes short, hidden, unavailable, and review-pending records, source evidence, and curator overlays. It is never bundled or uploaded.
- `mac-spike/data/app-catalog.db` is a complete derived snapshot containing only approved public eligible songs and public fields required by the player. The player opens it with SQLite URI `mode=ro` and `PRAGMA query_only=ON`.
- `mac-spike/data/personal.db` contains ratings, listening aggregates, and the last safe observation. It has no signed media URLs and is never read by the catalog publisher or release client.

The ordinary player receives no master write interface. The curator workspace is the only component given the master path and read/write connection. This separation is enforced in code and verified with a write attempt against the app connection, not just by file permissions.

### 2. Make SQLite an explicit desktop-only backend choice

Keep the existing `catalog.py` implementation as the legacy Android path. Add a catalog facade accepted by `Bridge` and inject the new SQLite implementation only from the desktop `server.main()` path. Android's existing `start_bridge()` call retains the legacy default, so no Android file or runtime migration is needed.

New bridge operations check catalog/profile/update capabilities. Legacy mode reports those capabilities as unavailable and keeps the current phone presentation. Shared Kotlin additions use default values for backward-compatible decoding; new curator, update, and discovery controls are exposed only in the desktop experience. `PhoneBrowse.kt`, `ChaquopyBackend.kt`, and website files remain untouched.

### 3. Use explicit schemas, transactions, and date indexes

The master schema has these logical tables:

- `sources`: canonical channel identity, URL, display name, category fallback, automatic/review mode, origin, last automatic attempt, last success, and error state.
- `songs`: video ID primary key, nullable channel identity, public source metadata, automatic category, duration, availability, missing-read evidence, and first-seen/last-seen/source-publication evidence.
- `curation`: video ID, hidden/restored state, nullable category override, review approval, and nullable recommendation timestamp.
- `master_state`: schema/master revisions, dirty-publication state, and last successful generation metadata.

All timestamps used for ordering are nullable UTC epoch-seconds integers. They sort correctly and can be serialized as ISO timestamps at the bridge boundary. Unknown legacy dates remain null. Foreign keys are enabled, video IDs and canonical channel identities are unique, and constrained enums are checked where practical.

Add indexes for channel identity, effective browsing category, first-seen descending, recommendation descending, review/publication eligibility, and duration. Dynamic management sorting uses a fixed allowlist of columns; all values are parameterized.

Use `PRAGMA user_version` for migrations. Master and personal databases use WAL, a busy timeout, short transactions, and a single serialized writer lane with per-operation readers. A source refresh merges metadata and evidence in one transaction while preserving the separate curator overlay. No polling loop writes on every read.

### 4. Generate a portable read-only app database

A publication builder reads one consistent master snapshot and creates a new temporary SQLite file. It never exposes the master connection to the player. The generated schema contains only:

- Public source and song fields needed for artwork, title, creator, duration, effective category, channel identity, first-seen, source-publication, and recommendation presentation.
- A `publication` table with app schema version, app revision, source master revision, and generation time.
- Indexes for first-seen/recommendation ordering, category, and channel.

The projection requires confirmed duration of at least 3,600 seconds, review approval, not hidden, and no explicit unavailable status. Missing/unknown availability alone does not erase a song. Only this projection is copied; master-only review/error fields, personal data, credentials, and signed URLs cannot appear.

Build steps:

1. Read a consistent master snapshot and select rows in deterministic video-ID order.
2. Create the candidate with a portable rollback-journal mode, populate the public schema and indexes, and record the source master revision.
3. Close the candidate, ensure no WAL/SHM sidecar is required, then run `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, schema/field allowlist checks, and expected-count checks.
4. Reopen the candidate read-only and verify the public queries. Retain the previous app database as `app-catalog.previous.db`.
5. Atomically replace `app-catalog.db` only after all checks pass. Delete failed candidates without touching the active app database.

A master mutation increments its revision and marks the public projection dirty. One debounced background builder regenerates the local app database after accepted curator changes or approved source updates. Review-only changes do not publish anything. An explicit Rebuild action is available for recovery. If generation fails, the master keeps its changes, the previous app catalog remains active, and management shows the unpublished master revision and error rather than claiming success.

The app catalog reader detects atomic replacement using file identity/mtime/size and reopens the new file read-only. Existing readers may finish on the old file; no mixed half-old/half-new catalog response is allowed. Downloaded candidates use this same validation and activation pipeline.

### 5. Publish and discover revisions through GitHub Releases

Use a dedicated public `Moozaq-catalog` repository for catalog-only releases. Each immutable release tag is `catalog-v<revision>` and contains `app-catalog.db` plus `catalog-manifest.json`. Keeping catalog releases separate from source/application releases makes `/releases/latest/download/catalog-manifest.json` a stable update channel.

The manifest contains:

- `revision`, `schema_version`, and `min_app_version`;
- `generated_at`;
- the exact tag-specific `download_url`;
- database `size` and `sha256`.

A new `catalog_release.py` accepts only an already validated generated database, computes its size/hash, writes the manifest, and verifies that manifest and embedded publication revisions match. Public release creation is always an explicit owner action through authenticated `gh`; a refresh, curator edit, or local generation never publishes to GitHub automatically. Repository creation and each release require owner approval. GitHub Actions can later automate validation of derived artifacts, but is not needed for the update signal and must not receive the master in this version.

A new `catalog_update.py` checks the manifest on startup and hourly while open, with short timeouts and conditional requests when supported. It compares the manifest revision, schema, and minimum app version with the active catalog. Equal/older revisions and incompatible schemas are not downloaded. An explicit Check now action is available.

For a newer compatible revision, stream the exact manifest `download_url` to a temporary file with a strict size cap. Do not follow a second “latest” alias for the database: the tag-specific URL binds the candidate to the original manifest even if another release appears during download.

Validate byte size, SHA-256, embedded revision, schema compatibility, SQLite integrity, foreign keys, public schema, and public queries before entering the existing atomic activation path. Persist the highest accepted revision and reject delayed older manifests. Roll back by publishing previous validated content as a new higher revision, never by requiring clients to downgrade.

SHA-256 verifies artifact integrity, not publisher identity. The first version relies on HTTPS, owner-controlled GitHub write access, and the public manifest for distribution trust; optional signed manifests are future hardening, not a claim made by this design.

### 6. Keep source import and curator operations in the master workspace

Use the existing flat-playlist channel import for metadata-only full-channel refreshes. Seeded channels migrate in automatic mode to preserve current behavior; newly followed sources default to review. Refresh stores last-attempt and last-success separately and schedules the next automatic attempt at least 24 hours later, including after failure.

For one video, use oEmbed plus the allowlisted public watch-page meta parser established during planning. It obtains public title, creator, author URL, artwork, duration, and publication dates without yt-dlp single-video format extraction or persisted streaming data. Unknown duration stays an ineligible master draft until flat channel metadata supplies it. The supplied `VHS Dreams` example is eligible using its observed `PT182M54S` duration.

Following a channel is independent of recommending one video. Video ID is the merge key, and curator fields survive later source refreshes and canonical channel alias deduplication. Effective category is the manual override when present, otherwise automatic classification.

### 7. Let SQL supply ordered catalog candidates and keep the visible UI stable

The Mac catalog facade uses read-only SQL queries for management and for the canonical ordered inputs:

- Recommended: eligible rows with a recommendation timestamp, descending, limit 12.
- Recently added: eligible rows with non-null first-seen, descending, limit 20.
- Mood and For you candidates: the public eligible rows with effective category and public metadata.

The bridge returns ordered IDs/rows plus app/master revision, latest released revision, last update check, and stale/error status. The Kotlin UI holds a separate visible discovery snapshot so recurring polls, local generation, downloads, and activation do not move visible cards. A cold app start, Browse re-entry, or explicit discovery refresh takes a new snapshot. Recently added changes only at those boundaries.

The desktop search hides unrelated special shelves until cleared. The persistent receiver player uses the latest catalog for source identification even while visible browse order is frozen. Curator, generation, release, and update actions remain pending until their relevant authoritative revision is observed; failures remain explicit.

### 8. Store personal data independently in `personal.db`

The personal schema contains:

- `ratings`: video ID primary key, up/down rating, and update timestamp.
- `listening`: video ID primary key, qualified seconds, qualified-session count, and last-listened timestamp.
- `observation`: the latest safe public video ID, receiver media-session identifier, position, and observation time.
- `personal_state`: schema and personal revision.

There are no cross-file foreign keys to either catalog database. Ratings, aggregates, and reset operations commit in short transactions and advance `personal_revision`. The bridge exposes only a local profile response to Kotlin; neither database publisher, manifest, nor release client can include this data.

Desktop rating controls must not bubble into card Play. The full-screen player can rate an identified app-catalog song; unknown external sessions are not rateable. Reset erases ratings, aggregates, and the safe observation and returns recommendations to honest general discovery.

### 9. Keep listening evidence and recommendation logic independent of storage

A read-only listening tracker uses fresh synchronized receiver evidence already present in the bridge. It requires a recognized app-catalog source, `PLAYING`, a media-session ID, and advancing position. Consecutive observations must have the same source/session and a nonnegative position delta plausible against elapsed wall time plus a small tolerance; accepted credit never exceeds elapsed wall time.

Reset the baseline on pause, idle, stale evidence, source/session changes, backward seeks, and implausible forward jumps. Signed-link renewal cannot manufacture listening time. Same-session reconnect progression may count. Five accumulated verified minutes become a qualified listen. Flush periodically and on close, not on every poll; no receiver command is introduced.

For you remains a pure shared Kotlin ranking function over app-catalog candidates and the local profile. Likes outweigh capped/decayed listening affinity, dislikes are excluded and reduce similar items, previously listened items are downranked, and roughly one quarter of the shelf is stable-seeded exploration. Limit a channel to two items before backfilling when alternatives exist. Explanations cite only real contributing likes/listens. With no positive evidence, label the shelf **Discover**.

## Risks / Trade-offs

- **Master and app revisions diverge after a failed build** → Retain the previous app database, expose the unpublished master revision/error, and allow explicit rebuild; never silently roll back curator changes.
- **The master or personal data could accidentally be released** → Keep them in separate ignored files, inject the master only into the curator workspace, use public schema allowlists, and inspect release assets before upload.
- **Read-only enforcement could be bypassed by architecture rather than SQLite** → Give the player no master connection, use `mode=ro` plus `query_only`, and test attempted app writes.
- **Replacing a SQLite file with active readers can mix revisions** → Build/download a complete candidate, atomically rename, fingerprint replacements, and reopen only between catalog responses.
- **GitHub's latest channel can be delayed or change during a download** → Use the latest alias only for the small manifest, bind every candidate to its manifest's immutable tag URL, and never promise instantaneous global visibility.
- **Application and catalog release channels could collide** → Use a dedicated catalog repository rather than the source repository's latest release.
- **A manifest and database can both be maliciously replaced if publisher access is compromised** → Treat SHA-256 as integrity only, keep GitHub write access owner-controlled, and consider signed manifests as later hardening.
- **GitHub or the network can be unavailable** → Keep the last validated app catalog, expose stale/update errors, and never block browsing or playback on an update.
- **Concurrent bridge/refresh/listener work can contend for SQLite** → Use WAL, busy timeouts, short transactions, one writer lane, and infrequent listening flushes.
- **Legacy first-seen dates are unknowable** → Leave them null rather than fabricating freshness.
- **Receiver evidence can be ambiguous** → Prefer missed listening credit over false affinity and never infer human attention beyond receiver activity.
- **A migration defect could damage the existing catalog** → Back up JSON first, use transactional migrations and integrity checks, and retain a launcher-level legacy rollback path until acceptance is complete.

## Migration Plan

1. Back up `channel-catalog.json` and record source/song counts. Create `master.db` with `user_version` migrations, import seeds/starters and existing records, and preserve unknown first-seen dates as null. Keep the original JSON untouched as rollback input.
2. Add the desktop-only SQLite catalog injection while keeping Android's legacy default. Generate and validate the first `app-catalog.db`, then run the Mac player read-only against it and compare catalog counts/identities.
3. Add master curation, individual-video import, freshness scheduling, and debounced local generation. Verify failure paths preserve both the master and previous app catalog.
4. Add the stable Mac discovery UI and `personal.db`, followed by ratings/reset and mocked receiver-listening validation without starting audio.
5. After explicit owner approval, create the dedicated public `Moozaq-catalog` repository and publish the first validated derived database and manifest with `gh`. Verify account-free manifest/artifact reads and inspect that no master or personal artifact was uploaded.
6. Publish a subsequent revision and verify Mac discovery, download validation, atomic activation, stable browsing, and offline fallback. Test rollback by publishing previous content under a new higher revision.
7. Exercise the `VHS Dreams` individual recommendation and later Silent Depths channel deduplication end to end. Record exactly which migration, visual, metadata-network, update, and read-only checks ran.
8. For local rollback, restore the previous generated app database or switch the desktop launcher to the preserved legacy JSON path. For published rollback, release previous validated content under a new higher revision. Do not delete the master, JSON backup, personal database, or previous app catalog during rollback.
