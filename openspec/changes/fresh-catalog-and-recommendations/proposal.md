# Proposal

## Why

Moozaq's catalog is refreshed manually and its browse screen repeatedly presents the same mood-grouped inventory, so even a large catalog can feel stale. The owner wants one authoritative, owner-managed master database containing every discovered song, a read-only app database produced from it, and a simple way to signal and distribute new catalog revisions without making the master public.

## What Changes

- Focus this change on the Mac app. Android, Chaquopy migration, Cloudflare publication/deployment, and cross-device synchronization or acceptance are deferred to a later change. GitHub Releases distribution to the Mac is included.
- Migrate the existing JSON catalog into one private `master.db`, managed only by the owner. The master retains all discovered records, including hidden, short, unavailable, and review-pending items, together with source evidence and curator decisions.
- Add bounded metadata refresh, truthful first-seen/last-seen/publication dates, individual-video review with optional channel following, persistent recommendation/hide/category controls, and automatic-versus-review source behavior in the Mac curator workspace.
- Generate a separate versioned `app-catalog.db` from the master. It contains only approved, publicly browsable records and the fields the Mac app needs. Build and validate it as a new SQLite file, then replace the previous app catalog atomically; the app opens it read-only and can never edit the master.
- Publish validated app-database revisions as GitHub Releases in a dedicated public `Moozaq-catalog` repository. Each release contains the derived database and a small manifest with revision, schema/compatibility, tag-specific download URL, size, and SHA-256. The private master, personal database, and owner-only records are never uploaded.
- Initially publish from the owner's Mac with the already available authenticated `gh` client; GitHub Actions remains an optional later automation. Creating the public catalog repository and publishing releases require separate explicit owner approval.
- Let the Mac check the manifest on startup and periodically, download only a newer compatible revision, verify it completely, and atomically activate it while retaining the previous database. Failed downloads or validation leave the current catalog usable. A rollback republishes previous content under a new higher revision.
- Keep JSON as a legacy migration/import and small manifest format, not as the runtime catalog database. The derived SQLite database remains the Mac app's catalog input.
- Store likes, qualified listening signals, and resettable personal state in a separate `personal.db`; none of it enters the master, generated app catalog, manifest, or GitHub release.
- Put **Recommended**, **Recently added**, and **For you** ahead of the Mac mood shelves. Recommended persists without daily expiry, Recently added uses real first-seen dates, and For you uses local likes and confirmed receiver progression.
- Rotate discovery only at a new browsing/app session or explicit refresh. Background polling, catalog downloads, and database replacement must not reorder visible cards, and older rotated items must not be labeled as new.
- Preserve the confirmed one-hour minimum for app inclusion and editorial eligibility, silent browsing, independent receiver playback, and existing playback controls. Catalog work must not resolve or play audio.

## Capabilities

### New Capabilities

- `curated-catalog`: The owner's authoritative master catalog, source freshness and curator decisions, and validated generation of a read-only Mac app database.
- `catalog-distribution`: GitHub Releases publication, manifest-based update signaling, and verified atomic download/activation of derived app databases without exposing the master or personal data.
- `fresh-discovery`: Editorial recommendations, truthful Recently added presentation, and stable discovery rotation in the Mac app without misleading freshness claims or visible reordering.
- `personal-recommendations`: Mac-local likes, confirmed listening signals, explainable For you ranking, and private reset behavior.

### Modified Capabilities

None. The existing Android Cast specifications cover parked behavior and are not changed by this Mac catalog work.

## Impact

- A new SQLite-backed Mac catalog path in `compose-app/bridge/`, with one-time migration from `mac-spike/data/channel-catalog.json` and preservation of the legacy Android catalog path.
- Separate owner-master, generated-app, and personal database files under the ignored Mac data directory; the master is never bundled into the ordinary app or uploaded to GitHub.
- GitHub Releases publication and update-client code, a versioned manifest, and a dedicated public `Moozaq-catalog` repository. Repository creation and public releases are external actions requiring explicit approval.
- Desktop catalog-management, browse, update-status, rating, and discovery UI plus backward-compatible shared Kotlin models. Phone-specific UI and Android runtime migration are not part of this change.
- SQLite schema migrations, transactional imports, indexed publication queries, generated-database and download integrity checks, and silent Python/Compose tests.
- Documentation and validation for the Mac-only architecture and GitHub distribution. No website, Cloudflare, DNS, deployment, or Cast protocol changes.
