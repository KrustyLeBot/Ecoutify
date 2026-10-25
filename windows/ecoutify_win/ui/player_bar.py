"""The bar at the bottom: cover, title, controls, seek bar and volume."""
from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QSlider, QStyle, QVBoxLayout, QWidget

from ..core.models import Track


def _time(ms: int) -> str:
    s = max(0, ms // 1000)
    return f"{s // 60}:{s % 60:02d}"


class PlayerBar(QWidget):
    def __init__(self, ctx):
        super().__init__()
        self.setObjectName("playerbar")
        self.ctx = ctx
        self.player = ctx.player
        style = self.style()

        self.cover = QLabel()
        self.cover.setFixedSize(56, 56)
        self.title = QLabel("Rien en lecture")
        self.title.setStyleSheet("font-weight: bold;")
        self.artist = QLabel("")
        self.artist.setObjectName("dim")
        text = QVBoxLayout()
        text.addWidget(self.title)
        text.addWidget(self.artist)
        info = QWidget()
        info.setLayout(text)
        info.setMinimumWidth(240)
        info.setMaximumWidth(340)

        self.prev = QPushButton()
        self.prev.setIcon(style.standardIcon(QStyle.StandardPixmap.SP_MediaSkipBackward))
        self.play = QPushButton()
        self.play.setObjectName("primary")
        self.play.setIcon(style.standardIcon(QStyle.StandardPixmap.SP_MediaPlay))
        self.next = QPushButton()
        self.next.setIcon(style.standardIcon(QStyle.StandardPixmap.SP_MediaSkipForward))
        self.prev.clicked.connect(self.player.previous)
        self.play.clicked.connect(self.player.toggle)
        self.next.clicked.connect(self.player.next)

        self.position = QSlider(Qt.Orientation.Horizontal)
        self.position.sliderReleased.connect(lambda: self.player.seek(self.position.value()))
        self.elapsed = QLabel("0:00")
        self.total = QLabel("0:00")
        self.volume = QSlider(Qt.Orientation.Horizontal)
        self.volume.setRange(0, 100)
        self.volume.setValue(70)
        self.volume.setMaximumWidth(110)
        self.volume.valueChanged.connect(self.player.set_volume)

        row = QHBoxLayout(self)
        row.addWidget(self.cover)
        row.addWidget(info)
        row.addWidget(self.prev)
        row.addWidget(self.play)
        row.addWidget(self.next)
        row.addWidget(self.elapsed)
        row.addWidget(self.position, 1)
        row.addWidget(self.total)
        row.addWidget(QLabel("🔊"))
        row.addWidget(self.volume)

        self.player.track_changed.connect(self._track)
        self.player.playing_changed.connect(self._playing)
        self.player.position_changed.connect(self._position)
        self.player.duration_changed.connect(self._duration)
        ctx.library_changed.connect(lambda: self._track(self.player.current))

    def _track(self, track: Track | None) -> None:
        self.title.setText(track.title if track else "Rien en lecture")
        self.artist.setText(track.artist if track else "")
        pixmap = self.ctx.covers.pixmap(track, 56) if track else None
        self.cover.setPixmap(pixmap) if pixmap else self.cover.clear()

    def _playing(self, playing: bool) -> None:
        icon = QStyle.StandardPixmap.SP_MediaPause if playing else QStyle.StandardPixmap.SP_MediaPlay
        self.play.setIcon(self.style().standardIcon(icon))

    def _position(self, ms: int) -> None:
        if not self.position.isSliderDown():
            self.position.setValue(ms)
        self.elapsed.setText(_time(ms))

    def _duration(self, ms: int) -> None:
        self.position.setRange(0, ms)
        self.total.setText(_time(ms))
