# Playback control reliability

The owner explicitly approved these six core behaviors: confirmed actions, reliable Stop, clean reconnection, responsive sliders, honest status, and synchronization with external changes. The owner wants unusually dependable controls compared with typical Cast apps. Reliability is a core product requirement, not a later polish task. These are implementation and acceptance requirements; they are not all implemented or verified in the spike.

## Receiver state governs the interface

Distinguish disconnected, connecting, synchronizing, ready, reconnecting, and failed control states. A connected socket alone does not mean a media session is ready. Separately show playback state: loading, buffering, playing, paused, stopped, or unknown.

Track the selected device, receiver app/session, media session and source identity. Discard callbacks from replaced connections or sessions. On reconnect, request fresh state before issuing media commands. Rejoining must not load a mix, seek to an old phone position, or restore an old volume.

If state is stale or unknown, say so. Do not present an extrapolated playback position as a fresh receiver measurement. Receiver changes caused by voice, physical speaker controls or another controller must update the interface.

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
