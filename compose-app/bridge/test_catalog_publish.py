import os
import sqlite3
import tempfile
from contextlib import closing
import unittest
from pathlib import Path
from unittest.mock import patch

from catalog_publish import (APP_SCHEMA_VERSION, CatalogPublisher, candidate_app_path,
                             previous_app_path, validate_app_catalog)
from catalog_sqlite import MasterCatalog


class CatalogPublishTests(unittest.TestCase):
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
            connection.execute("UPDATE master_state SET revision=7, dirty=1 WHERE id=1")
        self.app_path = self.root / "app-catalog.db"
        self.publisher = CatalogPublisher(self.master, self.app_path)

    def song(self, video_id, *, duration=7200, hidden=0, review="approved", availability="unknown", recommended=None, first=100):
        with self.master.store.writing() as connection:
            connection.execute(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, availability, origin)
                   VALUES(?, ?, ?, 'Creator', -1, ?, ?, 'Automatic', ?, ?, ?, 'channel')""",
                (video_id, self.source_id, video_id, f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
                 duration, first, first, availability),
            )
            connection.execute(
                "INSERT INTO curation(song_id, hidden, category_override, review_status, recommended_at) VALUES(?, ?, ?, ?, ?)",
                (video_id, hidden, "Curated mood" if video_id == "abcdefghijk" else None, review, recommended),
            )

    def test_projection_contains_only_public_eligible_records(self):
        self.song("abcdefghijk", recommended=200, first=100)
        self.song("12345678901", duration=120, recommended=210, first=110)
        self.song("zyxwvutsrqp", hidden=1)
        self.song("review00001", review="pending")
        self.song("unavail0001", availability="unavailable")
        self.song("missing0001", availability="missing", first=120)
        self.song("unknown0001", availability="unknown", first=130)
        self.song("nulldur0001", duration=None)
        info = self.publisher.build(now=123456)
        self.assertEqual(info["revision"], 7)
        self.assertEqual(info["songs"], 3)
        self.assertEqual(info["sources"], 1)
        self.assertEqual(info["generated_at"], 123456)
        self.assertEqual(info["schema_version"], APP_SCHEMA_VERSION)
        with closing(sqlite3.connect(self.app_path)) as connection:
            ids = {row[0] for row in connection.execute("SELECT id FROM songs")}
            self.assertEqual(ids, {"abcdefghijk", "missing0001", "unknown0001"})
            self.assertEqual(connection.execute("SELECT category FROM songs WHERE id='abcdefghijk'").fetchone()[0], "Curated mood")
            self.assertEqual(connection.execute("SELECT revision, source_master_revision FROM publication").fetchone(), (7, 7))
            tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertNotIn("curation", tables)
            self.assertNotIn("master_state", tables)
            self.assertNotIn("ratings", tables)
            indexes = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='index'")}
            self.assertIn("idx_app_songs_first_seen", indexes)
            self.assertIn("idx_app_songs_recommended", indexes)
        self.assertEqual(validate_app_catalog(self.app_path)["songs"], 3)
        self.assertEqual(self.master.state()["dirty"], 0)
        self.assertEqual(self.master.state()["generated_revision"], 7)

    def test_non_public_artwork_is_rejected(self):
        self.song("abcdefghijk")
        self.publisher.build(now=100)
        with closing(sqlite3.connect(self.app_path)) as connection:
            connection.execute("UPDATE songs SET thumbnail='https://googlevideo.com/play?expire=1&signature=secret' WHERE id='abcdefghijk'")
            connection.commit()
        with self.assertRaisesRegex(ValueError, "artwork"):
            validate_app_catalog(self.app_path)

    def test_failed_validation_keeps_active_and_previous_byte_identical(self):
        self.song("abcdefghijk")
        self.publisher.build(now=100)
        with self.master.store.writing() as connection:
            connection.execute("UPDATE master_state SET revision=8, dirty=1 WHERE id=1")
        self.publisher.build(now=101)
        active_before = self.app_path.read_bytes()
        previous_before = previous_app_path(self.app_path).read_bytes()
        with patch("catalog_publish.validate_app_catalog", side_effect=RuntimeError("injected validation")):
            with self.assertRaisesRegex(RuntimeError, "injected validation"):
                self.publisher.build(now=102)
        self.assertEqual(self.app_path.read_bytes(), active_before)
        self.assertEqual(previous_app_path(self.app_path).read_bytes(), previous_before)
        self.assertFalse(candidate_app_path(self.app_path).exists())
        self.assertEqual(self.master.state()["dirty"], 1)
        self.assertEqual(self.master.state()["last_generation_error"], "injected validation")

    def test_failed_activation_keeps_active_catalog(self):
        self.song("abcdefghijk")
        self.publisher.build(now=100)
        active_before = self.app_path.read_bytes()
        with patch("catalog_publish.os.replace", side_effect=OSError("activation failed")):
            with self.assertRaises(OSError):
                self.publisher.build(now=101)
        self.assertEqual(self.app_path.read_bytes(), active_before)
        self.assertFalse(candidate_app_path(self.app_path).exists())

    def test_candidate_schema_rejects_extra_private_table(self):
        self.song("abcdefghijk")
        candidate = candidate_app_path(self.app_path)
        self.publisher._create_candidate(100)
        with closing(sqlite3.connect(candidate)) as connection:
            connection.execute("CREATE TABLE ratings(video_id TEXT)")
            connection.commit()
        with self.assertRaisesRegex(ValueError, "non-public tables"):
            validate_app_catalog(candidate)


if __name__ == "__main__":
    unittest.main()
