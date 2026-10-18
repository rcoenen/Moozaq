# Spec Delta

## Purpose

Keeps automatic Android recovery from reclaiming a speaker after playback has moved with a membership change.

## ADDED Requirements

### Requirement: Recovery yields during membership handoff

While a membership command has been sent and its playback location is not yet confirmed, and while a confirmed adopted endpoint is the playback location, automatic recovery SHALL NOT select the previous speaker route. Recovery SHALL NOT load media, start playback, seek, or restore volume in order to follow that move.

#### Scenario: Previous speaker is not reselected during handoff

- **WHEN** automatic recovery runs after a membership command was sent or after an endpoint was adopted
- **THEN** the system SHALL NOT select the pre-change speaker route and SHALL NOT load media

### Requirement: Reopen follows the group already playing the session

When the app is open and is not already controlling media, the system SHALL look for a discovered group that is already playing the remembered session. It SHALL select that route once when exactly one group matches. It SHALL NOT send `SET_PLAYBACK_DEVICES` or load media. It SHALL NOT save a temporary group as the remembered speaker. A missing session id, or two matching groups, SHALL leave the route unchanged.

The session id SHALL be the connected Cast session id when the phone is attached to a speaker that has reported no media. When no Cast session is connected and recovery has settled with no Default Media Receiver on the remembered speaker, the session id SHALL be read with receiver `GET_STATUS` on that speaker only.

#### Scenario: Restart joins the temporary group without loading

- **WHEN** the app opens with no Cast session, the remembered speaker is not running the Default Media Receiver, and exactly one discovered group is actively playing that speaker's application session
- **THEN** the system SHALL select that group once, SHALL NOT load media, SHALL NOT send `SET_PLAYBACK_DEVICES`, and SHALL NOT save the temporary group as the remembered speaker

#### Scenario: No unique group is selected

- **WHEN** the remembered speaker has no session id, or zero or two discovered groups match that session
- **THEN** the system SHALL NOT select a route
