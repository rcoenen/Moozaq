import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock
from volume import VolumeTracker

class VolumeTests(unittest.TestCase):
    def setUp(self):
        self.cast = Mock(spec=['register_status_listener', 'set_volume', 'socket_client'])
        self.lab = SimpleNamespace(cast=self.cast, generation=0, playback_gate=threading.RLock())
        self.tracker = VolumeTracker(self.cast)
    def test_fresh_matching_confirmation(self):
        self.cast.socket_client.receiver_controller.update_status.side_effect = lambda: self.tracker.new_cast_status(SimpleNamespace(volume_level=.7))
        self.tracker.set_confirmed(self.lab,.7)
        self.cast.set_volume.assert_called_once_with(.7)
    def test_stale_matching_value_is_not_confirmation(self):
        self.tracker.new_cast_status(SimpleNamespace(volume_level=.7))
        with self.assertRaises(TimeoutError):
            self.tracker.set_confirmed(self.lab,.7,timeout=.01)
    def test_changed_target_sends_nothing(self):
        self.lab.cast = Mock()
        with self.assertRaises(RuntimeError): self.tracker.set_confirmed(self.lab,.7)
        self.cast.set_volume.assert_not_called()
    def test_stop_cancels_wait(self):
        def stop(value): self.lab.generation += 1
        self.cast.set_volume.side_effect = stop
        with self.assertRaises(RuntimeError): self.tracker.set_confirmed(self.lab,.7)
    def test_invalid_value_sends_nothing(self):
        with self.assertRaises(ValueError): self.tracker.set_confirmed(self.lab,float('nan'))
        self.cast.set_volume.assert_not_called()
