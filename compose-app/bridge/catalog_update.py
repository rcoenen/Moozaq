import hashlib
import re
import threading
import time
from pathlib import Path

import requests

from catalog_publish import APP_SCHEMA_VERSION, activate_app_catalog
from catalog_sqlite import iso_timestamp, utc_timestamp


DEFAULT_MANIFEST_URL = "https://github.com/rcoenen/Moozaq-catalog/releases/latest/download/catalog-manifest.json"
MAX_MANIFEST_BYTES = 64 * 1024
MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024


def version_tuple(value):
    match = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", value or "")
    if not match:
        raise ValueError("Invalid application version.")
    return tuple(int(part) for part in match.groups())


class CatalogUpdater:
    def __init__(self, app, manifest_url=DEFAULT_MANIFEST_URL, current_version="0.6.0",
                 check_interval=3600, http=requests, auto_start=False):
        self.app = app
        self.manifest_url = manifest_url
        self.current_version = current_version
        self.check_interval = check_interval
        self.http = http
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.thread = None
        self.last_check_at = None
        self.next_check_at = 0
        self.last_manifest = None
        self.last_error = None
        self.last_release_url = None
        self.highest_revision = app.status()["catalog_revision"]
        if auto_start:
            self.start()

    def status(self):
        with self.lock:
            active = self.app.status()["catalog_revision"]
            observed = self.last_manifest["revision"] if self.last_manifest else active
            return {"active_revision": active,
                    "latest_revision": max(active, self.highest_revision, observed),
                    "last_update_check": iso_timestamp(self.last_check_at),
                    "update_error": self.last_error,
                    "last_release_url": self.last_release_url}

    def start(self):
        if self.thread is not None:
            return
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _run(self):
        while not self.stop_event.is_set():
            try:
                self.check()
            except Exception as exc:
                self.last_error = str(exc)
            self.stop_event.wait(self.check_interval)

    def close(self):
        self.stop_event.set()
        if self.thread is not None:
            self.thread.join(timeout=2)
            self.thread = None

    def check(self, now=None, force=False):
        now = int(time.time() if now is None else now)
        with self.lock:
            if not force and now < self.next_check_at:
                return {"status": "not_due", "revision": self.highest_revision}
            self.last_check_at = now
            self.next_check_at = now + self.check_interval
            try:
                response = self.http.get(self.manifest_url, timeout=10)
                if response.status_code == 304:
                    self.last_error = None
                    return {"status": "current", "revision": self.highest_revision}
                if response.status_code != 200:
                    raise RuntimeError("Catalog manifest could not be read.")
                if len(response.content) > MAX_MANIFEST_BYTES:
                    raise ValueError("Catalog manifest exceeds the size limit.")
                manifest = response.json()
                self._validate_manifest(manifest)
                self.last_manifest = manifest
                revision = manifest["revision"]
                if revision <= self.highest_revision:
                    self.last_error = None
                    return {"status": "current", "revision": self.highest_revision}
                if manifest["schema_version"] != APP_SCHEMA_VERSION or version_tuple(manifest["min_app_version"]) > version_tuple(self.current_version):
                    self.last_error = None
                    return {"status": "update_required", "revision": self.highest_revision,
                            "required_version": manifest["min_app_version"]}
                candidate = self._download(manifest)
                activate_app_catalog(candidate, self.app.path, expected_revision=revision)
                self.highest_revision = revision
                self.app._refresh_if_changed()
                self.last_release_url = manifest["download_url"]
                self.last_error = None
                return {"status": "updated", "revision": revision}
            except Exception as exc:
                self.last_error = str(exc)
                return {"status": "error", "error": self.last_error, "revision": self.highest_revision}

    def _validate_manifest(self, manifest):
        required = {"revision", "schema_version", "min_app_version", "generated_at", "download_url", "size", "sha256"}
        if not isinstance(manifest, dict) or not required.issubset(manifest):
            raise ValueError("Invalid catalog manifest.")
        revision = manifest["revision"]
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 1:
            raise ValueError("Invalid catalog revision.")
        if not isinstance(manifest["schema_version"], int) or manifest["schema_version"] < 1:
            raise ValueError("Invalid catalog schema version.")
        version_tuple(manifest["min_app_version"])
        utc_timestamp(manifest["generated_at"])
        expected_url = f"https://github.com/.+/releases/download/catalog-v{revision}/app-catalog.db"
        if not re.fullmatch(expected_url, manifest["download_url"]):
            raise ValueError("Catalog download is not bound to an immutable release tag.")
        size = manifest["size"]
        if isinstance(size, bool) or not isinstance(size, int) or size < 1 or size > MAX_DOWNLOAD_BYTES:
            raise ValueError("Invalid catalog size.")
        if not re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"]):
            raise ValueError("Invalid catalog SHA-256.")

    def _download(self, manifest):
        candidate = self.app.path.with_name(f"{self.app.path.stem}.download{self.app.path.suffix}")
        expected_size = manifest["size"]
        digest = hashlib.sha256()
        size = 0
        try:
            with self.http.get(manifest["download_url"], stream=True, timeout=(10, 60)) as response:
                if response.status_code != 200:
                    raise RuntimeError("Catalog database could not be downloaded.")
                with candidate.open("wb") as output:
                    for chunk in response.iter_content(65536):
                        size += len(chunk)
                        if size > expected_size:
                            raise ValueError("Catalog download exceeds the declared size.")
                        digest.update(chunk)
                        output.write(chunk)
            if size != expected_size:
                raise ValueError("Catalog download size does not match the manifest.")
            if digest.hexdigest() != manifest["sha256"]:
                raise ValueError("Catalog download hash does not match the manifest.")
            return candidate
        except Exception:
            candidate.unlink(missing_ok=True)
            raise
