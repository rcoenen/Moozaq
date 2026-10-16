"""Silent presentation tests for the unified player."""
import unittest
import threading
from unittest.mock import Mock
from app import App, clock_time, player_status, transport_availability


class PlayerPresentation(unittest.TestCase):
    def test_connection_states_and_pending_precedence(self):
        self.assertEqual(player_status({'connected': True}), ('● Connected', '#53b879'))
        self.assertEqual(player_status({'connected': False}), ('● Disconnected', '#ee7069'))
        self.assertEqual(player_status({'connected': True}, True), ('● Connecting…', '#e6a23c'))

    def test_time_formats(self):
        self.assertEqual(clock_time(623.8), '10:23')
        self.assertEqual(clock_time(10966.8), '3:02:46')
        self.assertEqual(clock_time(None), '—')
        self.assertEqual(clock_time(-5), '0:00')

    def test_progress_and_disconnect_clear_stale_values(self):
        app = App.__new__(App)
        app.connection_pending = threading.Event()
        app.busy = False
        app.play_button = Mock()
        app.pause_button = Mock()
        app.switch_button = Mock()
        app.now_playing_label = Mock()
        for name in ('connection_label', 'playback_label', 'elapsed', 'total', 'live_volume', 'source'):
            setattr(app, name, Mock())
        app.source.get.return_value = 'source'
        app.catalog_buttons = []
        app.progress = {}
        app.update_player(dict(connected=True, state='PLAYING', device='Office', source='source',
                               position=30, duration=120, volume=25))
        self.assertEqual(app.progress['value'], 25)
        app.elapsed.configure.assert_called_with(text='0:30')
        app.total.configure.assert_called_with(text='2:00')
        app.update_player({'connected': False})
        self.assertEqual(app.progress['value'], 0)
        app.elapsed.configure.assert_called_with(text='—')
        app.total.configure.assert_called_with(text='—')

    def test_unknown_duration_does_not_invent_progress(self):
        app = App.__new__(App)
        app.connection_pending = threading.Event()
        app.busy = False
        app.play_button = Mock()
        app.pause_button = Mock()
        app.switch_button = Mock()
        app.now_playing_label = Mock()
        for name in ('connection_label', 'playback_label', 'elapsed', 'total', 'live_volume', 'source'):
            setattr(app, name, Mock())
        app.source.get.return_value = ''
        app.catalog_buttons = []
        app.progress = {}
        app.update_player(dict(connected=True, state='PLAYING', position=70, duration=None))
        self.assertEqual(app.progress['value'], 0)
        app.total.configure.assert_called_with(text='—')


class TransportAvailability(unittest.TestCase):
    def test_playing_disables_play_enables_pause(self):
        self.assertEqual(transport_availability({'connected': True, 'state': 'PLAYING'}, True), (False, True))

    def test_paused_allows_resume_only(self):
        self.assertEqual(transport_availability({'connected': True, 'state': 'PAUSED'}), (True, False))

    def test_idle_requires_selection(self):
        status = {'connected': True, 'state': 'IDLE'}
        self.assertEqual(transport_availability(status, True), (True, False))
        self.assertEqual(transport_availability(status), (False, False))

    def test_pending_buffering_and_disconnected_disable_both(self):
        playing = {'connected': True, 'state': 'PLAYING'}
        self.assertEqual(transport_availability(playing, True, busy=True), (False, False))
        self.assertEqual(transport_availability(playing, True, connecting=True), (False, False))
        self.assertEqual(transport_availability({'connected': False}, True), (False, False))
        self.assertEqual(transport_availability({'connected': True, 'state': 'BUFFERING'}, True), (False, False))
