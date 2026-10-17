import threading
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import Mock, patch

from engine import receiver_identity
from following import EvidenceListener
from membership import MembershipService, endpoint_info
from pychromecast.models import CastInfo, HostServiceInfo

KITCHEN = 'bbd0ffc7-b832-70e7-810f-9ea9d3552048'
BEDROOM = '9818a09f-2077-5ca6-79d7-9f88c7aca98f'
GROUP = '302fdeb3-90ee-46ca-a23b-9c1ee486485e'


def status(ids):
    return {'devices': [{'deviceId': uid, 'name': uid} for uid in ids],
            'playbackSession': {'appAllowsGrouping': True, 'isVideoContent': False, 'streamTransferSupported': True}}


def info(uid, host, kind='audio'):
    return CastInfo({HostServiceInfo(host, 8009)}, uuid.UUID(uid), 'Nest Audio', uid,
                    host, 8009, kind, 'Google')


def cast(uid, host):
    result = Mock(uuid=uuid.UUID(uid), cast_type='audio')
    result.cast_info = info(uid, host)
    result.socket_client.is_connected = True
    result.status.session_id = 'app-session'
    result.media_controller.status = SimpleNamespace(content_id='stream', title=None, media_custom_data={},
                                                     player_state='PLAYING', adjusted_current_time=40, duration=7200)
    return result


class MembershipTests(unittest.TestCase):
    def setUp(self):
        self.old = cast(KITCHEN, '192.168.8.106')
        self.infos = {KITCHEN: self.old.cast_info, BEDROOM: info(BEDROOM, '192.168.8.111')}
        self.lab = Mock(cast=self.old, generation=4, playback_gate=threading.RLock())
        self.lab.browser.devices = self.infos
        self.bridge = SimpleNamespace(lab=self.lab, lock=threading.RLock(), busy=False,
                                      closed=False, stopping=False, epoch=8, target=KITCHEN)
        self.service = MembershipService(self.bridge)
        self.controller = Mock(status=status([KITCHEN]))
        self.controller.refresh.return_value = status([KITCHEN])
        self.service.cast, self.service.controller = self.old, self.controller
        self.service._record(self.old, status([KITCHEN]))

    def request(self, ids=None):
        action = self.service.prepare(ids or [KITCHEN, BEDROOM], self.service.snapshot()['token'])
        self.bridge.epoch += 1
        return action

    def response(self):
        return {'type': 'PLAYBACK_SESSION_UPDATED', 'failedDevices': [],
                'sessionEndpoint': {'deviceId': GROUP.replace('-', ''), 'ipAddress': '192.168.8.106',
                                    'port': 32004, 'sessionId': 'app-session'}}

    def test_same_endpoint_expansion_confirms_without_reconnecting_or_retrying(self):
        action = self.request()
        self.controller.response = {'type': 'PLAYBACK_SESSION_UPDATED', 'failedDevices': []}
        self.controller.response_event.wait.return_value = True
        self.controller.refresh.side_effect = [status([KITCHEN]), status(sorted(self.infos))]
        evidence = EvidenceListener()
        self.old.media_controller.update_status.side_effect = evidence.media.set
        with patch('membership.EvidenceListener', return_value=evidence), patch('membership.pychromecast.get_chromecast_from_cast_info') as connect:
            action()
            connect.assert_not_called()
        self.assertIsNone(self.service.pending)
        self.assertFalse(self.service.uncertain)
        self.assertEqual(self.service.snapshot()['members'], sorted(self.infos))
        self.controller.set_devices.assert_called_once()
        self.old.disconnect.assert_not_called()
        self.old.media_controller.play_media.assert_not_called()

    def test_no_endpoint_still_requires_original_media_identity(self):
        action = self.request()
        self.controller.response = {'type': 'PLAYBACK_SESSION_UPDATED'}
        self.controller.response_event.wait.return_value = True
        evidence = EvidenceListener()
        def changed():
            self.old.media_controller.status.content_id = 'other-stream'
            evidence.media.set()
        self.old.media_controller.update_status.side_effect = changed
        with patch('membership.EvidenceListener', return_value=evidence):
            with self.assertRaisesRegex(RuntimeError, 'Original session'):
                action()
        self.assertTrue(self.service.uncertain)
        self.assertIsNotNone(self.service.pending)
        self.controller.set_devices.assert_called_once()

    def test_individual_membership_is_authoritative_not_selection(self):
        view = self.service.snapshot()
        self.assertEqual(view['members'], [KITCHEN])
        self.assertTrue(view['available'])
        self.bridge.target = BEDROOM
        self.assertEqual(self.service.snapshot()['members'], [KITCHEN])
        self.controller.set_devices.assert_not_called()

    def test_no_active_media_has_no_playback_checkmarks(self):
        self.old.media_controller.status.player_state = 'IDLE'
        self.assertEqual(self.service.snapshot()['members'], [])
        self.assertFalse(self.service.snapshot()['available'])

    def test_capability_and_freshness_gate_changes(self):
        for field, value in [('appAllowsGrouping', False), ('streamTransferSupported', False), ('isVideoContent', True)]:
            observation = status([KITCHEN])
            observation['playbackSession'][field] = value
            self.service._record(self.old, observation)
            self.assertFalse(self.service.snapshot()['available'])
        self.service._record(self.old, status([KITCHEN]))
        self.service.observed = (self.old, status([KITCHEN]), 0)
        self.assertFalse(self.service.snapshot()['available'])

    def test_stale_token_and_non_audio_or_empty_requests_rejected(self):
        with self.assertRaises(ValueError):
            self.service.prepare([KITCHEN], 'old-token')
        for devices in ([], [KITCHEN, KITCHEN], [GROUP]):
            with self.assertRaises(ValueError):
                self.service.prepare(devices, self.service.token)
        self.lab.browser.devices[GROUP] = info(GROUP, '192.168.8.106', 'group')
        with self.assertRaises(ValueError):
            self.service.prepare([GROUP], self.service.token)
        self.controller.set_devices.assert_not_called()

    def test_stop_before_send_cancels_without_mutation(self):
        action = self.request()
        self.bridge.stopping = True
        self.bridge.epoch += 1
        with self.assertRaises(RuntimeError):
            action()
        self.controller.set_devices.assert_not_called()
        self.assertIsNotNone(self.service.snapshot()['pending'])
        self.service.wait_for_stop()  # Nothing was sent: safe to stop original.

    def test_external_membership_change_before_send_rejected(self):
        action = self.request()
        self.controller.refresh.return_value = status([KITCHEN, BEDROOM])
        with self.assertRaises(RuntimeError):
            action()
        self.controller.set_devices.assert_not_called()

    def test_unconfirmed_response_keeps_intent_and_blocks_false_stop_success(self):
        action = self.request()
        self.controller.response_event.wait.return_value = False
        with self.assertRaises(TimeoutError):
            action()
        self.assertEqual(self.service.pending, sorted([KITCHEN, BEDROOM]))
        self.assertIsNotNone(self.service.error)
        with self.assertRaises(RuntimeError):
            self.service.wait_for_stop()
        with self.assertRaises(ValueError):
            self.service.prepare([KITCHEN], self.service.token)
        self.controller.set_devices.assert_called_once()
        self.old.disconnect.assert_not_called()

    def test_endpoint_is_only_known_local_requested_host(self):
        endpoint = self.response()['sessionEndpoint']
        good = endpoint_info(endpoint, sorted(self.infos), self.infos, self.old)
        self.assertEqual(str(good.uuid), GROUP)
        self.assertEqual(good.cast_type, 'group')
        for bad in ({'ipAddress': '8.8.8.8'}, {'ipAddress': '127.0.0.1'},
                    {'ipAddress': '192.168.8.224'}, {'port': 0}, {'port': True}):
            with self.assertRaises(ValueError):
                endpoint_info({**endpoint, **bad}, sorted(self.infos), self.infos, self.old)

    def perform_confirmed_change(self, stop_after_send=False, observed_members=None, failed_devices=None):
        action = self.request()
        requested = sorted(self.infos)
        self.controller.response = self.response()
        self.controller.response['failedDevices'] = failed_devices or []
        self.controller.response_event.wait.return_value = True
        if stop_after_send:
            def stop(*args):
                self.bridge.stopping = True
                self.bridge.epoch += 1
                self.lab.generation += 1
            self.controller.set_devices.side_effect = stop
        candidate = cast(GROUP, '192.168.8.106')
        candidate.cast_type = 'group'
        evidence = EvidenceListener()
        candidate.media_controller.update_status.side_effect = lambda: evidence.new_media_status(candidate.media_controller.status)
        new_controller = Mock()
        new_controller.refresh.return_value = status(observed_members or requested)
        with patch('membership.pychromecast.get_chromecast_from_cast_info', return_value=candidate), \
                patch('membership.install_passive_status') as passive, \
                patch('membership.EvidenceListener', return_value=evidence), \
                patch('membership.ObservedController', return_value=new_controller):
            candidate.wait.side_effect = lambda **_: passive.assert_called_once_with(candidate)
            if failed_devices or observed_members or stop_after_send:
                with self.assertRaises(RuntimeError):
                    action()
            else:
                action()
        return candidate

    def test_success_moves_only_control_endpoint_and_confirms_exact_members(self):
        revision = self.service.revision
        candidate = self.perform_confirmed_change()
        self.assertIs(self.lab.cast, candidate)
        self.assertEqual(self.bridge.target, GROUP)
        self.assertEqual(self.service.snapshot()['members'], sorted(self.infos))
        self.assertIsNone(self.service.pending)
        self.assertGreater(self.service.revision, revision)
        self.old.disconnect.assert_called_once()
        candidate.media_controller.play_media.assert_not_called()
        candidate.set_volume.assert_not_called()
        self.lab.play_selection.assert_not_called()

    def test_handoff_evidence_is_available_before_discovery_and_expires(self):
        candidate = self.perform_confirmed_change()
        self.assertEqual(self.service.confirmed_receiver()['id'], GROUP)
        self.assertEqual(self.service.confirmed_receiver()['state'], 'PLAYING')
        self.assertIn(GROUP, self.service.temporary_ids)
        evidence = self.service.handoff[1]
        evidence.media_at -= 20
        self.assertIsNone(self.service.confirmed_receiver())
        evidence.new_media_status(candidate.media_controller.status)
        candidate.status.session_id = 'different-session'
        self.assertIsNone(self.service.confirmed_receiver())
        candidate.media_controller.play_media.assert_not_called()

    def test_stop_after_send_follows_endpoint_before_stopping(self):
        candidate = self.perform_confirmed_change(stop_after_send=True)
        self.service.wait_for_stop()
        self.assertIs(self.lab.cast, candidate)
        self.assertTrue(self.bridge.stopping)
        self.assertEqual(self.service.error, 'Speaker change cancelled by Stop.')
        self.lab.stop_verified.assert_not_called()  # Bridge performs Stop after wait.

    def test_partial_success_adopts_real_endpoint_but_keeps_error_and_intent(self):
        candidate = self.perform_confirmed_change(observed_members=[KITCHEN], failed_devices=[BEDROOM])
        self.assertIs(self.lab.cast, candidate)
        self.assertEqual(self.service.snapshot()['members'], [KITCHEN])
        self.assertEqual(self.service.pending, sorted(self.infos))
        self.assertIsNotNone(self.service.error)
        self.service.wait_for_stop()  # Location known even though membership failed.
        self.service.dismiss()
        self.assertIsNone(self.service.pending)
        self.assertIsNone(self.service.error)

    def test_singleton_endpoint_must_match_requested_speaker(self):
        endpoint = self.response()['sessionEndpoint']
        with self.assertRaises(ValueError):
            endpoint_info(endpoint, [KITCHEN], self.infos, self.old)

    def test_stale_group_monitor_cannot_replace_live_controller(self):
        stale = cast(BEDROOM, '192.168.8.111')
        with self.assertRaises(RuntimeError):
            self.service.controller_for(stale)
        self.assertIs(self.service.controller, self.controller)
        self.old.unregister_handler.assert_not_called()
        stale.register_handler.assert_not_called()

    def test_only_explicit_remote_selection_clears_uncertain_context(self):
        self.service.uncertain = True
        self.service.pending = [BEDROOM]
        self.service.error = 'Unconfirmed'
        self.service.dismiss()
        self.assertTrue(self.service.uncertain)
        self.service.explicit_selection()
        self.assertFalse(self.service.uncertain)
        self.assertIsNone(self.service.token)

    def test_member_join_converges_with_status_only_no_mutation_retry(self):
        desired = sorted(self.infos)
        fingerprint = receiver_identity(self.old.media_controller.status)[0]
        self.controller.refresh.return_value = status(desired)
        with patch.object(self.service.closed, 'wait'):
            self.service._await_members(self.old, self.controller, status([KITCHEN]), desired,
                                        'app-session', fingerprint)
        self.assertEqual(self.service.snapshot()['members'], desired)
        self.controller.refresh.assert_called_once()
        self.controller.set_devices.assert_not_called()

    def test_missing_member_deadline_is_failure_without_retrying_change(self):
        fingerprint = receiver_identity(self.old.media_controller.status)[0]
        with self.assertRaises(TimeoutError):
            self.service._await_members(self.old, self.controller, status([KITCHEN]), sorted(self.infos),
                                        'app-session', fingerprint, timeout=0)
        self.controller.set_devices.assert_not_called()
        self.controller.refresh.assert_not_called()

    def test_stop_during_member_convergence_exits_without_more_queries(self):
        self.bridge.stopping = True
        fingerprint = receiver_identity(self.old.media_controller.status)[0]
        with self.assertRaisesRegex(RuntimeError, 'cancelled by Stop'):
            self.service._await_members(self.old, self.controller, status([KITCHEN]), sorted(self.infos),
                                        'app-session', fingerprint)
        self.controller.refresh.assert_not_called()


if __name__ == '__main__':
    unittest.main()
