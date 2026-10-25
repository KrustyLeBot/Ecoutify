"""Light and dark palettes (the same colors as the Android app), following the Windows setting."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QGuiApplication, QPalette
from PySide6.QtWidgets import QApplication

DARK = dict(bg="#0B0B10", surface="#15151C", high="#1D1D26", text="#F2F2F7", dim="#A7A7B8", accent="#A99BFF", on_accent="#1A1442", outline="#2E2E3A")
LIGHT = dict(bg="#F8F7FC", surface="#EFEDF7", high="#E6E3F2", text="#16141F", dim="#5C5A6B", accent="#5B47E0", on_accent="#FFFFFF", outline="#D2CFE0")


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
    QPushButton {{ background: {c['high']}; border: none; border-radius: 8px; padding: 7px 14px; }}
    QPushButton:hover {{ background: {c['outline']}; }}
    QPushButton#primary {{ background: {c['accent']}; color: {c['on_accent']}; font-weight: bold; }}
    QLineEdit {{ background: {c['surface']}; border: 1px solid {c['outline']}; border-radius: 16px; padding: 7px 14px; }}
    QWidget#playerbar {{ background: {c['surface']}; border-top: 1px solid {c['outline']}; }}
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
