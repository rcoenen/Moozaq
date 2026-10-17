# Moozaq UI: design handoff

Desktop web player, 1428 x 830, Material 3 dark theme with Google Cast conventions.

Live prototype (source of truth): https://claude.ai/code/artifact/fe11b73f-d019-411f-80e6-dbe0403b0caa
Live spec doc: https://claude.ai/code/artifact/85833486-35d2-44df-9ee2-d6819fbe5eba

## Contents

| Path | What it is |
| --- | --- |
| `Moozaq-UI-design-handoff.pdf` | The full spec: tokens, icons, every screen and state, copy, Cast gaps, accessibility, open questions |
| `images/playback-states.png` | Playback state diagram |
| `tokens/tokens.json` | Color roles, type scale, radii, motion, state opacities |
| `tokens/tokens.css` | The same as CSS custom properties (`--md-sys-color-*`, `--md-sys-typescale-*`), plus the Roboto Flex import |
| `icons/*.svg` | The 12 Material icons used, 24 px grid, `currentColor` fill |
| `prototype-source/` | Source of each artboard (`*.dc.html`) and the canvas layout (`canvas.json`) |

## Notes

- `prototype-source` files are reference markup for layout, states and logic. They render only inside the design canvas, and their images point at canvas-hosted assets (`/_blob/...`). Open the live prototype to see them.
- Cast icons must come from the Google Cast SDK in production; the SVGs here are for mockups.
- Latencies in the prototype are simulated. In production, end each pending state on the Cast receiver's status event.
