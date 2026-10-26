# Spec Delta

## Purpose

Makes Android browsing visibly fresh and personally useful through truthful additions, persistent editorial picks, local recommendations, and predictable compact-phone interactions without uploading taste history or disturbing playback.

## ADDED Requirements

### Requirement: Fresh sections lead compact Android browsing

Android Browse SHALL present non-empty **Recommended**, **Recently added**, and **For you** or **Discover** sections ahead of the mood shelves, in that order. These logical sections SHALL share one horizontal discovery row with a small source label on every card rather than giving a sparsely populated section its own mostly empty row. The presentation SHALL preserve compact phone browsing, explicit card Play behavior, and independent mini/full-screen receiver player. Browsing, rating, and discovery refresh SHALL NOT change receiver playback.

#### Scenario: Fresh content appears before moods

- **WHEN** editorial, recently added, and discovery items are available on Android
- **THEN** their labeled items precede the ordinary mood shelves in the specified order

#### Scenario: One recommendation does not waste a row

- **WHEN** only one Recommended item and other recent or personalized items are available
- **THEN** the Recommended card is followed by the next labeled discovery item in the same row rather than occupying a separate mostly empty shelf

#### Scenario: Discovering another mix leaves the session alone

- **WHEN** a listener scrolls, rates, or refreshes discovery while a receiver session is active
- **THEN** no media load, play, resume, pause, stop, seek, transfer, or volume action is initiated

### Requirement: Editorial and addition freshness is truthful

**Recommended** SHALL retain owner-selected songs until the owner's published projection changes, without a daily expiry. **Recently added** SHALL contain at most 20 distinct eligible songs ordered by actual first-seen time, newest first; unknown dates SHALL NOT be presented as recent. Source-publication time SHALL NOT substitute for Moozaq's first-seen time, and duplicate video IDs SHALL remain one logical song.

#### Scenario: Irregular owner recommendations remain valid

- **WHEN** the owner has not changed a recommendation for several weeks
- **THEN** Android continues to show it as Recommended without calling it newly added

#### Scenario: Unknown legacy additions are not fabricated

- **WHEN** eligible catalog songs have no first-seen timestamps
- **THEN** those songs do not populate Recently added

### Requirement: Visible discovery has explicit stability boundaries

A downloaded or activated catalog revision SHALL NOT reorder, rotate, or reset the scroll position of the current visible discovery shelves. New discovery snapshots SHALL occur only at a cold app start, a deliberate return to Browse from the full-screen player, or an explicit discovery refresh. A confirmed rating or personal-data reset SHALL update affected personal content without rotating unrelated editorial or recent sections. Receiver polling, catalog dialogs, artwork loads, and Cast-sheet changes SHALL NOT create a new discovery snapshot.

#### Scenario: Update arrives while reading

- **WHEN** a newer catalog activates while the listener is reading a discovery row
- **THEN** the visible row order and scroll position remain unchanged until a defined discovery boundary

#### Scenario: Returning to Browse refreshes deliberately

- **WHEN** the listener returns from the full-screen player to Browse
- **THEN** a new snapshot may use the latest catalog and profile without restarting or altering playback

### Requirement: Android ratings are local, confirmed, and silent

The listener SHALL be able to thumbs-up, thumbs-down, change, or clear a rating for an identified public-catalog song from the compact phone interface and the identified current song. Rating controls SHALL NOT trigger the card's Play action, and unknown external receiver sessions SHALL NOT be rateable. Requested ratings SHALL show pending/error feedback until the persisted personal state confirms them.

#### Scenario: Thumbs-up does not start a mix

- **WHEN** a listener taps thumbs-up on a mix that is not playing
- **THEN** the rating is persisted after confirmation and no Play request occurs

#### Scenario: Rating survives process recreation

- **WHEN** a listener changes or clears a rating and later reopens the Android app
- **THEN** the restored personal profile reflects that exact rating and applies it to recommendations

### Requirement: Listening personalization requires receiver evidence

Android listening signals SHALL require fresh confirmed PLAYING state, an identified public-catalog source, matching media-session identity, and wall-clock-plausible advancing receiver position. Card taps, pending or failed starts, paused sessions, seeks, signed-link renewal, and catalog downloads SHALL NOT count as listening. Five accumulated verified minutes SHALL qualify as a listening signal; backgrounding and process boundaries SHALL NOT manufacture unobserved listening credit.

#### Scenario: Failed start creates no affinity

- **WHEN** a requested mix never reaches confirmed advancing playback
- **THEN** no qualified listening evidence is added to the personal profile

#### Scenario: Phone returns after backgrounding

- **WHEN** the app becomes visible again after receiver playback continued without phone observation
- **THEN** it does not award the entire unobserved interval as listening credit, and it does not reload the receiver to establish a baseline

### Requirement: For you is varied, explainable, and honest at cold start

Android SHALL reuse shared recommendation ranking in which explicit likes outweigh capped and decayed qualified-listening affinity, disliked songs are excluded and reduce similar recommendations, and channel diversity is favored where alternatives exist. Personalized cards SHALL expose a concise reason based on actual contributing local evidence. Without positive or negative local preference evidence, the section SHALL be labeled **Discover** rather than claiming personalization.

#### Scenario: A like produces an honest explanation

- **WHEN** a liked noir-jazz song contributes to another eligible noir-jazz recommendation
- **THEN** the recommendation can name that actual like as its reason and does not invent listening history

#### Scenario: Disliked song is removed after confirmation

- **WHEN** a listener gives a song thumbs-down and the write is confirmed
- **THEN** that song is absent from the next personalized ranking unless the rating is changed or cleared

#### Scenario: Empty profile sees Discover

- **WHEN** no ratings or qualified listening evidence exist
- **THEN** Android presents varied general discovery under Discover, not For you

### Requirement: Personal data stays on the phone and can be erased

Ratings, listening aggregates, and safe observations SHALL remain in Android-private storage, SHALL be excluded from cloud backup/device transfer, and SHALL NOT enter either catalog database, update manifests, logs, GitHub, or a Mac synchronization channel. Persisted observations SHALL use video/session identifiers and position rather than signed media URLs. A confirmed reset SHALL erase ratings, aggregates, pending listening credit, and in-memory observation state so erased data cannot reappear on close or restart.

#### Scenario: Catalog update contains no taste data

- **WHEN** Android downloads or activates a new public catalog
- **THEN** no ratings, listening history, safe observations, or signed media URLs are copied into it or transmitted with the update request

#### Scenario: Reset cannot be undone by a later close

- **WHEN** a listener confirms personal-data reset and then backgrounds, closes, and reopens the app
- **THEN** erased ratings and observations remain absent and recommendations use no pre-reset signal

#### Scenario: Reset requires deliberate confirmation

- **WHEN** a listener chooses to clear personal data
- **THEN** the app explains the scope and asks for confirmation before erasing it, with pending or failure feedback until completion
