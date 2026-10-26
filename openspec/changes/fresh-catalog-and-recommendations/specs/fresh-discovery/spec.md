# Spec Delta

## Purpose

Makes the Mac browse experience feel fresh through truthful new additions, durable editorial picks, and controlled discovery rotation while keeping the visible interface stable and predictable.

## ADDED Requirements

### Requirement: Discovery sections lead Mac browsing

Mac browsing SHALL present non-empty **Recommended**, **Recently added**, and **For you** sections ahead of the mood shelves, in that order. These sections SHALL reuse the existing silent mix-selection behavior and SHALL NOT interrupt the receiver session.

#### Scenario: New content is immediately distinguishable

- **WHEN** a user opens Mac Browse with editorial, newly added, and personalized items available
- **THEN** those three sections appear before the mood shelves in the specified order

#### Scenario: Browsing does not change playback

- **WHEN** discovery sections update while an existing receiver session is active
- **THEN** the current playback session is not loaded, resumed, paused, stopped, or transferred

### Requirement: Editorial recommendations persist without a cadence

The **Recommended** section SHALL contain owner-selected songs, with the most recently selected editorial choice presented first. It SHALL retain those items until the owner changes their recommendation state; no daily replacement or expiry SHALL be imposed. A recommended song that later arrives through a channel import SHALL remain one catalog item rather than a duplicate.

#### Scenario: Irregular editorial updates are valid

- **WHEN** the owner adds no new recommendation for several weeks
- **THEN** the existing recommendation remains valid without being labeled as new or expiring automatically

#### Scenario: Featured import is deduplicated

- **WHEN** a video already editorially recommended is imported from its channel
- **THEN** browsing contains one logical song with both its import metadata and editorial decision

### Requirement: Recently added uses actual addition evidence

**Recently added** SHALL sort eligible app-catalog songs by their known first-seen timestamp, newest first, and SHALL be limited to a bounded leading set. Items with unknown first-seen timestamps SHALL NOT be presented as recently added. Source publication dates MAY be displayed separately but SHALL NOT be substituted for Moozaq addition dates.

#### Scenario: Legacy catalog is not mislabeled

- **WHEN** all legacy items have unknown first-seen timestamps and no later additions exist
- **THEN** the system does not populate Recently added by pretending those items were just added

#### Scenario: New import leads the section

- **WHEN** a later master import discovers and publishes a previously unseen eligible song
- **THEN** that song precedes older first-seen items in Recently added after the next visible-content boundary

### Requirement: Rotation never destabilizes visible browsing

Discovery order SHALL remain stable during a Mac browsing session and across background state polls. Reordering or rotating content SHALL occur only at a new browsing session, a new app session, or an explicit user refresh. An incoming app-catalog revision SHALL NOT move cards that are already visible.

#### Scenario: Background poll preserves position

- **WHEN** a receiver or catalog status poll completes while the user is reading a discovery row
- **THEN** the row's item order and scroll position remain unchanged

#### Scenario: Next session can offer a different discovery

- **WHEN** enough eligible unexplored alternatives exist and the user starts a new app session
- **THEN** the system may rotate the discovery selection without changing items labeled Recently added

### Requirement: Rotated older content has honest framing

Older catalog items selected for variety SHALL be presented as discovery or personalized content, not as newly added or newly published. The system SHALL favor eligible items without a dislike and without sufficient prior exposure where evidence is available.

#### Scenario: Old unseen mix is not called new

- **WHEN** rotation selects an old catalog item that the local listener has not meaningfully explored
- **THEN** it appears as discovery content without a Recently added or new-publication claim

### Requirement: Search takes precedence over discovery shelves

When a Mac search query is active, browsing SHALL show matching catalog results without unrelated editorial, recently added, personalized, or rotating discovery shelves. Clearing the query SHALL restore the discovery layout without changing playback.

#### Scenario: Search does not leave unrelated shelves

- **WHEN** a user enters a query that matches only one mood
- **THEN** unrelated discovery sections are hidden and only relevant results are presented
