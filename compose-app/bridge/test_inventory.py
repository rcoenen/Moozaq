import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from inventory import DeviceInventory


def info(uid='office', kind='audio'):
    return SimpleNamespace(uuid=uid, friendly_name='Office speaker' if uid == 'office' else uid,
                           model_name='Nest Audio', cast_type=kind)


class InventoryTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.inventory = DeviceInventory(Path(temp.name) / 'devices.json')

    def test_disappearing_speaker_stays_visible_but_unavailable(self):
        self.inventory.observe([info()])
        row = self.inventory.snapshot([])[0]
        self.assertEqual(row['name'], 'Office speaker')
        self.assertFalse(row['available'])
        self.assertNotIn('volume', row)
        self.assertNotIn('state', row)
        self.assertTrue(self.inventory.snapshot([info()])[0]['available'])

    def test_known_physical_devices_survive_restart_but_temporary_groups_do_not(self):
        self.inventory.observe([info(), info('temp-group', 'group')])
        loaded = DeviceInventory(self.inventory.path)
        self.assertEqual([row['id'] for row in loaded.snapshot([])], ['office'])
        self.assertEqual(len(loaded.snapshot([info('temp-group','group')])), 2)

    def test_renamed_device_updates_without_duplicates(self):
        self.inventory.observe([info()])
        renamed = info()
        renamed.friendly_name = 'Study speaker'
        self.inventory.observe([renamed])
        self.assertEqual([r['name'] for r in self.inventory.snapshot([renamed])], ['Study speaker'])

    def test_partial_update_does_not_turn_a_speaker_into_a_tv(self):
        mini = SimpleNamespace(uuid='kitchen', friendly_name='Kitchen speaker', model_name='Google Home Mini', cast_type='audio')
        self.inventory.observe([mini])
        partial = SimpleNamespace(uuid='kitchen', friendly_name='Kitchen speaker', model_name=None, cast_type='cast')
        self.inventory.observe([partial])
        row = self.inventory.snapshot([partial])[0]
        self.assertEqual((row['cast_type'], row['model'], row['available']), ('audio', 'Google Home Mini', True))
        reloaded = DeviceInventory(self.inventory.path)
        self.assertEqual(reloaded.snapshot([])[0]['cast_type'], 'audio')
