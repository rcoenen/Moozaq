# Compose implementation of the design handoff

Implemented October 17, 2026 from the owner-supplied archive. `README.md`, the PDF, prototypes, tokens and SVG files in this directory are the original reference. Prototype code is reference material and is not executed by the app.

## Mapping

- `Design.kt`: Material 3 dark color roles, Roboto Flex and typography; pending slider palette.
- `MixCard.kt`: 240 × 135 artwork, two-line title, creator, hover/focus play action, current-track outline and equalizer. Cards select without loading media.
- `PlayerBar.kt`: 136 dp persistent player; confirmed transport state, held seek/volume intent, verified Stop casting, last-track Play again and fresh-start placeholder.
- `SpeakerPicker.kt`: 480 dp anchored popup above the Cast chip, blurred/dimmed background, 52 dp rows, device glyphs, checkboxes and inline volumes. Escape/close restores chip focus.
- `ChannelsDialog.kt`: 560 dp channel manager, per-source refresh state, expandable URL input and hidden debug info.

## Integration decisions

The desktop app retains its existing Python Cast adapter. The handoff's simulated timers are not confirmation: real status/revisions end pending states. Stop advances the transport revision only after `stop_verified()` succeeds. Removing the last participant uses this Stop path, never an empty native membership update.

Idle checkboxes remain exclusive playback destinations; live physical checkboxes remain native session membership. Clicking a device name observes its remote without loading music. Unsupported receivers are not presented as supported native audio-group participants. Selecting a destination alone never transfers a track.

Channels refresh serially through the existing metadata worker. Rows show queued/in-progress sources and clear individually as each completes. The handoff's fake parallel timing is not copied. Short and unknown-duration videos stay out of browse shelves.

The Mac uses the supplied Material Cast vectors and a connection spinner. These are not the Android Cast SDK's animated Cast button and do not imply Cast certification. The shared Android library builds, but a native Android Cast adapter and responsive phone layout remain separate work. Roboto Flex regular is bundled; Compose supplies the requested heavier type styles. The font license is included.

The desktop starts at 1428 × 830 with a minimum of 1100 × 650. Reduce motion is available under Channels → Debug info for decorative animations; it is currently an in-session preference. The missing design-canvas runtime and hosted prototype artwork are not dependencies of Moozaq; the app uses its existing cached catalog artwork.

## Validation

Silent control regressions cover last-participant Stop routing, pending acknowledgement, failed Stop without retry, last-track retention and channel refresh progress after errors. A separate `:composeApp:designPreview` window runs in-memory fixtures only; it cannot contact speakers. Build, unit test and visual results are recorded in `../spike-results.md`. No audible test is implied by these checks.

## Native Mac window appearance

The title bar must use dark macOS chrome to match the always-dark Compose content. Set `apple.awt.application.appearance=NSAppearanceNameDarkAqua` as a JVM launch argument before AWT/AppKit initializes. The setting is applied to packaged launches, Gradle run and the silent design preview; it does not change macOS-wide appearance or remove native window controls. OpenJDK's [AppKit initialization](https://github.com/openjdk/jdk/blob/master/src/java.desktop/macosx/native/libosxapp/NSApplicationAWT.m) otherwise chooses Aqua by default.
