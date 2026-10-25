import hashlib
import os
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from ecoutify_win.services import updater

PAYLOAD = b"MZ" + b"x" * 300_000


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", str(len(PAYLOAD)))
        self.end_headers()
        self.wfile.write(PAYLOAD)

    def log_message(self, *args):
        pass


@pytest.fixture
def server():
    httpd = HTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_port}/Ecoutify.exe"
    httpd.shutdown()


def test_download_is_verified(server):
    good = updater.Release(5, server, len(PAYLOAD), hashlib.sha256(PAYLOAD).hexdigest())
    seen = []
    path = updater.download(good, lambda done, total: seen.append((done, total)))
    assert open(path, "rb").read() == PAYLOAD and seen[-1] == (len(PAYLOAD), len(PAYLOAD))

    with pytest.raises(OSError, match="SHA-256"):
        updater.download(updater.Release(5, server, len(PAYLOAD), "0" * 64))
    with pytest.raises(OSError, match="incomplet"):
        updater.download(updater.Release(5, server, len(PAYLOAD) + 1, ""))


def test_install_replaces_a_running_exe_and_cleanup_removes_the_old_one(tmp_path):
    exe, new = tmp_path / "Ecoutify.exe", tmp_path / "new.download"
    exe.write_bytes(b"old")
    new.write_bytes(b"new")
    updater.install(str(exe), str(new))
    assert exe.read_bytes() == b"new" and (tmp_path / "Ecoutify.exe.old").read_bytes() == b"old" and not new.exists()
    assert updater.cleanup_previous(str(exe)) is True
    assert not (tmp_path / "Ecoutify.exe.old").exists()
    assert updater.cleanup_previous(str(exe)) is True  # nothing left: still fine


def test_install_puts_the_old_version_back_if_it_fails(tmp_path):
    exe = tmp_path / "Ecoutify.exe"
    exe.write_bytes(b"old")
    with pytest.raises(OSError):
        updater.install(str(exe), str(tmp_path / "missing"))
    assert exe.read_bytes() == b"old"


def test_install_never_renames_across_folders(tmp_path, monkeypatch):
    # The download sits in the temp folder, which can be on another drive than the exe: Windows refuses
    # such a rename (WinError 17). Simulate it by refusing any rename that leaves its folder.
    exe_dir, temp_dir = tmp_path / "D-drive", tmp_path / "C-temp"
    exe_dir.mkdir()
    temp_dir.mkdir()
    exe, new = exe_dir / "Ecoutify.exe", temp_dir / "Ecoutify-9.exe.download"
    exe.write_bytes(b"old")
    new.write_bytes(b"new")
    real_replace = os.replace

    def same_drive_only(src, dst):
        if os.path.dirname(os.fspath(src)) != os.path.dirname(os.fspath(dst)):
            raise OSError(17, "Cannot move the file to a different disk drive")
        real_replace(src, dst)

    monkeypatch.setattr(updater.os, "replace", same_drive_only)
    updater.install(str(exe), str(new))
    assert exe.read_bytes() == b"new"
    assert not (exe_dir / "Ecoutify.exe.new").exists()
