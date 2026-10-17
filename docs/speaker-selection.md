# Speaker combinations

The owner wants to select a combination of speakers, such as two devices from the existing “All speakers” setup, with synchronized playback and dependable controls. This is a feature requirement, not authorization to play audio on any group.

## Known devices

Discovery observed the saved group `All speakers`, plus Bedroom speaker, Office speaker, Kitchen speaker, Fridge speaker and Bedroom TV. A group advertisement does not establish its membership; do not infer which devices belong to it solely from their presence on the network.

## Supported platform paths

Google Home supports adding/removing devices for current playback without creating a preset group. Android Cast Output Switcher documents remote-to-remote expansion for audio apps and dynamic group selection. See [Google Home multiroom controls](https://support.google.com/googlehome/answer/10012636?hl=en) and [Android Output Switcher](https://developers.google.com/cast/docs/android_sender/output_switcher).

Implement this as a temporary playback combination, preserving the saved All speakers group. Use Cast's synchronized group session; independently starting the same track on multiple speakers is not equivalent and may drift.

## Current Mac limitation

The installed PyChromecast multizone controller monitors group membership and status. Its inspected API does not provide a verified operation to create/change an arbitrary dynamic group. The Mac spike can select existing advertised groups, but cannot yet choose an arbitrary subset within All speakers.

For the Mac spike, a separate group created in Google Home with the desired speakers can be selected once discovered. Do not modify the owner's saved groups implicitly. Dynamic checkbox selection inside Moozaq remains to be implemented and verified through a supported path.

## Android implementation work

Integrate Android's Output Switcher prerequisites, media notifications and transfer callbacks. Validate the actual receiver's audio/group support. On expansion or contraction, reconcile receiver/session identity, group name, current position, per-speaker volume and Stop behavior without reloading the mix. Changes through Google Home must be reflected in Moozaq.

Do all silent implementation/testing first. Ask for an explicit playback request before any audible group validation.

## Read-only group investigation — October 16, 2026

Live discovery identifies All speakers as `model_name=Google Cast Group`, `cast_type=group`. Its advertised endpoint was Kitchen speaker's host on port 32164, separate from Kitchen's individual Cast endpoint on port 8009. Treat that host/port as discovery data, not a fixed configuration.

A read-only connection to the group succeeded. Multizone status returned four members: Kitchen speaker, Fridge speaker, Bedroom speaker and Office speaker (matched by discovered UUID). Bedroom TV was not in the returned membership.

The group reported Default Media Receiver (`CC1AD845`) with its media namespace available, but media state UNKNOWN, no media session and no title. This is an empty group media session; an individually playing Office session is not automatically the group session. Selecting the group in Moozaq only changes the remote target and does not transfer playback.

The engine passes the discovered CastInfo to PyChromecast, retaining group type and its dedicated endpoint. The Compose dropdown currently displays only names, hiding the known model/type. Logs inspected showed a successful All speakers connection but no group media-load attempt, so the reported playback failure's root cause remains unconfirmed. No media was loaded, resumed, stopped or transferred, and no volume or group membership was changed in this investigation.

## Reliability research and network checks — October 16, 2026

Read-only checks at approximately 14:33 EDT:

- Kitchen is explicitly reported as the elected leader by Kitchen, Fridge and Bedroom. The group's Cast port stayed 32164 through the sample.
- Four members remain configured: Kitchen/Fridge Home Mini and Bedroom/Office Nest Audio. All four report Cast firmware `3.78.540761`, system build `540761`, release track `preview-joining-stable-channel`. That track string alone does not prove preview firmware is causing trouble.
- Ten ICMP probes per speaker: zero loss. Average RTT Kitchen 18.9 ms, Fridge 16.9 ms, Bedroom 12.7 ms, Office 31.7 ms; maxima 64–95 ms. These short Mac-to-speaker samples cannot establish speaker-to-speaker multicast health, Wi-Fi signal strength, jitter under playback, or long-term reliability.
- Office was absent at the first 8-second discovery snapshot but appeared by the end. That is evidence of discovery latency, not evidence it was offline.
- Group socket and media namespace were reachable, but no active group media session was present. This inspection therefore did not capture the reported partial-playback failure.

Research:

1. Google explicitly documents a historical leader-dependent failure where the leader plays but other members do not. Its 2024 notice says fixes for Home Mini and Nest Audio arrived in firmware 3.73. The current help page still advises 3.73 or newer. Our four devices exceed that threshold, so this establishes a relevant failure mechanism, not a diagnosis of the same old bug.
   - https://www.googlenestcommunity.com/t5/Speakers-and-Displays/Having-trouble-with-Speaker-groups-Read-this-first/m-p/628332 (indexed official community-manager notice; live legacy URLs may redirect following forum migration)
   - https://support.google.com/googlehome/answer/7174267?hl=en
2. PyChromecast documents multicast/mDNS discovery requirements; its SocketClient accepts a mutable service set specifically to accommodate leader handover. Moozaq passes CastInfo/services through rather than hard-coding port 8009 or the current leader. A stale fixed endpoint is not demonstrated here.
   - https://github.com/home-assistant-libs/pychromecast
   - installed `pychromecast/socket_client.py`, SocketClient docstring
3. PyChromecast offers a MultizoneController/MultizoneManager for member and group updates. Moozaq's normal engine currently does not register these. It confirms requested media PLAYING on the group receiver, not audible output from every member. This is a confirmed observability gap, not proof it causes missing audio.
   - https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/controllers/multizone.py
4. Individual devices have separate mute/volume controls. The observed Kitchen/Fridge/Bedroom devices were unmuted with nonzero volumes; no volume change was made. A configured member is not proof that it is actively rendering the group stream.
   - https://developers.google.com/cast/docs/reference/ios/interface_g_c_k_multizone_device

Next diagnostic implementation: label groups, monitor their current leader and membership, log connection transitions and receiver load results with target UUID, and distinguish group session PLAYING from unverified member participation. Do not claim '4/4 audible' from group membership alone. A later owner-authorized comparison of the same group using an established Cast app and a stable authorized audio source would separate Google/network behavior from Moozaq or extracted-URL behavior. Do not start comparison audio automatically, reload on transient failures, or fake synchronized playback by sending independent loads to each member.

Reusable read-only diagnostic: `mac-spike/.venv/bin/python mac-spike/tools/inspect_groups.py`. Its sanitized report is saved under ignored `mac-spike/data/group-diagnostics.json`. No router settings, membership, firmware, playback or volume were modified.

## Dynamic selection implementation gate — October 16, 2026

Owner explicitly requested functional individual selection checkboxes. Rechecked the installed PyChromecast controller and current upstream source: `MultizoneManager.add_multizone` / `remove_multizone` manage monitoring registrations, not remote group membership; `_add_member` / `_remove_member` update local state from incoming events. `update_members` and `get_casting_groups` are queries. There is no verified membership mutation method in this backend.

Primary references reviewed:
- https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/controllers/multizone.py
- https://developers.google.com/cast/docs/reference/web_sender/chrome.cast.Session
- https://developers.google.com/cast/docs/android_sender/output_switcher

Google documents stream expansion through Android 13+ Output Switcher for eligible Cast audio apps, with selected-device taps adding/removing devices. This requires Android sender/session integration and validation of receiver audio eligibility. Our shared Compose Android target is currently a library, not a functioning native Cast backend. The existing Java spike uses Cast SDK 22.3.1 but does not establish the full Output Switcher flow.

No functional Mac membership checkbox has been implemented: neither independent speaker loads nor muting is an equivalent group operation. No network mutation, playback, volume change, credentials, or saved-group change was attempted. This review does not prove that undocumented protocol methods are impossible; they remain unverified and are not a production implementation. Next supported implementation path is the Android Cast backend plus Output Switcher and fresh membership confirmation; arbitrary Mac grouping needs separate protocol research before UI enablement.

## Mac dynamic-group probe — October 16, 2026

Implemented `mac-spike/tools/grouping_probe.py`: read-only, sanitized snapshots of all discovered receivers, saved versus dynamic groups, group members, receiver application/session and position. `--compare PATH` reports topology/session changes; discovery disappearance is not treated as stopped playback. Snapshots are sequential observations, not proof of acoustic synchronization. Reports live under ignored `mac-spike/data/grouping-spike/`.

Fixed the older `inspect_groups.py` to install passive media status before connecting (its original status query could implicitly launch a receiver). Both tools now use the safe path. Three silent regression tests cover connection ordering, temporary versus saved topology and missing discovery.

Usage from repository root:

```sh
mac-spike/.venv/bin/python mac-spike/tools/grouping_probe.py --label baseline
# After an explicitly authorized membership change through Google Home:
mac-spike/.venv/bin/python mac-spike/tools/grouping_probe.py --label expanded --compare PATH_TO_BASELINE
# After removing that member:
mac-spike/.venv/bin/python mac-spike/tools/grouping_probe.py --label contracted --compare PATH_TO_EXPANDED
```

First live baseline: six targets found. Kitchen reported PLAYING in Default Media Receiver; All speakers retained four configured members but no active receiver app. All physical speakers returned empty dynamic_groups. No media load, launch, transport, volume, or topology mutation was sent by the probe. This is not yet a dynamic grouping success.

Additional protocol research:
- [Google Home GroupManagement Android reference](https://developers.home.google.com/reference/kotlin/com/google/home/google/GroupManagement) exposes createCastSpeakerGroup, addGroupMembers and removeGroupMembers. It does not by itself prove live stream expansion, Mac availability or account/device eligibility. It is an additional official avenue beyond the Output Switcher.
- [2017 Cast multiroom certification test specification, section 4](https://www.soundcertificate.com/files/ccbi4a/documents/CCBI4A_MR_TestSpec_v4.3.pdf) describes local HTTP join_group and disband_group operations. These configure persistent groups and leader roles; disband is permanent for a UUID. They are not evidence of modern temporary session expansion. No such mutation was attempted on the owner's saved group.

Next experiment: with explicit playback authorization, expand the current Kitchen session to Bedroom through Google Home and capture expanded/contracted snapshots. Verify temporary UUID, membership and receiver migration, unchanged saved group, continuity, and owner-audible synchronization. This establishes the native behavior to reproduce; it does not capture or establish the mutation command. A Mac command implementation remains gated on identifying and validating that actual protocol, rather than inventing command names or sending independent LOADs.

### First expansion observation

Google Home's current-stream selection created `Kitchen speaker + 1`, with Kitchen and Bedroom, even though Home showed a connection error. Two passive Mac observations confirmed the temporary group PLAYING and position advancing; saved-group membership stayed unchanged. Individual device media state became UNKNOWN because the authoritative media session moved to the temporary group. This is concrete evidence for following dynamic group identity during expansion. It is not yet a Mac mutation implementation or audible confirmation. See spike-results.md for measurements.

### Native Mac mutation result (supersedes earlier implementation gate)

Direct Mac SET_PLAYBACK_DEVICES over the Cast multizone namespace successfully removed Bedroom from the temporary session and then added it back. Both replies had no failed devices; passive checks verified resulting membership, receiver migration and continued PLAYING. This provides the previously missing Mac mutation path. It is an experimentally recovered protocol, not an officially supported public Mac SDK. Preserve the saved All speakers definition; use application session identity and reconcile returned endpoint plus fresh membership before confirming UI changes. No copying of decompiled Google code into the app: only the observed wire contract is implemented independently.

## Integrated Mac controls — October 16, 2026

Implemented native temporary membership in the Compose Mac bridge and speaker picker, superseding the earlier limitation/gate. Checkboxes represent the current session's physical participants; Control selects the remote separately. Fresh session capabilities, request binding, verified endpoint adoption and exact membership confirmation guard mutations. Delayed membership reports receive a bounded read-only convergence wait, without replaying SET or LOAD. Final-speaker removal uses Stop. The integrated live Kitchen/Bedroom contraction and expansion both succeeded; see spike-results.md for evidence and remaining visual/long-session checks.
