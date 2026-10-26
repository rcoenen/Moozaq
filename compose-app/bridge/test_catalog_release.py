import hashlib
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from catalog_publish import CatalogPublisher
from catalog_release import prepare_release, publish_release
from catalog_sqlite import MasterCatalog


class CatalogReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.master = MasterCatalog(self.root / "master.db")
        source_id = "UC" + "a" * 22
        with self.master.store.writing() as connection:
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
            connection.execute("UPDATE master_state SET revision=7, dirty=1 WHERE id=1")
        self.database = CatalogPublisher(self.master, self.root / "app-catalog.db")
        self.database.build(now=123456)

    def test_prepare_release_has_immutable_manifest_and_no_network(self):
        with patch("catalog_release.subprocess.run") as process, patch("requests.get") as get:
            bundle = prepare_release(self.root / "app-catalog.db", min_app_version="0.6.0")
        self.assertEqual(bundle.tag, "catalog-v7")
        self.assertEqual(bundle.data["revision"], 7)
        self.assertEqual(bundle.data["schema_version"], 1)
        self.assertEqual(bundle.data["min_app_version"], "0.6.0")
        self.assertEqual(bundle.data["generated_at"], 123456)
        self.assertEqual(bundle.data["download_url"], "https://github.com/rcoenen/Moozaq-catalog/releases/download/catalog-v7/app-catalog.db")
        content = (self.root / "app-catalog.db").read_bytes()
        self.assertEqual(bundle.data["size"], len(content))
        self.assertEqual(bundle.data["sha256"], hashlib.sha256(content).hexdigest())
        self.assertEqual(json.loads(bundle.manifest.read_text()), bundle.data)
        process.assert_not_called()
        get.assert_not_called()

    def test_revision_must_increase(self):
        prepare_release(self.root / "app-catalog.db")
        with self.assertRaisesRegex(ValueError, "increase"):
            prepare_release(self.root / "app-catalog.db")

    def test_private_or_signed_content_is_rejected(self):
        with closing(sqlite3.connect(self.root / "app-catalog.db")) as connection:
            connection.execute("UPDATE songs SET thumbnail='https://googlevideo.com/play?expire=1&signature=secret' WHERE id='WlPiQGR18jE'")
            connection.commit()
        with self.assertRaisesRegex(ValueError, "artwork"):
            prepare_release(self.root / "app-catalog.db")
        self.assertFalse((self.root / "catalog-manifest.json").exists())

    def test_non_derived_file_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "derived"):
            prepare_release(self.root / "master.db")

    def test_publish_requires_confirmation(self):
        bundle = prepare_release(self.root / "app-catalog.db")
        with patch("catalog_release.subprocess.run") as process:
            with self.assertRaises(PermissionError):
                publish_release(bundle, confirmed=False)
            process.assert_not_called()
        with patch("catalog_release.subprocess.run", return_value=SimpleNamespace(stdout="https://github.com/rcoenen/Moozaq-catalog/releases/tag/catalog-v7")) as process:
            result = publish_release(bundle, confirmed=True)
        self.assertEqual(result["tag"], "catalog-v7")
        self.assertEqual(result["revision"], 7)
        self.assertIn("catalog-manifest.json", result["assets"])
        command = process.call_args.args[0]
        self.assertEqual(command[:3], ["gh", "release", "create"])


if __name__ == "__main__":
    unittest.main()
