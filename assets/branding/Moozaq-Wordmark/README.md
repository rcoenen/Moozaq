# Moozaq wordmark handoff

The neon wordmark: geometric monoline letters with the icon's stacked 70s stripes (cream, orange, groove, amber, burnt orange), a neon tube through the front layer, and a faint amber glow.

## Which file to use

| Use | File |
|---|---|
| Default, on dark backgrounds | `svg/moozaq-wordmark-neon.svg` |
| Same, with the navy background baked in | `svg/moozaq-wordmark-neon-on-navy.svg` |
| Small sizes, single colour, print, embroidery, stamps | `svg/moozaq-wordmark-cream.svg`, `-navy`, `-black`, `-white` |
| With tagline | `lockups/moozaq-wordmark-tagline.svg` (and `-on-navy`) |
| Raster fallback (email, slides, social) | `png/`, 640, 1280 and 2560 px wide |

All files in `svg/` and `lockups/` are fully outlined filled shapes: no strokes, no fonts, no filters. They render the same in every browser, Figma, Illustrator, Android and iOS.

`svg/editable/` holds the same artwork built from strokes on one shared letter path. Use those only to edit the design (change stripe colours, stroke weight, spacing); export from them and re-outline before shipping.

## Rules

- **Background:** the neon version is made for midnight navy `#0D1322` or similarly dark backgrounds. On light backgrounds use the navy or black flat version.
- **Clear space:** keep empty space around the wordmark at least half the height of the "o" on every side.
- **Minimum size:** neon at 200 px wide or more on screen. Below that the stripes blur together, so switch to a flat version (minimum 80 px wide).
- **Don't:** stretch or skew it, recolour individual stripes, add drop shadows or outlines, set it on busy photos, or rebuild it in a font.

## Colours

| Role | Hex |
|---|---|
| Background, midnight navy | `#0D1322` |
| Top stripe, cream | `#F8D38A` |
| Stripe 2, orange | `#F2921F` |
| Groove | `#0D1322` |
| Stripe 4, amber | `#F07F1C` |
| Front, burnt orange | `#D2461E` |
| Neon tube | `#F8DFA8`, core `#FFF7E6` |
| Glows | `#F2921F` at 5 and 8%, `#FFC46B` at 16%, `#FFD27A` at 28% |
| Tagline | `#CFC4AC` |

## Tagline

"Let your mood run." is set in **Jost Regular** (Google Fonts, SIL Open Font License), slightly tracked (+20). In the lockup files it is already outlined. For live text in the app or on the web, load Jost from Google Fonts.
