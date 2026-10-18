# Spec Delta

## Purpose

Defines the Cast control behaviour that the Android app and the desktop app both get from one shared client: what it may send to a speaker, what counts as confirmation, and how it follows a mix that moves between speakers.

## ADDED Requirements

### Requirement: Both apps use one Cast control implementation

The Android app and the desktop app SHALL discover speakers, read receiver state, and send every Cast command through the same shared client implementation. For the same receiver state and the same user action, both apps SHALL send the same Cast messages and report the same confirmed or failed outcome. Platform code SHALL be limited to lifecycle, network access, source resolution, storage, and diagnostics output.

#### Scenario: Same rule on both platforms

- **WHEN** a scripted receiver produces the same sequence of replies for a user action on Android and on desktop
- **THEN** both apps SHALL send identical Cast frames and SHALL reach the same confirmed or failed state

### Requirement: Connections and reads never change playback

Opening a connection, reconnecting, discovery, status polling, and diagnostics SHALL use only CONNECT, heartbeat, and status reads. They SHALL NOT launch a receiver app, load media, play, seek, or change volume. App startup and catalog or metadata fetching SHALL NOT play audio. A connection that has no fresh receiver and media status SHALL NOT enable playback controls.

#### Scenario: Missing media channel does not launch an app

- **WHEN** the client connects to a speaker whose media channel is absent
- **THEN** it SHALL report no media session and SHALL NOT send LAUNCH or LOAD

#### Scenario: Socket without fresh evidence keeps controls disabled

- **WHEN** a control connection is open but no fresh receiver and media status has arrived for it
- **THEN** transport and volume controls SHALL stay unavailable and the status SHALL say the session is not yet synchronized

### Requirement: Discovery keeps known speakers honest

The client SHALL discover speakers on the local network. A remembered address SHALL be used only as a discovery hint, and a speaker found there SHALL be accepted only when its reported device identity matches. A speaker that has dropped out of discovery SHALL stay listed as unavailable, with its controls disabled, until it is discovered again. Physical speakers SHALL be remembered across restarts. Temporary groups SHALL NOT be remembered. Once the receiver's saved group list is known, any group that is not in it SHALL be treated as temporary and hidden from the speaker picker, while staying available to routing.

#### Scenario: Remembered address with a different identity

- **WHEN** a speaker found at a remembered address reports a different device identity than the one remembered
- **THEN** the client SHALL NOT select it as the remembered speaker

#### Scenario: Speaker briefly missing from discovery

- **WHEN** a known physical speaker is absent from discovery
- **THEN** it SHALL be listed as unavailable with disabled controls, and SHALL become available again only after it is rediscovered

#### Scenario: New temporary group appears

- **WHEN** a group route appears that is not in the known saved group list
- **THEN** it SHALL be hidden from the speaker picker and SHALL NOT be saved as the remembered speaker

### Requirement: Explicit destination selection is confirmed and never retried automatically

Selecting a speaker or saved group SHALL hold the requested destination as pending until a fresh connection to that same device reports receiver state. Selection alone SHALL NOT load media. A failed selection SHALL stay visible as failed, with a deliberate manual retry, and SHALL NOT be retried automatically. A newer selection, Stop, or close SHALL cancel an older pending selection, and a late result from the older selection SHALL be ignored.

#### Scenario: Selection confirms only from the chosen device

- **WHEN** the user selects a speaker and a fresh status arrives from that speaker
- **THEN** the destination SHALL become confirmed without any media being loaded

#### Scenario: Older selection completes late

- **WHEN** a selection completes after the user has already selected another destination
- **THEN** the client SHALL ignore the late completion and keep the newer destination

### Requirement: Media commands confirm from fresh, bound receiver state

Play, Pause, Resume, seek, Stop, and volume changes SHALL each be bound to the device and session they were issued for. Each SHALL be confirmed only from receiver status received after the command was sent, from that same device and session. A cached value that happens to match SHALL NOT count as confirmation. A command whose target changed before sending SHALL NOT be sent. A command that is not confirmed SHALL be reported as failed or uncertain and SHALL NOT be retried automatically. A seek on a paused session SHALL leave it paused. An invalid volume or seek value SHALL NOT reach the receiver.

#### Scenario: Stale matching value is not confirmation

- **WHEN** a volume change is sent and the only status showing the requested level was received before the change was sent
- **THEN** the change SHALL stay pending until fresh status arrives, and SHALL fail at its deadline without a second write

#### Scenario: Seek on a paused session

- **WHEN** the user seeks while the session is paused
- **THEN** confirmation SHALL require the requested position with the session still paused

### Requirement: Stop takes priority and is verified

Stop SHALL stay available during pending work. Stop SHALL cancel pending loads, membership commands that have not been sent yet, confirmation waits, and source resolution. A late completion of cancelled work SHALL NOT start sound. Stop SHALL be reported as successful only after fresh receiver state shows the intended session stopped or the receiver app ended. A failed Stop SHALL be reported as failed and SHALL NOT be acknowledged or retried silently.

#### Scenario: Load finishes after Stop

- **WHEN** a media load was cancelled by Stop and its source resolution completes afterwards
- **THEN** the client SHALL NOT send LOAD

#### Scenario: Stop is not confirmed

- **WHEN** Stop is sent and no fresh status shows the session stopped before the deadline
- **THEN** the app SHALL show that Stop was not confirmed and SHALL NOT report the speaker as stopped

### Requirement: Live membership uses one gated multizone command on both platforms

Adding or removing a physical speaker on a playing or paused session SHALL be allowed only when fresh receiver status reports grouping allowed, stream transfer supported, and audio-only content. The change SHALL be a single `SET_PLAYBACK_DEVICES` on the multizone platform channel, carrying the receiver application session id and the full desired speaker list. It SHALL NOT be retried. It SHALL be confirmed only from a correlated `PLAYBACK_SESSION_UPDATED` reply, the same media identity, the original playing or paused state, and the exact requested membership observed afterwards. A returned endpoint SHALL be adopted only when it is a private address of a requested speaker and reports the same session and media. Failed speakers in the reply SHALL leave the change unconfirmed. A paused session observed playing afterwards SHALL get one pause. Stop before sending SHALL cancel the command, and Stop after sending SHALL stop the confirmed session. A saved speaker group SHALL NOT be modified.

#### Scenario: Ineligible session

- **WHEN** fresh status does not report all three eligibility conditions
- **THEN** speaker checkboxes SHALL stay unavailable and no membership command SHALL be sent

#### Scenario: Endpoint handoff

- **WHEN** the reply returns a new endpoint for a requested speaker and that endpoint reports the same session and media
- **THEN** the client SHALL move control to that endpoint without loading media

#### Scenario: Unknown outcome

- **WHEN** the reply times out or does not match the request
- **THEN** the playback location SHALL be marked uncertain, further membership changes SHALL be locked until a fresh read, and the command SHALL NOT be sent again

### Requirement: The client follows a moved session without playback commands

When the controlled session disappears from its endpoint, the client SHALL look for a replacement that is the only fresh candidate with the same media identity, overlapping physical membership, and plausible forward position. That replacement can be a group, a different group, or a single remaining speaker. It SHALL follow that replacement by reading state only. Two or more matching candidates SHALL NOT be followed. Restarted, rewound, or unrelated playback SHALL NOT be followed, and neither SHALL a match found after a long outage. While following, the last title and position SHALL be shown as unconfirmed and transport controls SHALL be disabled. Missing observations alone SHALL NOT be treated as the end of playback.

#### Scenario: Group contracts to one speaker

- **WHEN** a temporary group ends and exactly one of its former members reports the same session and media with forward position
- **THEN** the client SHALL follow that speaker without sending LOAD, PLAY, seek, or volume

#### Scenario: Ambiguous candidates

- **WHEN** two discovered endpoints both match the moved session
- **THEN** the client SHALL follow neither and SHALL keep the location unconfirmed

#### Scenario: One idle member

- **WHEN** one former member is freshly idle while others have not reported
- **THEN** the client SHALL NOT conclude that playback ended

### Requirement: Group volume is the confirmed member average

Each member speaker's own receiver level SHALL be shown for that member. The main volume of a multi-speaker session SHALL be the average of every member's confirmed level, or unknown when any member is unreadable. A main volume change SHALL scale member levels proportionally and confirm each member from its own fresh status. It SHALL be disabled while membership is changing, and it SHALL be cancelled if the member set changes during the transaction. Reconnect and startup SHALL NOT restore remembered volumes.

#### Scenario: Unreadable member

- **WHEN** any current member's level cannot be read
- **THEN** the main volume SHALL be unknown and its slider disabled

#### Scenario: Members change mid-transaction

- **WHEN** a speaker joins or leaves while a main volume change is being applied
- **THEN** the change SHALL stop, confirmed members SHALL keep their new levels, and the main request SHALL be reported unconfirmed

### Requirement: Source links come from the platform and are renewed before they lapse

The client SHALL receive playable source links and their expiry from a platform source provider. It SHALL NOT log signed links or include them in reports. Before Resume or seek, a link that will not outlast the rest of the mix plus a safety margin SHALL be renewed and reloaded at the intended position, keeping a paused session paused. If renewal is unavailable while the old link still works, the client SHALL fall back to a plain Resume or seek. A failed renewal SHALL be reported and SHALL NOT be retried automatically.

#### Scenario: Resume after the link expired

- **WHEN** the user resumes a session whose source link has expired
- **THEN** the client SHALL request a new link and reload at the paused position instead of sending a plain play

#### Scenario: Renewal unavailable but link still valid

- **WHEN** a renewal fails and the current link has not expired
- **THEN** the client SHALL send a plain Resume and SHALL report nothing as failed
