import json
import re
import shutil
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

from catalog import channel_url
from sqlite_store import SqliteStore


SCHEMA_VERSION = 1
STARTERS = (
    {"id": "7vy0H3ERRiA", "title": "No Rush. Just Coffee. – 3 Hours of Dark Vintage Noir Jazz for Slow Mornings & Quiet Starts", "creator": "The Worn Record", "artwork": 0},
    {"id": "MpoIuYCfEvQ", "title": "''The Day Begins in Lagos'' | Afro House Jazz Mix | Morning Grooves for Focus & Work", "creator": "Rhythm & Form", "artwork": 1},
    {"id": "yYkRaDUMavk", "title": "Old Guitars, Raw Blues & Mississippi Delta Nights", "creator": "Coyote Delta Blues", "artwork": 2},
    {"id": "zdIaNCDA4ZY", "title": "Focus Music Deep Work 2026 | Study Focus Background | Hyperfocus Concentration Ambient Beats", "creator": "Deep Productivity", "artwork": 3},
    {"id": "jsLX0Kp1Zpg", "title": "low cortisol jazz for overthinkers.", "creator": "SILEO", "artwork": 4},
    {"id": "eJA9E7RXf5U", "title": "you’re in a cozy 1940's jazz lounge on a rainy night | vintage oldies ambience", "creator": "Retro Rainy Melodies", "artwork": 5},
)

MIGRATIONS = {
    1: (
        """CREATE TABLE sources (
            id TEXT PRIMARY KEY,
            url TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL,
            category TEXT NOT NULL,
            publication_mode TEXT NOT NULL CHECK(publication_mode IN ('automatic', 'review')),
            origin TEXT NOT NULL CHECK(origin IN ('seed', 'channel', 'individual')),
            count INTEGER NOT NULL DEFAULT 0 CHECK(count >= 0),
            last_attempt_at INTEGER,
            last_success_at INTEGER,
            error TEXT
        )""",
        """CREATE TABLE source_aliases (
            url TEXT PRIMARY KEY,
            source_id TEXT NOT NULL REFERENCES sources(id) ON UPDATE CASCADE ON DELETE CASCADE
        )""",
        """CREATE TABLE songs (
            id TEXT PRIMARY KEY,
            channel_id TEXT REFERENCES sources(id) ON UPDATE CASCADE ON DELETE SET NULL,
            title TEXT NOT NULL,
            creator TEXT NOT NULL,
            artwork INTEGER NOT NULL DEFAULT -1,
            thumbnail TEXT,
            duration REAL CHECK(duration IS NULL OR duration >= 0),
            automatic_category TEXT NOT NULL,
            first_seen_at INTEGER,
            last_seen_at INTEGER,
            source_published_at INTEGER,
            availability TEXT NOT NULL DEFAULT 'unknown' CHECK(availability IN ('unknown', 'available', 'missing', 'unavailable')),
            missing_reads INTEGER NOT NULL DEFAULT 0 CHECK(missing_reads >= 0),
            origin TEXT NOT NULL CHECK(origin IN ('seed', 'channel', 'individual'))
        )""",
        """CREATE TABLE curation (
            song_id TEXT PRIMARY KEY REFERENCES songs(id) ON DELETE CASCADE,
            hidden INTEGER NOT NULL DEFAULT 0 CHECK(hidden IN (0, 1)),
            category_override TEXT,
            review_status TEXT NOT NULL DEFAULT 'approved' CHECK(review_status IN ('pending', 'approved')),
            recommended_at INTEGER
        )""",
        """CREATE TABLE master_state (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            revision INTEGER NOT NULL CHECK(revision >= 1),
            dirty INTEGER NOT NULL DEFAULT 0 CHECK(dirty IN (0, 1)),
            generated_revision INTEGER,
            last_generation_at INTEGER,
            last_generation_error TEXT
        )""",
        "INSERT INTO master_state(id, revision, dirty) VALUES(1, 1, 0)",
        "CREATE INDEX idx_sources_last_attempt ON sources(last_attempt_at)",
        "CREATE INDEX idx_songs_channel ON songs(channel_id)",
        "CREATE INDEX idx_songs_category ON songs(automatic_category)",
        "CREATE INDEX idx_songs_first_seen ON songs(first_seen_at DESC)",
        "CREATE INDEX idx_songs_duration ON songs(duration)",
        "CREATE INDEX idx_songs_availability ON songs(availability)",
        "CREATE INDEX idx_curation_recommended ON curation(recommended_at DESC)",
        "CREATE INDEX idx_curation_publication ON curation(review_status, hidden)",
        "CREATE INDEX idx_curation_category ON curation(category_override)",
    )
}


def utc_timestamp(value):
    if value is None:
        return None
    if isinstance(value, bool):
        raise ValueError("Invalid timestamp.")
    if isinstance(value, (int, float)):
        return int(value)
    text = str(value).strip()
    if not text:
        return None
    if re.fullmatch(r"\d{8}", text):
        parsed = datetime.strptime(text, "%Y%m%d").replace(tzinfo=timezone.utc)
        return int(parsed.timestamp())
    if re.fullmatch(r"\d+(\.\d+)?", text):
        return int(float(text))
    parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return int(parsed.astimezone(timezone.utc).timestamp())


def iso_timestamp(value):
    if value is None:
        return None
    return datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_channel_id(url):
    match = re.fullmatch(r"/channel/(UC[\w-]{22})", urlparse(url).path)
    return match.group(1) if match else None


def validate_video_id(value):
    if not re.fullmatch(r"[\w-]{11}", value or ""):
        raise ValueError(f"Invalid YouTube video ID: {value!r}")
    return value


class MasterCatalog:
    backend_name = "sqlite"
    supports_curation = True

    def __init__(self, path, seeds=()):
        self.store = SqliteStore(path)
        self.seeds = [dict(seed) for seed in seeds]
        self.store.migrate(MIGRATIONS)

    def status(self):
        state = self.state()
        return {"catalog_revision": state["revision"], "importing": False,
                "refreshing_channels": [],
                "catalog_message": "Master changes need app rebuild" if state["dirty"] else "Catalog ready"}

    def counts(self):
        with self.store.reading() as connection:
            return {name: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                    for name, table in (("sources", "sources"), ("aliases", "source_aliases"),
                                        ("songs", "songs"), ("curation", "curation"))}

    def state(self):
        with self.store.reading() as connection:
            return dict(connection.execute("SELECT * FROM master_state WHERE id=1").fetchone())

    def source_status(self):
        with self.store.reading() as connection:
            rows = connection.execute("SELECT * FROM sources ORDER BY name, id").fetchall()
            aliases = {}
            for row in connection.execute("SELECT * FROM source_aliases ORDER BY url"):
                aliases.setdefault(row["source_id"], []).append(row["url"])
        return [{
            "id": row["id"] if re.fullmatch(r"UC[\w-]{22}", row["id"]) else None,
            "url": row["url"], "name": row["name"], "category": row["category"],
            "publication_mode": row["publication_mode"], "origin": row["origin"],
            "count": row["count"], "error": row["error"],
            "refreshed": iso_timestamp(row["last_success_at"]),
            "last_attempt": iso_timestamp(row["last_attempt_at"]),
            "last_success": iso_timestamp(row["last_success_at"]),
            "last_attempt_at": row["last_attempt_at"], "last_success_at": row["last_success_at"],
            "aliases": aliases.get(row["id"], []),
        } for row in rows]

    def snapshot(self):
        with self.store.reading() as connection:
            source_rows = connection.execute("SELECT * FROM sources ORDER BY name, id").fetchall()
            aliases = {}
            for row in connection.execute("SELECT * FROM source_aliases ORDER BY url"):
                aliases.setdefault(row["source_id"], []).append(row["url"])
            song_rows = connection.execute(
                """SELECT songs.*, curation.hidden, curation.category_override,
                          curation.review_status, curation.recommended_at
                   FROM songs JOIN curation ON curation.song_id=songs.id
                   ORDER BY songs.title, songs.id"""
            ).fetchall()
            state = connection.execute("SELECT * FROM master_state WHERE id=1").fetchone()
        channels = []
        for row in source_rows:
            channels.append({
                "id": row["id"] if canonical_channel_id(row["url"]) == row["id"] or re.fullmatch(r"UC[\w-]{22}", row["id"]) else None,
                "url": row["url"], "name": row["name"], "category": row["category"],
                "publication_mode": row["publication_mode"], "origin": row["origin"],
                "count": row["count"], "error": row["error"],
                "refreshed": iso_timestamp(row["last_success_at"]),
                "last_attempt_at": row["last_attempt_at"], "last_success_at": row["last_success_at"],
                "aliases": aliases.get(row["id"], []),
            })
        mixes = []
        for row in song_rows:
            mixes.append({
                "id": row["id"], "title": row["title"], "creator": row["creator"],
                "artwork": row["artwork"], "thumbnail": row["thumbnail"], "duration": row["duration"],
                "category": row["category_override"] or row["automatic_category"],
                "automatic_category": row["automatic_category"], "category_override": row["category_override"],
                "channel_id": row["channel_id"], "first_seen_at": row["first_seen_at"],
                "last_seen_at": row["last_seen_at"], "source_published_at": row["source_published_at"],
                "availability": row["availability"], "missing_reads": row["missing_reads"],
                "origin": row["origin"], "scope": "shared", "hidden": bool(row["hidden"]),
                "review_status": row["review_status"], "recommended_at": row["recommended_at"],
            })
        return {"channels": channels, "mixes": mixes,
                "master_revision": state["revision"], "generated_revision": state["generated_revision"],
                "dirty": bool(state["dirty"]), "last_generation_error": state["last_generation_error"]}

    def migrate_json(self, json_path, backup_path=None):
        json_path = Path(json_path)
        if any(self.counts().values()):
            raise RuntimeError("The master catalog is not empty; JSON migration is one-time only.")
        raw_text = json_path.read_text()
        legacy = json.loads(raw_text)
        if not isinstance(legacy, dict) or not isinstance(legacy.get("channels"), list) or not isinstance(legacy.get("mixes"), list):
            raise ValueError("Legacy catalog must contain channel and mix lists.")

        seed_urls = {channel_url(seed["url"]) for seed in self.seeds}
        records = list(legacy["channels"]) + list(self.seeds)
        source_rows = {}
        alias_rows = {}
        for record in records:
            url = channel_url(record["url"])
            source_id = alias_rows.get(url) or record.get("id") or canonical_channel_id(url) or f"url:{url}"
            if not re.fullmatch(r"(UC[\w-]{22}|url:https://www\.youtube\.com/.+)", source_id):
                raise ValueError(f"Invalid source identity: {source_id!r}")
            last_attempt = utc_timestamp(record.get("last_attempt_at") or record.get("refreshed"))
            last_success = utc_timestamp(record.get("last_success_at") or record.get("refreshed"))
            row = {
                "id": source_id, "url": url, "name": record.get("name") or url,
                "category": record.get("category") or "Uncategorized",
                "publication_mode": record.get("publication_mode") or "automatic",
                "origin": "seed" if url in seed_urls else "channel",
                "count": int(record.get("count") or 0),
                "last_attempt_at": last_attempt, "last_success_at": last_success,
                "error": record.get("error"),
            }
            if source_id in source_rows:
                previous = source_rows[source_id]
                row["count"] = max(previous["count"], row["count"])
                row["last_attempt_at"] = max(filter(None, [previous["last_attempt_at"], row["last_attempt_at"]]), default=None)
                row["last_success_at"] = max(filter(None, [previous["last_success_at"], row["last_success_at"]]), default=None)
                row["error"] = row["error"] or previous["error"]
                row["origin"] = "seed" if "seed" in (previous["origin"], row["origin"]) else "channel"
                if url in seed_urls:
                    row["category"] = next(seed.get("category") or "Uncategorized" for seed in self.seeds if channel_url(seed["url"]) == url)
            source_rows[source_id] = row
            alias_rows[url] = source_id

        song_rows = {}
        for item in legacy["mixes"]:
            song = self._song_record(item, source_rows, alias_rows, "channel")
            song_rows[song["id"]] = song
        for starter in STARTERS:
            if starter["id"] not in song_rows:
                item = dict(starter, category="Starter mixes", duration=None,
                            thumbnail=f"https://i.ytimg.com/vi/{starter['id']}/hqdefault.jpg")
                song_rows[starter["id"]] = self._song_record(item, source_rows, alias_rows, "seed")

        backup = Path(backup_path) if backup_path is not None else json_path.with_suffix(".backup.json")
        if backup.exists() and backup.read_bytes() != json_path.read_bytes():
            raise FileExistsError(f"Refusing to overwrite a different migration backup: {backup}")
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(json_path, backup)

        with self.store.writing() as connection:
            connection.executemany(
                """INSERT INTO sources(id, url, name, category, publication_mode, origin, count,
                                      last_attempt_at, last_success_at, error)
                   VALUES(:id, :url, :name, :category, :publication_mode, :origin, :count,
                          :last_attempt_at, :last_success_at, :error)""",
                list(source_rows.values()),
            )
            connection.executemany("INSERT INTO source_aliases(url, source_id) VALUES(?, ?)",
                                   [(url, source_id) for url, source_id in sorted(alias_rows.items())])
            connection.executemany(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, source_published_at,
                                    availability, missing_reads, origin)
                   VALUES(:id, :channel_id, :title, :creator, :artwork, :thumbnail, :duration,
                          :automatic_category, :first_seen_at, :last_seen_at, :source_published_at,
                          :availability, :missing_reads, :origin)""",
                list(song_rows.values()),
            )
            connection.executemany(
                """INSERT INTO curation(song_id, hidden, category_override, review_status, recommended_at)
                   VALUES(:id, :hidden, :category_override, :review_status, :recommended_at)""",
                list(song_rows.values()),
            )
            connection.execute("UPDATE master_state SET revision=1, dirty=1, generated_revision=NULL, last_generation_error=NULL WHERE id=1")
            migrated = connection.execute("SELECT (SELECT COUNT(*) FROM sources), (SELECT COUNT(*) FROM source_aliases), (SELECT COUNT(*) FROM songs)").fetchone()
            expected = (len(source_rows), len(alias_rows), len(song_rows))
            if tuple(migrated) != expected:
                raise RuntimeError(f"Migration count mismatch: expected {expected}, got {tuple(migrated)}")
        return {"sources": len(source_rows), "aliases": len(alias_rows), "songs": len(song_rows),
                "backup": str(backup)}

    def _song_record(self, item, source_rows, alias_rows, origin):
        video_id = validate_video_id(item.get("id"))
        title = item.get("title")
        if not isinstance(title, str) or not title.strip():
            raise ValueError(f"Song {video_id} has no title.")
        duration = item.get("duration")
        if duration is not None and (isinstance(duration, bool) or not isinstance(duration, (int, float)) or duration < 0):
            raise ValueError(f"Song {video_id} has an invalid duration.")
        channel_id = item.get("channel_id")
        if channel_id not in source_rows:
            channel_id = alias_rows.get(channel_id)
        availability = item.get("availability") or "unknown"
        if availability not in ("unknown", "available", "missing", "unavailable"):
            raise ValueError(f"Song {video_id} has invalid availability.")
        review_status = item.get("review_status") or "approved"
        if review_status not in ("pending", "approved"):
            raise ValueError(f"Song {video_id} has invalid review status.")
        return {
            "id": video_id, "channel_id": channel_id, "title": title,
            "creator": item.get("creator") or "", "artwork": int(item.get("artwork", -1)),
            "thumbnail": item.get("thumbnail"), "duration": duration,
            "automatic_category": item.get("automatic_category") or item.get("category") or "Uncategorized",
            "first_seen_at": utc_timestamp(item.get("first_seen_at")),
            "last_seen_at": utc_timestamp(item.get("last_seen_at")),
            "source_published_at": utc_timestamp(item.get("source_published_at") or item.get("upload_date")),
            "availability": availability, "missing_reads": int(item.get("missing_reads") or 0),
            "origin": origin, "hidden": int(bool(item.get("hidden", False))),
            "category_override": item.get("category_override"), "review_status": review_status,
            "recommended_at": utc_timestamp(item.get("recommended_at")),
        }
