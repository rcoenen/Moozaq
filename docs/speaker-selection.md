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
