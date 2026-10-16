import threading
import unittest
from unittest.mock import Mock
from app import App
from engine import STARTERS, youtube_url


class MixSwitch(unittest.TestCase):
    def setUp(self):
        self.app = App.__new__(App)
        self.app.busy = False
        self.app.connection_pending = threading.Event()
        for name in ('source_picker', 'source', 'load_preview', 'message', 'lab', 'sync_mix_choice'):
            setattr(self.app, name, Mock())
        self.app.source_picker.current.return_value = 1
        self.app.work = Mock(side_effect=lambda label, action: action())
        self.app.lab.status.return_value = dict(connected=True, volume=25, state='PLAYING', source=youtube_url(STARTERS[0]))

    def test_selection_previews_without_switching_playback(self):
        self.app.choose_mix()
        self.app.source.set.assert_called_once_with(youtube_url(STARTERS[1]))
        self.app.load_preview.assert_called_once()
        self.app.lab.play_test.assert_not_called()
        self.app.work.assert_not_called()

    def test_disconnected_selection_never_starts_audio(self):
        self.app.lab.status.return_value = {'connected': False}
        self.app.choose_mix()
        self.app.lab.play_test.assert_not_called()

    def test_same_track_does_not_restart_or_resume(self):
        self.app.source_picker.current.return_value = 0
        self.app.choose_mix()
        self.app.lab.play_test.assert_not_called()

    def test_busy_selection_is_ignored(self):
        self.app.busy = True
        self.app.choose_mix()
        self.app.lab.play_test.assert_not_called()
        self.app.source.set.assert_not_called()

    def test_explicit_switch_button_plays_selected_mix(self):
        self.app.source.get.return_value = youtube_url(STARTERS[1])
        self.app.do_play_selected()
        self.app.lab.play_selection.assert_called_once_with(youtube_url(STARTERS[1]))

    def test_transport_play_resumes_current_session_not_preview(self):
        self.app.lab.status.return_value = {'connected': True, 'state': 'PAUSED'}
        self.app.source.get.return_value = youtube_url(STARTERS[1])
        self.app.do_play()
        self.app.lab.play_selection.assert_called_once_with('')
