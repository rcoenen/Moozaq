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
