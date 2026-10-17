# Moozaq Android: design handoff

Phone app, 412 x 915 dp, Jetpack Compose, Material 3 dark, Google Cast SDK.

Live prototype (Android row, source of truth): https://claude.ai/code/artifact/fe11b73f-d019-411f-80e6-dbe0403b0caa
Live spec doc: https://claude.ai/code/artifact/ba445999-1132-4138-b6fa-46700a15f702

## Contents

| Path | What it is |
| --- | --- |
| `Moozaq-Android-design-handoff.pdf` | Full spec: tokens, screens and states, navigation, Cast behavior, copy, TalkBack, open questions |
| `compose/Color.kt`, `Theme.kt`, `Type.kt`, `Dimens.kt` | Drop-in Compose theme (`MoozaqTheme`) and dp values |
| `res/values/colors.xml` | Same colors as XML resources (`mz_*`) |
| `res/drawable/ic_*.xml` | 12 Material icons as VectorDrawables, tinted by `colorControlNormal` |
| `tokens/tokens.json` | Platform-neutral tokens shared with desktop |
| `images/navigation.png` | Navigation diagram |
| `prototype-source/` | The four Android artboards (`*.dc.html`) and `canvas.json`, for reference |

## Notes

- `Type.kt` uses `FontFamily.Default` as a placeholder; swap in Roboto Flex.
- Use the Cast SDK's `MediaRouteButton`, media notification and introductory overlay in the app; the cast vectors here are for mockups.
- The prototype files render only inside the design canvas, and their images point at canvas-hosted assets (`/_blob/...`).
