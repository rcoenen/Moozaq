import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import requests

from app_catalog import AppCatalog
from catalog_publish import APP_SCHEMA_VERSION, CatalogPublisher, previous_app_path
from catalog_sqlite import MasterCatalog
from catalog_update import CatalogUpdater


class FakeResponse:
    def __init__(self, status_code=200, payload=None, content=b"", headers=None):
        self.status_code = status_code
        self.payload = payload
        self.content = content
        self.headers = headers or {}

    def json(self):
        return self.payload

    def iter_content(self, chunk_size):
        yield self.content

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeHttp:
    def __init__(self):
        self.responses = {}
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        response = self.responses[url]
        if isinstance(response, Exception):
            raise response
        return response


class CatalogUpdateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.manifest_url = "https://github.com/rcoenen/Moozaq-catalog/releases/latest/download/catalog-manifest.json"

    def make_app(self, revision):
        master = MasterCatalog(self.root / f"master-{revision}.db")
        source_id = "UC" + "a" * 22
        with master.store.writing() as connection:
            connection.execute(
                "INSERT INTO sources(id, url, name, category, publication_mode, origin) VALUES(?, ?, 'Channel', 'Noir Jazz', 'automatic', 'channel')",
                (source_id, "https://www.youtube.com/channel/" + source_id),
            )
            connection.execute("INSERT INTO source_aliases VALUES(?, ?)", ("https://www.youtube.com/channel/" + source_id, source_id))
            connection.execute(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, availability, origin)
                   VALUES('WlPiQGR18jE', ?, 'VHS Dreams', 'Silent Depths', -1,
                          'https://i.ytimg.com/vi/WlPiQGR18jE/hqdefault.jpg', 10974,
                          'Noir Jazz', 100, 100, 'unknown', 'channel')""",
                (source_id,),
            )
            connection.execute("INSERT INTO curation(song_id) VALUES('WlPiQGR18jE')")
            connection.execute("UPDATE master_state SET revision=?, dirty=1 WHERE id=1", (revision,))
        app_path = self.root / f"app-{revision}.db"
        CatalogPublisher(master, app_path).build(now=100)
        return AppCatalog(app_path)

    def manifest(self, revision, database, **changes):
        content = database.read_bytes()
        data = {"revision": revision, "schema_version": APP_SCHEMA_VERSION,
                "min_app_version": "0.6.0", "generated_at": 100,
                "download_url": f"https://github.com/rcoenen/Moozaq-catalog/releases/download/catalog-v{revision}/app-catalog.db",
                "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
        data.update(changes)
        return data

    def http_for(self, manifest, database):
        http = FakeHttp()
        http.responses[self.manifest_url] = FakeResponse(payload=manifest)
        http.responses[manifest["download_url"]] = FakeResponse(content=database.read_bytes())
        return http

    def test_current_revision_does_not_download(self):
        app = self.make_app(1)
        http = self.http_for(self.manifest(1, app.path), app.path)
        updater = CatalogUpdater(app, manifest_url=self.manifest_url, http=http)
        result = updater.check(now=100, force=True)
        self.assertEqual(result["status"], "current")
        self.assertEqual(len(http.calls), 1)
        self.assertEqual(updater.status()["latest_revision"], 1)
        self.assertEqual(updater.status()["last_update_check"], "1970-01-01T00:01:40Z")
        self.assertIsNone(updater.status()["update_error"])

    def test_new_revision_downloads_and_activates_and_older_is_ignored(self):
        app = self.make_app(1)
        other = self.make_app(2)
        manifest = self.manifest(2, other.path)
        http = self.http_for(manifest, other.path)
        updater = CatalogUpdater(app, manifest_url=self.manifest_url, http=http)
        result = updater.check(force=True)
        self.assertEqual(result["status"], "updated")
        self.assertEqual(result["revision"], 2)
        self.assertEqual(app.status()["catalog_revision"], 2)
        self.assertEqual(updater.highest_revision, 2)
        older = self.manifest(1, other.path)
        http.responses[self.manifest_url] = FakeResponse(payload=older)
        self.assertEqual(updater.check(force=True)["status"], "current")
        self.assertEqual(updater.highest_revision, 2)
        newer = self.make_app(3)
        newer_manifest = self.manifest(3, newer.path)
        http.responses[self.manifest_url] = FakeResponse(payload=newer_manifest)
        http.responses[newer_manifest["download_url"]] = FakeResponse(content=newer.path.read_bytes())
        self.assertEqual(updater.check(force=True)["status"], "updated")
        self.assertEqual(updater.highest_revision, 3)
        previous = previous_app_path(app.path)
        self.assertTrue(previous.exists())

    def test_incompatible_schema_or_app_version_is_not_downloaded(self):
        app = self.make_app(1)
        other = self.make_app(2)
        manifest = self.manifest(2, other.path, schema_version=2)
        http = self.http_for(manifest, other.path)
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "update_required")
        self.assertEqual(len(http.calls), 1)
        manifest = self.manifest(2, other.path, min_app_version="99.0.0")
        http = self.http_for(manifest, other.path)
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "update_required")
        self.assertEqual(len(http.calls), 1)

    def test_hash_and_size_failures_keep_last_good(self):
        app = self.make_app(1)
        other = self.make_app(2)
        before = app.path.read_bytes()
        manifest = self.manifest(2, other.path, sha256="0" * 64)
        http = self.http_for(manifest, other.path)
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "error")
        self.assertEqual(app.path.read_bytes(), before)
        manifest = self.manifest(2, other.path, size=other.path.stat().st_size - 1)
        http = self.http_for(manifest, other.path)
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "error")
        self.assertEqual(app.path.read_bytes(), before)

    def test_malformed_manifest_and_download_and_network_failures(self):
        app = self.make_app(1)
        other = self.make_app(2)
        manifest = self.manifest(2, other.path)
        del manifest["sha256"]
        http = self.http_for(manifest, other.path)
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "error")
        manifest["sha256"] = "not-a-hash"
        http.responses[self.manifest_url] = FakeResponse(payload=manifest)
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "error")
        http.responses[self.manifest_url] = FakeResponse(payload=self.manifest(2, other.path))
        http.responses[self.manifest(2, other.path)["download_url"]] = FakeResponse(content=b"not a database")
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "error")
        http.responses[self.manifest_url] = requests.RequestException("offline")
        result = CatalogUpdater(app, manifest_url=self.manifest_url, http=http).check(force=True)
        self.assertEqual(result["status"], "error")

    def test_latest_release_race_stays_on_original_tag(self):
        app = self.make_app(1)
        second = self.make_app(2)
        third = self.make_app(3)
        first_manifest = self.manifest(2, second.path)
        second_manifest = self.manifest(3, third.path)
        http = FakeHttp()
        http.responses[self.manifest_url] = FakeResponse(payload=first_manifest)
        http.responses[first_manifest["download_url"]] = FakeResponse(content=second.path.read_bytes())
        http.responses[second_manifest["download_url"]] = FakeResponse(content=third.path.read_bytes())
        updater = CatalogUpdater(app, manifest_url=self.manifest_url, http=http)
        self.assertEqual(updater.check(force=True)["status"], "updated")
        http.responses[self.manifest_url] = FakeResponse(payload=second_manifest)
        self.assertEqual(updater.check(force=True)["status"], "updated")
        self.assertEqual(http.calls[1][0], first_manifest["download_url"])
        self.assertEqual(http.calls[3][0], second_manifest["download_url"])

    def test_check_is_hourly_and_explicit_check_can_force(self):
        app = self.make_app(1)
        other = self.make_app(2)
        manifest = self.manifest(2, other.path)
        http = self.http_for(manifest, other.path)
        updater = CatalogUpdater(app, manifest_url=self.manifest_url, http=http, check_interval=3600)
        self.assertEqual(updater.check(now=100)["status"], "updated")
        self.assertEqual(updater.check(now=200)["status"], "not_due")
        self.assertEqual(updater.check(now=3600)["status"], "not_due")
        self.assertEqual(updater.check(now=3700)["status"], "current")
        self.assertEqual(updater.check(now=3601, force=True)["status"], "current")


if __name__ == "__main__":
    unittest.main()
