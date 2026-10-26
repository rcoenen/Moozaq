# Spec Delta

## Purpose

Provides one owner-managed master catalog containing every discovered song and produces validated, read-only app catalogs for the Mac without exposing master or personal data.

## ADDED Requirements

### Requirement: One owner-managed master catalog

The system SHALL maintain one authoritative master database that only the owner's curator workspace can modify. The master SHALL retain every discovered song record, including hidden, short, unavailable, and review-pending items. The ordinary Mac player SHALL NOT open the master for writing or treat it as its runtime catalog.

#### Scenario: Player cannot edit the master

- **WHEN** the ordinary Mac player browses, plays, rates, or receives a catalog update
- **THEN** it does not modify the owner-managed master catalog

#### Scenario: Ineligible songs remain in the master

- **WHEN** a discovered song is short, hidden, unavailable, or awaiting review
- **THEN** its record and available evidence remain in the master even though it is excluded from the generated app catalog

### Requirement: Bounded freshness updates

The owner curator workspace SHALL automatically refresh due source metadata when it is running, with no automatic attempt more than once per source in a 24-hour period. The owner SHALL also be able to refresh a source or all sources on demand. Refreshing SHALL update the master without silently publishing unapproved changes to the app catalog.

#### Scenario: Due source is refreshed once

- **WHEN** a source's last automatic attempt is at least 24 hours old and the curator workspace starts
- **THEN** the system refreshes that source's metadata and does not automatically retry it within the next 24 hours

#### Scenario: Manual refresh remains available

- **WHEN** the owner requests a source refresh before its automatic interval has elapsed
- **THEN** the system performs a metadata-only refresh without starting playback

### Requirement: Truthful catalog dates

The master SHALL preserve the original first-seen timestamp for each video ID, update last-seen evidence after successful source reads, and store source publication dates only when supplied by the source. Existing records whose first-seen date is unknown SHALL remain unknown rather than being assigned the migration date.

#### Scenario: Refresh does not reset addition date

- **WHEN** a song already present with a first-seen timestamp is returned by a later source refresh
- **THEN** its first-seen timestamp is unchanged and its last-seen evidence is updated

#### Scenario: Legacy date remains unknown

- **WHEN** the master is migrated from a format without per-video addition dates
- **THEN** existing songs are not all displayed as newly added at migration time

### Requirement: Individual video curation

The owner SHALL be able to paste one supported YouTube video URL, retrieve its public metadata without resolving audio, and review it before adding or recommending it. Following the containing channel SHALL be a separate action. Following the channel SHALL NOT be required before the individual eligible video can be retained, and a later channel import SHALL merge with that video by video ID rather than duplicate it.

#### Scenario: Video and optional channel are distinct choices

- **WHEN** the owner reviews an eligible individual video and recommends it without following its channel
- **THEN** that video is retained in the master with its editorial decision and no new channel source is added

#### Scenario: Later channel import merges the featured video

- **WHEN** the owner later follows the channel and its import returns the same video ID
- **THEN** the existing master record and editorial decision are updated rather than duplicated

### Requirement: Persistent curator control

The owner SHALL be able to recommend or un-recommend an eligible song, hide or restore it, override its category, and choose whether a source publishes new items automatically or places them in review. Later imports and channel/video deduplication SHALL preserve those decisions. Editorial recommendations SHALL NOT expire daily or require a replacement.

#### Scenario: Import preserves a manual category

- **WHEN** a source refresh returns a song whose category was manually overridden
- **THEN** the manual category remains effective and the refreshed automatic classification does not replace it

#### Scenario: Recommendation has no daily deadline

- **WHEN** the owner recommends a song and no further editorial action occurs
- **THEN** it remains editorially recommended in subsequent generated app catalogs until the owner removes the recommendation

#### Scenario: Review source holds new items

- **WHEN** a source configured for review returns a previously unseen eligible song
- **THEN** the song is retained in the master for review and is not included in the generated app catalog before approval

### Requirement: Generated app catalog is derived and read-only

The system SHALL generate a versioned app database from the master containing only approved, publicly browsable songs and the public fields required by the Mac app. The app database SHALL be built and validated as a separate complete file before atomically replacing its predecessor. The Mac app SHALL open only that derived database in read-only mode and SHALL NOT modify its contents.

#### Scenario: Publication excludes non-public master records

- **WHEN** the master contains approved public songs alongside hidden, short, unavailable, and review-pending songs
- **THEN** the generated app database contains only the approved public eligible songs and required public metadata

#### Scenario: App database rejects writes

- **WHEN** the Mac player attempts any operation that would write to its derived app catalog
- **THEN** the read-only catalog remains unchanged and no master record is affected

#### Scenario: Failed generation preserves the previous app catalog

- **WHEN** generation or validation of a new app database fails
- **THEN** the previous validated app database remains available and the failed candidate does not replace it

### Requirement: Last-good master and availability retention

The system SHALL retain the last good master and app catalog after refresh or generation failures. A single failed read or a song's absence from one successful source response SHALL NOT delete it or establish permanent unavailability. Availability status SHALL distinguish unknown, missing, and explicitly unavailable evidence.

#### Scenario: Failed refresh preserves the catalog

- **WHEN** a source refresh fails after a usable master and app catalog exist
- **THEN** both remain usable and the stale/failed status is visible in catalog management

#### Scenario: One absence does not remove a song

- **WHEN** a previously imported song is absent from one otherwise successful source response
- **THEN** its master record and curator decisions are retained and it is not declared permanently unavailable

### Requirement: Existing browsing and playback constraints remain intact

Generated app inclusion and editorial eligibility SHALL retain the confirmed minimum duration of 3,600 seconds. Catalog refresh, curation, generation, rating, and browsing SHALL NOT resolve a signed media URL or initiate playback.

#### Scenario: Short video is not silently promoted

- **WHEN** a source returns a video with a confirmed duration below 3,600 seconds
- **THEN** its metadata remains in the master but it is excluded from the app catalog and editorial recommendations

#### Scenario: Curating is silent

- **WHEN** the owner recommends a song, refreshes metadata, or generates an app catalog
- **THEN** no media load, play, resume, seek, or volume operation is issued
