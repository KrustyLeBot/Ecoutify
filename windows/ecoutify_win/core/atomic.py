"""Atomic file writes: the phone, Synchrotron or the server never read half a file."""
from __future__ import annotations

import os
import uuid
from pathlib import Path


def temp_name(name: str) -> str:
    """`.<name>.synctmp-<uuid>`: never synced by Synchrotron, reserved for temporaries."""
    return f".{name}.synctmp-{uuid.uuid4().hex}"


def is_temp(name: str) -> bool:
    return name.startswith(".") and (".synctmp-" in name or name.endswith(".tmp"))


def write_bytes(target: Path, data: bytes) -> None:
    """Writes to a temporary in the same folder, flushed to disk, then renamed over target
    (also atomic on an SMB share). The temporary is removed on error."""
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(temp_name(target.name))
    try:
        with open(tmp, "xb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, target)
    except BaseException:
        try:
            tmp.unlink()
        except OSError:
            pass
        raise


def write_text(target: Path, text: str) -> None:
    write_bytes(target, text.encode("utf-8"))


class UnreadableFile(OSError):
    """A synced file that cannot be parsed, even when read again: reported, never overwritten."""

    def __init__(self, path: Path):
        super().__init__(f"{Path(path).name} est illisible : il n'est pas modifié")


def read_json(path: Path):
    """Parsed content of path, None when missing. A sync may be replacing the file: a parse
    failure is retried once before the file is declared unreadable (UnreadableFile)."""
    import json
    import time

    for attempt in range(2):
        try:
            return json.loads(Path(path).read_text(encoding="utf-8"))
        except FileNotFoundError:
            return None
        except (OSError, ValueError):
            if attempt == 0:
                time.sleep(0.3)
    raise UnreadableFile(path)
