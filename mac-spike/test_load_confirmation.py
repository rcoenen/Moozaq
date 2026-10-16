import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from engine import Lab


class LoadConfirmation(unittest.TestCase):
    def setUp(self):
        self.lab = Lab.__new__(Lab)
        self.lab.generation = 0
        self.lab.media_updated = threading.Event()
        self.lab.log = Mock()
        self.cast = Mock()
        self.lab.cast = self.cast
        self.media = SimpleNamespace(content_id='old', player_state='PLAYING', idle_reason=None)
        self.cast.media_controller.status = self.media

    def test_old_playing_status_is_not_success(self):
        with self.assertRaisesRegex(RuntimeError, 'did not confirm'):
            self.lab.confirm_loaded_media(self.cast, 'new', 0, timeout=.01)

    def test_new_media_playing_confirms(self):
        self.media.content_id = 'new'
        self.lab.confirm_loaded_media(self.cast, 'new', 0, timeout=.1)

    def test_receiver_rejection_surfaces(self):
        self.media.content_id = 'new'
        self.media.player_state = 'IDLE'
        self.media.idle_reason = 'ERROR'
        with self.assertRaisesRegex(RuntimeError, 'could not play'):
            self.lab.confirm_loaded_media(self.cast, 'new', 0, timeout=.1)

    def test_reconnecting_never_loads(self):
        self.cast.socket_client.is_connected = False
        with self.assertRaisesRegex(RuntimeError, 'reconnecting'):
            self.lab.wait_for_cast_ready(self.cast, 0, timeout=0)
        self.cast.media_controller.play_media.assert_not_called()

    def test_stop_invalidates_wait(self):
        self.lab.generation = 1
        with self.assertRaisesRegex(RuntimeError, 'cancelled'):
            self.lab.wait_for_cast_ready(self.cast, 0)

    def test_missing_media_channel_is_not_ready(self):
        self.cast.socket_client.is_connected = True
        self.cast.status.app_id = 'CC1AD845'
        self.cast.socket_client.destination_id = None
        with self.assertRaisesRegex(RuntimeError, 'reconnecting'):
            self.lab.wait_for_cast_ready(self.cast, 0, require_receiver=True, timeout=0)

    def test_failed_preflight_does_not_touch_receiver(self):
        import requests
        with patch('engine.requests.get', side_effect=requests.HTTPError()):
            with self.assertRaisesRegex(RuntimeError, 'not replaced'):
                self.lab.preflight_direct('https://example.test/audio')
        self.cast.assert_not_called()
        self.cast.media_controller.play_media.assert_not_called()
