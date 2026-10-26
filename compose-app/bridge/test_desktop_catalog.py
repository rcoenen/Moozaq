import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from desktop_catalog import DesktopCatalog, create_desktop_catalog
from server import Bridge


class DesktopCatalogTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.seeds_path = self.root / "channels.json"

    def make_catalog(self, delay=0.05):
        self.seeds_path.write_text("[]")
        (self.root / "channel-catalog.json").write_text(json.dumps({"channels": [], "mixes": []}))
        catalog = DesktopCatalog(self.root, self.seeds_path, generation_delay=delay)
        self.addCleanup(catalog.close)
        return catalog

    def add_song(self, catalog, video_id, first=100):
        source_id = "UC" + "a" * 22
        with catalog.master.store.writing() as connection:
            connection.execute(
                "INSERT OR IGNORE INTO sources(id, url, name, category, publication_mode, origin) VALUES(?, ?, 'Channel', 'Noir Jazz', 'automatic', 'channel')",
                (source_id, "https://www.youtube.com/channel/" + source_id),
            )
            connection.execute(
                """INSERT INTO songs(id, channel_id, title, creator, artwork, thumbnail, duration,
                                    automatic_category, first_seen_at, last_seen_at, availability, origin)
                   VALUES(?, ?, ?, 'Creator', -1, ?, 7200, 'Noir Jazz', ?, ?, 'unknown', 'channel')""",
                (video_id, source_id, video_id, f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg", first, first),
            )
            connection.execute("INSERT INTO curation(song_id) VALUES(?)", (video_id,))
            connection.execute("UPDATE master_state SET revision=revision+1, dirty=1 WHERE id=1")

    def lab(self):
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.status.return_value = {"connected": False}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        return lab

    def test_factory_migrates_once_and_preserves_master_on_reopen(self):
        self.seeds_path.write_text(json.dumps([
            {"url": "https://www.youtube.com/@seed", "name": "Seed", "category": "Noir Jazz"}
        ]))
        (self.root / "channel-catalog.json").write_text(json.dumps({"channels": [], "mixes": []}))
        catalog = create_desktop_catalog(self.root, self.seeds_path, automatic_refresh=False, automatic_updates=False)
        self.addCleanup(catalog.close)
        self.assertEqual(catalog.backend_name, "sqlite")
        self.assertTrue(catalog.supports_curation)
        self.assertEqual(catalog.counts()["sources"], 1)
        self.assertEqual(catalog.counts()["songs"], 6)
        self.assertEqual(catalog.status()["app_revision"], 1)
        self.assertFalse(catalog.status()["catalog_dirty"])
        reopened = create_desktop_catalog(self.root, self.seeds_path, automatic_refresh=False, automatic_updates=False)
        self.addCleanup(reopened.close)
        self.assertEqual(reopened.counts(), catalog.counts())
        self.assertEqual(reopened.snapshot(), catalog.snapshot())

    def test_public_changes_coalesce_into_one_local_build(self):
        catalog = self.make_catalog()
        with patch.object(catalog.publisher, "build", wraps=catalog.publisher.build) as build:
            self.add_song(catalog, "abcdefghijk")
            self.add_song(catalog, "zyxwvutsrqp", first=200)
            catalog.request_generation()
            catalog.request_generation()
            result = catalog.rebuild(timeout=2)
        self.assertEqual(build.call_count, 1)
        self.assertEqual(result["app_revision"], 3)
        self.assertEqual(catalog.master.state()["generated_revision"], 3)
        self.assertEqual(catalog.master.state()["dirty"], 0)

    def test_review_only_change_does_not_build(self):
        catalog = self.make_catalog()
        with patch.object(catalog.publisher, "build", wraps=catalog.publisher.build) as build:
            with catalog.master.store.writing() as connection:
                connection.execute("UPDATE master_state SET revision=revision+1, dirty=0 WHERE id=1")
            catalog.request_generation()
            result = catalog.rebuild(timeout=2)
        self.assertEqual(build.call_count, 0)
        self.assertEqual(result["app_revision"], 1)
        self.assertEqual(catalog.master.state()["revision"], 2)

    def test_failed_build_keeps_intent_until_rebuild_succeeds(self):
        catalog = self.make_catalog()
        self.add_song(catalog, "abcdefghijk")
        with patch.object(catalog.publisher, "build", side_effect=RuntimeError("build failed")):
            catalog.request_generation()
            with self.assertRaisesRegex(RuntimeError, "build failed"):
                catalog.rebuild(timeout=2)
        self.assertEqual(catalog.status()["app_revision"], 1)
        self.assertEqual(catalog.master.state()["revision"], 2)
        self.assertTrue(catalog.master.state()["dirty"])
        self.assertEqual(catalog.master.state()["last_generation_error"], "build failed")
        with patch("subprocess.run") as process:
            result = catalog.rebuild(timeout=2)
        self.assertEqual(result["app_revision"], 2)
        self.assertFalse(catalog.master.state()["dirty"])
        self.assertIsNone(catalog.master.state()["last_generation_error"])
        process.assert_not_called()

    def test_personal_profile_persists_and_resets_without_playback(self):
        catalog = self.make_catalog()
        lab = self.lab()
        bridge = Bridge(lab, catalog=catalog)
        result = bridge.dispatch({"op": "rate_song", "song": "WlPiQGR18jE", "rating": 1})
        self.assertEqual(result["revision"], 2)
        self.assertEqual(bridge.dispatch({"op": "personal_profile"})["ratings"], {"WlPiQGR18jE": 1})
        state = bridge.snapshot()
        self.assertTrue(state["personal_profile"])
        self.assertEqual(state["personal_revision"], 2)
        catalog.close()
        reopened = DesktopCatalog(self.root, self.seeds_path)
        self.addCleanup(reopened.close)
        self.assertEqual(reopened.profile()["ratings"], {"WlPiQGR18jE": 1})
        reset = Bridge(lab, catalog=reopened).dispatch({"op": "reset_personal"})
        self.assertTrue(reset["cleared"])
        self.assertEqual(reopened.profile()["ratings"], {})
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_reset_discards_pending_listening_and_empty_observation_on_restart(self):
        catalog = self.make_catalog()
        self.add_song(catalog, "abcdefghijk")
        catalog.rebuild(timeout=2)
        lab = self.lab()
        bridge = Bridge(lab, catalog=catalog)
        for now, position in ((100, 0), (160, 60)):
            catalog.observe_playback({
                "source": "https://www.youtube.com/watch?v=abcdefghijk",
                "media_session": "session-1", "position": position,
                "state": "PLAYING", "connected": True, "fresh": True,
            }, now=now)
        self.assertEqual(catalog.personal.last_observation()["video_id"], "abcdefghijk")
        result = bridge.dispatch({"op": "reset_personal"})
        self.assertTrue(result["cleared"])
        catalog.close()
        reopened = DesktopCatalog(self.root, self.seeds_path)
        self.addCleanup(reopened.close)
        self.assertEqual(reopened.profile()["ratings"], {})
        self.assertEqual(reopened.profile()["listening"], {})
        self.assertIsNone(reopened.personal.last_observation())
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_bridge_rebuild_confirms_new_revision_without_touching_player(self):
        catalog = self.make_catalog()
        self.add_song(catalog, "abcdefghijk")
        lab = self.lab()
        with patch("server.DATA", self.root):
            bridge = Bridge(lab, catalog=catalog)
            result = bridge.dispatch({"op": "rebuild_catalog"})
            state = bridge.snapshot()
        self.assertEqual(result["app_revision"], 2)
        self.assertEqual(state["master_revision"], 2)
        self.assertEqual(state["generated_revision"], 2)
        self.assertFalse(state["catalog_dirty"])
        self.assertFalse(state["catalog_building"])
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_release_requires_confirmation_of_exact_revision_and_assets(self):
        catalog = self.make_catalog()
        self.add_song(catalog, "abcdefghijk")
        lab = self.lab()
        bridge = Bridge(lab, catalog=catalog)
        from types import SimpleNamespace
        with patch("catalog_release.subprocess.run", return_value=SimpleNamespace(stdout="https://github.com/rcoenen/Moozaq-catalog/releases/tag/catalog-v2")) as process:
            for confirmation in (None, False, "false", 1):
                with self.subTest(confirmation=confirmation):
                    with self.assertRaises(PermissionError):
                        bridge.dispatch({"op": "publish_release", "confirmed": confirmation, "expected_revision": 2})
            with self.assertRaisesRegex(ValueError, "review and confirm"):
                bridge.dispatch({"op": "publish_release", "confirmed": True, "expected_revision": 99})
            process.assert_not_called()
            result = bridge.dispatch({"op": "publish_release", "confirmed": True, "expected_revision": 2})
        self.assertEqual(result["tag"], "catalog-v2")
        self.assertEqual(result["assets"], ["app-catalog.db", "catalog-manifest.json"])
        command = process.call_args.args[0]
        self.assertEqual(command[:4], ["gh", "release", "create", "catalog-v2"])
        self.assertEqual([Path(asset).name for asset in command[4:6]], result["assets"])
        self.assertEqual(process.call_count, 1)
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_failed_release_can_retry_identical_bundle(self):
        import subprocess
        from types import SimpleNamespace
        catalog = self.make_catalog()
        self.add_song(catalog, "abcdefghijk")
        with patch("catalog_release.subprocess.run", side_effect=[
            subprocess.CalledProcessError(1, ["gh"]),
            SimpleNamespace(stdout="https://github.com/rcoenen/Moozaq-catalog/releases/tag/catalog-v2"),
        ]) as process:
            with self.assertRaises(subprocess.CalledProcessError):
                catalog.publish_release(confirmed=True, expected_revision=2)
            manifest_before = (self.root / "catalog-manifest.json").read_bytes()
            database_before = self.root.joinpath("app-catalog.db").read_bytes()
            result = catalog.publish_release(confirmed=True, expected_revision=2)
        self.assertEqual(result["tag"], "catalog-v2")
        self.assertEqual(process.call_count, 2)
        self.assertEqual((self.root / "catalog-manifest.json").read_bytes(), manifest_before)
        self.assertEqual(self.root.joinpath("app-catalog.db").read_bytes(), database_before)

    def test_release_rejects_catalog_change_before_upload(self):
        from catalog_release import prepare_release as original_prepare
        catalog = self.make_catalog()
        self.add_song(catalog, "abcdefghijk")
        def prepare_then_change(*args, **kwargs):
            bundle = original_prepare(*args, **kwargs)
            with catalog.master.store.writing() as connection:
                connection.execute("UPDATE master_state SET revision=revision+1, dirty=1 WHERE id=1")
            catalog.publisher.build()
            return bundle
        with patch("catalog_release.prepare_release", side_effect=prepare_then_change), \
                patch("catalog_release.publish_release") as publish:
            with self.assertRaisesRegex(ValueError, "Publication revision mismatch"):
                catalog.publish_release(confirmed=True, expected_revision=2)
        publish.assert_not_called()
        self.assertEqual(catalog.app.status()["catalog_revision"], 3)

    def test_curation_refresh_and_rebuild_never_publish(self):
        catalog = self.make_catalog()
        self.add_song(catalog, "abcdefghijk")
        lab = self.lab()
        bridge = Bridge(lab, catalog=catalog)
        source_url = "https://www.youtube.com/channel/UC" + "a" * 22
        payload = {"channel_id": "UC" + "a" * 22, "channel": "Channel",
                   "entries": [{"id": "zyxwvutsrqp", "title": "New mix", "duration": 7200}]}
        from types import SimpleNamespace
        with patch("catalog_release.subprocess.run") as process, patch(
            "catalog_ingest.subprocess.run",
            return_value=SimpleNamespace(returncode=0, stdout=json.dumps(payload)),
        ):
            bridge.dispatch({"op": "curate", "action": "recommend", "song": "abcdefghijk"})
            bridge.dispatch({"op": "import_channel", "url": source_url})
            bridge.dispatch({"op": "rebuild_catalog"})
        process.assert_not_called()
        self.assertFalse(catalog.status()["catalog_dirty"])
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_explicit_update_check_uses_bridge_facade(self):
        catalog = self.make_catalog()
        lab = self.lab()
        bridge = Bridge(lab, catalog=catalog)
        with patch.object(catalog.updater, "check", return_value={"status": "current", "revision": 1}) as check:
            result = bridge.dispatch({"op": "check_updates"})
        self.assertEqual(result, {"status": "current", "revision": 1})
        check.assert_called_once_with(force=True)
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
