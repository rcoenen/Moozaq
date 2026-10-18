# Spec Delta

## MODIFIED Requirements

### Requirement: Recovery yields to active SDK sessions

Before starting passive recovery, and again after each asynchronous receiver probe, the system SHALL check from fresh state that the shared Cast client has no connection to a destination that is connected, connecting, or being followed after a move. Only then may it reattach to the remembered receiver. A recovery attempt SHALL NOT attach to a receiver while any of those states is active, while an explicit destination selection is pending, or while a membership change owns the playback location.

#### Scenario: Session starts resuming during the receiver probe

- **WHEN** a passive receiver probe completes and the client has started connecting to a destination since the probe began
- **THEN** that recovery attempt SHALL NOT attach to the remembered receiver

#### Scenario: Another active SDK state remains after the receiver probe

- **WHEN** a passive receiver probe completes and the client is connected, is following a moved session, or has a pending explicit selection
- **THEN** that recovery attempt SHALL NOT attach to the remembered receiver
