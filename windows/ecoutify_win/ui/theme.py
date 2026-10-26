"""Light and dark palettes (the same colors as the Android app), following the Windows setting."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QGuiApplication, QPalette
from PySide6.QtWidgets import QApplication

DARK = dict(bg="#0A0F0E", surface="#131B1A", high="#192120", text="#DCE8E5", dim="#A2ADAA", accent="#5ADACA", on_accent="#003732", outline="#3F4A48")
LIGHT = dict(bg="#F6FAF8", surface="#E7F0ED", high="#E1EAE7", text="#2A3432", dim="#56615F", accent="#006A61", on_accent="#FFFFFF", outline="#A9B4B1")


def _palette(c: dict) -> QPalette:
    p = QPalette()
    roles = {
        QPalette.Window: c["bg"], QPalette.WindowText: c["text"], QPalette.Base: c["surface"], QPalette.AlternateBase: c["high"],
        QPalette.Text: c["text"], QPalette.Button: c["high"], QPalette.ButtonText: c["text"], QPalette.ToolTipBase: c["high"],
        QPalette.ToolTipText: c["text"], QPalette.Highlight: c["accent"], QPalette.HighlightedText: c["on_accent"],
        QPalette.PlaceholderText: c["dim"], QPalette.Link: c["accent"],
    }
    for role, color in roles.items():
        p.setColor(role, QColor(color))
    p.setColor(QPalette.Disabled, QPalette.Text, QColor(c["dim"]))
    p.setColor(QPalette.Disabled, QPalette.ButtonText, QColor(c["dim"]))
    return p


def _stylesheet(c: dict) -> str:
    return f"""
    QListWidget#nav {{ background: {c['surface']}; border: none; outline: 0; font-size: 14px; }}
    QListWidget#nav::item {{ padding: 12px 18px; border-radius: 10px; margin: 2px 8px; }}
    QListWidget#nav::item:selected {{ background: {c['high']}; color: {c['accent']}; }}
    QListWidget#playlists::item {{ padding: 8px 10px; border-radius: 8px; }}
    QListWidget#playlists::item:selected {{ background: {c['high']}; color: {c['accent']}; }}
    QTreeWidget, QListWidget {{ border: none; outline: 0; }}
    QTreeWidget::item {{ padding: 3px 0; }}
    QHeaderView::section {{ background: {c['bg']}; color: {c['dim']}; border: none; padding: 6px; }}
    QPushButton {{ background: transparent; color: {c['accent']}; border: none; border-radius: 18px; padding: 8px 16px; }}
    QPushButton:hover {{ background: {c['high']}; }}
    QPushButton#primary {{ background: {c['accent']}; color: {c['on_accent']}; }}
    QLineEdit {{ background: {c['surface']}; border: 1px solid {c['outline']}; border-radius: 16px; padding: 7px 14px; }}
    QWidget#playerbar {{ background: {c['surface']}; border-top: 1px solid {c['outline']}; }}
    QFrame#toast {{ background: {c['high']}; border: 1px solid {c['outline']}; border-radius: 16px; }}
    QProgressBar {{ background: {c['outline']}; border: none; border-radius: 2px; }}
    QProgressBar::chunk {{ background: {c['accent']}; border-radius: 2px; }}
    QLabel#dim {{ color: {c['dim']}; }}
    QLabel#title {{ font-size: 20px; font-weight: bold; }}
    QSlider::groove:horizontal {{ height: 4px; background: {c['outline']}; border-radius: 2px; }}
    QSlider::sub-page:horizontal {{ background: {c['accent']}; border-radius: 2px; }}
    QSlider::handle:horizontal {{ background: {c['accent']}; width: 12px; margin: -5px 0; border-radius: 6px; }}
    """


def apply_theme(app: QApplication) -> None:
    def apply() -> None:
        dark = QGuiApplication.styleHints().colorScheme() != Qt.ColorScheme.Light
        colors = DARK if dark else LIGHT
        app.setStyle("Fusion")
        app.setPalette(_palette(colors))
        app.setStyleSheet(_stylesheet(colors))

    apply()
    QGuiApplication.styleHints().colorSchemeChanged.connect(lambda _: apply())
