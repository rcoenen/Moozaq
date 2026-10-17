"""Silent bridge routing for expired signed links. Mocked lab; nothing loads or plays."""
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

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
                                        'expires_at': time.time() - 5, 'source_expired': True,
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
        lab.refresh_expired.assert_called_once_with(paused=False)
        self.assertEqual(bridge.transport_revision, revision + 1)

    def test_resume_with_live_link_uses_normal_transport(self):
        bridge, lab = self.make(source_expired=False, expires_at=time.time() + 3600)
        with patch('server.SeekTracker') as tracker:
            bridge.dispatch({'op': 'resume'})
            self.finish(bridge)
        lab.refresh_expired.assert_not_called()
        tracker.return_value.playback_confirmed.assert_called_once()

    def test_seek_after_expiry_reloads_at_target_and_keeps_pause(self):
        bridge, lab = self.make()
        bridge.dispatch({'op': 'seek', 'value': 9000})
        self.finish(bridge)
        lab.refresh_expired.assert_called_once_with(position=9000, paused=True)

    def test_seek_on_failed_expired_session_requires_resume(self):
        bridge, lab = self.make(state='IDLE', idle_reason='ERROR')
        with self.assertRaises(ValueError):
            bridge.dispatch({'op': 'seek', 'value': 9000})
        lab.refresh_expired.assert_not_called()

    def test_failed_refresh_is_reported_not_retried(self):
        bridge, lab = self.make()
        lab.refresh_expired.side_effect = RuntimeError('Nest could not play the selected stream (ERROR).')
        bridge.dispatch({'op': 'resume'})
        self.finish(bridge)
        lab.refresh_expired.assert_called_once()
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
        lab.refresh_expired.assert_not_called()
        lab.play_selection.assert_not_called()


if __name__ == '__main__':
    unittest.main()
