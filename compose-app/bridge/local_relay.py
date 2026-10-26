import os
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from engine import DATA, redacted


class RangeHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def send_head(self):
        path = Path(self.translate_path(self.path))
        if not path.is_file():
            self.send_error(404)
            return None
        size = path.stat().st_size
        start, end = 0, size - 1
        partial = False
        requested = self.headers.get("Range")
        if requested:
            units, _, values = requested.partition("=")
            first, _, last = values.partition("-")
            try:
                if units != "bytes":
                    raise ValueError
                if not first and last.isdigit():
                    start = max(0, size - int(last))
                else:
                    start = int(first or 0)
                    end = int(last) if last else size - 1
                end = min(end, size - 1)
            except ValueError:
                self.send_error(400)
                return None
            if start < 0 or end < start or start >= size:
                self.send_error(416)
                return None
            partial = True
        length = end - start + 1
        content_type = "application/vnd.apple.mpegurl" if path.suffix == ".m3u8" else "video/mp2t" if path.suffix == ".ts" else self.guess_type(path)
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-cache")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if self.command == "HEAD":
            return None
        with path.open("rb") as source:
            source.seek(start)
            remaining = length
            while remaining:
                chunk = source.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)
        return None


class LocalRelay:
    def __init__(self, lab, resolved, start=0):
        self.lab = lab
        self.resolved = resolved
        self.closed = False
        (DATA / "local-hls").mkdir(parents=True, exist_ok=True)
        self.directory = Path(tempfile.mkdtemp(prefix="local-hls-", dir=DATA / "local-hls"))
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), lambda *args, **kwargs: RangeHandler(*args, directory=str(self.directory), **kwargs))
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.process = None
        headers = dict(resolved.get("http_headers") or {})
        agent = headers.get("User-Agent", "Mozilla/5.0")
        args = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-user_agent", agent]
        args += ["-ss", str(max(0, start)), "-i", resolved["url"], "-map", "0:a:0", "-c:a", "copy",
                 "-f", "hls", "-hls_time", "8", "-hls_list_size", "0", "-hls_playlist_type", "event",
                 "-hls_flags", "append_list+independent_segments+program_date_time",
                 "-hls_segment_filename", str(self.directory / "segment-%06d.ts"), str(self.directory / "playlist.m3u8")]
        try:
            self.process = subprocess.Popen(args, cwd=self.directory, stdout=subprocess.DEVNULL,
                                             stderr=subprocess.PIPE, text=True, start_new_session=True)
        except Exception:
            self.close()
            raise
        threading.Thread(target=self._log_stderr, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/playlist.m3u8"

    def _log_stderr(self):
        try:
            for line in self.process.stderr:
                if line.strip():
                    self.lab.log("local-relay-note", redacted(line.strip()))
        except (OSError, ValueError):
            pass

    def wait_ready(self, timeout=30):
        deadline = time.monotonic() + timeout
        playlist = self.directory / "playlist.m3u8"
        try:
            while time.monotonic() < deadline:
                if self.closed:
                    raise RuntimeError("Local playback was cancelled.")
                if playlist.is_file() and "#EXTINF" in playlist.read_text(errors="replace"):
                    return self.url
                if self.process.poll() is not None and self.process.returncode != 0:
                    raise RuntimeError("The local media relay could not prepare the stream.")
                if time.monotonic() >= deadline:
                    raise TimeoutError("The local media relay did not become ready.")
                time.sleep(.05)
            raise TimeoutError("The local media relay did not become ready.")
        except Exception:
            self.close()
            raise

    def close(self):
        if self.closed:
            return
        self.closed = True
        process = self.process
        if process is not None and process.poll() is None:
            pid = getattr(process, "pid", None)
            try:
                if pid is not None:
                    os.killpg(pid, signal.SIGTERM)
                else:
                    process.terminate()
            except ProcessLookupError:
                pass
            except PermissionError:
                try:
                    process.terminate()
                except ProcessLookupError:
                    pass
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                if pid is not None:
                    try:
                        os.killpg(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    except PermissionError:
                        process.kill()
                else:
                    process.kill()
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.directory, ignore_errors=True)
