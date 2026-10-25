"""Settings kept between runs (Windows registry via QSettings): the working folder."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSettings


class Config:
    def __init__(self):
        self._settings = QSettings("Ecoutify", "EcoutifyWindows")

    @property
    def workdir(self) -> Path | None:
        value = self._settings.value("workdir", "")
        return Path(value) if value else None

    @workdir.setter
    def workdir(self, path: Path) -> None:
        self._settings.setValue("workdir", str(path))
        self._settings.sync()
