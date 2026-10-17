# Moozaq

An evolving music project for long-form, atmospheric mixes and home-speaker listening.

- [Current direction](docs/current-direction.md): Android first, with YouTube mixes on the owner's Nest devices as the initial use case.
- [Product brief v0.1](docs/product-brief.md): original October 2026 baseline, open to revision.
- [Starter videos](docs/starter-videos.md): six owner-selected YouTube mixes for playback testing.

The brief is a starting point, not a fixed specification. Later explicit decisions take precedence over its proposed architecture and roadmap.

## Cast engineering reference

[Google Cast and speaker-group lessons](docs/google-cast-lessons.md): discovery, passive monitoring, temporary grouping, recovery, confirmed controls, and validation limits.

## Playback spike

[Android prototype and test instructions](android-spike/README.md). Includes the six starter links, native Cast controls, receiver-state sync, a local test-audio server, and an exportable hardware-test checklist. Real Nest playback remains to be tested.

[Mac Cast Lab](mac-spike/README.md) compares direct YouTube audio handoff, a Mac relay, and cached-file playback. Launch `mac-spike/Moozaq Cast Lab.app`.

## Kotlin / Compose app

[Next-generation Mac app](compose-app/README.md): shared Kotlin/Compose browser and player, with a private bridge to the Python Cast engine. Run `./compose-app/Launch.command`. The Python reference is preserved. Shared UI compiles for Android; its native Cast adapter is still to come.
