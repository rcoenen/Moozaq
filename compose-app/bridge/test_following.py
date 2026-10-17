import hashlib
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from following import EvidenceListener, TemporaryGroupFollower, matches_session


def media(content='same-stream', position=112, state='PLAYING'):
    return SimpleNamespace(content_id=content, title=None, media_custom_data={},
                           adjusted_current_time=position, player_state=state)


class TemporaryGroupFollowing(unittest.TestCase):
    def setUp(self):
        self.previous = {'content_key': hashlib.sha256(b'same-stream').hexdigest(),
                         'state': 'PLAYING', 'position': 100, 'connected': True}
        self.old = Mock(cast_type='audio')
        self.candidate = Mock(uuid='temporary-group')
        self.candidate.socket_client.is_connected = True
        self.lab = Mock(cast=self.old, selected_id='kitchen', generation=4,
                        playback_gate=threading.RLock())
        self.lab.status.return_value = {'state': 'UNKNOWN'}
        self.bridge = SimpleNamespace(lab=self.lab, lock=threading.RLock(), busy=False,
                                      epoch=3, target='kitchen', closed=False)
        self.follower = TemporaryGroupFollower(self.bridge, clock=lambda: 10)

    def test_same_content_and_membership_survives_changed_session_id(self):
        self.assertTrue(matches_session(self.previous, media(), ['kitchen', 'bedroom'], 'kitchen', 12))

    def test_unrelated_or_restarted_session_is_not_followed(self):
        for current, members in [(media('other-stream'), ['kitchen']),
                                 (media(), ['bedroom']), (media(position=0), ['kitchen']),
                                 (media(state='IDLE'), ['kitchen'])]:
            self.assertFalse(matches_session(self.previous, current, members, 'kitchen', 12))

    def test_passive_adoption_changes_actual_control_target_without_load(self):
        self.assertTrue(self.follower._adopt('kitchen', self.old, self.candidate, 3))
        self.assertIs(self.lab.cast, self.candidate)
        self.assertEqual(self.bridge.target, 'temporary-group')
        self.assertEqual(self.lab.selected_id, 'temporary-group')
        self.assertEqual(self.lab.generation, 5)
        self.old.disconnect.assert_called_once()
        self.lab.connect.assert_not_called()
        self.lab.play_selection.assert_not_called()
        self.candidate.play_media.assert_not_called()
        self.candidate.set_volume.assert_not_called()

    def test_stop_selection_or_pending_command_invalidates_handoff(self):
        for change in ({'epoch': 4}, {'target': 'office'}, {'busy': True}, {'closed': True}):
            with self.subTest(change=change):
                original = {key: getattr(self.bridge, key) for key in change}
                for key, value in change.items():
                    setattr(self.bridge, key, value)
                self.assertFalse(self.follower._adopt('kitchen', self.old, self.candidate, 3))
                self.assertIs(self.lab.cast, self.old)
                for key, value in original.items():
                    setattr(self.bridge, key, value)
        self.old.disconnect.assert_not_called()

    def test_new_individual_playback_wins(self):
        self.lab.status.return_value = self.previous
        self.assertFalse(self.follower._adopt('kitchen', self.old, self.candidate, 3))

    def test_startup_without_prior_session_never_guesses_group(self):
        with patch('following.threading.Thread') as thread:
            self.follower.step({'connected': True, 'state': 'UNKNOWN'})
            thread.assert_not_called()

    def test_recent_observed_session_triggers_only_read_only_probe(self):
        self.follower.step(self.previous)
        with patch('following.threading.Thread') as thread:
            self.follower.step({'state': 'UNKNOWN'})
            thread.assert_called_once()
            self.assertEqual(thread.call_args.kwargs['target'], self.follower._probe)
        self.lab.connect.assert_not_called()

    def test_old_observation_expires(self):
        self.follower.step(self.previous)
        self.follower.clock = lambda: 80
        with patch('following.threading.Thread') as thread:
            self.follower.step({'state': 'UNKNOWN'})
            thread.assert_not_called()

    def test_saved_group_is_not_a_dynamic_candidate(self):
        self.lab.browser.devices = {'kitchen': SimpleNamespace(uuid='kitchen', services={'live-service'}),
                                   'all': SimpleNamespace(uuid='all', cast_type='group')}
        with patch('following.dial._get_status', return_value=(None, {'multizone': {'groups': [{'uuid': 'all'}]}})), \
                patch('following.pychromecast.get_chromecast_from_cast_info') as create:
            self.follower._probe('kitchen', self.old, self.previous, 9, 3)
            create.assert_not_called()

    def test_ambiguous_dynamic_groups_do_not_connect(self):
        self.lab.browser.devices = {'kitchen': SimpleNamespace(uuid='kitchen', services={'live-service'}),
                                   'one': SimpleNamespace(uuid='one', cast_type='group'),
                                   'two': SimpleNamespace(uuid='two', cast_type='group')}
        groups = {'multizone': {'dynamic_groups': [{'uuid': 'one'}, {'uuid': 'two'}]}}
        with patch('following.dial._get_status', return_value=(None, groups)), \
                patch('following.pychromecast.get_chromecast_from_cast_info') as create:
            self.follower._probe('kitchen', self.old, self.previous, 9, 3)
            create.assert_not_called()

    def test_probe_adopts_only_after_fresh_membership_and_media(self):
        self.lab.browser.devices = {'kitchen': SimpleNamespace(uuid='kitchen', services={'live-service'}),
                                   'temporary-group': SimpleNamespace(uuid='temporary-group', cast_type='group')}
        groups = {'multizone': {'dynamic_groups': [{'uuid': 'temporary-group'}]}}
        evidence = EvidenceListener()
        controller = Mock(members=['kitchen', 'bedroom'])
        controller.update_members.side_effect = evidence.members.set
        self.candidate.media_controller.status = media()
        self.candidate.media_controller.update_status.side_effect = evidence.media.set
        with patch('following.dial._get_status', return_value=(None, groups)), \
                patch('following.pychromecast.get_chromecast_from_cast_info', return_value=self.candidate), \
                patch('following.MultizoneController', return_value=controller), \
                patch('following.EvidenceListener', return_value=evidence), \
                patch('following.install_passive_status') as install:
            self.candidate.wait.side_effect = lambda **_: install.assert_called_once_with(self.candidate)
            self.follower._probe('kitchen', self.old, self.previous, 9, 3)
        self.assertIs(self.lab.cast, self.candidate)
        controller.update_members.assert_called_once()
        self.candidate.media_controller.update_status.assert_called_once()
        self.candidate.media_controller.play_media.assert_not_called()
        self.candidate.media_controller.play.assert_not_called()
        self.candidate.disconnect.assert_not_called()

    def test_failed_candidate_is_detached_without_affecting_original(self):
        self.lab.browser.devices = {'kitchen': SimpleNamespace(uuid='kitchen', services={'live-service'}),
                                   'temporary-group': SimpleNamespace(uuid='temporary-group', cast_type='group')}
        groups = {'multizone': {'dynamic_groups': [{'uuid': 'temporary-group'}]}}
        self.candidate.wait.side_effect = TimeoutError('disconnected')
        with patch('following.dial._get_status', return_value=(None, groups)), \
                patch('following.pychromecast.get_chromecast_from_cast_info', return_value=self.candidate), \
                patch('following.install_passive_status'):
            self.follower._probe('kitchen', self.old, self.previous, 9, 3)
        self.assertIs(self.lab.cast, self.old)
        self.candidate.disconnect.assert_called_once()
        self.old.disconnect.assert_not_called()


if __name__ == '__main__':
    unittest.main()
