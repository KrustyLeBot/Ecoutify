"""Smoke tests: the whole window is built offscreen with a fake catalog and used like a person would."""
from pathlib import Path

import pytest

pytest.importorskip("PySide6")

from PySide6.QtWidgets import QApplication

from ecoutify_win.core.models import Track


class FakeConfig:
    def __init__(self, workdir: Path):
        self.workdir = workdir


class FakeCatalog:
    def search(self, q):
        return [Track("https://www.youtube.com/watch?v=SEARCH00001", "Hit", "Star", 200)]

    def similar(self, url):
        return []

    def playlist(self, link):
        return "Imported", [Track("https://www.youtube.com/watch?v=IMPORT00001", "Imp", "Art", 180)]

    def audio_source(self, url):
        raise RuntimeError("offline in tests")


@pytest.fixture
def app():
    return QApplication.instance() or QApplication([])


@pytest.fixture
def window(app, tmp_path):
    from ecoutify_win.context import AppContext
    from ecoutify_win.ui.main_window import MainWindow
    from ecoutify_win.ui.theme import apply_theme

    apply_theme(app)
    ctx = AppContext(FakeConfig(tmp_path / "Ecoutify"), FakeCatalog())
    win = MainWindow(ctx)
    win.show()
    yield win
    ctx.player.stop()
    win.close()


def test_playlist_flow(window, tmp_path):
    ctx = window.ctx
    t = Track("https://www.youtube.com/watch?v=AAAAAAAAAA1", "One", "A", 100)
    playlist = ctx.create_playlist("Mix", [t])
    window.library.reload()
    assert window.library.table.topLevelItemCount() == 1
    ctx.rename_playlist(playlist, "Renamed")
    window.library.reload()
    assert window.library.name.text() == "Renamed"
    (ctx.store.music_dir / "A - One [AAAAAAAAAA1].m4a").write_bytes(b"x")  # a download that arrives from the phone
    ctx.store.invalidate()
    ctx.library_changed.emit()
    assert window.library.table.topLevelItem(0).text(3) == "✓"
    assert "1/1" in window.library.info.text()
    ctx.remove_from_playlist(ctx.store.playlists()[0], t)
    assert not (ctx.store.music_dir / "A - One [AAAAAAAAAA1].m4a").exists()  # freed: no playlist has it any more


def test_workdir_switch_and_persistence(window, tmp_path):
    other = tmp_path / "Other"
    window.ctx.set_workdir(other)
    assert window.ctx.config.workdir == other and (other / "music").is_dir()


def test_player_queue_always_loops_and_shuffles(window, monkeypatch):
    player = window.ctx.player
    played = []
    monkeypatch.setattr(player, "_play_index", lambda i: played.append(i))
    tracks = [Track(f"https://www.youtube.com/watch?v=TRACK00000{i}", f"T{i}", "A", 100) for i in range(4)]
    monkeypatch.setattr(window.ctx, "online", lambda: True)
    player.play_queue(tracks, 2)
    assert played == [2]
    for _ in range(9):  # two and a bit laps
        player.next()
    assert len(played) == 10
    for lap in (played[0:4], played[4:8]):
        assert sorted(lap) == [0, 1, 2, 3]  # every track exactly once per lap: random order, nothing skipped
    player.previous()
    assert played[-1] == played[-2] or len(played) == 11
