import time

from catalog_ingest import CatalogIngest


class CatalogCuration:
    def __init__(self, master):
        self.master = master

    def curate(self, action, **fields):
        now = int(fields.pop("now", None) or time.time())
        if action == "recommend":
            return self.recommend(fields["song"], now)
        if action == "unrecommend":
            return self.unrecommend(fields["song"])
        if action in ("hide", "restore"):
            return self.set_hidden(fields["song"], action == "hide")
        if action == "category":
            return self.set_category(fields["song"], fields.get("category"))
        if action == "review":
            return self.set_review(fields["song"], fields["status"])
        if action == "source_mode":
            return self.set_source_mode(fields["url"], fields["mode"])
        raise ValueError(f"Unknown curation action: {action}")

    def recommend(self, song_id, now):
        with self.master.store.writing() as connection:
            song = self._song(connection, song_id)
            if song["duration"] is None or song["duration"] < 3600:
                raise ValueError("Only songs with a confirmed duration of at least one hour can be recommended.")
            if song["availability"] == "unavailable":
                raise ValueError("An unavailable song cannot be recommended.")
            already = song["recommended_at"] is not None and song["hidden"] == 0 and song["review_status"] == "approved"
            if already:
                return {"changed": False, "recommended": True, "recommended_at": song["recommended_at"]}
            recommended_at = song["recommended_at"] or now
            connection.execute(
                "UPDATE curation SET hidden=0, review_status='approved', recommended_at=? WHERE song_id=?",
                (recommended_at, song_id),
            )
            self._changed(connection, True)
        return {"changed": True, "recommended": True, "recommended_at": recommended_at}

    def unrecommend(self, song_id):
        with self.master.store.writing() as connection:
            song = self._song(connection, song_id)
            if song["recommended_at"] is None:
                return {"changed": False, "recommended": False}
            public = self._public(song)
            connection.execute("UPDATE curation SET recommended_at=NULL WHERE song_id=?", (song_id,))
            self._changed(connection, public)
        return {"changed": True, "recommended": False}

    def set_hidden(self, song_id, hidden):
        with self.master.store.writing() as connection:
            song = self._song(connection, song_id)
            hidden = int(hidden)
            if song["hidden"] == hidden:
                return {"changed": False, "hidden": bool(hidden)}
            before = self._public(song)
            connection.execute("UPDATE curation SET hidden=? WHERE song_id=?", (hidden, song_id))
            after = self._public(song, hidden=hidden)
            self._changed(connection, before != after)
        return {"changed": True, "hidden": bool(hidden)}

    def set_category(self, song_id, category):
        category = category.strip() if isinstance(category, str) else None
        category = category or None
        with self.master.store.writing() as connection:
            song = self._song(connection, song_id)
            if song["category_override"] == category:
                return {"changed": False, "category": category}
            connection.execute("UPDATE curation SET category_override=? WHERE song_id=?", (category, song_id))
            self._changed(connection, self._public(song))
        return {"changed": True, "category": category}

    def set_review(self, song_id, status):
        if status not in ("pending", "approved"):
            raise ValueError("Review status must be pending or approved.")
        with self.master.store.writing() as connection:
            song = self._song(connection, song_id)
            if song["review_status"] == status:
                return {"changed": False, "review_status": status}
            before = self._public(song)
            connection.execute("UPDATE curation SET review_status=? WHERE song_id=?", (status, song_id))
            after = self._public(song, review_status=status)
            self._changed(connection, before != after)
        return {"changed": True, "review_status": status}

    def set_source_mode(self, value, mode):
        if mode not in ("automatic", "review"):
            raise ValueError("Source mode must be automatic or review.")
        with self.master.store.writing() as connection:
            source = connection.execute(
                "SELECT sources.* FROM sources JOIN source_aliases ON source_aliases.source_id=sources.id WHERE source_aliases.url=?",
                (value,),
            ).fetchone()
            if source is None:
                raise ValueError("Follow the channel before changing its publication mode.")
            if source["publication_mode"] == mode:
                return {"changed": False, "publication_mode": mode}
            connection.execute("UPDATE sources SET publication_mode=? WHERE id=?", (mode, source["id"]))
            self._changed(connection, False)
        return {"changed": True, "publication_mode": mode}

    @staticmethod
    def _song(connection, song_id):
        song = connection.execute(
            """SELECT songs.*, curation.hidden, curation.category_override,
                      curation.review_status, curation.recommended_at
               FROM songs JOIN curation ON curation.song_id=songs.id WHERE songs.id=?""",
            (song_id,),
        ).fetchone()
        if song is None:
            raise ValueError("The song is not present in the master catalog.")
        return song

    @staticmethod
    def _public(song, hidden=None, review_status=None):
        hidden = song["hidden"] if hidden is None else hidden
        review_status = song["review_status"] if review_status is None else review_status
        return bool(song["duration"] is not None and song["duration"] >= 3600
                    and song["availability"] != "unavailable" and hidden == 0 and review_status == "approved")

    @staticmethod
    def _changed(connection, public_changed):
        CatalogIngest._mark_changed(connection, public_changed)
