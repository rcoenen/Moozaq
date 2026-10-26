import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from app_catalog import AppCatalog
from catalog_publish import CatalogPublisher, activate_app_catalog, previous_app_path
from catalog_sqlite import MasterCatalog
from server import Bridge


class AppCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.master = MasterCatalog(self.root / "master.db")
        self.source_id = "UC" + "a" * 22
        with self.master.store.writing() as connection:
            connection.execute(
                "INSERT INTO sources(id, url, name, category, publication_mode, origin) VALUES(?, ?, ?, ?, 'automatic', 'channel')",
                (self.source_id, "https://www.youtube.com/channel/" + self.source_id, "Channel", "Noir Jazz"),
            )
            connection.execute("INSERT INTO source_aliases VALUES(?, ?)", ("https://www.youtube.com/channel/" + self.source_id, self.source_id))
        self.app_path = self.root / "app-catalog.db"
        self.publisher = CatalogPublisher(self.master, self.app_path)

    def add_song(self, video_id="abcdefghijk", first=100, recommended=200):
        with self.master.store.writing() as connection:
            connection.execute(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, availability, origin)
                   VALUES(?, ?, ?, 'Creator', -1, ?, 7200, 'Noir Jazz', ?, ?, 'unknown', 'channel')""",
                (video_id, self.source_id, video_id, f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg", first, first),
            )
            connection.execute("INSERT INTO curation(song_id, recommended_at) VALUES(?, ?)", (video_id, recommended))
            connection.execute("UPDATE master_state SET revision=revision+1, dirty=1 WHERE id=1")

    def test_readonly_snapshot_and_bridge_leave_master_unchanged(self):
        self.add_song()
        self.publisher.build(now=100)
        app = AppCatalog(self.app_path)
        master_before = self.master.snapshot()
        snapshot = app.snapshot()
        self.assertEqual(snapshot["app_revision"], 2)
        self.assertEqual(snapshot["recommended_ids"], ["abcdefghijk"])
        self.assertEqual(snapshot["recently_added_ids"], ["abcdefghijk"])
        with self.assertRaises(PermissionError):
            with app.store.writing():
                pass
        with app.store.reading() as connection:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("INSERT INTO songs(id, title, creator, duration, category, origin) VALUES('x', 'x', 'x', 7200, 'x', 'x')")
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.status.return_value = {"connected": False}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        with patch("server.DATA", self.root):
            bridge = Bridge(lab, catalog=app)
            state = bridge.snapshot()
        self.assertEqual(state["catalog_backend"], "sqlite")
        self.assertEqual(state["catalog_revision"], 2)
        self.assertEqual(self.master.snapshot(), master_before)
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_replacement_is_validated_and_seen_as_one_revision(self):
        self.add_song()
        self.publisher.build(now=100)
        app = AppCatalog(self.app_path)
        self.assertEqual(app.snapshot()["app_revision"], 2)
        self.add_song("zyxwvutsrqp", first=300, recommended=300)
        self.publisher.build(now=101)
        status = app.status()
        snapshot = app.snapshot()
        self.assertEqual(status["catalog_revision"], 3)
        self.assertEqual(snapshot["app_revision"], 3)
        self.assertEqual(snapshot["master_revision"], 3)
        self.assertEqual({song["id"] for song in snapshot["mixes"]}, {"abcdefghijk", "zyxwvutsrqp"})
        self.assertEqual(snapshot["recently_added_ids"][0], "zyxwvutsrqp")
        self.assertEqual(self.master.state()["dirty"], 0)

    def test_sql_sections_are_bounded_and_use_real_dates(self):
        for index in range(25):
            self.add_song(f"{index:011d}", first=100+index, recommended=200+index)
        self.add_song("unknown0001", first=None, recommended=1)
        self.publisher.build(now=100)
        snapshot = AppCatalog(self.app_path).snapshot()
        self.assertEqual(len(snapshot["recommended_ids"]), 12)
        self.assertEqual(len(snapshot["recently_added_ids"]), 20)
        self.assertEqual(snapshot["recommended_ids"][0], f"{24:011d}")
        self.assertEqual(snapshot["recently_added_ids"][0], f"{24:011d}")
        self.assertNotIn("unknown0001", snapshot["recently_added_ids"])

    def test_failed_replacement_validation_keeps_previous_publication(self):
        self.add_song()
        self.publisher.build(now=100)
        app = AppCatalog(self.app_path)
        original_fingerprint = app.fingerprint
        self.app_path.touch()
        with patch("app_catalog.validate_app_catalog", side_effect=RuntimeError("invalid replacement")):
            with self.assertRaisesRegex(RuntimeError, "invalid replacement"):
                app.status()
        self.assertEqual(app.publication["revision"], 2)
        self.assertEqual(app.fingerprint, original_fingerprint)

    def test_previous_activation_restores_old_content_and_retains_current(self):
        self.add_song()
        self.publisher.build(now=100)
        initial = AppCatalog(self.app_path).snapshot()
        self.add_song("zyxwvutsrqp", first=300, recommended=300)
        self.publisher.build(now=101)
        current = AppCatalog(self.app_path).snapshot()
        self.assertEqual(current["app_revision"], 3)
        result = activate_app_catalog(previous_app_path(self.app_path), self.app_path, expected_revision=2)
        self.assertEqual(result["revision"], 2)
        restored = AppCatalog(self.app_path).snapshot()
        self.assertEqual(restored["app_revision"], 2)
        self.assertEqual({song["id"] for song in restored["mixes"]}, {song["id"] for song in initial["mixes"]})
        previous = AppCatalog(previous_app_path(self.app_path)).snapshot()
        self.assertEqual(previous["app_revision"], 3)
        self.assertEqual({song["id"] for song in previous["mixes"]}, {song["id"] for song in current["mixes"]})
        self.assertEqual(self.master.state()["revision"], 3)
        self.assertFalse(list(self.root.glob("app-catalog.restore-*.db")))


if __name__ == "__main__":
    unittest.main()
