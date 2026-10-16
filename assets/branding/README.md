# Moozaq branding

Original icon handoff supplied by the owner from `Moozaq-AppIcon.zip`, imported October 16, 2026. The complete handoff is preserved in `Moozaq-AppIcon/`, including SVG masters, Android resources and iOS assets.

Android uses the supplied adaptive and legacy launcher resources. The Mac app's `Moozaq.icns` and PNG are generated from the supplied SVG master with Mac-specific rounded corners and transparent padding. The derivative lives in `macos/moozaq-macos.svg`; originals are unchanged. Rebuild using `mac-spike/tools/build_icon.py` (CairoSVG, native cairo and iconutil). The running Tk app uses the same rounded PNG.

## Active icon: version B

The owner supplied `Moozaq-AppIcon-B.zip` on October 16, 2026. Its complete contents are preserved in `Moozaq-AppIcon-B/`; the original handoff remains untouched. Android launcher resources now use version B. The macOS icon generator reads the B SVG master and preserves the rounded mask and transparent padding. Both the Mac bundle icon and in-app header use this new design. iOS assets are preserved for future use.
