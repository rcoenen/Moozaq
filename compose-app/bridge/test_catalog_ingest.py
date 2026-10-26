import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from desktop_catalog import DesktopCatalog
from server import Bridge


class CatalogIngestTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "channels.json").write_text("[]")
        (self.root / "channel-catalog.json").write_text(json.dumps({"channels": [], "mixes": []}))
        self.catalog = DesktopCatalog(self.root, self.root / "channels.json", generation_delay=0)
        self.addCleanup(self.catalog.close)
        self.lab = self.make_lab()
        self.bridge = Bridge(self.lab, catalog=self.catalog)

    def make_lab(self):
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.status.return_value = {"connected": False}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        return lab

    def metadata(self, video_id="WlPiQGR18jE", duration=10974):
        return {
            "id": video_id, "title": "VHS Dreams", "creator": "Silent Depths",
            "channel_url": "https://www.youtube.com/@Silent-Depths-b9j", "channel_id": None,
            "thumbnail": f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
            "duration": duration, "source_published_at": 1774008766,
            "webpage_url": f"https://www.youtube.com/watch?v={video_id}",
        }

    def test_preview_does_not_write_or_play(self):
        with patch("video_metadata.fetch_video_metadata", return_value=self.metadata()):
            result = self.bridge.dispatch({"op": "preview_video", "source": "WlPiQGR18jE"})
        self.assertEqual(result["duration"], 10974)
        self.assertEqual(self.catalog.counts()["songs"], 6)
        self.assertEqual(self.catalog.counts()["sources"], 0)
        self.lab.play_selection.assert_not_called()
        self.lab.command.assert_not_called()

    def test_recommend_without_follow_then_follow_later_deduplicates(self):
        with patch("video_metadata.fetch_video_metadata", return_value=self.metadata()), \
                patch("catalog_ingest.time.time", return_value=100):
            result = self.bridge.dispatch({"op": "import_video", "source": "WlPiQGR18jE", "recommend": True})
        self.assertTrue(result["eligible"])
        self.assertTrue(result["recommended"])
        self.assertFalse(result["channel_followed"])
        self.assertEqual(self.catalog.counts()["sources"], 0)
        with self.catalog.master.store.reading() as connection:
            original = connection.execute(
                """SELECT songs.first_seen_at, curation.recommended_at, songs.origin
                   FROM songs JOIN curation ON curation.song_id=songs.id WHERE songs.id='WlPiQGR18jE'"""
            ).fetchone()
        self.assertEqual(original["first_seen_at"], 100)
        self.assertEqual(original["recommended_at"], 100)
        self.assertEqual(original["origin"], "individual")
        self.catalog.rebuild(timeout=2)
        self.assertIn("WlPiQGR18jE", self.catalog.snapshot()["recommended_ids"])

        payload = {
            "channel_id": "UC" + "a" * 22,
            "channel": "Silent Depths",
            "entries": [
                {"id": "WlPiQGR18jE", "title": "VHS Dreams updated", "duration": 10974},
                {"id": "abcdefghijk", "title": "New review upload", "duration": 7200},
            ],
        }
        with patch("catalog_ingest.subprocess.run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(payload))) as run:
            followed = self.bridge.dispatch({
                "op": "follow_channel", "url": "https://www.youtube.com/@Silent-Depths-b9j", "refresh": True,
            })
        self.assertTrue(followed["created"])
        self.assertEqual(followed["import_count"], 2)
        self.assertEqual(self.catalog.counts()["songs"], 8)
        with self.catalog.master.store.reading() as connection:
            rows = connection.execute(
                """SELECT songs.title, songs.channel_id, songs.first_seen_at, curation.recommended_at,
                          curation.review_status, songs.origin
                   FROM songs JOIN curation ON curation.song_id=songs.id WHERE songs.id='WlPiQGR18jE'"""
            ).fetchone()
            source = connection.execute("SELECT * FROM sources").fetchone()
            new_item = connection.execute(
                "SELECT review_status FROM curation WHERE song_id='abcdefghijk'"
            ).fetchone()
        self.assertEqual(rows["title"], "VHS Dreams updated")
        self.assertEqual(rows["channel_id"], source["id"])
        self.assertEqual(rows["first_seen_at"], 100)
        self.assertEqual(rows["recommended_at"], 100)
        self.assertEqual(rows["origin"], "individual")
        self.assertEqual(new_item["review_status"], "pending")
        self.catalog.rebuild(timeout=2)
        snapshot = self.catalog.snapshot()
        self.assertEqual({song["id"] for song in snapshot["mixes"]}, {"WlPiQGR18jE"})
        self.assertEqual(snapshot["recommended_ids"], ["WlPiQGR18jE"])
        args = run.call_args.args[0]
        self.assertIn("--skip-download", args)
        self.assertIn("--flat-playlist", args)
        self.assertNotIn("--cookies", args)
        self.lab.play_selection.assert_not_called()
        self.lab.command.assert_not_called()

    def test_unknown_and_short_duration_stay_out_of_app(self):
        unknown = self.metadata(duration=None)
        short = self.metadata(video_id="12345678901", duration=120)
        with patch("video_metadata.fetch_video_metadata", side_effect=[unknown, short]):
            first = self.bridge.dispatch({"op": "import_video", "source": "WlPiQGR18jE", "recommend": True})
            second = self.bridge.dispatch({"op": "import_video", "source": "12345678901", "recommend": True})
        self.assertFalse(first["eligible"])
        self.assertFalse(first["recommended"])
        self.assertFalse(second["eligible"])
        self.assertFalse(second["recommended"])
        self.assertEqual(self.catalog.counts()["songs"], 8)
        self.assertEqual(self.catalog.snapshot()["mixes"], [])
        with self.catalog.master.store.reading() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM curation WHERE recommended_at IS NOT NULL").fetchone()[0], 0)

    def test_follow_without_refresh_does_not_import_or_play(self):
        with patch("catalog_ingest.subprocess.run") as run:
            result = self.bridge.dispatch({"op": "follow_channel", "url": "https://www.youtube.com/@Silent-Depths-b9j"})
        self.assertTrue(result["created"])
        self.assertEqual(result["publication_mode"], "review")
        self.assertEqual(self.catalog.counts()["sources"], 1)
        run.assert_not_called()
        self.lab.play_selection.assert_not_called()
        self.lab.command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
