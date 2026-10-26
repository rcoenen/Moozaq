# Spec Delta

## Purpose

Provides explainable personal recommendations in the Mac app from explicit ratings and real listening evidence while keeping the listener's taste history in a separate private database.

## ADDED Requirements

### Requirement: Persistent local ratings

The listener SHALL be able to give an app-catalog song a thumbs-up or thumbs-down, change or clear that rating, and have it persist across app restarts. Ratings SHALL remain in the local personal database on the current Mac in this version. Rating a song SHALL NOT modify either catalog database or alter playback.

#### Scenario: Rating survives restart

- **WHEN** a listener likes a song and later reopens the Mac app
- **THEN** the same like is visible and contributes to local recommendations

#### Scenario: Rating is silent and does not edit the catalog

- **WHEN** a listener changes a thumbs-up or thumbs-down rating
- **THEN** no media operation is issued and neither the master nor generated app catalog changes

### Requirement: Listening evidence comes from receiver state

A qualified listening signal SHALL require confirmed receiver playback of an identified app-catalog source and advancing receiver position. A card tap, pending start, failed load, paused session, seek, or signed-URL refresh alone SHALL NOT count as listening. Position continuity across a reconnection MAY contribute only when source and session identity match and the elapsed progression is plausible; seeks SHALL NOT be counted as listening time.

#### Scenario: Accidental start does not create a preference

- **WHEN** a listener taps a song but playback is not confirmed or its position does not advance
- **THEN** no positive listening signal is recorded

#### Scenario: Independent receiver playback can be recognized

- **WHEN** the app reconnects to the same identified session and fresh receiver evidence shows plausible position advancement
- **THEN** the confirmed progression may contribute to the local listening signal without reloading the source

### Requirement: For you ranking is explainable and varied

**For you** SHALL rank eligible items using local ratings and qualified listening affinity for category, creator, and channel. Explicit likes SHALL have greater influence than passive listening. Thumbs-down songs SHALL be excluded, and the same negative signal SHALL reduce similar recommendations. Where enough alternatives exist, the section SHALL limit dominance by one channel. Personalized items SHALL expose a concise reason based on the contributing signal.

#### Scenario: Like influences similar items

- **WHEN** a listener likes a noir-jazz song and eligible unseen noir-jazz alternatives exist
- **THEN** those alternatives receive stronger For you consideration than unrelated items and can show the reason for the recommendation

#### Scenario: Disliked item is excluded

- **WHEN** a listener gives a song a thumbs-down
- **THEN** that song is not subsequently offered in For you unless the rating is changed or cleared

#### Scenario: Channel does not dominate unnecessarily

- **WHEN** enough well-ranked items from different channels are available
- **THEN** For you presents a varied selection rather than filling the shelf from one channel

### Requirement: Cold start is not presented as personalized

When insufficient local ratings or listening evidence exist, the system SHALL label the selection as general discovery rather than claiming it is based on the listener. The selection SHALL become personalized only after actual local preference evidence is available.

#### Scenario: New profile sees honest discovery

- **WHEN** a listener has no ratings and no qualified listening history
- **THEN** the app presents general discovery content and does not claim that it was selected because of that listener's taste

### Requirement: Personal data remains local and erasable

Ratings and listening history SHALL be stored without an account and SHALL NOT be included in the master or generated app catalog. Persisted listening records SHALL use catalog/video and session identifiers rather than signed media URLs. The listener SHALL be able to clear ratings and listening history, after which subsequent recommendations no longer use the erased signals.

#### Scenario: Reset removes personalization

- **WHEN** a listener clears personal ratings and listening history
- **THEN** the erased data no longer influences For you and the app returns to honest general discovery until new evidence exists

#### Scenario: Generated catalog excludes taste data

- **WHEN** the owner generates a new app-catalog revision
- **THEN** the generated database contains no local ratings, listening history, or signed media URLs
