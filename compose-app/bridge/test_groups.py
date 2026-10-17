import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace
from groups import GroupMonitor

class GroupDiagnostics(unittest.TestCase):
    def setUp(self):
        self.lab = Mock()
        self.lab.cast = None
        self.monitor = GroupMonitor(self.lab)

    def test_old_target_data_is_hidden_immediately(self):
        self.monitor.cast = object()
        self.monitor.view = {'leader': 'old'}
        self.assertIsNone(self.monitor.snapshot())

    def test_individual_selection_does_not_attach_group_controller(self):
        self.lab.cast = SimpleNamespace(cast_type='audio')
        with patch('groups.MultizoneController') as controller:
            self.monitor.step()
            controller.assert_not_called()
        self.assertIsNone(self.monitor.snapshot())

    def test_group_inspection_uses_only_status(self):
        group = Mock(cast_type='group', uuid='group')
        group.socket_client.host = 'host'
        group.socket_client.port = 123
        group.socket_client.is_connected = True
        self.lab.cast = group
        self.lab.browser.devices = {}
        with patch('groups.MultizoneController') as make:
            make.return_value.members = []
            self.monitor.step()
            make.return_value.update_members.assert_called_once()
        group.start_app.assert_not_called()
        group.media_controller.play_media.assert_not_called()
        group.set_volume.assert_not_called()
        self.assertEqual(self.monitor.snapshot()['status'], 'Waiting for group membership')

    def test_member_update_does_not_claim_audibility(self):
        member = Mock()
        member.cast_info.friendly_name = 'Kitchen'
        member.socket_client.is_connected = True
        member.status.volume_muted = False
        self.monitor.members['member'] = member
        self.monitor.record_member('member',member,SimpleNamespace(player_state='PLAYING'))
        self.assertEqual(self.monitor.states['member']['state'], 'PLAYING')
        self.assertNotIn('audible',self.monitor.states['member'])
        member.media_controller.play_media.assert_not_called()

    def test_reset_only_disconnects_remotes(self):
        member=Mock()
        self.monitor.members['member']=member
        self.monitor.reset()
        member.disconnect.assert_called_once()
        member.quit_app.assert_not_called()
        member.media_controller.stop.assert_not_called()

class MemberVolumeTests(unittest.TestCase):
    def setUp(self):
        import threading
        self.lab = Mock(generation=0)
        self.lab.playback_gate = threading.RLock()
        self.monitor = GroupMonitor(self.lab)
        self.monitor.cast = self.lab.cast
        self.monitor.controller = Mock(members=['kitchen'])
        self.member = Mock(spec=['status', 'socket_client', 'cast_info', 'media_controller', 'set_volume', 'start_app'])
        self.member.status.volume_level = .5
        self.monitor.members['kitchen'] = self.member

    def test_confirmed_volume_never_loads_media(self):
        def receipt():
            self.monitor.receipts['kitchen'] = 1
        self.member.socket_client.receiver_controller.update_status.side_effect = receipt
        self.monitor.set_member_volume('kitchen', .5)
        self.member.set_volume.assert_called_once_with(.5)
        self.assertEqual(self.monitor.states['kitchen']['volume_revision'], 1)
        self.member.media_controller.play_media.assert_not_called()
        self.member.start_app.assert_not_called()

    def test_cached_matching_volume_is_not_confirmation(self):
        with self.assertRaises(TimeoutError):
            self.monitor.set_member_volume('kitchen', .5, timeout=.01)

    def test_unknown_member_cannot_receive_commands(self):
        with self.assertRaises(RuntimeError):
            self.monitor.set_member_volume('unknown', .5)
        self.member.set_volume.assert_not_called()

    def test_changed_group_cannot_receive_commands(self):
        self.lab.cast = Mock()
        with self.assertRaises(RuntimeError):
            self.monitor.set_member_volume('kitchen', .5)
        self.member.set_volume.assert_not_called()

    def test_stop_cancels_confirmation_without_retry(self):
        def cancel(value):
            self.lab.generation += 1
        self.member.set_volume.side_effect = cancel
        with self.assertRaises(RuntimeError):
            self.monitor.set_member_volume('kitchen', .5)
        self.member.set_volume.assert_called_once()

    def test_invalid_volume_is_rejected(self):
        for value in (-1, 2, float('nan')):
            with self.assertRaises(ValueError):
                self.monitor.set_member_volume('kitchen', value)
        self.member.set_volume.assert_not_called()
