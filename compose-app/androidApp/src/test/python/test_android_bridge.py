"""Silent tests for the Android shims around the unchanged Python bridge. No discovery or speakers.

Run from the repository root:
    mac-spike/.venv/bin/python -m unittest discover -s compose-app/androidApp/src/test/python -v
"""
import json
import sys
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve()
COMPOSE = HERE.parents[4]
sys.path[:0] = [str(COMPOSE / 'androidApp/src/main/python'), str(COMPOSE / 'bridge'), str(COMPOSE.parent / 'mac-spike')]

import android_bridge
import catalog
import engine


class FakeRunner:
    def __init__(self, stdout='{"ok": true}', error=None):
        self.stdout, self.error = stdout, error
        self.calls, self.cancelled = [], 0

    def run(self, arguments, timeout_ms):
        self.calls.append((json.loads(arguments), timeout_ms))
        if self.error:
            raise RuntimeError(self.error)
        return self.stdout

    def cancel(self):
        self.cancelled += 1


class FakeLab:
    def __init__(self):
        self.cancelled = threading.Event()


class AndroidBridgeShimTest(unittest.TestCase):
    def setUp(self):
        self.saved = engine.Lab.run_process, engine.Lab.cancel, catalog.subprocess
        android_bridge._patch_engine(engine)
        android_bridge._patch_catalog(catalog)

    def tearDown(self):
        engine.Lab.run_process, engine.Lab.cancel, catalog.subprocess = self.saved
        android_bridge._runner = None

    def test_ytdlp_arguments_drop_interpreter_and_js_runtime(self):
        args = ['/usr/bin/python3', '-m', 'yt_dlp', '--ignore-config', '--js-runtimes', 'node:/x/node', '-f', 'bestaudio', 'https://www.youtube.com/watch?v=7vy0H3ERRiA']
        self.assertEqual(android_bridge._ytdlp_arguments(args), ['--ignore-config', '-f', 'bestaudio', 'https://www.youtube.com/watch?v=7vy0H3ERRiA'])

    def test_resolve_arguments_reach_runner_with_timeout(self):
        android_bridge._runner = runner = FakeRunner('{"url": "x"}')
        lab = FakeLab()
        out = engine.Lab.run_process(lab, ['py', '-m', 'yt_dlp', '--skip-download', 'https://www.youtube.com/watch?v=7vy0H3ERRiA'], timeout=12)
        self.assertEqual(out, '{"url": "x"}')
        self.assertEqual(runner.calls, [(['--skip-download', 'https://www.youtube.com/watch?v=7vy0H3ERRiA'], 12000)])

    def test_non_ytdlp_tool_is_refused_without_running(self):
        android_bridge._runner = runner = FakeRunner()
        with self.assertRaisesRegex(RuntimeError, 'not available on the phone'):
            engine.Lab.run_process(FakeLab(), ['ffmpeg', '-y'], timeout=5)
        self.assertEqual(runner.calls, [])

    def test_cancel_during_resolve_reports_cancellation_not_tool_error(self):
        lab = FakeLab()
        class CancellingRunner(FakeRunner):
            def run(self, arguments, timeout_ms):
                lab.cancelled.set()
                raise RuntimeError('process destroyed')
        android_bridge._runner = CancellingRunner()
        with self.assertRaisesRegex(RuntimeError, 'cancelled; no new media loaded'):
            engine.Lab.run_process(lab, ['py', '-m', 'yt_dlp', 'https://www.youtube.com/watch?v=7vy0H3ERRiA'])

    def test_cancel_destroys_running_process(self):
        android_bridge._runner = runner = FakeRunner()
        lab = FakeLab()
        engine.Lab.cancel(lab)
        self.assertTrue(lab.cancelled.is_set())
        self.assertEqual(runner.cancelled, 1)

    def test_catalog_failure_becomes_nonzero_return(self):
        android_bridge._runner = FakeRunner(error='offline')
        result = catalog.subprocess.run(['py', '-m', 'yt_dlp', '--flat-playlist', 'https://www.youtube.com/@x'], capture_output=True, text=True, timeout=300)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, '')

    def test_request_before_start_is_an_error_reply(self):
        reply = json.loads(android_bridge.request('{"id": 1, "op": "state"}'))
        self.assertIn('not running', reply['error'])


if __name__ == '__main__':
    unittest.main()
