import time

from catalog_sqlite import validate_video_id
from sqlite_store import SqliteStore


MIGRATIONS = {
    1: (
        """CREATE TABLE ratings (
            video_id TEXT PRIMARY KEY,
            rating INTEGER NOT NULL CHECK(rating IN (-1, 1)),
            updated_at INTEGER NOT NULL
        )""",
        """CREATE TABLE listening (
            video_id TEXT PRIMARY KEY,
            qualified_seconds INTEGER NOT NULL DEFAULT 0 CHECK(qualified_seconds >= 0),
            qualified_sessions INTEGER NOT NULL DEFAULT 0 CHECK(qualified_sessions >= 0),
            last_listened_at INTEGER
        )""",
        """CREATE TABLE observation (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            video_id TEXT,
            media_session TEXT,
            position REAL,
            observed_at INTEGER,
            qualified INTEGER NOT NULL DEFAULT 0 CHECK(qualified IN (0, 1)),
            session_seconds REAL NOT NULL DEFAULT 0
        )""",
        """CREATE TABLE personal_state (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            revision INTEGER NOT NULL CHECK(revision >= 1)
        )""",
        "INSERT INTO personal_state(id, revision) VALUES(1, 1)",
    )
}


class PersonalStore:
    def __init__(self, path):
        self.store = SqliteStore(path)
        self.store.migrate(MIGRATIONS)

    def revision(self):
        with self.store.reading() as connection:
            return connection.execute("SELECT revision FROM personal_state WHERE id=1").fetchone()[0]

    def profile(self):
        with self.store.reading() as connection:
            ratings = {row["video_id"]: row["rating"] for row in connection.execute("SELECT * FROM ratings")}
            listening = {row["video_id"]: {"qualified_seconds": row["qualified_seconds"],
                                           "qualified_sessions": row["qualified_sessions"],
                                           "last_listened_at": row["last_listened_at"]}
                         for row in connection.execute("SELECT * FROM listening")}
        return {"ratings": ratings, "listening": listening, "revision": self.revision()}

    def set_rating(self, video_id, rating, now=None):
        validate_video_id(video_id)
        if rating not in (-1, 0, 1):
            raise ValueError("Rating must be -1, 0, or 1.")
        now = int(time.time() if now is None else now)
        with self.store.writing() as connection:
            if rating == 0:
                connection.execute("DELETE FROM ratings WHERE video_id=?", (video_id,))
            else:
                connection.execute(
                    "INSERT INTO ratings(video_id, rating, updated_at) VALUES(?, ?, ?) ON CONFLICT(video_id) DO UPDATE SET rating=excluded.rating, updated_at=excluded.updated_at",
                    (video_id, rating, now),
                )
            connection.execute("UPDATE personal_state SET revision=revision+1 WHERE id=1")
        return {"rating": rating, "revision": self.revision()}

    def record_listening(self, video_id, seconds, now=None, qualified_session=False):
        validate_video_id(video_id)
        seconds = int(seconds)
        if seconds < 0:
            raise ValueError("Listening seconds cannot be negative.")
        now = int(time.time() if now is None else now)
        with self.store.writing() as connection:
            connection.execute(
                """INSERT INTO listening(video_id, qualified_seconds, qualified_sessions, last_listened_at)
                   VALUES(?, ?, ?, ?)
                   ON CONFLICT(video_id) DO UPDATE SET
                     qualified_seconds=qualified_seconds+excluded.qualified_seconds,
                     qualified_sessions=qualified_sessions+excluded.qualified_sessions,
                     last_listened_at=excluded.last_listened_at""",
                (video_id, seconds, int(qualified_session), now),
            )
            connection.execute("UPDATE personal_state SET revision=revision+1 WHERE id=1")
        return self.profile()["listening"].get(video_id)

    def save_observation(self, video_id, media_session, position, observed_at, qualified=False, session_seconds=0.0):
        with self.store.writing() as connection:
            connection.execute(
                """INSERT INTO observation(id, video_id, media_session, position, observed_at, qualified, session_seconds)
                   VALUES(1, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET video_id=excluded.video_id,
                     media_session=excluded.media_session, position=excluded.position,
                     observed_at=excluded.observed_at, qualified=excluded.qualified,
                     session_seconds=excluded.session_seconds""",
                (video_id, media_session, position, observed_at, int(qualified), session_seconds),
            )

    def last_observation(self):
        with self.store.reading() as connection:
            row = connection.execute("SELECT * FROM observation WHERE id=1").fetchone()
        return dict(row) if row is not None else None

    def reset(self):
        with self.store.writing() as connection:
            connection.execute("DELETE FROM ratings")
            connection.execute("DELETE FROM listening")
            connection.execute("DELETE FROM observation")
            connection.execute("UPDATE personal_state SET revision=revision+1 WHERE id=1")
        return {"revision": self.revision(), "cleared": True}
