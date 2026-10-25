"""Recommendations: start only from downloaded tracks and playlists, reuse the YouTube Music radio of a few of them."""
from __future__ import annotations

import random
from concurrent.futures import ThreadPoolExecutor

from .models import Track


def recommend(catalog, store, history: set[str] | None = None, seeds_count: int = 5, limit: int = 40) -> list[Track]:
    known = list(dict.fromkeys([t for p in store.playlists() for t in p.tracks] + store.downloads()))
    seen = {t.url for t in known} | set(history or ())

    # one seed per artist, picked at random: every refresh gives other suggestions
    random.shuffle(known)
    seeds, artists = [], set()
    for track in known:
        if track.artist.lower() not in artists:
            artists.add(track.artist.lower())
            seeds.append(track)
        if len(seeds) == seeds_count:
            break
    if not seeds:
        return catalog.search("top hits musique")[:limit]

    def radio(seed: Track) -> list[Track]:
        try:
            return catalog.similar(seed.url)
        except Exception:
            return []

    with ThreadPoolExecutor(max_workers=4) as pool:
        lists = list(pool.map(radio, seeds))

    out: list[Track] = []
    songs: set[str] = set()  # the same song uploaded by several channels
    for i in range(max((len(x) for x in lists), default=0)):  # interleave to vary artists
        for items in lists:
            if i < len(items):
                track = items[i]
                key = "".join(c for c in (track.artist + track.title).lower() if c.isalnum())
                if track.url not in seen and key not in songs:
                    seen.add(track.url)
                    songs.add(key)
                    out.append(track)
    return out[:limit]
