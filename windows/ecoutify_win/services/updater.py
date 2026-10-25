"""Self-update from the 'latest' GitHub release (published by the CI with the APK and the EXE)."""
from __future__ import annotations

import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import requests

from .. import _version

API = "https://api.github.com/repos/KrustyLeBot/Ecoutify/releases/tags/latest"
_UA = "Ecoutify-Windows/1.0 (https://github.com/KrustyLeBot/Ecoutify)"


@dataclass
class Release:
    build: int
    url: str
    size: int


def current_build() -> int:
    return int(_version.BUILD)


def can_self_update() -> bool:
    """Only the packaged .exe can replace itself."""
    return bool(getattr(sys, "frozen", False))


def parse_release(data: dict) -> Release | None:
    """The release title ends with the build number; the Windows asset is the .exe."""
    match = re.search(r"(\d+)\s*$", data.get("name") or "")
    if not match:
        return None
    for asset in data.get("assets", []):
        if asset.get("name", "").lower().endswith(".exe"):
            return Release(int(match[1]), asset["browser_download_url"], int(asset.get("size") or 0))
    return None


def latest() -> Release | None:
    response = requests.get(API, headers={"Accept": "application/vnd.github+json", "User-Agent": _UA}, timeout=15)
    if response.status_code == 404:
        return None
    response.raise_for_status()
    return parse_release(response.json())


def download(release: Release, dest: Path, on_progress: Callable[[int], None] | None = None) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    with requests.get(release.url, headers={"User-Agent": _UA}, stream=True, timeout=30) as response:
        response.raise_for_status()
        total = int(response.headers.get("Content-Length") or release.size or 0)
        done = 0
        with open(part, "wb") as out:
            for chunk in response.iter_content(256 * 1024):
                out.write(chunk)
                done += len(chunk)
                if on_progress and total:
                    on_progress(min(100, done * 100 // total))
    os.replace(part, dest)
    return dest


def apply_and_restart(new_exe: Path) -> None:
    """Starts a small script that waits for this process to exit, replaces the .exe, and relaunches it.
    The caller must quit the application right after."""
    target = Path(sys.executable)
    script = new_exe.with_suffix(".update.bat")
    script.write_text(
        "@echo off\r\n"
        "set /a tries=0\r\n"
        ":retry\r\n"
        "timeout /t 1 /nobreak >nul\r\n"
        f'copy /y "{new_exe}" "{target}" >nul 2>&1\r\n'
        "if not errorlevel 1 goto done\r\n"
        "set /a tries+=1\r\n"
        "if %tries% LSS 60 goto retry\r\n"
        "exit /b 1\r\n"
        ":done\r\n"
        f'start "" "{target}"\r\n'
        f'del "{new_exe}" >nul 2>&1\r\n'
        '(goto) 2>nul & del "%~f0"\r\n',
        encoding="utf-8",
    )
    flags = getattr(subprocess, "DETACHED_PROCESS", 0) | getattr(subprocess, "CREATE_NO_WINDOW", 0)
    subprocess.Popen(["cmd", "/c", str(script)], creationflags=flags, close_fds=True)
