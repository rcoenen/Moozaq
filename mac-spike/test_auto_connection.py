import threading
import unittest
from unittest.mock import Mock, patch
from app import App


class AutoConnection(unittest.TestCase):
    def setUp(self):
        self.app = App.__new__(App)
        self.app.busy = False
        self.app.connection_pending = threading.Event()
        self.app.device = Mock()
        self.app.device.get.return_value = 'Office'
        self.app.device_ids = {'Office': 'office-id'}
        self.app.lab = Mock()
        self.app.lab.selected_id = 'office-id'
        self.app.retry_at = 0
        self.app.retry_delay = 5
        self.app.connect = Mock()

    def test_healthy_connection_is_not_rejoined(self):
        self.app.maintain_connection({'connected': True})
        self.app.connect.assert_not_called()

    def test_drop_waits_then_rejoins_read_only(self):
        with patch('app.time.monotonic', return_value=100):
            self.app.maintain_connection({'connected': False})
        self.assertEqual(self.app.retry_at, 105)
        with patch('app.time.monotonic', return_value=104):
            self.app.maintain_connection({'connected': False})
        self.app.connect.assert_not_called()
        with patch('app.time.monotonic', return_value=106):
            self.app.maintain_connection({'connected': False})
        self.app.connect.assert_called_once()
        self.app.lab.play_selection.assert_not_called()
        self.app.lab.play_test.assert_not_called()

    def test_changed_target_connects_immediately(self):
        self.app.lab.selected_id = 'bedroom-id'
        self.app.maintain_connection({'connected': True})
        self.app.connect.assert_called_once()

    def test_no_connection_change_during_job(self):
        self.app.busy = True
        self.app.maintain_connection({'connected': False})
        self.app.connect.assert_not_called()

    def test_failed_connect_uses_bounded_backoff(self):
        self.app.connect = App.connect.__get__(self.app)
        self.app.connection_label = Mock()
        self.app.work = lambda label, fn: fn()
        self.app.lab.connect.side_effect = RuntimeError('Unavailable')
        for delay in (5, 10, 20, 30, 30):
            with patch('app.time.monotonic', return_value=100):
                with self.assertRaises(RuntimeError):
                    self.app.connect()
            self.assertEqual(self.app.retry_at, 100 + delay)
            self.assertFalse(self.app.connection_pending.is_set())
        self.app.lab.play_test.assert_not_called()
        self.app.lab.command.assert_not_called()
