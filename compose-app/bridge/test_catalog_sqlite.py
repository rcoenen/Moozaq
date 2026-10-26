import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from catalog_sqlite import MasterCatalog, SCHEMA_VERSION, STARTERS, utc_timestamp


class MasterCatalogMigrationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.json_path = self.root / "channel-catalog.json"
        self.master_path = self.root / "master.db"
        self.channel_id = "UC" + "a" * 22
        self.seeds = [{"url": "https://www.youtube.com/@seed", "name": "Seed", "category": "Noir Jazz"}]
        self.legacy = {
            "channels": [
                {"url": "https://www.youtube.com/@seed", "id": self.channel_id, "name": "Seed", "category": "Noir Jazz", "count": 2, "refreshed": "2026-10-16T20:20:41Z", "error": None},
                {"url": "https://www.youtube.com/channel/" + self.channel_id, "id": self.channel_id, "name": "Seed alias", "category": "Noir Jazz", "count": 2, "refreshed": "2026-10-16T20:20:41Z", "error": None},
                {"url": "https://www.youtube.com/@other", "name": "Other", "category": "Cozy Jazz / Swing", "count": 1, "refreshed": None, "error": None},
            ],
            "mixes": [
                {"id": "abcdefghijk", "title": "Long", "creator": "Seed", "channel_id": self.channel_id, "artwork": -1, "thumbnail": "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", "duration": 7200, "category": "Noir Jazz"},
                {"id": "12345678901", "title": "Short", "creator": "Seed", "channel_id": self.channel_id, "artwork": -1, "thumbnail": None, "duration": 120, "category": "Noir Jazz"},
                {"id": "zyxwvutsrqp", "title": "Unknown duration", "creator": "Other", "channel_id": None, "artwork": -1, "thumbnail": None, "duration": None, "category": "Cozy Jazz / Swing"},
            ],
        }

    def write_legacy(self):
        self.json_path.write_text(json.dumps(self.legacy, ensure_ascii=False))

    def master(self):
        return MasterCatalog(self.master_path, self.seeds)

    def test_migration_preserves_records_backup_dates_and_indexes(self):
        self.write_legacy()
        original = self.json_path.read_bytes()
        master = self.master()
        summary = master.migrate_json(self.json_path)
        self.assertEqual(summary["sources"], 2)
        self.assertEqual(summary["aliases"], 3)
        self.assertEqual(summary["songs"], len(self.legacy["mixes"]) + len(STARTERS))
        self.assertEqual(self.json_path.read_bytes(), original)
        backup = Path(summary["backup"])
        self.assertEqual(backup.read_bytes(), original)
        self.assertEqual(master.counts(), {"sources": 2, "aliases": 3, "songs": 9, "curation": 9})
        self.assertEqual(master.store.user_version(), SCHEMA_VERSION)
        self.assertEqual(master.state()["revision"], 1)
        self.assertEqual(master.state()["dirty"], 1)
        snapshot = master.snapshot()
        self.assertEqual(len(snapshot["channels"]), 2)
        self.assertEqual(len(snapshot["mixes"]), 9)
        self.assertTrue(all(song["first_seen_at"] is None for song in snapshot["mixes"]))
        self.assertIn("zyxwvutsrqp", {song["id"] for song in snapshot["mixes"]})
        self.assertIn("12345678901", {song["id"] for song in snapshot["mixes"]})
        short = next(song for song in snapshot["mixes"] if song["id"] == "12345678901")
        self.assertEqual(short["duration"], 120)
        unknown = next(song for song in snapshot["mixes"] if song["id"] == "zyxwvutsrqp")
        self.assertIsNone(unknown["duration"])
        seed = next(channel for channel in snapshot["channels"] if channel["url"].endswith("@seed"))
        self.assertEqual(seed["id"], self.channel_id)
        self.assertEqual(len(seed["aliases"]), 2)
        self.assertEqual(seed["last_success_at"], utc_timestamp("2026-10-16T20:20:41Z"))
        self.assertEqual(seed["origin"], "seed")
        with master.store.reading() as connection:
            self.assertEqual(connection.execute("PRAGMA integrity_check").fetchone()[0], "ok")
            self.assertEqual(connection.execute("PRAGMA foreign_key_check").fetchall(), [])
            indexes = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='index'")}
        self.assertIn("idx_songs_first_seen", indexes)
        self.assertIn("idx_curation_recommended", indexes)
        self.assertIn("idx_songs_duration", indexes)

    def test_second_migration_is_refused_without_duplication(self):
        self.write_legacy()
        master = self.master()
        summary = master.migrate_json(self.json_path)
        before = master.snapshot()
        with self.assertRaisesRegex(RuntimeError, "one-time"):
            master.migrate_json(self.json_path)
        self.assertEqual(master.snapshot(), before)
        self.assertEqual(Path(summary["backup"]).exists(), True)
        self.assertEqual(master.counts()["songs"], 9)

    def test_invalid_record_leaves_original_and_empty_master(self):
        self.legacy["mixes"][0]["id"] = "invalid"
        self.write_legacy()
        original = self.json_path.read_bytes()
        master = self.master()
        with self.assertRaises(ValueError):
            master.migrate_json(self.json_path)
        self.assertEqual(self.json_path.read_bytes(), original)
        self.assertEqual(master.counts(), {"sources": 0, "aliases": 0, "songs": 0, "curation": 0})
        self.assertFalse(self.json_path.with_suffix(".backup.json").exists())

    def test_failed_database_insert_rolls_back_everything(self):
        self.legacy["mixes"][0]["missing_reads"] = -1
        self.write_legacy()
        original = self.json_path.read_bytes()
        master = self.master()
        with self.assertRaises(sqlite3.IntegrityError):
            master.migrate_json(self.json_path)
        self.assertEqual(self.json_path.read_bytes(), original)
        self.assertEqual(master.counts(), {"sources": 0, "aliases": 0, "songs": 0, "curation": 0})
        self.assertEqual(self.json_path.with_suffix(".backup.json").read_bytes(), original)

    def test_foreign_keys_and_availability_constraints_are_enforced(self):
        self.write_legacy()
        master = self.master()
        master.migrate_json(self.json_path)
        with self.assertRaises(sqlite3.IntegrityError):
            with master.store.writing() as connection:
                connection.execute("INSERT INTO curation(song_id) VALUES('not-a-song')")
        with self.assertRaises(sqlite3.IntegrityError):
            with master.store.writing() as connection:
                connection.execute("UPDATE songs SET availability='invented' WHERE id='abcdefghijk'")


if __name__ == "__main__":
    unittest.main()
