# Moozaq app icon handoff

Icon v2. One ribbon of arcs and straight lines, four concentric round-capped bands.

## Palette

| Role | Hex |
|---|---|
| Background, midnight navy | `#0D1322` |
| Outer band, burnt orange | `#C2421D` |
| Band 2, amber orange | `#E8742A` |
| Band 3, golden yellow | `#EFAA3C` |
| Core, cream | `#F6E2B3` |

## Android

Copy `android/res/` into `app/src/main/res/` (merge folders, overwrite existing launcher files).

- `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`: adaptive icon (API 26+). Background is a color, foreground is a vector drawable, so it is sharp at every density.
- `drawable/ic_launcher_monochrome.xml`: themed icon for Android 13+ (Material You). Solid M silhouette; the system tints it.
- `mipmap-*/ic_launcher.png` and `ic_launcher_round.png`: legacy fallback for API 24 and 25, 48dp at mdpi through xxxhdpi.
- `mipmap-*/ic_launcher_foreground.png`: raster foreground layer, only for tools that need PNG. The app uses the XML.
- Manifest, if not already set:
  `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`
- `play-store/moozaq-play-store-512.png`: Google Play listing icon, 512 x 512, 32-bit, full-bleed square. Play applies its own corner rounding.

The mark sits inside the 66dp safe circle of the 108dp layer, so no launcher mask clips it.

## iOS

Replace `AppIcon.appiconset` in your project's `Assets.xcassets` with `ios/Assets.xcassets/AppIcon.appiconset`.

- `AppIcon-1024.png`: default (light) icon, opaque RGB, no alpha. Xcode 14+ generates all device sizes from it.
- `AppIcon-1024-dark.png`: iOS 18+ dark appearance. Transparent background; the system draws its dark backdrop behind the mark.
- `AppIcon-1024-tinted.png`: iOS 18+ tinted appearance. Grayscale on black; the system applies the user's tint.
- `legacy-sizes/`: individual PNGs (40 to 180 px plus 1024 marketing) for older Xcode versions or other tooling. Not referenced by `Contents.json`.
- `icon-composer-layers/`: background plus one SVG per band, in stacking order (01 at the back), for building a layered `.icon` file in Apple's Icon Composer for the iOS 26 Liquid Glass style. Import the five files as separate layers in this order.

Do not add rounded corners or transparency to the light icon. iOS applies its own mask.

## Source

`source/` holds the master SVGs: full icon, adaptive foreground, background, monochrome, and the iOS crop. Edit these and re-export rather than editing PNGs.
