import threading
from pathlib import Path

from catalog_publish import validate_app_catalog
from sqlite_store import SqliteStore


def file_fingerprint(path):
    try:
        stat = Path(path).stat()
        return stat.st_dev, stat.st_ino, stat.st_mtime_ns, stat.st_size
    except FileNotFoundError:
        return None


class AppCatalog:
    backend_name = "sqlite"
    supports_curation = False

    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.RLock()
        self.store = SqliteStore(self.path, readonly=True)
        self.publication = validate_app_catalog(self.path)
        self.fingerprint = file_fingerprint(self.path)

    def status(self):
        with self.lock:
            self._refresh_if_changed()
            return {"catalog_revision": self.publication["revision"],
                    "app_revision": self.publication["revision"],
                    "published_revision": self.publication["revision"],
                    "master_revision": self.publication["source_master_revision"],
                    "generated_revision": self.publication["source_master_revision"],
                    "importing": False, "refreshing_channels": [],
                    "catalog_message": f"Catalog revision {self.publication['revision']}"}

    def snapshot(self):
        with self.lock:
            self._refresh_if_changed()
            with self.store.reading() as connection:
                publication = connection.execute("SELECT * FROM publication WHERE id=1").fetchone()
                source_rows = connection.execute(
                    """SELECT sources.*,
                              (SELECT COUNT(*) FROM songs WHERE songs.channel_id=sources.id) AS song_count
                       FROM sources ORDER BY name, id"""
                ).fetchall()
                song_rows = connection.execute("SELECT * FROM songs ORDER BY title, id").fetchall()
                recommended = [row[0] for row in connection.execute(
                    "SELECT id FROM songs WHERE recommended_at IS NOT NULL ORDER BY recommended_at DESC, id LIMIT 12"
                )]
                recent = [row[0] for row in connection.execute(
                    "SELECT id FROM songs WHERE first_seen_at IS NOT NULL ORDER BY first_seen_at DESC, id LIMIT 20"
                )]
            channels = [{
                "id": row["id"], "url": row["url"], "name": row["name"],
                "category": row["category"], "count": row["song_count"],
                "error": None, "refreshed": None, "last_attempt_at": None,
                "last_success_at": None, "aliases": [],
            } for row in source_rows]
            mixes = [{
                "id": row["id"], "title": row["title"], "creator": row["creator"],
                "artwork": row["artwork"], "thumbnail": row["thumbnail"], "duration": row["duration"],
                "category": row["category"], "automatic_category": row["category"],
                "category_override": None, "channel_id": row["channel_id"],
                "first_seen_at": row["first_seen_at"], "last_seen_at": None,
                "source_published_at": row["source_published_at"],
                "availability": "unknown", "missing_reads": 0, "origin": row["origin"],
                "scope": "shared", "hidden": False, "review_status": "approved",
                "recommended_at": row["recommended_at"],
            } for row in song_rows]
            return {"channels": channels, "mixes": mixes,
                    "master_revision": publication["source_master_revision"],
                    "generated_revision": publication["source_master_revision"],
                    "app_revision": publication["revision"],
                    "published_revision": publication["revision"],
                    "generated_at": publication["generated_at"],
                    "dirty": False, "last_generation_error": None,
                    "recommended_ids": recommended, "recently_added_ids": recent}

    def recognize(self, source):
        from listening import video_id_from_source
        video_id = video_id_from_source(source)
        if not video_id:
            return None
        with self.lock:
            self._refresh_if_changed()
            with self.store.reading() as connection:
                row = connection.execute("SELECT 1 FROM songs WHERE id=?", (video_id,)).fetchone()
        return video_id if row is not None else None

    def _refresh_if_changed(self):
        fingerprint = file_fingerprint(self.path)
        if fingerprint == self.fingerprint:
            return
        for _ in range(3):
            publication = validate_app_catalog(self.path)
            confirmed = file_fingerprint(self.path)
            if confirmed == fingerprint:
                self.publication = publication
                self.store = SqliteStore(self.path, readonly=True)
                self.fingerprint = confirmed
                return
            fingerprint = confirmed
        raise RuntimeError("App catalog kept changing while it was being validated.")
