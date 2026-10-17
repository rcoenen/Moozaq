# Spec Delta

## Purpose

Keeps Android destination selection dependable and understandable when Cast route transitions interrupt a pending join or replace an existing session.

## ADDED Requirements

### Requirement: Pending destination joins reconcile one route change

When an explicitly selected destination's pending initial join ends with `CASTING_ROUTE_CHANGED`, the system SHALL keep the requested destination pending and attempt at most one route-join reconciliation. The reconciliation SHALL occur only when the original destination and operation generation remain current, the target is still available with the same device identity, the phone route is selected rather than another Cast destination, and no SDK session is active. It SHALL NOT issue media, volume, membership, or source-resolution operations.

#### Scenario: Eligible interrupted join is reconciled

- **WHEN** a pending explicit destination join ends with `CASTING_ROUTE_CHANGED` and all fresh destination, generation, route, foreground, and SDK-state guards pass
- **THEN** the system SHALL keep the destination pending and select that route at most once without sending media or volume commands

#### Scenario: Second route-change failure is explicit

- **WHEN** the destination has already used its automatic route-join reconciliation and the join fails again
- **THEN** the system SHALL show an unconfirmed destination state with deliberate manual retry and SHALL NOT retry automatically

### Requirement: Route changes cannot reclaim replaced or attached sessions

The system SHALL NOT automatically reselect a route after a stale replaced-session event, after an attached session ends, or when another non-default route is selected. Delayed reconciliation SHALL be cancelled or ignored after backgrounding, Stop, a newer destination selection, device change, close, or a newer attachment.

#### Scenario: Another destination becomes selected

- **WHEN** a pending reconciliation observes that another Cast route is selected
- **THEN** the system SHALL yield to that route and SHALL NOT reselect the original destination

#### Scenario: Target attaches before delayed reconciliation

- **WHEN** the intended destination attaches before the delayed reconciliation executes
- **THEN** the system SHALL synchronize that attachment and SHALL NOT issue another route selection

### Requirement: Diagnostics preserve route-change reasons

Private diagnostics SHALL record mapped Cast reason information and relevant route/session context for session start, resume, and end failures without recording credentials or signed media URLs. Numeric SDK details SHALL remain in diagnostics rather than becoming raw user-facing errors.

#### Scenario: Route-change status is recorded

- **WHEN** a session callback reports a Cast status associated with `CASTING_ROUTE_CHANGED`
- **THEN** diagnostics SHALL include the mapped reason and route/session context while the user interface retains friendly pending or failure text
