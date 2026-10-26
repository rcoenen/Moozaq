import inspect
import io
import os
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "mac-spike"))
from local_relay import LocalRelay


class FakeProcess:
    def __init__(self):
        self.returncode = None
        self.terminated = False
        self.stderr = io.StringIO("")

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True
        self.returncode = 0

    def kill(self):
        self.returncode = -9

    def wait(self, timeout=None):
        return self.returncode


class LocalRelayTests(unittest.TestCase):
    def make(self):
        process = FakeProcess()
        with patch("local_relay.subprocess.Popen", return_value=process):
            relay = LocalRelay(Mock(), {"url": "https://example.test/mix.m4a", "http_headers": {}}, 0)
        self.addCleanup(relay.close)
        return relay, process

    def test_range_response_and_close_without_media(self):
        relay, process = self.make()
        (relay.directory / "playlist.m3u8").write_text("#EXTM3U\n#EXTINF:8.0,\n")
        (relay.directory / "segment-000000.ts").write_bytes(b"abcdef")
        url = relay.wait_ready()
        response = requests.get(url.replace("playlist.m3u8", "segment-000000.ts"), headers={"Range": "bytes=0-1"}, timeout=5)
        self.assertEqual(response.status_code, 206)
        self.assertEqual(response.headers["Content-Range"], "bytes 0-1/6")
        self.assertEqual(response.headers["Content-Type"], "video/mp2t")
        self.assertEqual(response.content, b"ab")
        response = requests.get(url.replace("playlist.m3u8", "segment-000000.ts"), timeout=5)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b"abcdef")
        directory = relay.directory
        relay.close()
        self.assertTrue(process.terminated)
        self.assertFalse(directory.exists())

    def test_close_cancels_wait_and_removes_temp_files(self):
        relay, process = self.make()
        directory = relay.directory
        relay.close()
        with self.assertRaisesRegex(RuntimeError, "cancelled"):
            relay.wait_ready()
        self.assertTrue(process.terminated)
        self.assertFalse(directory.exists())

    def test_failed_ffmpeg_does_not_claim_ready(self):
        relay, process = self.make()
        process.returncode = 1
        with self.assertRaisesRegex(RuntimeError, "could not prepare"):
            relay.wait_ready()
        self.assertFalse(relay.directory.exists())

    def test_relay_starts_an_independent_process_group(self):
        self.assertIn("start_new_session=True", inspect.getsource(LocalRelay.__init__))

    def test_close_kills_the_process_group_without_media(self):
        real_popen = subprocess.Popen

        def sleepy_process(args, **kwargs):
            return real_popen(["sleep", "30"], cwd=kwargs["cwd"], stdout=subprocess.DEVNULL,
                              stderr=subprocess.PIPE, text=True, start_new_session=True)
        with patch("local_relay.subprocess.Popen", side_effect=sleepy_process):
            relay = LocalRelay(Mock(), {"url": "https://example.test/mix.m4a", "http_headers": {}}, 0)
        self.addCleanup(relay.close)
        (relay.directory / "playlist.m3u8").write_text("#EXTM3U\n#EXTINF:8.0,\n")
        relay.wait_ready()
        pid = relay.process.pid
        directory = relay.directory
        relay.close()
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        self.assertFalse(directory.exists())


if __name__ == "__main__":
    unittest.main()
