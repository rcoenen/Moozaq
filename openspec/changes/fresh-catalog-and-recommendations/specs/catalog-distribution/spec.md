# Spec Delta

## Purpose

Distributes owner-approved app database revisions through GitHub Releases and lets the Mac discover, verify, and activate updates without exposing the master or personal data.

## ADDED Requirements

### Requirement: Only derived public catalogs are released

Catalog releases SHALL be published in a dedicated public catalog repository and SHALL contain the validated derived app database plus its manifest. The private master database, personal database, hidden/review-only records, credentials, and signed media URLs SHALL NOT be included. Only the owner's authorized publication process SHALL create catalog releases; public readers SHALL NOT need a GitHub account.

#### Scenario: Release contains only public artifacts

- **WHEN** the owner publishes a derived app-catalog revision
- **THEN** the release contains the validated app database and manifest without the master, personal data, credentials, or non-public master records

#### Scenario: Public update check needs no account

- **WHEN** a Mac without GitHub credentials checks the public catalog manifest
- **THEN** it can read the manifest and referenced public release artifact without authenticating

### Requirement: Manifest is the catalog update signal

Each release SHALL provide a manifest containing a monotonically increasing app revision, app schema version, minimum compatible app version, generation time, tag-specific database URL, byte size, and SHA-256. The Mac SHALL check the manifest on startup and periodically while open. It SHALL download only a revision newer than its active compatible revision and SHALL NOT treat a release title or timestamp alone as the update signal.

#### Scenario: Current revision causes no download

- **WHEN** the manifest revision and schema match the already active app catalog
- **THEN** the Mac performs no database download and retains its current catalog

#### Scenario: Newer compatible revision is fetched

- **WHEN** the manifest advertises a newer revision compatible with the running app
- **THEN** the Mac downloads the exact tag-specific database referenced by that manifest for validation

#### Scenario: Incompatible revision is not activated

- **WHEN** the manifest requires a newer app schema or application version than the running client supports
- **THEN** the current catalog remains active and the client reports that an app update is required

### Requirement: Download validation precedes atomic activation

The Mac SHALL download a candidate to a separate temporary file and SHALL verify its byte size, SHA-256, embedded publication revision, schema compatibility, SQLite integrity, foreign-key consistency, public schema, and expected public queries before activation. Activation SHALL atomically replace the active app database and retain the previous validated database. The app SHALL open the activated database read-only.

#### Scenario: Hash mismatch preserves current catalog

- **WHEN** a downloaded candidate does not match the manifest hash
- **THEN** the candidate is rejected before activation and the current app database remains unchanged

#### Scenario: Manifest changes cannot mix releases

- **WHEN** a newer release becomes latest while an older manifest's database is still downloading
- **THEN** the in-flight candidate remains bound to the original manifest's tag-specific URL and cannot be presented as the newer revision

#### Scenario: Activated catalog is read-only

- **WHEN** a downloaded candidate has passed every check and is activated
- **THEN** the Mac uses it through the same read-only enforcement as a locally generated app catalog

### Requirement: Rollbacks move revision forward

The Mac SHALL NOT replace its active catalog with an equal or older manifest revision. To restore previous content, the owner SHALL publish that content as a new higher app revision so normal update checks can accept it.

#### Scenario: Older manifest is ignored

- **WHEN** a cached or delayed manifest advertises a revision lower than the active catalog
- **THEN** the Mac does not download or activate it

#### Scenario: Previous content is restored safely

- **WHEN** the owner needs to reverse a published catalog change
- **THEN** the previous validated content is released with a new higher revision and clients activate it through ordinary validation

### Requirement: Update failures preserve honest last-good behavior

A manifest or database network failure, timeout, malformed response, or validation error SHALL NOT remove or corrupt the active app catalog. The Mac SHALL retain the last validated revision and expose stale/update-error status in catalog management without interrupting playback or reordering visible discovery cards.

#### Scenario: GitHub is unavailable

- **WHEN** the Mac cannot retrieve a manifest or candidate during an update check
- **THEN** the last validated catalog remains browsable and the update failure is visible without a playback command

#### Scenario: Background update does not jump the UI

- **WHEN** a new database is activated while the user is browsing
- **THEN** its content becomes visible only at the next defined discovery boundary and the current card order remains stable
