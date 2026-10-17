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

## Phone implementation (October 17, 2026)

Implemented from the Android handoff in `android/`. The phone layout is used below 600 dp and replaces the earlier compact bar; the desktop screen above 600 dp is unchanged.

## Mapping

- `Dimens.kt`: every dp value from the handoff (screen margin, 64 dp app bar, 200 × 112 cards, 64 dp mini player, 364 × 205 cover, 96 dp play/pause, 28 dp sheet corners, 16/12 dp slider tracks, 4 dp thumb) plus the motion constants.
- `Design.kt`: adds `titleSmall` (14/20 600) and `bodySmall` (12/16 400) to complete the phone type scale, and `playerTitleStyle()` for the bold 22/28 Now playing title.
- `MzControls.kt`: flat-track sliders matching the handoff geometry inside a 44 dp hit area, the 900 ms equalizer with a 300 ms per-bar offset, the 1 s linear spinner and the 18 dp checkbox in a 48 dp target.
- `PhoneModel.kt`: pure derivation of title, creator, device name, status and card state from confirmed receiver state plus one explicit pending request. Eight tests in `PhonePresentationTest` cover stopped, first launch, paused, casting, starting, seeking/pausing and failed requests.
- `PhoneBrowse.kt`: app bar, shelves, shelf snapping, card states, the 168 dp bottom fade and the mini player.
- `PhoneNowPlaying.kt`: expanded controller with seek, session volume and Stop casting.
- `PhoneCastTo.kt`: modal sheet with one card per device; behaviour still routes through `canToggleMembership`, `canSelectDestination` and `canAdjustOutputVolume`.
- `PhoneApp.kt`: screen state, back handling, the error snackbar and sheet/dialog layering.
- `SystemMotion.kt`: `systemReducedMotion()` with an Android actual reading the system animation scale and a desktop stub.

## Integration decisions

The Cast button is our own vector opening the Cast to sheet, not the SDK `MediaRouteButton`: Moozaq owns destination selection and confirmation, and the handoff itself marks its cast vectors mockup-only. Slider and checkbox states follow the handoff geometry but keep the product's intent → pending → confirmed rules, so a pending control is grey and locked and a failed one is tinted as an error rather than rolled back.

A pending Stop shows "Stopping…" even though the handoff table has no Stopping row, because reporting a stop before receiver confirmation is forbidden. Devices whose volume has not been read show a disabled slider rather than an invented level. The prototype's simulated delays are not copied; every pending state ends on fresh receiver state.

The handoff's open questions are still open: no Stop casting inside the sheet, no group-volume scaling decision, device order as the SDK returns it, and plain back for collapsing Now playing.
