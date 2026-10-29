

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
