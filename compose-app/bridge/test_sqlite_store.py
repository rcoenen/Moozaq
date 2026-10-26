import sqlite3
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from sqlite_store import SqliteStore


class SqliteStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "store.db"

    def store(self, **kwargs):
        return SqliteStore(self.path, **kwargs)

    def migrate(self, store):
        store.migrate({
            1: (
                "CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
                "CREATE INDEX idx_items_name ON items(name)",
            ),
            2: ("ALTER TABLE items ADD COLUMN created_at INTEGER",),
        })

    def test_real_sqlite_schema_migration_and_read_write(self):
        store = self.store()
        self.migrate(store)
        with store.writing() as connection:
            connection.execute("INSERT INTO items(id, name, created_at) VALUES (?, ?, ?)", (1, "Noir", 123))
        with store.reading() as connection:
            row = connection.execute("SELECT * FROM items WHERE id=1").fetchone()
            self.assertEqual(row["name"], "Noir")
            self.assertEqual(row["created_at"], 123)
            self.assertEqual(connection.execute("PRAGMA foreign_keys").fetchone()[0], 1)
            self.assertEqual(connection.execute("PRAGMA journal_mode").fetchone()[0], "wal")
        self.assertEqual(store.user_version(), 2)
        self.assertGreater(sqlite3.sqlite_version_info[0], 0)

    def test_failed_transaction_rolls_back(self):
        store = self.store()
        self.migrate(store)
        with self.assertRaisesRegex(RuntimeError, "abort"):
            with store.writing() as connection:
                connection.execute("INSERT INTO items(id, name) VALUES (1, 'discard')")
                raise RuntimeError("abort")
        with store.reading() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM items").fetchone()[0], 0)

    def test_concurrent_readers_and_serialized_writers(self):
        store = self.store()
        self.migrate(store)
        barrier = threading.Barrier(5)

        def insert_many(offset):
            barrier.wait()
            for index in range(25):
                with store.writing() as connection:
                    connection.execute("INSERT INTO items(id, name) VALUES (?, ?)", (offset + index, "mix"))

        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = [executor.submit(insert_many, offset) for offset in (0, 25, 50, 75)]
            barrier.wait()
            for _ in range(20):
                with store.reading() as connection:
                    count = connection.execute("SELECT COUNT(*) FROM items").fetchone()[0]
                    self.assertGreaterEqual(count, 0)
                    self.assertLessEqual(count, 100)
            for future in futures:
                future.result()
        with store.reading() as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM items").fetchone()[0], 100)
            self.assertEqual(connection.execute("SELECT COUNT(DISTINCT id) FROM items").fetchone()[0], 100)

    def test_readonly_store_rejects_writes(self):
        store = self.store()
        self.migrate(store)
        readonly = self.store(readonly=True)
        with self.assertRaises(PermissionError):
            with readonly.writing():
                pass
        with readonly.reading() as connection:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("INSERT INTO items(id, name) VALUES (1, 'no')")

    def test_newer_database_schema_is_not_downgraded(self):
        store = self.store()
        self.migrate(store)
        with self.assertRaisesRegex(ValueError, "newer"):
            store.migrate({1: ("SELECT 1",)})
        self.assertEqual(store.user_version(), 2)


if __name__ == "__main__":
    unittest.main()
