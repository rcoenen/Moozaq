# android-cast-recovery Specification

## Purpose
Restores reliable Android control of an existing Cast receiver session without competing with SDK lifecycle transitions or changing receiver playback.

## Requirements

### Requirement: Recovery yields to active SDK sessions

Before starting passive recovery and again after each asynchronous receiver probe, the system SHALL verify freshly that the current SDK session is not connected, connecting, resuming, or suspended before attempting fallback route selection. A recovery attempt SHALL NOT select a route while any of those session states is active.

#### Scenario: Session starts resuming during the receiver probe

- **WHEN** a passive receiver probe completes and the current SDK session has started resuming since the probe began
- **THEN** that recovery attempt SHALL NOT select a route or otherwise race the resuming session

#### Scenario: Another active SDK state remains after the receiver probe

- **WHEN** a passive receiver probe completes and the current SDK session is connected, connecting, or suspended
- **THEN** that recovery attempt SHALL NOT select a route

### Requirement: Recovery does not change receiver playback

Automatic recovery SHALL limit itself to discovery, read-only receiver inspection, route/session reattachment, and fresh state synchronization. Recovery SHALL NOT load media, start or resume playback, seek, restore volume, or automatically retry an audible operation.

#### Scenario: Existing authorized speaker session is rejoined

- **WHEN** automatic recovery finds the remembered receiver already running compatible media
- **THEN** the system SHALL synchronize the existing session without issuing LOAD, PLAY, seek, or volume commands
