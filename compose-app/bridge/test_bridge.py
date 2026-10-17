import threading
import unittest
import tempfile
from pathlib import Path
from unittest.mock import Mock, patch
from server import Bridge

class BridgeSafety(unittest.TestCase):
    def test_entrypoint_passes_saved_address_before_lab_starts_discovery(self):
        import json
        from server import create_lab
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'preferences.json').write_text(json.dumps({'fallback_host':'192.168.8.224'}))
            with patch('server.DATA',root), patch('server.Lab') as lab:
                create_lab()
                lab.assert_called_once_with(known_hosts=['192.168.8.224'])

    def make(self):
        lab = Mock()
        lab.devices.return_value = []
        lab.browser.devices = {}
        lab.status.return_value = {'connected': False}
        lab.playback_gate = threading.RLock()
        lab.generation = 0
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        with patch('server.DATA', Path(temporary.name)):
            return Bridge(lab), lab

    def test_snapshot_does_not_play_or_leak_stream(self):
        bridge, lab = self.make()
        lab.status.return_value = {'connected': True, 'signed_url': 'secret'}
        state = bridge.dispatch({'op': 'state'})
        self.assertNotIn('signed_url', state['receiver'])
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()

    def test_stop_blocks_new_load_until_confirmation(self):
        bridge, lab = self.make()
        entered, release = threading.Event(), threading.Event()
        def stop():
            entered.set()
            release.wait(2)
        lab.stop_verified.side_effect = stop
        try:
            bridge.dispatch({'op': 'stop'})
            self.assertTrue(entered.wait(1))
            self.assertTrue(bridge.busy)
            with self.assertRaises(ValueError):
                bridge.dispatch({'op': 'play', 'source': 'video'})
            lab.play_selection.assert_not_called()
            lab.cancel.assert_called_once()
        finally:
            release.set()

    def test_expired_group_recovery_only_connects_physical_remote(self):
        from types import SimpleNamespace
        bridge,lab=self.make()
        bridge.target='gone-group'
        bridge.remote_preferences.data={'speaker_id':'gone-group','speaker_kind':'group','fallback_speaker_id':'office'}
        bridge.remote_preferences.missing_since=1
        lab.devices.return_value=[('office','Office speaker','Nest Audio')]
        lab.browser.devices={'office':SimpleNamespace(uuid='office',cast_type='audio',friendly_name='Office speaker',model_name='Nest Audio')}
        lab.selected_id='gone-group'
        bridge.group_follower=Mock()
        bridge.connect=Mock()
        with patch('server.time.monotonic',return_value=100), patch('server.time.sleep',side_effect=lambda _:setattr(bridge,'closed',True)):
            bridge.maintain()
        self.assertEqual(bridge.target,'office')
        bridge.connect.assert_called_once()
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()
        lab.stop_verified.assert_not_called()

    def test_stop_revision_waits_for_verified_receiver_result(self):
        bridge, lab = self.make()
        revision = bridge.transport_revision
        with patch('server.threading.Thread') as thread:
            bridge.dispatch({'op': 'stop'})
            self.assertEqual(bridge.transport_revision, revision)
            lab.stop_verified.side_effect = lambda: self.assertEqual(bridge.transport_revision, revision)
            thread.call_args.kwargs['target']()
        self.assertEqual(bridge.transport_revision, revision + 1)
        self.assertFalse(bridge.stopping)
        lab.play_selection.assert_not_called()

    def test_failed_stop_never_acknowledges_or_retries(self):
        bridge, lab = self.make()
        revision = bridge.transport_revision
        lab.stop_verified.side_effect = RuntimeError('Receiver unavailable')
        with patch('server.threading.Thread') as thread:
            bridge.dispatch({'op': 'stop'})
            thread.call_args.kwargs['target']()
        self.assertEqual(bridge.transport_revision, revision)
        self.assertIn('Stop unconfirmed', bridge.message)
        lab.stop_verified.assert_called_once()
        lab.play_selection.assert_not_called()

    def test_invalid_volume_never_reaches_receiver(self):
        bridge, lab = self.make()
        for value in (-1, 2, float('nan')):
            with self.assertRaises(ValueError):
                bridge.dispatch({'op': 'volume', 'value': value})
        lab.command.assert_not_called()

    def test_recovery_follows_verified_session_without_playback_commands(self):
        from types import SimpleNamespace
        from test_recovery import observed
        bridge, lab = self.make()
        bridge.target = lab.selected_id = 'old'
        bridge.session_recovery.step('old', [observed(members=['office'])], 100)
        bridge.outputs.recovery_rows = Mock(return_value=[observed('new', members=['office'], position=110)])
        lab.devices.return_value = [('new', 'New temporary group', 'Group')]
        lab.browser.devices = {'new': SimpleNamespace(uuid='new', cast_type='group')}
        bridge.connect = Mock()
        with patch('server.time.monotonic', return_value=110):
            bridge.maintain_once()
        self.assertEqual(bridge.target, 'new')
        bridge.connect.assert_called_once()
        lab.play_selection.assert_not_called()
        lab.command.assert_not_called()
        lab.stop_verified.assert_not_called()

    def test_busy_or_stop_prevents_recovery_selection(self):
        bridge, lab = self.make()
        bridge.target = 'old'
        bridge.operation_busy = True
        bridge.session_recovery.step = Mock(return_value='new')
        bridge.maintain_once()
        bridge.session_recovery.step.assert_not_called()
        self.assertEqual(bridge.target, 'old')
        lab.connect.assert_not_called()

    def test_socket_without_fresh_evidence_does_not_enable_controls(self):
        bridge, lab = self.make()
        bridge.target = lab.selected_id = 'office'
        lab.status.return_value = {'connected': True, 'state': 'PLAYING'}
        s = bridge.snapshot()
        self.assertFalse(s['receiver']['connected'])
        self.assertTrue(s['connecting'])

    def test_verified_handoff_does_not_wait_for_second_discovery_connection(self):
        from test_recovery import observed
        bridge, lab = self.make()
        bridge.target = lab.selected_id = 'new-group'
        lab.status.return_value = {'connected': True, 'state': 'PLAYING'}
        bridge.membership.confirmed_receiver = Mock(return_value=observed('new-group'))
        # No mDNS record / output monitor socket yet.
        self.assertEqual(bridge.outputs.recovery_rows(), [])
        state = bridge.snapshot()
        self.assertTrue(state['receiver']['connected'])
        self.assertFalse(state['connecting'])
        self.assertEqual(state['receiver']['title'], 'Our mix')
        lab.connect.assert_not_called()
        lab.play_selection.assert_not_called()

    def test_player_reads_fresh_observation_instead_of_stale_main_socket(self):
        from test_recovery import observed
        bridge, lab = self.make()
        bridge.target = lab.selected_id = 'office'
        lab.status.return_value = {'connected': True, 'state': 'IDLE', 'title': None}
        bridge.outputs.recovery_rows = Mock(return_value=[observed('office')])
        s = bridge.snapshot()
        self.assertEqual(s['receiver']['state'], 'PLAYING')
        self.assertEqual(s['receiver']['title'], 'Our mix')
        self.assertTrue(s['receiver']['connected'])

if __name__ == '__main__':
    unittest.main()
