# Playback control reliability

The owner explicitly approved these six core behaviors: confirmed actions, reliable Stop, clean reconnection, responsive sliders, honest status, and synchronization with external changes. The owner wants unusually dependable controls compared with typical Cast apps. Reliability is a core product requirement, not a later polish task. These are implementation and acceptance requirements; they are not all implemented or verified in the spike.

## Product-wide UI rule: intent → pending → confirmed

Apply this interaction contract to every asynchronous control in Moozaq, on desktop and Android, including future controls. It is not specific to volume, seeking, or transport buttons.

- Respond immediately to user intent. Hold the requested slider value or show the requested button action as pending; do not present it as already applied.
- Use a consistent pending colour plus a short text label. Colour alone is insufficient. Keep feedback beside the affected control.
- Keep pending intent separate from confirmed device state. Old polls and out-of-order callbacks must not snap controls back or overwrite a newer request.
- Restore normal styling only after fresh authoritative confirmation for the intended device, session and request, using an appropriate value/time tolerance. Command acceptance alone is not confirmation.
- Keep unrelated status truthful and stable. Adjusting volume must not replace “Playing”; pending Play/Pause keeps the last confirmed playback label until the change is confirmed. Unknown or disconnected state must still be disclosed.
- Bound confirmation waits. On failure or timeout, show an explicit unconfirmed state, preserve the requested value where applicable, and offer deliberate retry or reconciliation with actual state. Never silently declare success, roll back, or restart audio.
- Cancel superseded intent and ignore late responses. Stop takes priority; device/session changes invalidate requests bound to the old target. External changes resume driving controls after pending intent has been resolved or explicitly reconciled.
- Share state handling and visual conventions across controls rather than inventing separate interaction rules for each feature. Verify delayed, stale, failed, cancelled and successful responses with silent tests.

This is the required product contract. Existing control-specific implementations are incremental; this rule does not claim every control already uses a single shared component or state machine.

## Receiver state governs the interface

Distinguish disconnected, connecting, synchronizing, ready, reconnecting, and failed control states. A connected socket alone does not mean a media session is ready. Separately show playback state: loading, buffering, playing, paused, stopped, or unknown.

Track the selected device, receiver app/session, media session and source identity. Discard callbacks from replaced connections or sessions. On reconnect, request fresh state before issuing media commands. Rejoining must not load a mix, seek to an old phone position, or restore an old volume.

If state is stale or unknown, say so. Do not present an extrapolated playback position as a fresh receiver measurement. Receiver changes caused by voice, physical speaker controls or another controller must update the interface.

### Plain-language playback status (2026-10-17)

Only a confirmed idle receiver may be presented as “Nothing playing”. Use “Last played: …” when a previous in-memory track exists and “Choose a mix” otherwise. Genuine connection or synchronization work uses “Connecting…”, not “Checking playback…”. Unknown state must not be disguised as confirmed idle, and protocol phrases such as “No active Moozaq receiver” belong in diagnostics rather than the main player.

### Play with a selected but unavailable destination (2026-10-17)

An explicit Play click is the user's playback authorization and must not silently no-op when the selected destination is disconnected. Show Starting immediately, connect the current destination if needed, and wait at most 15 seconds for fresh readiness before starting once. Revalidate the target and intent generation before Play. Stop or a newer destination cancels the wait; failure remains explicit and never triggers a late or automatic playback retry. On Android, a missing destination still requires speaker selection rather than implicit playback. On Mac, an explicit Play may select This Mac when local playback is available; that is the authorized local destination, never an automatic fallback or startup action.

### Pending-mix presentation (2026-10-17)

While a selected mix is starting, present that requested mix's title and artwork with a clear pending label. Do not pair Starting with the previous track's metadata or timeline. Hide the previous timeline until fresh receiver confirmation establishes the requested source; keep the underlying previous receiver state only for reconciliation, not as the pending presentation.

## Commands express intent and require confirmation

- Send explicit play and pause intent rather than toggling a potentially stale state.
- Show pending operations immediately, then confirm them from receiver state. Distinguish request acceptance from observed effect. A timeout must produce a visible uncertain/failed result, not an assumed success.
- Serialize conflicting transport operations. Stop cancels pending loads and invalidates late completions; cancelled work must never restart playback.
- Coalesce rapid volume and seek changes so the latest user intent wins. Older callbacks must not move sliders backward. Confirm volume within an appropriate tolerance and seek position within an appropriate timing tolerance.
- Keep Stop available during loading, downloading, buffering and reconnection. If necessary, reconnect to the previously targeted receiver. Confirm stopped state; if ordinary media Stop fails, end that receiver app and verify the result.
- Bind destructive transport actions to the intended device and session. If another controller replaces the session, synchronize and show the change rather than silently stopping unrelated newly started content.
- Use bounded retries for safe status queries and idempotent controls. Do not blindly retry load, skip, toggle or other operations that could restart/advance playback. Make manual retry available after a clear failure.

## Reconnection and lifecycle

Keep control state separate from the audio source. A phone leaving the network must not stop receiver playback when the source is independently reachable. App backgrounding, process recreation and network changes should trigger reconciliation with the existing receiver, not automatic media loading.

Persist enough session identity to attempt reattachment, but treat stored playback state as a hint, never authority. On return, distinguish the original mix still playing, a paused session, a completed/stopped session, a replacement session and an unreachable receiver.

## Acceptance tests

Run audible tests only when the owner explicitly requests playback. General development or testing requests do not authorize starting or resuming sound. Prefer mocked protocol tests and read-only state checks otherwise.

Test on Bedroom speaker first. Use other devices and groups only when selected by the owner.

1. Rapid play/pause changes settle to the latest requested state.
2. Rapid seek/volume changes settle correctly without later rollback from stale responses.
3. Stop during extraction, download, receiver launch, loading, buffering and playback prevents later sound from that operation.
4. Disconnect/reconnect, close/reopen and phone restart preserve the current receiver session without restarting the mix.
5. Voice pause/stop, speaker volume changes and another controller's changes are reflected correctly.
6. A delayed acknowledgement, lost response or failed receiver command is visible and recoverable; the app never claims an unverified Stop succeeded.
7. Network loss and recovery do not create duplicate loads or apply stale queued commands.
8. A full three-hour mix and seeks near its end work, or source expiry/failure is clearly distinguished from control failure.

Proposed responsiveness targets for a healthy local network: visible pending feedback within 100 ms; ordinary command confirmation within one second for at least 95% of measured operations. These are goals to benchmark, not current promises. Record request-to-confirmation latency, retries, timeouts, reconnection duration and unintended reloads.

External receiver/network failures and unofficial YouTube URL expiry cannot be eliminated by app code. Handle them explicitly and preserve user intent instead of disguising them as successful controls.

### Passive Cast status requests

PyChromecast's MediaController.update_status() is not inherently read-only: its send_message path can auto-launch a supporting receiver when the media namespace is absent. Install the guarded passive-status adapter before starting each main/member connection, including automatic channel callbacks. Diagnostics, reconnect and Stop verification must never launch a receiver. Explicit playback remains the only authorized load/launch path.

### Individual group volume feedback

On release, retain the requested slider position while awaiting a fresh, confirmed member-volume revision. Show a distinct pending colour and “Syncing…” label. Old polled values must not move the thumb. Restore normal styling only after confirmation of the requested value. After ten seconds without confirmation, retain the requested position with “Not confirmed”; allow a deliberate adjustment or “Read speaker” to return to reported state. Browsing away and reopening group details must retain pending state.

The main receiver/group volume slider follows the same pending-position and colour behaviour. The Compose bridge waits for a fresh matching receiver status before incrementing its confirmation revision. A stale matching value alone cannot confirm the command; changing target or Stop cancels confirmation. No volume is restored on reconnect.

Playback status text reflects receiver playback state even while volume commands are pending. Volume operations must not replace “Playing” with an operation message; pending feedback belongs beside the corresponding slider. General command errors remain available in the status area.

### Seek feedback

The progress slider and elapsed-time label hold the requested position during seek confirmation. Pending colour and “Seeking…” are separate from receiver playback state. A fresh media status from the same session must report within two seconds of the requested position in the original playing/paused state. On timeout, retain the target with “Seek not confirmed” and offer “Read playback”. Seek preserves paused playback; it must not implicitly resume it. Stop and target/session changes cancel confirmation.

### Play and Pause feedback

Play/resume and Pause show a pending button colour and Starting…/Pausing… label until confirmation. Keep the prior playback label while pending. Confirmation requires a fresh status for the intended media session; sending a command or seeing stale matching state is insufficient. Starting a selected mix uses the engine's load confirmation. Failures show Not confirmed, without automatic retry; Stop remains available and cancels pending transport feedback.


### Compact handoff controls (2026-10-17)

Stop casting and unchecking the last active participant use the same verified Stop operation. Keep Stopping… visible until verification advances the transport acknowledgement; failures remain explicit. Do not attempt an empty native group mutation. This supersedes disabling the final participant's checkbox. After a confirmed stop, last-track memory may offer Play again, which requires an explicit click and a ready destination.

The handoff's condensed volume controls still include local Syncing…/Not confirmed text as well as pending colour. Preserve playback/recovery status while volume changes. Decorative reduced motion does not replace protocol status or confirmation.

### Local Mac output (2026-10-26)

With no Cast destination selected, an explicit Play may select This Mac; no speaker selection is required on Mac when the local helper is available. This is still an explicit playback authorization, never a startup or browsing side effect. A chosen but disconnected Cast destination never silently falls back to the Mac. Selecting This Mac in the output picker alone does not start, stop or transfer audio.

Local playback uses the same intent → pending → confirmed rule as Cast. Confirmation comes from fresh AVPlayer state for the intended local session and request, including actual transport state, seek position and volume. Stop remains available during source resolution, preflight, loading and buffering; it invalidates late loads and confirms local IDLE. A timeout or dead helper is shown as unavailable, not as confirmed playback or Stop.

Changing between Cast and local output during active or uncertain playback requires an explicit confirmed Stop first, so two sessions cannot become uncontrolled. Local playback stops on app close and is not reattached or automatically resumed on restart. The helper starts at 15% volume and otherwise uses its current in-memory volume; no previous-app volume or position is restored. Signed media URLs stay in memory and out of logs. Local Resume and seek use the same expiry guards as Cast, without automatic mid-play URL replacement. Local seeks explicitly reload at the requested source position with an offset, preserving pause and avoiding dependence on unavailable seekable ranges.
