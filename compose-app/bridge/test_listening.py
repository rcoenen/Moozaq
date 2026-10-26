import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from catalog_publish import CatalogPublisher
from desktop_catalog import DesktopCatalog
from listening import ListeningTracker
from personal_sqlite import PersonalStore
from server import Bridge


class ListeningTrackerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.personal = PersonalStore(self.root / "personal.db")
        self.source = "https://www.youtube.com/watch?v=WlPiQGR18jE"

    def status(self, position, *, state="PLAYING", session="session-1", fresh=True, source=None):
        return {"video_id": "WlPiQGR18jE", "media_session": session, "position": position,
                "state": state, "fresh": fresh, "connected": True, "source": source or self.source}

    def test_pending_and_failed_start_do_not_count(self):
        tracker = ListeningTracker(self.personal)
        for state in ("IDLE", "BUFFERING", "PAUSED", "UNKNOWN"):
            self.assertEqual(tracker.observe(self.status(0, state=state), 0)["credited"], 0)
        self.assertEqual(tracker.observe(self.status(0, fresh=False), 1)["credited"], 0)
        self.assertEqual(self.personal.profile()["listening"], {})

    def test_five_verified_minutes_qualify_once_with_bounded_flushes(self):
        tracker = ListeningTracker(self.personal, flush_interval=30)
        tracker.observe(self.status(0), 0)
        tracker.observe(self.status(30), 30)
        self.assertEqual(self.personal.profile()["listening"], {})
        tracker.observe(self.status(60), 60)
        aggregate = self.personal.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 60)
        self.assertEqual(aggregate["qualified_sessions"], 0)
        tracker.observe(self.status(240), 240)
        self.assertEqual(self.personal.profile()["listening"]["WlPiQGR18jE"]["qualified_seconds"], 60)
        tracker.observe(self.status(300), 300)
        aggregate = self.personal.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 300)
        self.assertEqual(aggregate["qualified_sessions"], 1)
        tracker.observe(self.status(360), 360)
        tracker.close()
        aggregate = self.personal.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 360)
        self.assertEqual(aggregate["qualified_sessions"], 1)
        observation = self.personal.last_observation()
        self.assertTrue(observation["qualified"])
        self.assertEqual(observation["session_seconds"], 360)

    def test_pause_seek_and_source_change_reset_without_false_credit(self):
        tracker = ListeningTracker(self.personal, flush_interval=30)
        tracker.observe(self.status(0), 0)
        tracker.observe(self.status(100), 100)
        tracker.observe(self.status(110, state="PAUSED"), 110)
        tracker.observe(self.status(50), 120)
        tracker.observe(self.status(70), 140)
        tracker.observe(self.status(1000), 160)
        tracker.close()
        aggregate = self.personal.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 120)
        self.assertEqual(aggregate["qualified_sessions"], 0)

    def test_reconnect_same_session_counts_only_plausible_progress(self):
        tracker = ListeningTracker(self.personal, flush_interval=30)
        tracker.observe(self.status(100), 100)
        tracker.close()
        resumed = ListeningTracker(self.personal, flush_interval=30)
        result = resumed.observe(self.status(160), 200)
        self.assertEqual(result["credited"], 60)
        resumed.observe(self.status(220), 260)
        aggregate = self.personal.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 120)
        self.assertEqual(aggregate["qualified_sessions"], 0)

    def test_new_session_does_not_count_gap_or_signed_url(self):
        tracker = ListeningTracker(self.personal, flush_interval=30)
        tracker.observe(self.status(100), 100)
        tracker.close()
        resumed = ListeningTracker(self.personal, flush_interval=30)
        self.assertEqual(resumed.observe(self.status(500, session="new"), 200)["credited"], 0)
        self.assertEqual(resumed.observe(self.status(550, source="https://googlevideo.com/play?expire=1&signature=secret"), 220)["credited"], 0)
        self.assertEqual(self.personal.profile()["listening"], {})

    def test_link_refresh_with_same_source_and_session_is_safe(self):
        tracker = ListeningTracker(self.personal, flush_interval=30)
        tracker.observe(self.status(100), 100)
        tracker.observe(self.status(150), 150)
        tracker.observe(self.status(200), 200)
        tracker.observe(self.status(250, session="renewed"), 250)
        tracker.close()
        aggregate = self.personal.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 100)
        self.assertEqual(aggregate["qualified_sessions"], 0)
    def test_empty_observation_survives_restart_and_repeated_close(self):
        tracker = ListeningTracker(self.personal)
        tracker.observe(self.status(0, state="IDLE"), 0)
        tracker.close()
        self.assertEqual(self.personal.profile()["listening"], {})
        resumed = ListeningTracker(self.personal)
        resumed.close()
        resumed.close()
        self.assertIsNone(self.personal.last_observation()["video_id"])
        self.assertEqual(self.personal.profile()["listening"], {})

    def test_bridge_observation_is_read_only_and_flushes_on_close(self):
        (self.root / "channels.json").write_text("[]")
        (self.root / "channel-catalog.json").write_text('{"channels": [], "mixes": []}')
        catalog = DesktopCatalog(self.root, self.root / "channels.json", generation_delay=0)
        self.addCleanup(catalog.close)
        source_id = "UC" + "a" * 22
        with catalog.master.store.writing() as connection:
            connection.execute(
                "INSERT INTO sources(id, url, name, category, publication_mode, origin) VALUES(?, ?, 'Channel', 'Noir Jazz', 'automatic', 'channel')",
                (source_id, "https://www.youtube.com/channel/" + source_id),
            )
            connection.execute("INSERT INTO source_aliases VALUES(?, ?)", ("https://www.youtube.com/channel/" + source_id, source_id))
            connection.execute(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, availability, origin)
                   VALUES('WlPiQGR18jE', ?, 'VHS Dreams', 'Silent Depths', -1,
                          'https://i.ytimg.com/vi/WlPiQGR18jE/hqdefault.jpg', 10974,
                          'Noir Jazz', 100, 100, 'unknown', 'channel')""",
                (source_id,),
            )
            connection.execute("INSERT INTO curation(song_id) VALUES('WlPiQGR18jE')")
        CatalogPublisher(catalog.master, catalog.app_path).build()
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        lab.cast = SimpleNamespace(cast_type="audio")
        lab.selected_id = "speaker"
        lab.status.return_value = {
            "connected": True, "device": "speaker", "title": "VHS Dreams", "state": "PLAYING",
            "source": self.source, "position": 100, "duration": 10974, "volume": 50,
            "idle_reason": None, "mode": "direct", "media_session": "session-1", "app": "Default Media Receiver",
        }
        bridge = Bridge(lab, catalog=catalog)
        bridge.membership = Mock()
        bridge.membership.temporary_ids = set()
        bridge.membership.snapshot.return_value = {}
        bridge.target = "speaker"
        bridge.recovery_rows = Mock(return_value=[{
            "id": "speaker", "fresh": True, "state": "PLAYING", "content_key": "k",
            "source": self.source, "position": 100,
        }])
        with patch("listening.time.time", side_effect=[100, 160]):
            bridge.snapshot()
            lab.status.return_value = dict(lab.status.return_value, position=160)
            bridge.recovery_rows.return_value[0]["position"] = 160
            bridge.snapshot()
        catalog.close()
        aggregate = catalog.personal.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 60)
        self.assertEqual(aggregate["qualified_sessions"], 0)
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
