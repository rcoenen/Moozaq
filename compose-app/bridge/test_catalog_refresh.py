import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from catalog_ingest import CatalogIngest
from catalog_sqlite import MasterCatalog
from desktop_catalog import DesktopCatalog
from server import Bridge


class CatalogRefreshTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.seeds_path = self.root / "channels.json"
        self.seeds_path.write_text("[]")
        (self.root / "channel-catalog.json").write_text(json.dumps({"channels": [], "mixes": []}))
        self.channel_id = "UC" + "a" * 22
        self.channel_url = "https://www.youtube.com/channel/" + self.channel_id

    def make_catalog(self, automatic=False):
        catalog = DesktopCatalog(self.root, self.seeds_path, generation_delay=0, automatic_refresh=automatic)
        self.addCleanup(catalog.close)
        return catalog

    def follow(self, catalog, url):
        catalog.ingest.follow_channel(url)
        catalog.curation.set_source_mode(url, "automatic")

    def payload(self, ids=("abcdefghijk",)):
        return {"channel_id": self.channel_id, "channel": "Channel",
                "entries": [{"id": video_id, "title": video_id, "duration": 7200} for video_id in ids]}

    def result(self, payload, returncode=0):
        return SimpleNamespace(returncode=returncode, stdout=json.dumps(payload) if returncode == 0 else "")

    def wait_idle(self, catalog):
        deadline = time.monotonic() + 3
        while catalog.ingest.busy and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertFalse(catalog.ingest.busy)

    def source(self, catalog):
        return next(channel for channel in catalog.snapshot()["channels"] if channel["url"] == self.channel_url)

    def test_due_refresh_is_bounded_but_manual_refresh_is_allowed(self):
        catalog = self.make_catalog()
        self.follow(catalog, self.channel_url)
        with patch("catalog_ingest.subprocess.run", return_value=self.result(self.payload())) as run:
            first = catalog.refresh_due(now=1000)
            self.wait_idle(catalog)
            self.assertTrue(first["accepted"])
            self.assertEqual(run.call_count, 1)
            self.assertEqual(self.source(catalog)["last_attempt_at"], 1000)
            self.assertEqual(self.source(catalog)["last_success_at"], 1000)
            second = catalog.refresh_due(now=1001)
            self.assertFalse(second["accepted"])
            catalog.refresh(self.channel_url, now=1002)
            self.wait_idle(catalog)
            self.assertEqual(run.call_count, 2)
            self.assertEqual(self.source(catalog)["last_success_at"], 1002)
            self.assertFalse(catalog.refresh_due(now=1003)["accepted"])
        catalog.rebuild(timeout=2)
        self.assertEqual(len(catalog.snapshot()["mixes"]), 1)

    def test_failure_backs_off_and_preserves_last_good(self):
        catalog = self.make_catalog()
        self.follow(catalog, self.channel_url)
        with patch("catalog_ingest.subprocess.run", return_value=self.result(self.payload())):
            catalog.refresh_due(now=1000)
            self.wait_idle(catalog)
        catalog.rebuild(timeout=2)
        app_before = catalog.app_path.read_bytes()
        with patch("catalog_ingest.subprocess.run", return_value=self.result(self.payload(), returncode=1)) as run:
            catalog.refresh_due(now=90000)
            self.wait_idle(catalog)
            self.assertEqual(run.call_count, 1)
            self.assertEqual(self.source(catalog)["last_attempt_at"], 90000)
            self.assertEqual(self.source(catalog)["last_success_at"], 1000)
            self.assertIsNotNone(self.source(catalog)["error"])
            self.assertEqual(catalog.app_path.read_bytes(), app_before)
            self.assertFalse(catalog.refresh_due(now=90001)["accepted"])
            catalog.refresh_due(now=176400)
            self.wait_idle(catalog)
            self.assertEqual(run.call_count, 2)
            self.assertEqual(self.source(catalog)["last_attempt_at"], 176400)
        self.assertEqual(len(catalog.snapshot()["mixes"]), 1)
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.status.return_value = {"connected": False}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        bridge = Bridge(lab, catalog=catalog)
        bridge.snapshot()
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_missing_items_are_retained_and_restore_on_return(self):
        catalog = self.make_catalog()
        self.follow(catalog, self.channel_url)
        with patch("catalog_ingest.subprocess.run", return_value=self.result(self.payload(("abcdefghijk", "zyxwvutsrqp")))):
            catalog.refresh_due(now=1000)
            self.wait_idle(catalog)
        catalog.rebuild(timeout=2)
        with patch("catalog_ingest.subprocess.run", return_value=self.result(self.payload(("abcdefghijk",)))):
            catalog.refresh_due(now=90000)
            self.wait_idle(catalog)
        with catalog.master.store.reading() as connection:
            missing = connection.execute("SELECT availability, missing_reads FROM songs WHERE id='zyxwvutsrqp'").fetchone()
        self.assertEqual(missing["availability"], "missing")
        self.assertEqual(missing["missing_reads"], 1)
        catalog.rebuild(timeout=2)
        self.assertIn("zyxwvutsrqp", {song["id"] for song in catalog.snapshot()["mixes"]})
        with patch("catalog_ingest.subprocess.run", return_value=self.result(self.payload(("abcdefghijk", "zyxwvutsrqp")))):
            catalog.refresh_due(now=176400)
            self.wait_idle(catalog)
        with catalog.master.store.reading() as connection:
            restored = connection.execute("SELECT availability, missing_reads FROM songs WHERE id='zyxwvutsrqp'").fetchone()
        self.assertEqual(restored["availability"], "available")
        self.assertEqual(restored["missing_reads"], 0)

    def test_automatic_refresh_starts_for_due_sources(self):
        master = MasterCatalog(self.root / "master.db")
        CatalogIngest(master).follow_channel(self.channel_url)
        with patch("catalog_ingest.subprocess.run", return_value=self.result(self.payload())) as run:
            catalog = self.make_catalog(automatic=True)
            self.wait_idle(catalog)
        self.assertEqual(run.call_count, 1)
        self.assertIsNotNone(self.source(catalog)["last_success_at"])

    def test_refresh_all_reports_failures_and_clears_progress(self):
        catalog = self.make_catalog()
        other_url = "https://www.youtube.com/@other"
        self.follow(catalog, self.channel_url)
        catalog.ingest.follow_channel(other_url)
        other_payload = dict(self.payload(), channel_id="UC" + "b" * 22)
        with patch("catalog_ingest.subprocess.run", side_effect=[self.result(other_payload), self.result(self.payload(), returncode=1)]) as run:
            catalog.refresh(now=1000)
            self.wait_idle(catalog)
        self.assertEqual(run.call_count, 2)
        self.assertEqual(catalog.ingest.refreshing_channels, [])
        self.assertFalse(catalog.ingest.busy)
        self.assertIn("1 channel imports need retry", catalog.ingest.message)
        other = next(channel for channel in catalog.snapshot()["channels"] if channel["url"] == other_url)
        self.assertEqual(other["last_success_at"], 1000)
        self.assertIsNone(self.source(catalog)["last_success_at"])
        self.assertIsNotNone(self.source(catalog)["error"])


if __name__ == "__main__":
    unittest.main()
