import tempfile
import unittest
from pathlib import Path

from catalog_publish import APP_COLUMNS
from personal_sqlite import PersonalStore


class PersonalStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "personal.db"

    def test_ratings_and_revision_persist_across_restart(self):
        store = PersonalStore(self.path)
        first = store.set_rating("WlPiQGR18jE", 1, now=100)
        self.assertEqual(first["revision"], 2)
        self.assertEqual(store.profile()["ratings"], {"WlPiQGR18jE": 1})
        reopened = PersonalStore(self.path)
        self.assertEqual(reopened.profile()["ratings"], {"WlPiQGR18jE": 1})
        reopened.set_rating("WlPiQGR18jE", -1, now=200)
        self.assertEqual(reopened.profile()["ratings"], {"WlPiQGR18jE": -1})
        reopened.set_rating("WlPiQGR18jE", 0, now=300)
        self.assertEqual(reopened.profile()["ratings"], {})
        self.assertEqual(reopened.profile()["revision"], 4)

    def test_listening_aggregates_and_observation_are_private_and_bounded(self):
        store = PersonalStore(self.path)
        store.record_listening("WlPiQGR18jE", 300, now=100, qualified_session=True)
        store.record_listening("WlPiQGR18jE", 60, now=160)
        aggregate = store.profile()["listening"]["WlPiQGR18jE"]
        self.assertEqual(aggregate["qualified_seconds"], 360)
        self.assertEqual(aggregate["qualified_sessions"], 1)
        self.assertEqual(aggregate["last_listened_at"], 160)
        revision = store.profile()["revision"]
        store.save_observation("WlPiQGR18jE", "session-1", 123.5, 160, qualified=True)
        self.assertEqual(store.profile()["revision"], revision)
        observation = store.last_observation()
        self.assertEqual(observation["media_session"], "session-1")
        self.assertEqual(observation["position"], 123.5)
        self.assertTrue(observation["qualified"])
        self.assertNotIn("googlevideo", str(observation))

    def test_reset_clears_profile_and_observation(self):
        store = PersonalStore(self.path)
        store.set_rating("WlPiQGR18jE", 1, now=100)
        store.record_listening("WlPiQGR18jE", 300, now=100, qualified_session=True)
        store.save_observation("WlPiQGR18jE", "session", 300, 100, qualified=True)
        store.reset()
        self.assertEqual(store.profile()["ratings"], {})
        self.assertEqual(store.profile()["listening"], {})
        self.assertIsNone(store.last_observation())

    def test_rating_validation(self):
        store = PersonalStore(self.path)
        with self.assertRaises(ValueError):
            store.set_rating("WlPiQGR18jE", 2)
        with self.assertRaises(ValueError):
            store.record_listening("WlPiQGR18jE", -1)

    def test_catalog_schema_has_no_personal_tables(self):
        self.assertNotIn("ratings", APP_COLUMNS)
        self.assertNotIn("listening", APP_COLUMNS)
        self.assertNotIn("observation", APP_COLUMNS)
        self.assertNotIn("personal_state", APP_COLUMNS)


if __name__ == "__main__":
    unittest.main()
