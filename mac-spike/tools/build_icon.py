"""Render a Mac-specific rounded icon from the preserved owner-supplied SVG.
Run with DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib on Apple Silicon if needed.
Requires CairoSVG and the native cairo library; not needed to run the app.
"""
from pathlib import Path
import subprocess
import cairosvg
root=Path(__file__).resolve().parents[2]
source=(root/'assets/branding/Moozaq-AppIcon-B/source/moozaq-icon-ios-1024.svg').read_text()
inner=source[source.index('>')+1:source.rindex('</svg>')]
svg='''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
<defs><clipPath id="macCorners"><rect x="80" y="80" width="864" height="864" rx="194"/></clipPath></defs>
<g clip-path="url(#macCorners)"><svg x="80" y="80" width="864" height="864" viewBox="135 135 754 754">'''+inner+'''</svg></g></svg>'''
master=root/'assets/branding/macos/moozaq-macos.svg';master.parent.mkdir(parents=True,exist_ok=True);master.write_text(svg)
resources=root/'mac-spike/Moozaq Cast Lab.app/Contents/Resources'
cairosvg.svg2png(bytestring=svg.encode(),write_to=str(resources/'Moozaq.png'),output_width=1024,output_height=1024)
iconset=root/'mac-spike/build/Moozaq.iconset';iconset.mkdir(parents=True,exist_ok=True)
for points in (16,32,128,256,512):
 for scale in (1,2):
  size=points*scale;suffix='@2x' if scale==2 else ''
  cairosvg.svg2png(bytestring=svg.encode(),write_to=str(iconset/f'icon_{points}x{points}{suffix}.png'),output_width=size,output_height=size)
subprocess.run(['iconutil','-c','icns',str(iconset),'-o',str(resources/'Moozaq.icns')],check=True)
print('Generated rounded Mac icon from supplied SVG master')
