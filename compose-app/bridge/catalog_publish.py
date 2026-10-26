import os
import shutil
import sqlite3
import tempfile
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

from catalog import channel_url
from sqlite_store import SqliteStore


APP_SCHEMA_VERSION = 1
APP_COLUMNS = {
    "sources": {"id", "url", "name", "category"},
    "songs": {"id", "channel_id", "title", "creator", "artwork", "thumbnail", "duration",
              "category", "first_seen_at", "source_published_at", "recommended_at", "origin"},
    "publication": {"id", "revision", "source_master_revision", "schema_version", "generated_at"},
}
APP_SCHEMA = (
    """CREATE TABLE sources (
        id TEXT PRIMARY KEY,
        url TEXT NOT NULL UNIQUE,
        name TEXT NOT NULL,
        category TEXT NOT NULL
    )""",
    """CREATE TABLE songs (
        id TEXT PRIMARY KEY,
        channel_id TEXT REFERENCES sources(id) ON DELETE SET NULL,
        title TEXT NOT NULL,
        creator TEXT NOT NULL,
        artwork INTEGER NOT NULL DEFAULT -1,
        thumbnail TEXT,
        duration REAL NOT NULL CHECK(duration >= 3600),
        category TEXT NOT NULL,
        first_seen_at INTEGER,
        source_published_at INTEGER,
        recommended_at INTEGER,
        origin TEXT NOT NULL
    )""",
    """CREATE TABLE publication (
        id INTEGER PRIMARY KEY CHECK(id = 1),
        revision INTEGER NOT NULL CHECK(revision >= 1),
        source_master_revision INTEGER NOT NULL CHECK(source_master_revision >= 1),
        schema_version INTEGER NOT NULL,
        generated_at INTEGER NOT NULL
    )""",
    "CREATE INDEX idx_app_songs_first_seen ON songs(first_seen_at DESC)",
    "CREATE INDEX idx_app_songs_recommended ON songs(recommended_at DESC)",
    "CREATE INDEX idx_app_songs_category ON songs(category)",
    "CREATE INDEX idx_app_songs_channel ON songs(channel_id)",
    f"PRAGMA user_version={APP_SCHEMA_VERSION}",
)


def previous_app_path(path):
    path = Path(path)
    return path.with_name(f"{path.stem}.previous{path.suffix}")


def candidate_app_path(path):
    path = Path(path)
    return path.with_name(f"{path.stem}.candidate{path.suffix}")


def validate_app_catalog(path, expected_revision=None, expected_songs=None, expected_sources=None):
    store = SqliteStore(path, readonly=True)
    with store.reading() as connection:
        integrity = [row[0] for row in connection.execute("PRAGMA integrity_check")]
        if integrity != ["ok"]:
            raise ValueError(f"App catalog integrity check failed: {integrity}")
        violations = connection.execute("PRAGMA foreign_key_check").fetchall()
        if violations:
            raise ValueError("App catalog has foreign-key violations.")
        if store.user_version() != APP_SCHEMA_VERSION:
            raise ValueError("Unsupported app catalog schema.")
        actual_columns = {}
        for table in APP_COLUMNS:
            actual_columns[table] = {row[1] for row in connection.execute(f"PRAGMA table_info({table})")}
        if actual_columns != APP_COLUMNS:
            raise ValueError("App catalog schema does not match the public allowlist.")
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if tables != set(APP_COLUMNS):
            raise ValueError("App catalog contains non-public tables.")
        publication = connection.execute("SELECT * FROM publication WHERE id=1").fetchone()
        if publication is None:
            raise ValueError("App catalog has no publication identity.")
        if publication["schema_version"] != APP_SCHEMA_VERSION:
            raise ValueError("Publication schema mismatch.")
        if expected_revision is not None and publication["revision"] != expected_revision:
            raise ValueError("Publication revision mismatch.")
        if publication["source_master_revision"] != publication["revision"]:
            raise ValueError("Publication and master revisions diverge.")
        song_count = connection.execute("SELECT COUNT(*) FROM songs").fetchone()[0]
        source_count = connection.execute("SELECT COUNT(*) FROM sources").fetchone()[0]
        if expected_songs is not None and song_count != expected_songs:
            raise ValueError("Publication song count mismatch.")
        if expected_sources is not None and source_count != expected_sources:
            raise ValueError("Publication source count mismatch.")
        for row in connection.execute("SELECT id, url FROM sources"):
            if channel_url(row["url"]) != row["url"]:
                raise ValueError("Invalid public source URL.")
        for row in connection.execute("SELECT id, thumbnail FROM songs WHERE thumbnail IS NOT NULL"):
            parsed = urlparse(row["thumbnail"])
            if parsed.scheme != "https" or parsed.hostname not in ("i.ytimg.com", "img.youtube.com"):
                raise ValueError("Invalid or non-public artwork URL in app catalog.")
        connection.execute(
            "SELECT id FROM songs WHERE recommended_at IS NOT NULL ORDER BY recommended_at DESC, id LIMIT 12"
        ).fetchall()
        connection.execute(
            "SELECT id FROM songs WHERE first_seen_at IS NOT NULL ORDER BY first_seen_at DESC, id LIMIT 20"
        ).fetchall()
        return {"revision": publication["revision"],
                "source_master_revision": publication["source_master_revision"],
                "schema_version": publication["schema_version"],
                "generated_at": publication["generated_at"],
                "songs": song_count, "sources": source_count}


def activate_app_catalog(candidate, app_path, *, expected_revision=None, expected_songs=None, expected_sources=None):
    candidate = Path(candidate)
    app_path = Path(app_path)
    app_path.parent.mkdir(parents=True, exist_ok=True)
    rollback_copy = None
    try:
        if candidate == previous_app_path(app_path) and app_path.exists():
            with tempfile.NamedTemporaryFile(
                prefix=f"{app_path.stem}.restore-", suffix=app_path.suffix,
                dir=app_path.parent, delete=False,
            ) as temporary:
                rollback_copy = Path(temporary.name)
            shutil.copy2(candidate, rollback_copy)
            candidate = rollback_copy
        info = validate_app_catalog(candidate, expected_revision=expected_revision,
                                    expected_songs=expected_songs, expected_sources=expected_sources)
        if app_path.exists():
            shutil.copy2(app_path, previous_app_path(app_path))
        os.replace(candidate, app_path)
        return info
    finally:
        if rollback_copy is not None:
            rollback_copy.unlink(missing_ok=True)


class CatalogPublisher:
    def __init__(self, master, path):
        self.master = master
        self.path = Path(path)
        self.candidate = candidate_app_path(self.path)
        self.previous = previous_app_path(self.path)
        self.lock = threading.RLock()

    def build(self, now=None):
        generated_at = int(time.time() if now is None else now)
        activated = False
        with self.lock:
            try:
                self._remove_candidate()
                revision, songs, sources = self._create_candidate(generated_at)
                info = activate_app_catalog(self.candidate, self.path, expected_revision=revision,
                                            expected_songs=len(songs), expected_sources=len(sources))
                activated = True
                with self.master.store.writing() as connection:
                    connection.execute(
                        """UPDATE master_state
                           SET generated_revision=?, dirty=CASE WHEN revision=? THEN 0 ELSE 1 END,
                               last_generation_at=?, last_generation_error=NULL WHERE id=1""",
                        (revision, revision, generated_at),
                    )
                return info
            except Exception as exc:
                self._remove_candidate()
                with self.master.store.writing() as connection:
                    connection.execute(
                        "UPDATE master_state SET dirty=1, last_generation_error=? WHERE id=1",
                        (str(exc),),
                    )
                if activated:
                    raise RuntimeError(f"App catalog activated but publication state failed: {exc}") from exc
                raise

    def _create_candidate(self, generated_at):
        with self.master.store.reading() as master_connection:
            master_connection.execute("BEGIN")
            state = master_connection.execute("SELECT * FROM master_state WHERE id=1").fetchone()
            songs = master_connection.execute(
                """SELECT songs.*, curation.hidden, curation.category_override,
                          curation.review_status, curation.recommended_at
                   FROM songs JOIN curation ON curation.song_id=songs.id
                   WHERE songs.duration IS NOT NULL AND songs.duration >= 3600
                     AND curation.review_status='approved' AND curation.hidden=0
                     AND songs.availability != 'unavailable'
                   ORDER BY songs.id"""
            ).fetchall()
            channel_ids = sorted({row["channel_id"] for row in songs if row["channel_id"] is not None})
            sources = []
            if channel_ids:
                placeholders = ",".join("?" for _ in channel_ids)
                sources = master_connection.execute(
                    f"SELECT * FROM sources WHERE id IN ({placeholders}) ORDER BY id", channel_ids
                ).fetchall()
            revision = state["revision"]
        connection = sqlite3.connect(self.candidate)
        try:
            connection.execute("PRAGMA journal_mode=DELETE")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("PRAGMA foreign_keys=ON")
            for statement in APP_SCHEMA:
                connection.execute(statement)
            connection.executemany(
                "INSERT INTO sources(id, url, name, category) VALUES(?, ?, ?, ?)",
                [(row["id"], row["url"], row["name"], row["category"]) for row in sources],
            )
            connection.executemany(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    category, first_seen_at, source_published_at, recommended_at, origin)
                   VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                [(row["id"], row["channel_id"], row["title"], row["creator"], row["artwork"],
                  row["thumbnail"], row["duration"], row["category_override"] or row["automatic_category"],
                  row["first_seen_at"], row["source_published_at"], row["recommended_at"], row["origin"])
                 for row in songs],
            )
            connection.execute(
                "INSERT INTO publication(id, revision, source_master_revision, schema_version, generated_at) VALUES(1, ?, ?, ?, ?)",
                (revision, revision, APP_SCHEMA_VERSION, generated_at),
            )
            connection.commit()
        finally:
            connection.close()
        return revision, songs, sources

    def _remove_candidate(self):
        for path in (self.candidate, Path(str(self.candidate) + "-wal"), Path(str(self.candidate) + "-shm")):
            path.unlink(missing_ok=True)
