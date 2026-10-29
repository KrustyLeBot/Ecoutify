

def test_unreadable_playlists_are_never_overwritten(tmp_path):
    import pytest
    from ecoutify_win.core import atomic
    from ecoutify_win.core.store import Store

    store = Store(tmp_path)
    store.playlists_file.write_text("[{broken", encoding="utf-8")
    assert store.playlists() == []
    with pytest.raises(atomic.UnreadableFile):
        store.create_playlist("Nouvelle")
    assert store.playlists_file.read_text(encoding="utf-8") == "[{broken"


def test_playlists_written_atomically(tmp_path):
    from ecoutify_win.core.store import Store

    store = Store(tmp_path)
    store.create_playlist("A")
    assert [p.name for p in store.playlists()] == ["A"]
    assert [p.name for p in tmp_path.iterdir() if ".synctmp-" in p.name] == []


def test_snapshot_reads_folder_listings_only(tmp_path, monkeypatch):
    """The change check must not stat every music file (one network round trip each on the share)."""
    import os
    from pathlib import Path
    from ecoutify_win.context import AppContext

    music = tmp_path / "music"
    music.mkdir()
    for i in range(50):
        (music / f"A - T{i} [abcdefghij{i % 10}].m4a").write_bytes(b"x")
    ctx = AppContext.__new__(AppContext)
    from ecoutify_win.core.store import Store
    ctx.store = Store(tmp_path)
    calls = []
    real = Path.stat
    monkeypatch.setattr(Path, "stat", lambda self, *a, **k: (calls.append(self), real(self, *a, **k))[1])
    first = ctx._take_snapshot()
    assert len(calls) <= 1  # only playlists.json
    (music / "B - New [zzzzzzzzzzz].m4a").write_bytes(b"x")
    assert ctx._take_snapshot() != first
