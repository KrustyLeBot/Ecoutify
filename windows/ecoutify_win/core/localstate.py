"""Settings of this PC only (not synced): the last recommendations, kept to show them offline."""
from __future__ import annotations

import json
from pathlib import Path

from .models import Track


class LocalState:
    def __init__(self, path: Path):
        self.path = Path(path)

    def _read(self) -> dict:
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def recommendations(self) -> list[Track]:
        try:
            return [Track.from_json(o) for o in self._read().get("recs", [])]
        except (KeyError, TypeError):
            return []

    def save_recommendations(self, tracks: list[Track]) -> None:
        data = self._read()
        data["recs"] = [t.to_json() for t in tracks]
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
