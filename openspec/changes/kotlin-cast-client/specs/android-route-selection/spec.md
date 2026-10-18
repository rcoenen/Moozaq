# Spec Delta

## REMOVED Requirements

### Requirement: Pending destination joins reconcile one route change

**Reason**: `CASTING_ROUTE_CHANGED` is a Cast SDK session event. Once the shared client replaces the SDK, there is no route join that can be interrupted this way.
**Migration**: Explicit destination selection is covered by the `cast-client` requirement "Explicit destination selection is confirmed and never retried automatically".

### Requirement: Reconciliation requires fresh route and SDK state

**Reason**: This guards the SDK route reconciliation that is removed above.
**Migration**: Freshness, generation, and cancellation guards for selection live in `cast-client`.

### Requirement: Route changes cannot reclaim replaced or attached sessions

**Reason**: The client no longer receives SDK route or replaced-session events.
**Migration**: The `cast-client` selection requirement ignores late completions of older selections, and the following requirement never attaches over another active destination.

### Requirement: Diagnostics preserve route-change reasons

**Reason**: Cast SDK reason codes no longer exist on the control path.
**Migration**: Shared client diagnostics record Cast protocol replies and connection errors without credentials or signed links.
