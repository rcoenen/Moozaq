import json
import threading
import time
from pathlib import Path

from app_catalog import AppCatalog
from catalog_publish import CatalogPublisher
from catalog_sqlite import MasterCatalog


class DesktopCatalog:
    backend_name = "sqlite"
    supports_curation = True

    def __init__(self, data_dir, seeds_path, generation_delay=0.05, automatic_refresh=False, automatic_updates=False):
        data_dir = Path(data_dir)
        seeds = json.loads(Path(seeds_path).read_text())
        self.master = MasterCatalog(data_dir / "master.db", seeds)
        if not any(self.master.counts().values()) and (data_dir / "channel-catalog.json").is_file():
            self.master.migrate_json(data_dir / "channel-catalog.json")
        self.app_path = data_dir / "app-catalog.db"
        self.publisher = CatalogPublisher(self.master, self.app_path)
        if self.app_path.exists():
            self.app = AppCatalog(self.app_path)
        else:
            self.publisher.build()
            self.app = AppCatalog(self.app_path)
        self.generation_delay = generation_delay
        self._condition = threading.Condition()
        self._generation_requested = False
        self._generation_active = False
        self._generation_thread = None
        self._closed = False
        from catalog_ingest import CatalogIngest
        from catalog_curation import CatalogCuration
        self.ingest = CatalogIngest(self.master)
        from personal_sqlite import PersonalStore
        from listening import ListeningTracker
        self.personal = PersonalStore(data_dir / "personal.db")
        self.listening = ListeningTracker(self.personal)
        self.curation = CatalogCuration(self.master)
        if self.master.state()["dirty"]:
            self.request_generation()
        if automatic_refresh:
            self.ingest.refresh_due(on_change=self.request_generation)
        from catalog_update import CatalogUpdater
        self.updater = CatalogUpdater(self.app, auto_start=automatic_updates)

    def curate(self, action, **fields):
        result = self.curation.curate(action, **fields)
        self.request_generation()
        return result

    def counts(self):
        return self.master.counts()

    def profile(self):
        return self.personal.profile()

    def set_rating(self, song, rating):
        return self.personal.set_rating(song, rating)

    def reset_personal(self):
        self.listening.reset()
        return self.personal.reset()

    def publish_release(self, confirmed=False, expected_revision=None):
        if confirmed is not True:
            raise PermissionError("Public catalog release requires explicit owner confirmation.")
        if isinstance(expected_revision, bool) or not isinstance(expected_revision, int) or expected_revision < 1:
            raise ValueError("Confirm the exact app catalog revision before publishing.")
        self.rebuild()
        revision = self.app.status()["catalog_revision"]
        if revision != expected_revision:
            raise ValueError(f"Catalog revision is now {revision}; review and confirm it before publishing.")
        import hashlib
        import shutil
        import tempfile
        from catalog_publish import validate_app_catalog
        from catalog_release import ReleaseBundle, prepare_release, publish_release
        bundle = prepare_release(self.app_path, reuse_existing=True)
        if bundle.data["revision"] != expected_revision:
            raise ValueError("Catalog changed before release; review and confirm the new revision.")
        with tempfile.TemporaryDirectory(prefix="catalog-release-", dir=self.app_path.parent) as directory:
            staged_database = Path(directory) / "app-catalog.db"
            staged_manifest = Path(directory) / "catalog-manifest.json"
            shutil.copy2(bundle.database, staged_database)
            shutil.copy2(bundle.manifest, staged_manifest)
            validate_app_catalog(staged_database, expected_revision=expected_revision)
            manifest = json.loads(staged_manifest.read_text())
            digest = hashlib.sha256(staged_database.read_bytes()).hexdigest()
            if (manifest != bundle.data or manifest["size"] != staged_database.stat().st_size
                    or manifest["sha256"] != digest):
                raise RuntimeError("Catalog assets changed before release; review and confirm again.")
            staged = ReleaseBundle(staged_database, staged_manifest, manifest, bundle.tag)
            return publish_release(staged, confirmed=True)

    def check_updates(self):
        return self.updater.check(force=True)

    def observe_playback(self, status, now=None):
        return self.listening.observe({**status, "video_id": self.app.recognize(status.get("source"))}, now=now)

    def preview_video(self, value):
        return self.ingest.preview_video(value)

    def import_video(self, value, recommend=False):
        result = self.ingest.import_video(value, recommend=recommend)
        self.request_generation()
        return result

    def follow_channel(self, value, refresh=False):
        result = self.ingest.follow_channel(value)
        if refresh:
            result["import_count"] = self.ingest.import_channel(value)
        self.request_generation()
        return result

    def import_channel(self, value):
        count = self.ingest.import_channel(value)
        self.request_generation()
        return {"accepted": True, "count": count}

    def refresh(self, value=None, now=None):
        return self.ingest.refresh(value, on_change=self.request_generation, now=now)

    def refresh_due(self, now=None):
        return self.ingest.refresh_due(now=now, on_change=self.request_generation)

    def status(self):
        state = self.master.state()
        with self._condition:
            building = self._generation_active or self._generation_requested
        message = state["last_generation_error"] or (
            "Master changes need app rebuild" if state["dirty"] else None
        )
        return {**self.app.status(), **self.ingest.status(), **self.updater.status(), "master_revision": state["revision"],
                "generated_revision": state["generated_revision"], "personal_revision": self.personal.revision(),
                "catalog_dirty": bool(state["dirty"]), "catalog_building": building,
                "last_generation_error": state["last_generation_error"],
                "catalog_message": message or self.ingest.status()["catalog_message"]}

    def snapshot(self):
        snapshot = self.app.snapshot()
        status = self.status()
        snapshot.update(master_revision=status["master_revision"],
                        generated_revision=status["generated_revision"],
                        dirty=status["catalog_dirty"], catalog_dirty=status["catalog_dirty"],
                        catalog_building=status["catalog_building"],
                        last_generation_error=status["last_generation_error"],
                        channels=self.master.source_status())
        return snapshot

    def request_generation(self):
        with self._condition:
            if self._closed or not self.master.state()["dirty"]:
                return
            self._generation_requested = True
            if self._generation_thread is None:
                self._generation_thread = threading.Thread(target=self._generation_loop, daemon=True)
                self._generation_thread.start()

    def rebuild(self, timeout=10):
        self.request_generation()
        deadline = time.monotonic() + timeout
        with self._condition:
            while True:
                state = self.master.state()
                if not state["dirty"] and not self._generation_active:
                    self.app._refresh_if_changed()
                    return self.app.status()
                if state["last_generation_error"] and not self._generation_active and not self._generation_requested:
                    raise RuntimeError(state["last_generation_error"])
                if time.monotonic() >= deadline:
                    raise TimeoutError("App catalog generation was not confirmed in time.")
                self._condition.wait(0.1)

    def _generation_loop(self):
        try:
            time.sleep(self.generation_delay)
            while True:
                with self._condition:
                    if self._closed or not self._generation_requested:
                        return
                    self._generation_requested = False
                    self._generation_active = True
                try:
                    self.publisher.build()
                except Exception as exc:
                    with self.master.store.writing() as connection:
                        connection.execute(
                            "UPDATE master_state SET dirty=1, last_generation_error=? WHERE id=1",
                            (str(exc),),
                        )
                    with self._condition:
                        self._generation_requested = False
                    return
                with self._condition:
                    self._generation_active = False
                    self._condition.notify_all()
        finally:
            with self._condition:
                self._generation_active = False
                self._generation_thread = None
                self._condition.notify_all()

    def close(self):
        with self._condition:
            self._closed = True
            self._generation_requested = False
            thread = self._generation_thread
            self._condition.notify_all()
        if thread is not None:
            thread.join(timeout=2)
        self.ingest.close()
        self.listening.close()
        self.updater.close()


def create_desktop_catalog(data_dir, seeds_path, automatic_refresh=True, automatic_updates=True):
    return DesktopCatalog(data_dir, seeds_path, automatic_refresh=automatic_refresh, automatic_updates=automatic_updates)
