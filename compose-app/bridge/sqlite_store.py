import sqlite3
import threading
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import quote


class SqliteStore:
    def __init__(self, path, *, readonly=False, busy_timeout_ms=5000):
        self.path = Path(path)
        self.readonly = readonly
        self.busy_timeout_ms = busy_timeout_ms
        self.writer_lock = threading.RLock()
        if not readonly:
            self.path.parent.mkdir(parents=True, exist_ok=True)

    def connect(self):
        if self.readonly:
            uri = f"file:{quote(self.path.resolve().as_posix(), safe='/')}?mode=ro"
            connection = sqlite3.connect(uri, uri=True, timeout=self.busy_timeout_ms / 1000)
        else:
            connection = sqlite3.connect(self.path, timeout=self.busy_timeout_ms / 1000)
        connection.row_factory = sqlite3.Row
        connection.execute(f"PRAGMA busy_timeout={self.busy_timeout_ms}")
        connection.execute("PRAGMA foreign_keys=ON")
        if self.readonly:
            connection.execute("PRAGMA query_only=ON")
        else:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=NORMAL")
        return connection

    @contextmanager
    def reading(self):
        connection = self.connect()
        try:
            yield connection
        finally:
            connection.close()

    @contextmanager
    def writing(self):
        if self.readonly:
            raise PermissionError("This SQLite store is read-only.")
        with self.writer_lock:
            connection = self.connect()
            try:
                connection.execute("BEGIN IMMEDIATE")
                yield connection
                connection.commit()
            except Exception:
                connection.rollback()
                raise
            finally:
                connection.close()

    def user_version(self):
        with self.reading() as connection:
            return connection.execute("PRAGMA user_version").fetchone()[0]

    def migrate(self, migrations):
        if not migrations:
            raise ValueError("At least one schema migration is required.")
        with self.writer_lock:
            current = self.user_version()
            target = max(migrations)
            if current > target:
                raise ValueError(f"Database schema {current} is newer than supported schema {target}.")
            for version in sorted(migrations):
                if version <= current:
                    continue
                with self.writing() as connection:
                    for statement in migrations[version]:
                        connection.execute(statement)
                    connection.execute(f"PRAGMA user_version={version}")
