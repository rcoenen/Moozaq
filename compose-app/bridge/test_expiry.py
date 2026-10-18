"""Silent bridge routing for expired signed links. Mocked lab; nothing loads or plays."""
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from engine import RefreshUnavailable
from server import Bridge

SOURCE = 'https://www.youtube.com/watch?v=7vy0H3ERRiA'


class ExpiredSourceBridge(unittest.TestCase):
    def make(self, **status):
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        lab.selected_id = None
        lab.status.return_value = dict({'connected': True, 'state': 'PAUSED', 'source': SOURCE,
                                        'title': 'Mix', 'duration': 10800, 'content_key': 'k',
                                        'expires_at': time.time() - 5, 'source_expired': True, 'refresh_needed': True,
                                        'resume_position': 4200.0}, **status)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        with patch('server.DATA', Path(temporary.name)):
            bridge = Bridge(lab)
        return bridge, lab

    def finish(self, bridge):
        deadline = time.monotonic() + 2
        while bridge.busy and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertFalse(bridge.busy)

    def test_resume_after_expiry_refreshes_instead_of_play(self):
        bridge, lab = self.make()
        revision = bridge.transport_revision
        bridge.dispatch({'op': 'resume'})
        self.finish(bridge)
        lab.refresh_source.assert_called_once_with(paused=False, expected_generation=0)
        self.assertEqual(bridge.transport_revision, revision + 1)

    def test_resume_renews_valid_link_that_will_not_last(self):
        bridge, lab = self.make(source_expired=False, expires_at=time.time() + 3600)
        bridge.dispatch({'op': 'resume'})
        self.finish(bridge)
        lab.refresh_source.assert_called_once_with(paused=False, expected_generation=0)

    def test_resume_falls_back_to_plain_play_when_no_new_link(self):
        bridge, lab = self.make(source_expired=False, expires_at=time.time() + 3600)
        lab.refresh_source.side_effect = RefreshUnavailable('no link')
        with patch('server.SeekTracker') as tracker:
            bridge.dispatch({'op': 'resume'})
            self.finish(bridge)
        tracker.return_value.playback_confirmed.assert_called_once_with(lab, paused=False, expected_generation=0)

    def test_resume_with_long_link_uses_normal_transport(self):
        bridge, lab = self.make(source_expired=False, refresh_needed=False, expires_at=time.time() + 5 * 3600)
        with patch('server.SeekTracker') as tracker:
            bridge.dispatch({'op': 'resume'})
            self.finish(bridge)
        lab.refresh_source.assert_not_called()
        tracker.return_value.playback_confirmed.assert_called_once()

    def test_seek_after_expiry_reloads_at_target_and_keeps_pause(self):
        bridge, lab = self.make()
        bridge.dispatch({'op': 'seek', 'value': 9000})
        self.finish(bridge)
        lab.refresh_source.assert_called_once_with(position=9000, paused=True, expected_generation=0)

    def test_seek_falls_back_to_plain_seek_when_no_new_link(self):
        bridge, lab = self.make(source_expired=False, state='PLAYING', expires_at=time.time() + 3600)
        lab.refresh_source.side_effect = RefreshUnavailable('no link')
        with patch('server.SeekTracker') as tracker:
            bridge.dispatch({'op': 'seek', 'value': 9000})
            self.finish(bridge)
        tracker.return_value.seek_confirmed.assert_called_once_with(lab, 9000, expected_generation=0)

    def test_seek_on_failed_expired_session_requires_resume(self):
        bridge, lab = self.make(state='IDLE', idle_reason='ERROR')
        with self.assertRaises(ValueError):
            bridge.dispatch({'op': 'seek', 'value': 9000})
        lab.refresh_source.assert_not_called()

    def test_failed_refresh_is_reported_not_retried(self):
        bridge, lab = self.make()
        lab.refresh_source.side_effect = RuntimeError('Nest could not play the selected stream (ERROR).')
        bridge.dispatch({'op': 'resume'})
        self.finish(bridge)
        lab.refresh_source.assert_called_once()
        self.assertTrue(bridge.message.startswith('Failed:'))

    def test_snapshot_presents_expired_failure_without_url(self):
        bridge, lab = self.make(state='IDLE', idle_reason='ERROR')
        view = Bridge.expiry_view(lab.status(), None)
        self.assertEqual(view['state'], 'EXPIRED')
        self.assertEqual((view['source'], view['position']), (SOURCE, 4200.0))
        self.assertNotIn('googlevideo', str(view))

    def test_user_stop_after_expiry_is_not_presented_as_failure(self):
        bridge, lab = self.make(state='IDLE', idle_reason='CANCELLED')
        view = Bridge.expiry_view(lab.status(), None)
        self.assertTrue(view['source_expired'])
        self.assertNotIn('state', view)

    def test_expiry_of_a_different_session_is_not_presented(self):
        bridge, lab = self.make()
        view = Bridge.expiry_view(lab.status(), {'content_key': 'other-session'})
        self.assertFalse(view['source_expired'])
        self.assertIsNone(view['expires_in'])

    def test_snapshot_reports_remaining_lifetime(self):
        bridge, lab = self.make(source_expired=False, expires_at=time.time() + 3600)
        view = Bridge.expiry_view(lab.status(), None)
        self.assertAlmostEqual(view['expires_in'], 3600, delta=2)

    def test_snapshot_never_loads(self):
        bridge, lab = self.make(state='IDLE', idle_reason='ERROR')
        bridge.dispatch({'op': 'state'})
        lab.refresh_source.assert_not_called()
        lab.play_selection.assert_not_called()


if __name__ == '__main__':
    unittest.main()


class FallbackHonoursStop(unittest.TestCase):
    def test_fallback_after_stop_sends_nothing(self):
        from seeking import SeekTracker
        cast = Mock()
        cast.socket_client.is_connected = True
        cast.media_controller.status.media_session_id = 7
        cast.media_controller.status.player_state = 'PAUSED'
        cast.media_controller.status.supports_seek = True
        cast.media_controller.namespace = 'urn:x-cast:com.google.cast.media'
        cast.socket_client.app_namespaces = [cast.media_controller.namespace]
        lab = Mock(cast=cast, generation=1, playback_gate=threading.RLock())
        tracker = SeekTracker(cast)
        with self.assertRaises(RuntimeError):
            tracker.playback_confirmed(lab, paused=False, expected_generation=0)
        with self.assertRaises(RuntimeError):
            tracker.seek_confirmed(lab, 9000, expected_generation=0)
        cast.media_controller.send_message_nocheck.assert_not_called()
