import json
import os
import sys
import threading
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "mac-spike"))
from local_player import LocalPlayer


SOURCE = "https://www.youtube.com/watch?v=7vy0H3ERRiA"


class LocalPlayerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.commands = self.root / "commands.jsonl"
        self.environment = patch.dict(os.environ, {"FAKE_PLAYER_COMMANDS": str(self.commands)}, clear=False)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.lab = Mock()
        self.lab.playback_gate = threading.RLock()
        self.lab.generation = 0
        self.resolved = self.resolution()
        self.lab.ensure_source.return_value = self.resolved
        self.relay = Mock()
        self.relay.wait_ready.return_value = "http://127.0.0.1/mock/playlist.m3u8"
        relay_patch = patch("local_player.LocalRelay", return_value=self.relay)
        relay_patch.start()
        self.addCleanup(relay_patch.stop)
        self.player = LocalPlayer(self.lab, Path(__file__).parent / "test_support" / "fake_local_player.py")
        self.addCleanup(self.player.close)

    def resolution(self, expires_in=3600):
        return {"id": "7vy0H3ERRiA", "title": "Test mix", "source_url": SOURCE,
                "url": f"https://rr1---sn-test.googlevideo.com/videoplayback?expire={int(time.time()+expires_in)}&sig=secret",
                "duration": 600, "ext": "m4a"}

    def start(self):
        return self.player.select()

    def command_log(self):
        if not self.commands.is_file():
            return []
        return [json.loads(line) for line in self.commands.read_text().splitlines()]

    def raw(self, operation, command_id=999):
        self.player.process.stdin.write(json.dumps({"id": command_id, "op": operation}) + "\n")
        self.player.process.stdin.flush()

    def wait_for(self, predicate, timeout=2):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.01)
        self.fail("Condition was not met in time")

    def test_selection_is_idle_and_does_not_resolve_or_load(self):
        status = self.start()
        self.assertTrue(status["connected"])
        self.assertEqual(status["state"], "IDLE")
        self.assertEqual(status["volume"], 15)
        self.lab.ensure_source.assert_not_called()
        self.assertEqual(self.command_log(), [{"id": 1, "op": "state"}])

    def test_explicit_play_pause_resume_seek_and_volume_are_confirmed(self):
        self.start()
        status = self.player.play_selection(SOURCE)
        self.assertEqual(status["state"], "PLAYING")
        self.assertEqual(status["source"], SOURCE)
        self.assertEqual(status["title"], "Test mix")
        self.assertEqual(status["volume"], 15)
        self.assertEqual(self.player.pause()["state"], "PAUSED")
        self.assertEqual(self.player.resume()["state"], "PLAYING")
        self.assertEqual(self.player.seek(42)["position"], 42)
        self.assertEqual(self.player.volume(.4)["volume"], 40)
        load = next(command for command in self.command_log() if command["op"] == "load")
        self.assertEqual(load["start"], 0)
        self.assertTrue(load["autoplay"])
        self.assertEqual(load["volume"], .15)

    def test_stop_during_pending_load_cancels_promptly_and_ignores_late_completion(self):
        os.environ["FAKE_PLAYER_AUTO_LOAD"] = "0"
        self.start()
        outcome = []
        worker = threading.Thread(target=lambda: outcome.append(self.capture(self.player.play_selection, SOURCE)))
        worker.start()
        self.wait_for(lambda: any(command["op"] == "load" for command in self.command_log()))
        self.player.stop()
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertIn("cancelled", outcome[0].lower())
        self.raw("finish")
        time.sleep(.1)
        self.assertEqual(self.player.status()["state"], "IDLE")
        self.assertIsNone(self.player.status()["source"])

    def capture(self, action, *args):
        try:
            action(*args)
            return "unexpected success"
        except Exception as exc:
            return str(exc)

    def test_stop_during_source_resolution_prevents_helper_load(self):
        entered, release = threading.Event(), threading.Event()
        def resolving(*args):
            entered.set()
            release.wait(2)
            return self.resolved
        self.lab.ensure_source.side_effect = resolving
        self.start()
        outcome = []
        worker = threading.Thread(target=lambda: outcome.append(self.capture(self.player.play_selection, SOURCE)))
        worker.start()
        self.assertTrue(entered.wait(1))
        self.player.stop()
        release.set()
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertIn("cancelled", outcome[0].lower())
        self.assertFalse(any(command["op"] == "load" for command in self.command_log()))

    def test_expired_resume_reloads_once_at_current_position(self):
        self.start()
        self.player.play_selection(SOURCE)
        self.player.pause()
        self.player.expires_at = time.time() - 1
        refreshed = self.resolution()
        self.lab.ensure_source.return_value = refreshed
        self.lab.ensure_source.reset_mock()
        status = self.player.resume()
        self.lab.ensure_source.assert_called_once_with(SOURCE, 0)
        self.assertEqual(status["state"], "PLAYING")
        self.assertEqual(self.player.session, 2)
        load = [command for command in self.command_log() if command["op"] == "load"][-1]
        self.assertEqual(load["start"], 0)
        self.assertTrue(load["autoplay"])

    def test_expiring_paused_seek_reloads_once_and_preserves_pause(self):
        self.start()
        self.player.load(SOURCE, start=20, autoplay=False)
        self.player.expires_at = time.time() + 30
        self.lab.ensure_source.reset_mock()
        status = self.player.seek(120)
        self.lab.ensure_source.assert_called_once_with(SOURCE, 120)
        self.assertEqual(status["state"], "PAUSED")
        self.assertEqual(status["position"], 120)
        load = [command for command in self.command_log() if command["op"] == "load"][-1]
        self.assertEqual(load["start"], 0)
        self.assertEqual(self.player.position_offset, 120)
        self.assertFalse(load["autoplay"])

    def test_valid_old_link_allows_plain_resume_after_refresh_failure(self):
        self.start()
        self.player.play_selection(SOURCE)
        self.player.pause()
        self.player.expires_at = time.time() + 30
        self.lab.ensure_source.side_effect = RuntimeError("offline")
        status = self.player.resume()
        self.assertEqual(status["state"], "PLAYING")
        self.assertEqual(len([command for command in self.command_log() if command["op"] == "load"]), 1)

    def test_process_crash_is_unknown_and_never_automatically_resumes(self):
        self.start()
        self.player.play_selection(SOURCE)
        self.raw("crash")
        self.wait_for(lambda: not self.player.status()["connected"])
        self.assertEqual(self.player.status()["state"], "UNKNOWN")
        self.assertIn("not ready", self.capture(self.player.resume))
        status = self.player.play_selection(SOURCE)
        self.assertEqual(status["state"], "PLAYING")
        self.assertEqual(self.player.session, 1)

    def test_old_process_cannot_write_replacement_state(self):
        self.start()
        old = self.player.process
        self.player.terminate()
        self.wait_for(lambda: old.poll() is not None)
        self.player.ensure_process()
        self.player._state_event({"connected": True, "state": "PLAYING", "position": 0,
                                  "duration": 600, "volume": 1, "session": 99, "command": None,
                                  "error": None, "idle_reason": None}, old)
        self.assertEqual(self.player.status()["state"], "IDLE")
        self.assertIsNone(self.player.status()["source"])

    def test_natural_finish_advances_revision_without_new_load(self):
        self.start()
        self.player.play_selection(SOURCE)
        revision = self.player.status()["event_transport_revision"]
        self.raw("end")
        self.wait_for(lambda: self.player.status()["state"] == "IDLE")
        self.assertEqual(self.player.status()["event_transport_revision"], revision + 1)
        self.assertEqual(self.player.status()["idle_reason"], "FINISHED")
        self.assertEqual(len([command for command in self.command_log() if command["op"] == "load"]), 1)

    def test_load_timeout_stops_late_playback(self):
        os.environ["FAKE_PLAYER_AUTO_LOAD"] = "0"
        self.start()
        original = self.player.command
        def short_timeout(operation, fields=None, timeout=10, generation=None):
            return original(operation, fields, timeout=.1 if operation == "load" else timeout, generation=generation)
        with patch.object(self.player, "command", side_effect=short_timeout):
            with self.assertRaises(TimeoutError):
                self.player.load(SOURCE)
        self.assertEqual(self.player.status()["state"], "IDLE")
        self.assertIsNone(self.player.status()["source"])

    def test_stderr_and_state_cannot_leak_signed_urls(self):
        os.environ["FAKE_PLAYER_STDERR"] = "1"
        os.environ["FAKE_PLAYER_LEAK_STATE"] = "1"
        self.start()
        self.wait_for(lambda: self.lab.log.called)
        self.assertNotIn("signature=secret", str(self.lab.log.call_args_list))
        self.assertIn("[media URL omitted]", str(self.lab.log.call_args_list))
        self.player.play_selection(SOURCE)
        self.assertNotIn("signature=secret", str(self.player.status()))

    def test_seek_offset_and_reset(self):
        self.start()
        self.player.load(SOURCE, start=15, autoplay=False)
        self.assertEqual(self.player.status()["position"], 15)
        self.assertEqual(self.player.seek(5)["position"], 5)
        self.player.stop()
        self.assertEqual(self.player.status()["position"], 0)

    def test_confirmed_error_is_not_reported_as_success(self):
        self.start()
        self.player.acks[1] = None
        self.player._state_event({"connected": True, "state": "ERROR", "position": 0,
                                  "duration": 600, "volume": .15, "session": 1, "command": 1,
                                  "error": "Load failed", "idle_reason": "ERROR"}, self.player.process)
        with self.assertRaisesRegex(RuntimeError, "Load failed"):
            self.player.wait_for(1, "load", timeout=1, starting_serial=0)

    def test_close_stops_the_helper_and_never_restarts_on_status(self):
        self.start()
        self.player.play_selection(SOURCE)
        process = self.player.process
        self.player.close()
        self.assertIsNotNone(process.wait(timeout=2))
        self.assertFalse(self.player.status()["connected"])
        self.assertEqual(len([command for command in self.command_log() if command["op"] == "load"]), 1)


if __name__ == "__main__":
    unittest.main()
