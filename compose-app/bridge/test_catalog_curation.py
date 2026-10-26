import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from desktop_catalog import DesktopCatalog
from server import Bridge


class CatalogCurationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "channels.json").write_text("[]")
        (self.root / "channel-catalog.json").write_text(json.dumps({"channels": [], "mixes": []}))
        self.catalog = DesktopCatalog(self.root, self.root / "channels.json", generation_delay=0)
        self.addCleanup(self.catalog.close)
        self.lab = Mock()
        self.lab.devices.return_value = []
        self.lab.browser.devices = {}
        self.lab.status.return_value = {"connected": False}
        self.lab.playback_gate = threading.RLock()
        self.lab.generation = 0
        self.bridge = Bridge(self.lab, catalog=self.catalog)
        self.channel_id = "UC" + "a" * 22
        self.channel_url = "https://www.youtube.com/channel/" + self.channel_id

    def add_song(self, video_id="abcdefghijk", duration=7200, review="approved", hidden=0, first=100):
        self.catalog.ingest.follow_channel(self.channel_url)
        with self.catalog.master.store.writing() as connection:
            connection.execute(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, availability, origin)
                   VALUES(?, ?, ?, 'Creator', -1, ?, ?, 'Automatic', ?, ?, 'unknown', 'channel')""",
                (video_id, self.channel_id, video_id, f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
                 duration, first, first),
            )
            connection.execute(
                "INSERT INTO curation(song_id, hidden, review_status) VALUES(?, ?, ?)",
                (video_id, hidden, review),
            )
            connection.execute("UPDATE master_state SET revision=revision+1, dirty=1 WHERE id=1")

    def test_recommend_category_hide_restore_and_unrecommend(self):
        self.add_song()
        first = self.bridge.dispatch({"op": "curate", "action": "recommend", "song": "abcdefghijk", "now": 100})
        second = self.bridge.dispatch({"op": "curate", "action": "recommend", "song": "abcdefghijk", "now": 200})
        self.assertTrue(first["changed"])
        self.assertFalse(second["changed"])
        self.assertEqual(second["recommended_at"], 100)
        self.bridge.dispatch({"op": "curate", "action": "category", "song": "abcdefghijk", "category": "Owner mood"})
        self.catalog.rebuild(timeout=2)
        self.assertEqual(self.catalog.snapshot()["mixes"][0]["category"], "Owner mood")
        self.bridge.dispatch({"op": "curate", "action": "hide", "song": "abcdefghijk"})
        self.catalog.rebuild(timeout=2)
        self.assertEqual(self.catalog.snapshot()["mixes"], [])
        with self.catalog.master.store.reading() as connection:
            saved = connection.execute("SELECT recommended_at, category_override FROM curation WHERE song_id='abcdefghijk'").fetchone()
        self.assertEqual(saved["recommended_at"], 100)
        self.assertEqual(saved["category_override"], "Owner mood")
        self.bridge.dispatch({"op": "curate", "action": "restore", "song": "abcdefghijk"})
        self.catalog.rebuild(timeout=2)
        self.assertEqual(self.catalog.snapshot()["recommended_ids"], ["abcdefghijk"])
        self.bridge.dispatch({"op": "curate", "action": "unrecommend", "song": "abcdefghijk"})
        self.catalog.rebuild(timeout=2)
        snapshot = self.catalog.snapshot()
        self.assertEqual(snapshot["recommended_ids"], [])
        self.assertEqual(len(snapshot["mixes"]), 1)
        self.lab.play_selection.assert_not_called()
        self.lab.command.assert_not_called()

    def test_short_song_cannot_be_recommended(self):
        self.add_song("12345678901", duration=120)
        before = self.catalog.master.state()["revision"]
        with self.assertRaisesRegex(ValueError, "one hour"):
            self.bridge.dispatch({"op": "curate", "action": "recommend", "song": "12345678901"})
        self.assertEqual(self.catalog.master.state()["revision"], before)
        self.assertEqual(self.catalog.snapshot()["mixes"], [])

    def test_review_approval_and_source_mode_are_independent(self):
        self.catalog.ingest.follow_channel(self.channel_url)
        self.add_song("abcdefghijk", review="pending")
        self.add_song("zyxwvutsrqp", review="pending")
        self.bridge.dispatch({"op": "curate", "action": "review", "song": "abcdefghijk", "status": "approved"})
        self.catalog.rebuild(timeout=2)
        self.assertEqual({song["id"] for song in self.catalog.snapshot()["mixes"]}, {"abcdefghijk"})
        self.bridge.dispatch({"op": "curate", "action": "source_mode", "url": self.channel_url, "mode": "automatic"})
        payload = {"channel_id": self.channel_id, "channel": "Channel", "entries": [{"id": "review00001", "title": "Third", "duration": 7200}]}
        with patch("catalog_ingest.subprocess.run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(payload))):
            self.catalog.import_channel(self.channel_url)
        self.catalog.rebuild(timeout=2)
        self.assertEqual({song["id"] for song in self.catalog.snapshot()["mixes"]}, {"abcdefghijk", "review00001"})
        with self.catalog.master.store.reading() as connection:
            self.assertEqual(connection.execute("SELECT review_status FROM curation WHERE song_id='zyxwvutsrqp'").fetchone()[0], "pending")
            self.assertEqual(connection.execute("SELECT review_status FROM curation WHERE song_id='review00001'").fetchone()[0], "approved")

    def test_alias_refresh_preserves_curator_overlay(self):
        self.catalog.ingest.follow_channel(self.channel_url)
        metadata = {
            "id": "WlPiQGR18jE", "title": "Original", "creator": "Silent Depths",
            "channel_url": "https://www.youtube.com/@Silent-Depths-b9j", "channel_id": None,
            "thumbnail": "https://i.ytimg.com/vi/WlPiQGR18jE/hqdefault.jpg",
            "duration": 10974, "source_published_at": 1774008766,
            "webpage_url": "https://www.youtube.com/watch?v=WlPiQGR18jE",
        }
        with patch("video_metadata.fetch_video_metadata", return_value=metadata), \
                patch("catalog_ingest.time.time", return_value=100):
            self.catalog.import_video("WlPiQGR18jE", recommend=True)
        self.catalog.curate("category", song="WlPiQGR18jE", category="Owner mood")
        self.catalog.ingest.follow_channel("https://www.youtube.com/@Silent-Depths-b9j")
        payload = {
            "channel_id": self.channel_id, "channel": "Silent Depths",
            "entries": [{"id": "WlPiQGR18jE", "title": "Updated", "duration": 10974}],
        }
        with patch("catalog_ingest.subprocess.run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(payload))):
            self.catalog.import_channel("https://www.youtube.com/@Silent-Depths-b9j")
        with self.catalog.master.store.reading() as connection:
            song = connection.execute(
                """SELECT songs.title, songs.first_seen_at, curation.recommended_at, curation.category_override
                   FROM songs JOIN curation ON curation.song_id=songs.id WHERE songs.id='WlPiQGR18jE'"""
            ).fetchone()
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM sources").fetchone()[0], 1)
            aliases = {row[0] for row in connection.execute("SELECT url FROM source_aliases")}
            mode = connection.execute("SELECT publication_mode FROM sources").fetchone()[0]
        self.assertEqual(song["title"], "Updated")
        self.assertEqual(song["first_seen_at"], 100)
        self.assertEqual(song["recommended_at"], 100)
        self.assertEqual(song["category_override"], "Owner mood")
        self.assertEqual(mode, "review")
        self.assertEqual(aliases, {self.channel_url, "https://www.youtube.com/@Silent-Depths-b9j"})
        self.catalog.rebuild(timeout=2)
        self.assertEqual(self.catalog.snapshot()["mixes"][0]["category"], "Owner mood")
        self.assertEqual(self.catalog.snapshot()["recommended_ids"], ["WlPiQGR18jE"])


if __name__ == "__main__":
    unittest.main()
