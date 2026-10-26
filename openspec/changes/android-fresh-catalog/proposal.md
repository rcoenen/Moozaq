# Proposal

## Why

The standalone Android app still browses and refreshes a device-local legacy JSON catalog, while the Mac now has the owner's curated catalog, truthful freshness, personal recommendations, and verified GitHub Releases updates. Android needs the same fresh discovery experience without creating a second catalog authority, requiring a Mac at runtime, or weakening the existing independent Cast session.

## What Changes

- Replace Android's ordinary runtime catalog with the same validated, derived `app-catalog.db` format used by the Mac. The phone opens it read-only and never opens, creates, packages, or uploads the owner-managed `master.db`.
- Keep owner curation, source refresh, generation, and publication on the Mac. Android exposes read-only source/catalog information and explicitly rejects legacy channel mutation, curation, rebuild, and release operations rather than merely hiding those controls.
- Bootstrap a fresh installation from a validated snapshot of an existing public catalog release so browsing is useful before the first network check. Preserve the active catalog and personal data across APK upgrades; retain the legacy JSON only as private migration/rollback evidence, not as a second writable catalog.
- Reuse the established public `rcoenen/Moozaq-catalog` manifest and immutable release artifacts. Check on foreground entry, periodically while visible, and on explicit request, using the actual Android app version for compatibility. Do not claim background execution after process death or exact hourly scheduling under Android power management.
- Make update checks non-blocking for receiver state and controls, single-flight, size/hash/schema/integrity validated, and atomic with a previous validated catalog retained. Preserve the highest accepted revision across restarts and keep the last-good catalog on network or validation failure.
- Add one compact Android discovery row ahead of mood browsing, with **Recommended**, **Recently added**, and **For you**/**Discover** items in that order and a small source label on every card. Sparse sections must not claim separate mostly empty rows. Reuse the Mac's ordered catalog inputs and shared ranking/snapshot logic; background updates must not reorder visible cards or reset scrolling.
- Add phone-local thumbs-up/down, concise recommendation reasons, and a confirmed reset for personal data. Store ratings, qualified receiver-listening evidence, and safe observations in an Android-private `personal.db`, excluded from backup and never included in catalog artifacts or sent to the Mac or GitHub.
- Preserve the existing Chaquopy Cast engine, silent browsing/reconnection, independent receiver playback, playback controls, and one-hour public eligibility rule. No catalog update, rating, reset, migration, or acceptance test may start audio.

## Capabilities

### New Capabilities

- `android-catalog-client`: Android-only bootstrap, migration, read-only catalog access, private storage, update signaling, validated activation, lifecycle, and last-good recovery using the established public distribution channel.
- `android-fresh-discovery`: Compact phone presentation of editorial and recently added items, stable discovery boundaries, local ratings/listening personalization, truthful explanations, and privacy-preserving reset.

### Modified Capabilities

None. `android-cast-recovery` and `android-route-selection` describe the parked Google Cast SDK implementation and are not changed. The completed Mac change `fresh-catalog-and-recommendations` remains the source of the shared catalog format and discovery behavior; this change adds Android clients rather than redefining its owner workflows.

## Impact

- New thin Android composition around existing `AppCatalog`, `PersonalStore`, `ListeningTracker`, and `CatalogUpdater` in `compose-app/bridge/`; no `DesktopCatalog`, master, ingest, curator, or `gh` publisher on the phone.
- Explicit catalog/profile/update injection through `android_bridge.py` and `server.start_bridge()`, plus guarded legacy catalog mutation dispatch. Shared updater fixes may benefit the Mac but must retain its release and curation behavior.
- `ChaquopyBackend.kt` supplies app-private paths and `BuildConfig.VERSION_NAME`, installs validated bootstrap data without overwriting existing databases on upgrades, and coordinates foreground/close lifecycle without a stale close affecting a replacement backend.
- `PhoneBrowse.kt`, a phone-specific read-only catalog dialog, compact rating controls, and shared `PlayerStore`/discovery models. The Mac layout and parked SDK Android app remain outside the feature work.
- A reproducible, checksum-verified public bootstrap snapshot derived from an existing catalog release; no master, personal database, credentials, or signed media URL is packaged. No new runtime database, account, backend, Cloudflare, or background-worker dependency is proposed.
- Host Python and shared Kotlin tests, new silent Android instrumentation/rendering tests with a fake-backend injection seam, and separate real-device Chaquopy/SQLite/storage/network acceptance. Existing test dependencies will be reused where possible; any required Android Compose test artifact must use the project's existing Compose version.
- Updates to `compose-app/androidApp/README.md`, `compose-app/README.md`, root `AGENTS.md`, `docs/current-direction.md`, and `docs/spike-results.md` during implementation. No website, DNS, source-repository publication, catalog publication, app-store deployment, or audible hardware test is authorized by this plan.
