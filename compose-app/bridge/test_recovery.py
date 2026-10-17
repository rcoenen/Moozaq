import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from recovery import RemotePreferences

class RecoveryTests(unittest.TestCase):
    def test_discovery_hint_is_only_an_address_not_a_selected_identity(self):
        from unittest.mock import Mock
        browser = Mock()
        self.prefs.data = {'speaker_id': 'group', 'fallback_host': '192.168.8.224'}
        self.prefs.seed_discovery(browser)
        browser.host_browser.add_hosts.assert_called_once_with(['192.168.8.224'])
        self.assertEqual(self.prefs.data['speaker_id'], 'group')
        browser.reset_mock()
        self.prefs.data['fallback_host'] = 'https://not-a-device/'
        self.prefs.seed_discovery(browser)
        browser.host_browser.add_hosts.assert_not_called()

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.prefs=RemotePreferences(Path(self.temp.name)/'prefs.json')
        self.infos={'kitchen':SimpleNamespace(cast_type='audio',host='kitchen'),
                    'office':SimpleNamespace(cast_type='audio',host='office'),
                    'group':SimpleNamespace(cast_type='group',host='kitchen')}

    def test_group_remembers_coordinator_as_fallback_across_restart(self):
        self.prefs.remember('group',self.infos['group'],['kitchen','office'],self.infos)
        reloaded=RemotePreferences(self.prefs.path)
        self.assertEqual(reloaded.data['fallback_speaker_id'],'kitchen')
        self.assertEqual(reloaded.data['speaker_kind'],'group')

    def test_only_recovers_absent_group_after_grace_period(self):
        self.prefs.remember('group',self.infos['group'],['kitchen'],self.infos)
        physical={k:v for k,v in self.infos.items() if k!='group'}
        self.assertIsNone(self.prefs.replacement('group',physical,False,False,100))
        self.assertIsNone(self.prefs.replacement('group',physical,False,False,114))
        self.assertEqual(self.prefs.replacement('group',physical,False,False,116),'kitchen')

    def test_no_recovery_for_healthy_busy_or_rediscovered_target(self):
        self.prefs.remember('group',self.infos['group'],['kitchen'],self.infos)
        for connected,busy,infos in ((True,False,{}),(False,True,{}),(False,False,self.infos)):
            self.prefs.missing_since=1
            self.assertIsNone(self.prefs.replacement('group',infos,connected,busy,100))
            self.assertIsNone(self.prefs.missing_since)

    def test_offline_home_and_unknown_legacy_target_do_not_guess(self):
        self.prefs.data={'speaker_id':'old'}
        self.assertIsNone(self.prefs.replacement('old',self.infos,False,False,100))
        self.prefs.remember('group',self.infos['group'],['kitchen'],self.infos)
        self.prefs.missing_since=1
        self.assertIsNone(self.prefs.replacement('group',{},False,False,100))

    def test_existing_physical_preference_is_preserved_if_still_member(self):
        self.prefs.remember('office',self.infos['office'],[],self.infos)
        self.prefs.remember('group',self.infos['group'],['kitchen','office'],self.infos)
        self.assertEqual(self.prefs.data['fallback_speaker_id'],'office')

from recovery import SessionRecovery


def observed(uid='old', *, members=None, state='PLAYING', position=100, fresh=True, key='fingerprint'):
    return dict(id=uid, fresh=fresh, connected=fresh, state=state, position=position,
                content_key=key, members=members or [], title='Our mix', source='public-video', duration=3600)


class SessionRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.recovery = SessionRecovery()
        self.old = observed(members=['kitchen', 'office'])
        self.recovery.step('old', [self.old], 100)

    def test_follows_group_to_group_using_fresh_identity_and_members(self):
        moved = observed('new-group', members=['kitchen', 'office', 'fridge'], position=108)
        self.assertEqual(self.recovery.step('old', [moved], 108), 'new-group')
        self.assertTrue(self.recovery.recovering)
        self.recovery.step('new-group', [moved], 109)
        self.assertFalse(self.recovery.recovering)

    def test_contracting_group_can_become_individual_speaker(self):
        self.assertEqual(self.recovery.step('old', [observed('office', position=105)], 105), 'office')

    def test_does_not_follow_wrong_stream_stale_members_or_restarted_music(self):
        cases = [observed('new', members=['kitchen'], key='different'),
                 observed('new', members=['stranger']),
                 observed('new', members=['kitchen'], fresh=False),
                 observed('new', members=['kitchen'], position=0),
                 observed('new', members=['kitchen'], position=900)]
        for row in cases:
            with self.subTest(row=row):
                self.assertIsNone(self.recovery.step('old', [row], 105))
                self.assertTrue(self.recovery.recovering)

    def test_ambiguity_never_chooses_a_candidate(self):
        rows = [observed('one', members=['kitchen']), observed('two', members=['office'])]
        self.assertIsNone(self.recovery.step('old', rows, 105))

    def test_last_track_is_retained_as_unconfirmed_and_eventually_unavailable(self):
        self.recovery.step('old', [], 105)
        for now, label in ((110, 'Reconnecting…'), (166, 'Playback unavailable · last known track')):
            result = self.recovery.present({'connected': True, 'state': 'IDLE'}, now)
            self.assertFalse(result['connected'])
            self.assertEqual(result['state'], 'UNKNOWN')
            self.assertEqual(result['title'], 'Our mix')
            self.assertEqual(result['position'], 100)
            self.assertEqual(result['recovery'], label)

    def test_fresh_idle_members_end_recovery_after_grace_without_playing(self):
        rows = [observed(uid, state='IDLE') for uid in ['kitchen', 'office']]
        self.assertIsNone(self.recovery.step('old', rows, 105))
        self.assertEqual(self.recovery.step('old', rows, 121), 'kitchen')
        self.assertIsNone(self.recovery.last)
        self.assertFalse(self.recovery.recovering)

    def test_one_idle_member_is_not_proof_session_ended(self):
        rows = [observed('office', state='IDLE')]
        self.recovery.step('old', rows, 105)
        self.assertIsNone(self.recovery.step('old', rows, 121))
        self.assertTrue(self.recovery.recovering)

    def test_long_outage_does_not_follow_coincidental_match(self):
        self.recovery.step('old', [], 105)
        self.assertIsNone(self.recovery.step('old', [observed('office', position=240)], 240))

    def test_changed_candidate_before_connection_is_not_confirmed(self):
        self.recovery.step('old', [observed('office', position=105)], 105)
        self.recovery.step('office', [observed('office', key='other-music')], 106)
        self.assertTrue(self.recovery.recovering)
        self.assertEqual(self.recovery.last['content_key'], 'fingerprint')

    def test_explicit_selection_or_stop_clears_old_intent(self):
        self.recovery.step('old', [], 105)
        self.recovery.clear()
        self.assertIsNone(self.recovery.step('office', [], 110))
        self.assertFalse(self.recovery.recovering)
