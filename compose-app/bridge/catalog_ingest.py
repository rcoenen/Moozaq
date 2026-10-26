import json
import re
import subprocess
import sys
import threading
import time
from urllib.parse import urlparse

from catalog import channel_url, classify, entries
from catalog_sqlite import canonical_channel_id, utc_timestamp, validate_video_id


class CatalogIngest:
    def __init__(self, master):
        self.master = master
        self.lock = threading.RLock()
        self.busy = False
        self.refreshing_channels = []
        self.message = "Catalog ready"
        self._refresh_thread = None
        self._closed = False

    def status(self):
        with self.lock:
            return {"importing": self.busy, "refreshing_channels": list(self.refreshing_channels),
                    "catalog_message": self.message}

    def refresh(self, value=None, on_change=None, now=None):
        url = channel_url(value) if value else None
        with self.master.store.reading() as connection:
            targets = [url] if url else [row[0] for row in connection.execute("SELECT url FROM sources ORDER BY name, id")]
        return self._start_refresh(targets, on_change, now, automatic=False)

    def refresh_due(self, now=None, on_change=None):
        now = int(time.time() if now is None else now)
        with self.master.store.reading() as connection:
            targets = [row[0] for row in connection.execute(
                """SELECT url FROM sources
                   WHERE last_attempt_at IS NULL OR last_attempt_at <= ?
                   ORDER BY COALESCE(last_attempt_at, 0), url""",
                (now - 86400,),
            )]
        return self._start_refresh(targets, on_change, now, automatic=True)

    def _start_refresh(self, targets, on_change, now, automatic):
        now = int(time.time() if now is None else now)
        with self.lock:
            if self._closed:
                return {"accepted": False, "reason": "closed"}
            if self.busy:
                if automatic:
                    return {"accepted": False, "reason": "busy"}
                raise ValueError("A catalog import is already running.")
            if not targets:
                return {"accepted": False, "targets": []}
            self.busy = True
            self.refreshing_channels = list(targets)
            self.message = "Importing channel metadata…"
            def run():
                failed = 0
                try:
                    for index, target in enumerate(targets):
                        with self.lock:
                            if self._closed:
                                break
                            self.message = f"Importing channel {index + 1} of {len(targets)}…"
                        try:
                            self.import_channel(target, now=now)
                        except Exception:
                            failed += 1
                        with self.lock:
                            if target in self.refreshing_channels:
                                self.refreshing_channels.remove(target)
                        if on_change:
                            on_change()
                finally:
                    with self.lock:
                        self.message = f'{self.master.counts()["songs"]} videos cached' + (
                            f" · {failed} channel imports need retry" if failed else " · Up to date"
                        )
                        self.refreshing_channels = []
                        self.busy = False
            self._refresh_thread = threading.Thread(target=run, daemon=True)
            self._refresh_thread.start()
            return {"accepted": True, "targets": list(targets)}

    def close(self):
        with self.lock:
            self._closed = True
            thread = self._refresh_thread
        if thread is not None:
            thread.join(timeout=2)

    def preview_video(self, value):
        from video_metadata import fetch_video_metadata
        return fetch_video_metadata(value)

    def import_video(self, value, recommend=False, now=None):
        from video_metadata import fetch_video_metadata
        metadata = fetch_video_metadata(value)
        now = int(time.time() if now is None else now)
        with self.master.store.writing() as connection:
            existing = connection.execute(
                """SELECT songs.*, curation.hidden, curation.category_override,
                          curation.review_status, curation.recommended_at
                   FROM songs JOIN curation ON curation.song_id=songs.id WHERE songs.id=?""",
                (metadata["id"],),
            ).fetchone()
            source = None
            if metadata.get("channel_url"):
                source = connection.execute(
                    "SELECT sources.* FROM sources JOIN source_aliases ON source_aliases.source_id=sources.id WHERE source_aliases.url=?",
                    (metadata["channel_url"],),
                ).fetchone()
            channel_id = source["id"] if source else (existing["channel_id"] if existing else None)
            fallback = source["category"] if source else (existing["automatic_category"] if existing else "Uncategorized")
            automatic_category = classify(metadata["title"], fallback)
            eligible = metadata["duration"] is not None and metadata["duration"] >= 3600
            recommended = bool(recommend and eligible)
            first_seen = existing["first_seen_at"] if existing else now
            recommended_at = existing["recommended_at"] if existing else None
            if recommended and recommended_at is None:
                recommended_at = now
            review_status = "approved" if recommended or not existing else existing["review_status"]
            hidden = 0 if recommended else (existing["hidden"] if existing else 0)
            origin = existing["origin"] if existing else "individual"
            if existing:
                connection.execute(
                    """UPDATE songs SET channel_id=?, title=?, creator=?, thumbnail=?, duration=?,
                       automatic_category=?, last_seen_at=?, source_published_at=COALESCE(?, source_published_at),
                       availability='available', missing_reads=0 WHERE id=?""",
                    (channel_id, metadata["title"], metadata["creator"], metadata["thumbnail"],
                     metadata["duration"], automatic_category, now, metadata["source_published_at"], metadata["id"]),
                )
            else:
                connection.execute(
                    """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                        automatic_category, first_seen_at, last_seen_at, source_published_at,
                                        availability, missing_reads, origin)
                       VALUES(?, ?, ?, ?, -1, ?, ?, ?, ?, ?, ?, 'available', 0, 'individual')""",
                    (metadata["id"], channel_id, metadata["title"], metadata["creator"], metadata["thumbnail"],
                     metadata["duration"], automatic_category, now, now, metadata["source_published_at"]),
                )
            connection.execute(
                """INSERT INTO curation(song_id, hidden, category_override, review_status, recommended_at)
                   VALUES(?, ?, ?, ?, ?)
                   ON CONFLICT(song_id) DO UPDATE SET hidden=excluded.hidden,
                     review_status=excluded.review_status, recommended_at=excluded.recommended_at""",
                (metadata["id"], hidden, existing["category_override"] if existing else None,
                 review_status, recommended_at),
            )
            self._mark_changed(connection, eligible)
        return {**metadata, "eligible": eligible, "recommended": recommended,
                "channel_followed": channel_id is not None}

    def follow_channel(self, value, now=None):
        url = channel_url(value)
        now = int(time.time() if now is None else now)
        with self.master.store.writing() as connection:
            existing = connection.execute(
                "SELECT sources.* FROM sources JOIN source_aliases ON source_aliases.source_id=sources.id WHERE source_aliases.url=?",
                (url,),
            ).fetchone()
            if existing:
                return {"created": False, "id": existing["id"], "url": existing["url"],
                        "name": existing["name"], "publication_mode": existing["publication_mode"]}
            source_id = canonical_channel_id(url) or f"url:{url}"
            name = url.rsplit("/", 1)[-1]
            connection.execute(
                """INSERT INTO sources(id, url, name, category, publication_mode, origin, count)
                   VALUES(?, ?, ?, 'Uncategorized', 'review', 'channel', 0)""",
                (source_id, url, name),
            )
            connection.execute("INSERT INTO source_aliases(url, source_id) VALUES(?, ?)", (url, source_id))
            self._mark_changed(connection, False)
        return {"created": True, "id": source_id, "url": url, "name": name, "publication_mode": "review"}

    def import_channel(self, value, now=None):
        url = channel_url(value)
        now = int(time.time() if now is None else now)
        self.follow_channel(url, now)
        result = subprocess.run(
            [sys.executable, "-m", "yt_dlp", "--flat-playlist", "--skip-download", "--ignore-errors",
             "--dump-single-json", "--socket-timeout", "20", url],
            capture_output=True, text=True, timeout=300,
        )
        if result.returncode or not result.stdout.strip():
            self._source_error(url, now, "YouTube metadata could not be read; previous entries were kept.")
            raise RuntimeError("YouTube metadata could not be read; previous entries were kept.")
        try:
            payload = json.loads(result.stdout)
            channel_id = payload.get("channel_id") or payload.get("id")
            if not re.fullmatch(r"UC[\w-]{22}", channel_id or ""):
                raise ValueError("YouTube did not return a valid channel identity.")
            creator = payload.get("channel") or payload.get("uploader") or payload.get("title") or url
            found = {}
            unavailable = set()
            for item in entries(payload):
                video_id = item.get("id", "")
                if not re.fullmatch(r"[\w-]{11}", video_id):
                    continue
                if item.get("title") in ("[Private video]", "[Deleted video]"):
                    unavailable.add(video_id)
                    continue
                if not item.get("title"):
                    continue
                found[video_id] = {
                    "id": video_id, "title": item["title"], "creator": creator,
                    "thumbnail": self._thumbnail(item, video_id), "duration": item.get("duration"),
                    "source_published_at": utc_timestamp(item.get("timestamp") or item.get("upload_date")),
                }
            if not found:
                raise RuntimeError("No public videos returned; previous entries were kept.")
        except Exception as exc:
            self._source_error(url, now, str(exc))
            raise
        with self.master.store.writing() as connection:
            source = self._source_for_url(connection, url)
            if source["id"] != channel_id:
                other = connection.execute("SELECT id FROM sources WHERE id=?", (channel_id,)).fetchone()
                if other:
                    connection.execute("UPDATE source_aliases SET source_id=? WHERE source_id=?", (channel_id, source["id"]))
                    connection.execute("UPDATE songs SET channel_id=? WHERE channel_id=?", (channel_id, source["id"]))
                    connection.execute("DELETE FROM sources WHERE id=?", (source["id"],))
                    connection.execute(
                        "UPDATE sources SET url=?, name=?, category=?, publication_mode=?, origin=? WHERE id=?",
                        (source["url"], creator, source["category"], source["publication_mode"], source["origin"], channel_id),
                    )
                else:
                    connection.execute("UPDATE sources SET id=? WHERE id=?", (channel_id, source["id"]))
            connection.execute(
                "UPDATE sources SET name=?, count=?, last_attempt_at=?, last_success_at=?, error=NULL WHERE id=?",
                (creator, len(found), now, now, channel_id),
            )
            connection.execute("INSERT OR IGNORE INTO source_aliases(url, source_id) VALUES(?, ?)", (url, channel_id))
            connection.execute("INSERT OR IGNORE INTO source_aliases(url, source_id) VALUES(?, ?)",
                               (f"https://www.youtube.com/channel/{channel_id}", channel_id))
            source = connection.execute("SELECT * FROM sources WHERE id=?", (channel_id,)).fetchone()
            public_changed = False
            for item in found.values():
                public_changed = self._merge_song(connection, source, item, now) or public_changed
            if unavailable:
                placeholders = ",".join("?" for _ in unavailable)
                rows = connection.execute(
                    f"SELECT id, duration, availability FROM songs WHERE channel_id=? AND id IN ({placeholders})",
                    (channel_id, *unavailable),
                ).fetchall()
                for row in rows:
                    curation = connection.execute("SELECT hidden, review_status FROM curation WHERE song_id=?", (row["id"],)).fetchone()
                    if row["duration"] is not None and row["duration"] >= 3600 and row["availability"] != "unavailable" \
                            and curation["hidden"] == 0 and curation["review_status"] == "approved":
                        public_changed = True
                connection.execute(
                    f"UPDATE songs SET availability='unavailable' WHERE channel_id=? AND id IN ({placeholders})",
                    (channel_id, *unavailable),
                )
            placeholders = ",".join("?" for _ in found)
            connection.execute(
                f"""UPDATE songs SET missing_reads=missing_reads+1, availability='missing'
                    WHERE channel_id=? AND id NOT IN ({placeholders}) AND availability != 'unavailable'""",
                (channel_id, *found),
            )
            self._mark_changed(connection, public_changed)
        return len(found)

    def _merge_song(self, connection, source, item, now):
        existing = connection.execute(
            """SELECT songs.*, curation.hidden, curation.review_status, curation.recommended_at
               FROM songs JOIN curation ON curation.song_id=songs.id WHERE songs.id=?""",
            (item["id"],),
        ).fetchone()
        automatic_category = classify(item["title"], source["category"])
        review_status = existing["review_status"] if existing else (
            "approved" if source["publication_mode"] == "automatic" else "pending"
        )
        duration = item["duration"]
        was_public = bool(existing and existing["duration"] is not None and existing["duration"] >= 3600
                          and existing["availability"] != "unavailable" and existing["hidden"] == 0
                          and existing["review_status"] == "approved")
        is_public = bool(duration is not None and duration >= 3600 and review_status == "approved"
                         and not (existing and existing["hidden"]))
        changed = not existing or any((
            existing["title"] != item["title"], existing["creator"] != item["creator"],
            existing["thumbnail"] != item["thumbnail"], existing["duration"] != duration,
            existing["automatic_category"] != automatic_category, existing["channel_id"] != source["id"],
            existing["source_published_at"] != item["source_published_at"] and item["source_published_at"] is not None,
            existing["availability"] != "available",
        ))
        if existing:
            connection.execute(
                """UPDATE songs SET channel_id=?, title=?, creator=?, thumbnail=?, duration=?,
                   automatic_category=?, last_seen_at=?, source_published_at=COALESCE(?, source_published_at),
                   availability='available', missing_reads=0 WHERE id=?""",
                (source["id"], item["title"], item["creator"], item["thumbnail"], duration,
                 automatic_category, now, item["source_published_at"], item["id"]),
            )
        else:
            connection.execute(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, source_published_at,
                                    availability, missing_reads, origin)
                   VALUES(?, ?, ?, ?, -1, ?, ?, ?, ?, ?, ?, 'available', 0, 'channel')""",
                (item["id"], source["id"], item["title"], item["creator"], item["thumbnail"], duration,
                 automatic_category, now, now, item["source_published_at"]),
            )
        connection.execute(
            "INSERT OR IGNORE INTO curation(song_id, review_status) VALUES(?, ?)",
            (item["id"], review_status),
        )
        return changed and (was_public or is_public)

    def _source_for_url(self, connection, url):
        return connection.execute(
            "SELECT sources.* FROM sources JOIN source_aliases ON source_aliases.source_id=sources.id WHERE source_aliases.url=?",
            (url,),
        ).fetchone()

    def _source_error(self, url, now, message):
        with self.master.store.writing() as connection:
            connection.execute(
                "UPDATE sources SET last_attempt_at=?, error=? WHERE id=(SELECT source_id FROM source_aliases WHERE url=?)",
                (now, message, url),
            )
            self._mark_changed(connection, False)

    @staticmethod
    def _thumbnail(item, video_id):
        thumbnails = [entry for entry in item.get("thumbnails", [])
                      if isinstance(entry, dict) and urlparse(entry.get("url", "")).hostname in ("i.ytimg.com", "img.youtube.com")]
        if thumbnails:
            return max(thumbnails, key=lambda entry: entry.get("width") or 0).get("url")
        return f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"

    @staticmethod
    def _mark_changed(connection, public_changed):
        connection.execute(
            "UPDATE master_state SET revision=revision+1, dirty=CASE WHEN ?=1 THEN 1 ELSE dirty END WHERE id=1",
            (int(public_changed),),
        )
