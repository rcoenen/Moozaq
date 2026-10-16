import unittest
from unittest.mock import Mock
from engine import Lab, youtube_url


class PlaySelection(unittest.TestCase):
    def setUp(self):
        self.lab = Lab.__new__(Lab)
        self.lab.cast = Mock()
        self.lab.cast.socket_client.is_connected = True
        self.lab.generation = 0
        self.lab.control_timeout = 3
        self.lab.sync_media = Mock(return_value=True)
        self.lab.command = Mock()
        self.lab.play_test = Mock()
        self.source = youtube_url('7vy0H3ERRiA')
        self.state = dict(state='UNKNOWN', media_session=None, volume=25)
        self.lab.status = Mock(side_effect=lambda: self.state)

    def test_play_loads_selection_when_receiver_has_no_session(self):
        self.lab.sync_media.return_value = False
        self.lab.play_selection(self.source)
        self.lab.play_test.assert_called_once_with('direct', self.source, .25, expected_generation=0)
        self.lab.command.assert_not_called()

    def test_pause_resumes_without_reloading(self):
        self.state.update(state='PAUSED', source=self.source, media_session=1)
        self.lab.play_selection(self.source)
        self.lab.command.assert_called_once_with('play')
        self.lab.play_test.assert_not_called()

    def test_already_playing_is_noop(self):
        self.state.update(state='PLAYING', source=self.source, media_session=1)
        self.lab.play_selection(self.source)
        self.lab.play_test.assert_not_called()
        self.lab.command.assert_not_called()

    def test_unsynchronized_existing_session_is_not_replaced(self):
        self.state.update(state='UNKNOWN', media_session=1)
        self.lab.sync_media.return_value = False
        with self.assertRaises(RuntimeError):
            self.lab.play_selection(self.source)
        self.lab.play_test.assert_not_called()

    def test_disconnected_does_not_start_audio(self):
        self.lab.cast.socket_client.is_connected = False
        with self.assertRaises(ValueError):
            self.lab.play_selection(self.source)
        self.lab.play_test.assert_not_called()

    def test_stop_during_sync_cancels_play(self):
        self.lab.sync_media.side_effect = lambda **kwargs: setattr(self.lab, 'generation', 1)
        with self.assertRaises(RuntimeError):
            self.lab.play_selection(self.source)
        self.lab.play_test.assert_not_called()
