import json
from pathlib import Path

from ecoutify_win.core.catalog import _seconds, looks_like_music, track_from_ytmusic
from ecoutify_win.core.models import Playlist, Track, normalize_url, video_id
from ecoutify_win.core.recommender import recommend
from ecoutify_win.core.store import Store, track_from_file
from ecoutify_win.services.updater import parse_release

VID = "dQw4w9WgXcQ"


def track(vid=VID, title="Never Gonna Give You Up", artist="Rick Astley", duration=213):
    return Track(f"https://www.youtube.com/watch?v={vid}", title, artist, duration)


def test_url_normalisation():
    assert normalize_url(f"https://music.youtube.com/watch?v={VID}&list=x") == f"https://www.youtube.com/watch?v={VID}"
    assert video_id("https://example.com/") is None


def test_file_naming_matches_android(tmp_path):
    store = Store(tmp_path)
    t = Track(f"https://www.youtube.com/watch?v={VID}", "REGULATION RELOADED", "Vald, Vladimir Cauchemar & TODIEFOR")
    assert store.file_for(t, "m4a").name == f"Vald, Vladimir Cauchemar & TODIEFOR - REGULATION RELOADED [{VID}].m4a"
    odd = Track(f"https://www.youtube.com/watch?v={VID}", 'A/B: "C" - D', "X - Y")
    assert store.file_for(odd, "webm").name == f"X Y - A_B_ _C_ D [{VID}].webm"


def test_downloads_are_read_from_the_folder(tmp_path):
    store = Store(tmp_path)
    store.ensure_dirs()
    (store.music_dir / f"Rick Astley - Never Gonna Give You Up [{VID}].m4a").write_bytes(b"x")
    (store.music_dir / f"Other [AAAAAAAAAAA].m4a.part").write_bytes(b"x")  # partial files are ignored
    (store.music_dir / "notes.txt").write_text("x")
    found = store.downloads()
    assert [(t.artist, t.title, t.id) for t in found] == [("Rick Astley", "Never Gonna Give You Up", VID)]
    assert store.downloaded_file(f"https://music.youtube.com/watch?v={VID}") is not None
    assert track_from_file(Path("Title only [BBBBBBBBBBB].mp3")).artist == ""


def test_reads_the_playlists_json_written_by_android(tmp_path):
    android = [{"id": "p1", "name": "Road trip", "keepOffline": True,
                "tracks": [{"url": f"https://music.youtube.com/watch?v={VID}", "title": "T", "artist": "A", "duration": 213, "thumb": None}]}]
    (tmp_path / "playlists.json").write_text(json.dumps(android), encoding="utf-8")
    playlists = Store(tmp_path).playlists()
    assert playlists[0].keep_offline and playlists[0].tracks[0].url == f"https://www.youtube.com/watch?v={VID}"
    assert playlists[0].tracks[0].thumb is None


def test_playlist_operations_roundtrip(tmp_path):
    store = Store(tmp_path)
    p = store.create_playlist("Mix")
    store.add_to_playlist(p.id, track())
    store.add_to_playlist(p.id, track())  # no duplicates
    store.rename_playlist(p.id, "Renamed")
    store.set_keep_offline(p.id, True)
    again = Store(tmp_path).playlists()[0]
    assert (again.name, again.keep_offline, len(again.tracks)) == ("Renamed", True, 1)
    assert store.in_any_playlist(f"https://music.youtube.com/watch?v={VID}")
    store.remove_from_playlist(p.id, track())
    assert not store.in_any_playlist(track().url)
    store.delete_playlist(p.id)
    assert store.playlists() == []
    # the file is plain JSON with the keys the Android app expects
    store.create_playlist("X", [track()])
    raw = json.loads((tmp_path / "playlists.json").read_text(encoding="utf-8"))[0]
    assert set(raw) == {"id", "name", "keepOffline", "tracks"} and set(raw["tracks"][0]) == {"url", "title", "artist", "duration", "thumb"}


def test_changes_from_the_phone_are_seen_after_invalidate(tmp_path):
    store = Store(tmp_path)
    store.ensure_dirs()
    assert store.downloads() == []
    (store.music_dir / f"A - B [{VID}].m4a").write_bytes(b"x")
    assert store.downloads() == []  # cached
    store.invalidate()
    assert len(store.downloads()) == 1


def test_music_filter():
    assert looks_like_music(track())
    assert not looks_like_music(track(duration=3000))
    assert not looks_like_music(track(title="Interview exclusive"))
    assert _seconds("3:21") == 201 and _seconds("1:02:03") == 3723 and _seconds(None) == 0


def test_ytmusic_result_to_track():
    d = {"videoId": VID, "title": "Song (Official Video)", "artists": [{"name": "A"}, {"name": "B"}], "duration": "3:21",
         "thumbnails": [{"url": "small", "width": 60}, {"url": "big", "width": 544}]}
    t = track_from_ytmusic(d)
    assert (t.title, t.artist, t.duration, t.thumb) == ("Song", "A, B", 201, "big")
    assert track_from_ytmusic({"videoId": VID, "isAvailable": False}) is None


class FakeCatalog:
    def search(self, q):
        return [track("SEARCH000001", "Hit", "Star")]

    def similar(self, url):
        return [track(f"SIMILAR{url[-4:]}"[:11].ljust(11, "0"), "Song " + url[-3:], "Artist " + url[-2:])]


def test_recommendations_start_from_playlists_and_downloads(tmp_path):
    store = Store(tmp_path)
    assert recommend(FakeCatalog(), store)[0].title == "Hit"  # nothing known yet: generic suggestions
    store.create_playlist("P", [track("AAAAAAAAAA1", "One", "A1"), track("AAAAAAAAAA2", "Two", "A2")])
    out = recommend(FakeCatalog(), store)
    assert out and all(t.url not in {"https://www.youtube.com/watch?v=AAAAAAAAAA1", "https://www.youtube.com/watch?v=AAAAAAAAAA2"} for t in out)


def test_release_parsing():
    data = {"name": "Ecoutify build 42", "assets": [{"name": "ecoutify.apk", "browser_download_url": "a", "size": 1},
                                                     {"name": "Ecoutify.exe", "browser_download_url": "b", "size": 2}]}
    release = parse_release(data)
    assert (release.build, release.url, release.size) == (42, "b", 2)
    assert parse_release({"name": "no number", "assets": []}) is None
    assert parse_release({"name": "build 5", "assets": [{"name": "ecoutify.apk", "browser_download_url": "a"}]}) is None
