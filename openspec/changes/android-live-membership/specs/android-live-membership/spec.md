# Spec Delta

## Purpose

Adds or removes one physical speaker on a playing or paused Android Cast session without reloading the mix, resuming a pause, or starting a second independent playback.

## ADDED Requirements

### Requirement: Live membership uses one gated platform command

When a fresh receiver session is playing or paused and reports `appAllowsGrouping=true`, `streamTransferSupported=true`, and `isVideoContent=false`, the system SHALL allow one physical speaker to be added or removed from the Cast to checkboxes. The mutation SHALL be a single `SET_PLAYBACK_DEVICES` on the multizone platform channel, carrying the receiver application session id and the full desired list of individual audio speakers. The system SHALL NOT send that command when any gate is missing or false, when the session or media changed since the checkbox action, or when a membership change is already unsettled. A saved speaker group SHALL remain unchanged.

#### Scenario: Eligible checkbox sends one command

- **WHEN** the user checks or unchecks one available individual speaker during an eligible playing or paused session
- **THEN** the system SHALL send one `SET_PLAYBACK_DEVICES` for the full desired speaker list and SHALL NOT send another membership mutation automatically

#### Scenario: Ineligible session stays locked

- **WHEN** a fresh session does not report all three eligibility flags
- **THEN** speaker checkboxes SHALL stay unavailable and the system SHALL NOT send `SET_PLAYBACK_DEVICES`

#### Scenario: Stale session is not mutated

- **WHEN** the application session or media identity changes after the checkbox action and before the command is sent
- **THEN** the system SHALL cancel without sending `SET_PLAYBACK_DEVICES`

### Requirement: Membership confirmation preserves playback

The system SHALL confirm a membership change only from a correlated `PLAYBACK_SESSION_UPDATED` reply, the same media identity, the original playing or paused state, and exact requested membership. A missing endpoint SHALL be confirmed on the current session. A returned endpoint SHALL be adopted only when it is a private address of a requested speaker and its session and media match. `failedDevices` SHALL leave the change unconfirmed. The system SHALL NOT load media, start playback, or send a second membership command. If a paused session is observed playing after the reply, the system SHALL send one pause and require the paused state again. It SHALL NOT send play to restore a session that paused.

#### Scenario: In-place change keeps the current session

- **WHEN** the reply has no endpoint, no failed speakers, the same media, and the original player state
- **THEN** the system SHALL keep controlling that session and SHALL confirm only after the requested speakers are observed

#### Scenario: Endpoint handoff is validated before use

- **WHEN** the reply includes a session endpoint
- **THEN** the system SHALL adopt it only after its host, port, session, and media pass validation, and SHALL NOT load media onto it

#### Scenario: Paused session is not resumed

- **WHEN** the session was paused and the confirmed status is playing
- **THEN** the system SHALL send one pause and SHALL NOT report success until the session is paused again

#### Scenario: Partial join is not success

- **WHEN** the reply lists any failed speaker
- **THEN** the system SHALL keep the requested checkboxes unconfirmed and SHALL NOT send another membership command

### Requirement: Stop and cancellation follow the real session

Stop before the membership command is sent SHALL cancel that command. Stop after it is sent SHALL wait for the reply and endpoint adoption, then stop the confirmed session. A timeout or unmatched reply SHALL leave the location uncertain, lock further membership changes, and SHALL NOT retry the mutation. Temporary group identities SHALL be available to routing and hidden from the speaker picker.

#### Scenario: Stop before send

- **WHEN** Stop is requested before `SET_PLAYBACK_DEVICES` is written
- **THEN** the system SHALL NOT write that command

#### Scenario: Stop after send

- **WHEN** Stop is requested after `SET_PLAYBACK_DEVICES` is written
- **THEN** the system SHALL adopt the confirmed location if one arrives and SHALL stop that session rather than only the pre-change session

#### Scenario: Unknown outcome is not retried

- **WHEN** the membership reply times out or does not match the request and no playback location was confirmed
- **THEN** the system SHALL mark the playback location uncertain, clear the unfinished checkbox request, stop status polls of the pre-change speaker, and SHALL NOT send `SET_PLAYBACK_DEVICES` again until a fresh read confirms the session

#### Scenario: One matching group is followed without another command

- **WHEN** a sent membership command ends without a confirmed location and exactly one discovered group reports the same session, the same media, and an active player state
- **THEN** the system SHALL select that group route once and SHALL NOT load media or send `SET_PLAYBACK_DEVICES` again
