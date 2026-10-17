import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from engine import Lab, discovered_cast_infos


class DiscoveryTests(unittest.TestCase):
    def test_known_addresses_are_configured_before_discovery_worker_starts(self):
        with tempfile.TemporaryDirectory() as directory:
            order = []
            browser = Mock()
            browser.start_discovery.side_effect = lambda: order.append('start')
            def create(*args, **kwargs):
                self.assertEqual(kwargs['known_hosts'], ['192.168.8.224'])
                order.append('seed')
                return browser
            with patch('engine.DATA', Path(directory)), patch('engine.zeroconf.Zeroconf'), \
                 patch('engine.pychromecast.CastBrowser', side_effect=create), patch('engine.MediaServer'):
                lab = Lab(known_hosts=['192.168.8.224'])
                self.assertEqual(order, ['seed', 'start'])
                lab.close()

    def test_records_without_services_are_not_available_devices(self):
        stale = SimpleNamespace(uuid='old-group', friendly_name='Old group', model_name='Group', services=set())
        live = SimpleNamespace(uuid='office', friendly_name='Office', model_name='Nest Audio', services={'host'})
        browser = SimpleNamespace(devices={'old-group': stale, 'office': live})
        self.assertEqual(discovered_cast_infos(browser), [live])
        lab = Lab.__new__(Lab)
        lab.browser = browser
        self.assertEqual(lab.devices(), [('office', 'Office', 'Nest Audio')])
        lab.cast = None
        lab.detach = Mock()
        with patch('engine.pychromecast.get_chromecast_from_cast_info') as connect:
            with self.assertRaises(ValueError): lab.connect('old-group')
            connect.assert_not_called()
