# Design

## Context

See `proposal.md` and `specs/android-route-selection/spec.md`. The earlier Bedroom 2155 event was an expected route transition, while the Office event returned to the phone route without attaching. The exact Office route-change actor is unknown. `AndroidBackend` currently collapses both into generic disconnect handling and logs only numeric statuses.

## Goals / Non-Goals

**Goals:**

- Distinguish route lifecycle events from generic connection failures.
- Make one safe, bounded reconciliation automatic for the user instead of requiring an immediate manual retry.
- Keep the decision deterministic and separately testable from the Android SDK.

**Non-Goals:**

- Infer a network or receiver root cause without evidence or change Cast dependencies.
- Reclaim an attached session, another controller's route, or media playback.
- Force a 2155 reproduction during validation.

## Decisions

### Separate session-failure classification from retry eligibility

Classify the callback's session role and mapped Cast reason before changing UI state. Stale replaced-session events remain ignored. Only a pending initial join with `CASTING_ROUTE_CHANGED` enters route-change reconciliation; attached sessions and other failures use explicit disconnection/reconciliation behavior.

Use a small pure policy for the retry guard and one-shot state. This makes edge cases testable without mocking final SDK classes, while `AndroidBackend` remains responsible for fresh SDK/route reads and side effects.

### Preserve pending UI through one delayed route-only reconciliation

Hold `connecting=true` and clear `connectionError` during reconciliation so `reconcileDestination()` does not mark the request failed. After a short settling delay, re-read the generation, foreground state, attachment, target route identity, selected route, and `CastSessionActivity`.

If the target attached meanwhile, do nothing. If every guard passes, select the current route object once. Otherwise yield or show an unconfirmed state as appropriate. A second failure always surfaces manual retry.

Bind the delayed job to the current rejoin generation and cancel it on new selection, background, Stop, close, or newer attachment. This job is separate from passive automatic recovery because it belongs to an explicit destination intent.

### Map and record reasons before friendly failure handling

Call `getCastReasonCodeForCastStatusCode()` at the session callback boundary and include status, reason, route, and session-role details in private logs. Keep raw numbers and reason names out of normal UI text. Bump to 0.3.4 / code 6 so new logs are identifiable.

## Risks / Trade-offs

- [The exact Office route-change actor remains unknown] → Capture richer route/session evidence and do not claim the retry fixes that actor.
- [A delayed retry could override a newer user or system route] → Recheck generation, target identity, default-route state, SDK activity, and attachment immediately before selection.
- [A single retry may still fail] → Preserve explicit unconfirmed state and manual retry rather than looping.
- [Hardware may not reproduce 2155] → Rely on deterministic policy tests for the race and report any hardware non-recurrence as inconclusive.

## Migration Plan

Deploy 0.3.4 with the normal data-preserving debug reinstall after silent tests. No persistent-data migration is needed. Rollback is a source-level return to 0.3.3 behavior followed by rebuild; do not reset or clean the dirty worktree.
