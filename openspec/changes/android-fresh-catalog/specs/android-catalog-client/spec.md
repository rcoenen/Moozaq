# Spec Delta

## Purpose

Gives the standalone Android app a durable, read-only view of the owner's public catalog, with safe offline bootstrap and verified updates that never require the Mac or expose private catalog and listening data.

## ADDED Requirements

### Requirement: Android consumes only the derived public catalog

The Android app SHALL use the validated public app-catalog format as its ordinary runtime catalog and SHALL open it read-only. The phone SHALL NOT create, open, package, or upload the owner-managed master, and SHALL NOT provide a second authoritative source of curator decisions. Public browsing SHALL retain the minimum confirmed duration of 3,600 seconds.

#### Scenario: Phone has no master authority

- **WHEN** the Android app starts, browses, rates a song, receives an update, or is upgraded
- **THEN** no owner-master database is created or modified and only the validated public projection is used for shared catalog content

#### Scenario: Short or non-public records stay absent

- **WHEN** a public catalog projection omits short, hidden, unavailable, or review-pending master records
- **THEN** the Android app does not recover those records into browsing or editorial sections

### Requirement: Catalog mutation is unavailable on Android

Android SHALL reject channel import or refresh, individual-video curation, source-mode changes, catalog rebuild, master access, and release publication, including requests that bypass the interface. The Android catalog interface SHALL present read-only source information and SHALL NOT offer those owner-only actions.

#### Scenario: Bypassed channel refresh is refused

- **WHEN** a caller requests a channel import or refresh directly through the Android bridge
- **THEN** the request is rejected without downloading channel metadata or changing the public catalog

#### Scenario: Owner controls are not presented on the phone

- **WHEN** the listener opens Android catalog information
- **THEN** no Add channel, Refresh, curator, Rebuild, or Publish release action is available

### Requirement: Fresh installs have a validated offline starting catalog

A fresh Android installation SHALL include a validated snapshot of an existing public catalog release, so its catalog can be browsed before network access is available. The snapshot SHALL contain only public derived data and SHALL be verified before becoming the active catalog. A failed bootstrap SHALL produce an honest catalog error rather than an unverified or fabricated catalog, and SHALL NOT trigger playback.

#### Scenario: First launch without network

- **WHEN** a new installation opens without access to GitHub
- **THEN** the bundled public catalog is browsable read-only and the update status distinguishes the local snapshot from a successful network check

#### Scenario: Bootstrap fails validation

- **WHEN** the bundled candidate is missing, damaged, or incompatible
- **THEN** it is not activated, the failure is visible, and the app neither creates a master nor silently presents legacy records as a new curated release

### Requirement: Upgrades preserve device-local state

APK upgrades and bridge-source replacement SHALL preserve an existing validated active catalog, its previous-version fallback, update state, and personal database. Migrating from legacy JSON SHALL NOT mark all legacy songs as recently added or upload the phone's former catalog to the owner master. Legacy JSON retained for recovery SHALL NOT remain a writable runtime catalog.

#### Scenario: New APK retains newer catalog and profile

- **WHEN** an APK containing an older bundled snapshot is installed over an app with a newer downloaded catalog and an existing personal profile
- **THEN** the newer active catalog and personal profile remain in effect after restart

#### Scenario: Legacy dates remain unknown

- **WHEN** an installation migrates from JSON without actual per-song first-seen evidence
- **THEN** those songs are not presented as newly added on the migration date

### Requirement: Updates use the public manifest and real app compatibility

The Android app SHALL check the established public catalog manifest on foreground entry, periodically while the app is visible, and on explicit listener request, without requiring GitHub credentials. It SHALL use the running application's actual compatibility version and SHALL download only a newer compatible revision from the manifest's immutable tag-specific artifact URL. The highest accepted revision SHALL survive process recreation so a delayed older manifest cannot downgrade the catalog.

#### Scenario: Older manifest after restart is ignored

- **WHEN** a phone previously accepted revision 16, restarts, and then receives a manifest for revision 15
- **THEN** it performs no revision-15 download or activation and retains revision 16

#### Scenario: App update is required

- **WHEN** a manifest advertises a schema or minimum application version that the running Android app does not support
- **THEN** the existing catalog remains active and the interface reports that an app update is required without downloading the incompatible database

#### Scenario: No background schedule is overstated

- **WHEN** the app process is stopped or Android suspends background execution
- **THEN** the app does not claim that hourly checks continue; the next foreground entry performs a new due check

### Requirement: Validation and atomic activation protect the last-good catalog

Every downloaded candidate SHALL pass byte-size, SHA-256, embedded-revision, compatibility, SQLite integrity, foreign-key, public-schema, and public-query validation before activation. Every local recovery candidate SHALL pass embedded-revision, compatibility, SQLite integrity, foreign-key, public-schema, and public-query validation, plus byte-size and SHA-256 checks against its available validated descriptor. Activation SHALL be atomic, retain the previous validated catalog, and prevent a single response from mixing catalog revisions. A corrupt active catalog at startup SHALL cause a validated previous or bundled fallback to be attempted, with the recovered revision and any update error reported honestly.

#### Scenario: Damaged download leaves browsing intact

- **WHEN** a candidate is truncated, has the wrong hash, or fails database validation
- **THEN** the active catalog remains usable and unchanged, the candidate is not activated, and the failure is visible

#### Scenario: Startup recovers a damaged active database

- **WHEN** the active catalog cannot be validated but a compatible previous or bundled copy is valid
- **THEN** the app activates a validated fallback without downloading older remote content or altering personal data

### Requirement: Update work does not block playback state or controls

Manifest retrieval, downloads, validation, and activation SHALL run separately from receiver operations and SHALL NOT hold a lock needed by routine state snapshots. A manual check SHALL remain pending until a fresh update outcome is observed, while repeated requests do not create concurrent checks or duplicate activation. Slow or timed-out update work SHALL leave Stop and other valid receiver controls available and SHALL NOT issue any media, membership, seek, or volume command.

#### Scenario: Slow download does not stall receiver state

- **WHEN** a catalog download takes longer than the ordinary bridge caller timeout
- **THEN** receiver state snapshots and valid control requests continue to be handled without waiting for that download

#### Scenario: Check now confirms the eventual result

- **WHEN** a listener requests Check now while a compatible newer release is available
- **THEN** the action remains pending until the activated revision or a failure is observed, and a second request does not start a competing download

### Requirement: Catalog status is readable and honest

Android SHALL expose the active revision, latest observed revision, last attempted check, in-progress state, compatibility message, and update failure in catalog information. The interface SHALL distinguish a local snapshot or stale last-good data from a confirmed current online revision. Network or validation failures SHALL NOT erase existing content or interrupt the receiver session.

#### Scenario: Offline status does not claim freshness

- **WHEN** the device cannot retrieve a newer manifest
- **THEN** the last validated catalog remains browsable and the panel reports the failed check rather than saying the catalog is up to date
