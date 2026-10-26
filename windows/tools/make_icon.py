"""Draws the Ecoutify icon, the same as the Android launcher icon (teal gradient, white music note), as icon.png and icon.ico.

Usage: python tools/make_icon.py   (needs PySide6 and Pillow)
"""
import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PIL import Image
from PySide6.QtCore import QByteArray
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

# The launcher icon's 108-unit grid, cropped to its visible 72-unit square.
SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">
  <defs><linearGradient id="g" x1="10" y1="0" x2="98" y2="108" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#3FD4C2"/><stop offset="1" stop-color="#0A6E66"/></linearGradient></defs>
  <rect x="18" y="18" width="72" height="72" rx="16" fill="url(#g)"/>
  <g transform="translate(13 24) scale(2.5)">
    <path fill="#FFFFFF" d="M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"/>
  </g>
  <g fill="none" stroke="#B5F5EC" stroke-width="3.5" stroke-linecap="round">
    <path d="M60,48 A9,9 0 0 1 60,62"/><path d="M66,42 A16,16 0 0 1 66,68"/>
  </g>
</svg>"""

out = Path(__file__).resolve().parent.parent / "ecoutify_win" / "assets"
out.mkdir(parents=True, exist_ok=True)
app = QGuiApplication(sys.argv)
image = QImage(256, 256, QImage.Format.Format_ARGB32)
image.fill(0)
painter = QPainter(image)
painter.setRenderHint(QPainter.RenderHint.Antialiasing)
QSvgRenderer(QByteArray(SVG.encode())).render(painter)
painter.end()
image.save(str(out / "icon.png"))
Image.open(out / "icon.png").save(out / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("written", out)
