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
from local_player import LOCAL_OUTPUT_ID, LocalPlayer
from server import Bridge, close_bridge


SOURCE = "https://www.youtube.com/watch?v=7vy0H3ERRiA"


class LocalBridgeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.commands = self.root / "commands.jsonl"
        self.environment = patch.dict(os.environ, {"FAKE_PLAYER_COMMANDS": str(self.commands)}, clear=False)
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def resolved(self):
        return {"id": "7vy0H3ERRiA", "title": "Test mix", "source_url": SOURCE,
                "url": f"https://rr1---sn-test.googlevideo.com/videoplayback?expire={int(time.time()+3600)}&sig=secret",
                "duration": 600, "ext": "m4a"}

    def make(self, local=True):
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.status.return_value = {"connected": False}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        lab.selected_id = None
        lab.ensure_source.return_value = self.resolved()
        catalog = Mock()
        catalog.status.return_value = {}
        catalog.snapshot.return_value = {}
        catalog.backend_name = "sqlite"
        catalog.supports_curation = False
        catalog.personal = None
        catalog.updater = None
        catalog.listening = None
        player = LocalPlayer(lab, Path(__file__).parent / "test_support" / "fake_local_player.py") if local else None
        relay = Mock()
        relay.wait_ready.return_value = "http://127.0.0.1/mock/playlist.m3u8"
        relay_patch = patch("local_player.LocalRelay", return_value=relay)
        relay_patch.start()
        self.addCleanup(relay_patch.stop)
        with patch("server.DATA", self.root):
            bridge = Bridge(lab, catalog=catalog, local=player)
        if player is not None:
            self.addCleanup(player.close)
        return bridge, lab, player

    def finish(self, bridge):
        deadline = time.monotonic() + 3
        while (bridge.busy or bridge.volume_busy) and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertFalse(bridge.busy or bridge.volume_busy, bridge.message)

    def command_log(self):
        if not self.commands.is_file():
            return []
        return [json.loads(line) for line in self.commands.read_text().splitlines()]

    def test_local_play_controls_never_use_cast(self):
        bridge, lab, player = self.make()
        bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.finish(bridge)
        state = bridge.snapshot()
        self.assertEqual(state["local_output"], LOCAL_OUTPUT_ID)
        self.assertEqual(state["target"], LOCAL_OUTPUT_ID)
        self.assertEqual(state["receiver"]["state"], "IDLE")
        self.assertEqual(state["receiver"]["volume"], 15)
        lab.connect.assert_not_called()
        bridge.dispatch({"op": "play", "source": SOURCE})
        self.finish(bridge)
        self.assertEqual(bridge.snapshot()["receiver"]["state"], "PLAYING")
        bridge.dispatch({"op": "pause"})
        self.finish(bridge)
        self.assertEqual(bridge.snapshot()["receiver"]["state"], "PAUSED")
        bridge.dispatch({"op": "seek", "value": 42})
        self.finish(bridge)
        self.assertEqual(bridge.snapshot()["receiver"]["position"], 42)
        bridge.dispatch({"op": "volume", "value": .4})
        self.finish(bridge)
        self.assertEqual(bridge.snapshot()["receiver"]["volume"], 40)
        bridge.dispatch({"op": "stop"})
        self.finish(bridge)
        self.assertEqual(bridge.snapshot()["receiver"]["state"], "IDLE")
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()
        lab.stop_verified.assert_not_called()

    def test_stop_during_pending_local_load_invalidates_late_completion(self):
        os.environ["FAKE_PLAYER_AUTO_LOAD"] = "0"
        bridge, lab, player = self.make()
        bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.finish(bridge)
        bridge.dispatch({"op": "play", "source": SOURCE})
        deadline = time.monotonic() + 2
        while not any(command["op"] == "load" for command in self.command_log()) and time.monotonic() < deadline:
            time.sleep(.01)
        bridge.dispatch({"op": "stop"})
        self.finish(bridge)
        self.assertEqual(bridge.snapshot()["receiver"]["state"], "IDLE")
        player.process.stdin.write(json.dumps({"id": 999, "op": "finish"}) + "\n")
        player.process.stdin.flush()
        time.sleep(.1)
        self.assertEqual(bridge.snapshot()["receiver"]["state"], "IDLE")
        self.assertIsNone(bridge.snapshot()["receiver"]["source"])
        lab.stop_verified.assert_not_called()

    def test_active_or_uncertain_cast_cannot_switch_to_local(self):
        bridge, lab, player = self.make()
        bridge.target = "office"
        lab.status.return_value = {"connected": True, "state": "PLAYING"}
        with self.assertRaisesRegex(ValueError, "Stop casting"):
            bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.assertIsNone(player.process)
        lab.play_selection.assert_not_called()

    def test_confirmed_idle_cast_can_choose_local_without_loading(self):
        bridge, lab, player = self.make()
        bridge.target = "office"
        lab.selected_id = "office"
        lab.status.return_value = {"connected": True, "state": "IDLE", "volume": .5}
        bridge.recovery_rows = Mock(return_value=[{"id": "office", "fresh": True, "state": "IDLE",
                                                   "title": None, "source": None, "position": 0,
                                                   "duration": None, "volume": .5}])
        bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.finish(bridge)
        self.assertEqual(bridge.snapshot()["receiver"]["state"], "IDLE")
        lab.play_selection.assert_not_called()

    def test_local_play_blocks_cast_until_confirmed_stop(self):
        bridge, lab, player = self.make()
        bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.finish(bridge)
        bridge.dispatch({"op": "play", "source": SOURCE})
        self.finish(bridge)
        lab.devices.return_value = [("office", "Office", "Nest Audio")]
        with self.assertRaisesRegex(ValueError, "Stop on this Mac"):
            bridge.dispatch({"op": "select", "device": "office"})
        with self.assertRaisesRegex(ValueError, "not available"):
            bridge.dispatch({"op": "set_playback_devices", "devices": ["office"], "token": "session"})
        lab.connect.assert_not_called()
        bridge.dispatch({"op": "stop"})
        self.finish(bridge)
        bridge.dispatch({"op": "select", "device": "office"})
        self.finish(bridge)
        lab.connect.assert_called_once_with("office")
        lab.play_selection.assert_not_called()

    def test_uncertain_local_blocks_cast_until_confirmed_stop(self):
        bridge, lab, player = self.make()
        bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.finish(bridge)
        with player.condition:
            player.state.update(state="UNKNOWN", connected=False)
        lab.devices.return_value = [("office", "Office", "Nest Audio")]
        with self.assertRaisesRegex(ValueError, "Stop on this Mac"):
            bridge.dispatch({"op": "select", "device": "office"})
        lab.connect.assert_not_called()
        bridge.dispatch({"op": "stop"})
        self.finish(bridge)
        bridge.dispatch({"op": "select", "device": "office"})
        self.finish(bridge)
        lab.connect.assert_called_once_with("office")

    def test_local_selection_skips_cast_recovery(self):
        bridge, lab, player = self.make()
        bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.finish(bridge)
        lab.devices.reset_mock()
        lab.status.reset_mock()
        bridge.maintain_once()
        lab.devices.assert_not_called()
        lab.status.assert_not_called()
        lab.connect.assert_not_called()

    def test_close_bridge_stops_only_local_playback(self):
        bridge, lab, player = self.make()
        bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.finish(bridge)
        bridge.dispatch({"op": "play", "source": SOURCE})
        self.finish(bridge)
        process = player.process
        self.assertIsNone(bridge.group_monitor)
        close_bridge(bridge)
        self.assertIsNotNone(process.wait(timeout=2))
        self.assertFalse(player.status()["connected"])
        lab.stop_verified.assert_not_called()
        lab.close.assert_called_once()

    def test_missing_helper_and_android_default_have_no_local_capability(self):
        bridge, lab, player = self.make(local=False)
        state = bridge.snapshot()
        self.assertIsNone(state["local_output"])
        self.assertFalse(any(device.get("cast_type") == "local" for device in state["devices"]))
        bridge, lab, player = self.make()
        player.helper_path = self.root / "missing-helper"
        state = bridge.snapshot()
        self.assertIsNone(state["local_output"])
        self.assertFalse(state["devices"][0]["available"])
        with self.assertRaisesRegex(ValueError, "unavailable"):
            bridge.dispatch({"op": "select", "device": LOCAL_OUTPUT_ID})
        self.assertIsNone(player.process)


if __name__ == "__main__":
    unittest.main()
