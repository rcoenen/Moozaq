import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from outputs import OutputMonitor, OutputEntry, OutputListener, resolve_activity


def row(uid, state='UNKNOWN', members=None, connected=True):
    return dict(id=uid, connected=connected, state=state, members=members or [], title=uid,
                volume=.2, muted=False, volume_revision=0)


class OutputTests(unittest.TestCase):
    def test_temporary_classification_uses_receiver_topology_not_group_name(self):
        uid='302fdeb3-90ee-46ca-a23b-9c1ee486485e'
        monitor=OutputMonitor(Mock())
        info=SimpleNamespace(uuid='physical',cast_type='audio',services=set())
        data={'multizone':{'dynamic_groups':[{'uuid':uid.replace('-','')}],
                           'groups':[{'uuid':'saved-group'}]}}
        with patch('outputs.dial._get_status',return_value=(None,data)) as query:
            monitor.read_dynamic_groups(info)
            monitor.read_dynamic_groups(info)
            query.assert_called_once()
        self.assertEqual(monitor.temporary_ids(),{uid})

    def test_terminated_monitor_connection_is_replaced_passively(self):
        lab = Mock()
        monitor = OutputMonitor(lab)
        old = Mock(cast_type='audio', uuid='office')
        old.socket_client.stop.is_set.return_value = True
        monitor.entries['office'] = OutputEntry(old)
        new = Mock(cast_type='audio', uuid='office')
        order = []
        new.wait.side_effect = lambda **_: order.append('wait')
        with patch('outputs.pychromecast.get_chromecast_from_cast_info', return_value=new), \
             patch('outputs.install_passive_status', side_effect=lambda _: order.append('guard')):
            monitor.poll(SimpleNamespace(uuid='office'))
        self.assertIs(monitor.entries['office'].cast, new)
        self.assertEqual(order, ['guard', 'wait'])
        old.disconnect.assert_called_once()
        new.media_controller.play_media.assert_not_called()
        new.set_volume.assert_not_called()

    def test_recovery_evidence_needs_fresh_media_and_group_membership(self):
        lab = Mock()
        lab.browser.devices = {'group': SimpleNamespace(uuid='group')}
        monitor = OutputMonitor(lab)
        cast = Mock(cast_type='group', uuid='group')
        cast.status.app_id = 'receiver'
        cast.media_controller.status.player_state = 'PLAYING'
        entry = OutputEntry(cast)
        monitor.entries['group'] = entry
        entry.receiver_at = entry.media_at = time.monotonic()
        with patch('outputs.receiver_identity', return_value=('hash', 'public-video', 'route')):
            self.assertFalse(monitor.recovery_rows()[0]['fresh'])
            entry.members_at = time.monotonic()
            entry.members = ['kitchen']
            self.assertEqual(monitor.recovery_rows()[0]['content_key'], 'hash')
            entry.media_at -= 20
            row = monitor.recovery_rows()[0]
            self.assertFalse(row['fresh'])
            self.assertIsNone(row['content_key'])

    def test_simultaneous_group_and_individual_music(self):
        rows=resolve_activity([row('kitchen'),row('bedroom'),row('office','PLAYING'),row('group','PLAYING',['kitchen','bedroom'])],
                              dict(kitchen='audio',bedroom='audio',office='audio',group='group'))
        self.assertEqual([r['session_id'] for r in rows], ['group','group','office','group'])
        self.assertEqual(rows[0]['control_id'],'group')
        self.assertEqual(rows[0]['volume'],.2)

    def test_configured_idle_group_does_not_imply_playback(self):
        rows=resolve_activity([row('kitchen'),row('group','IDLE',['kitchen'])],dict(kitchen='audio',group='group'))
        self.assertIsNone(rows[0]['session_id'])
        self.assertEqual(rows[0]['state'],'UNKNOWN')

    def test_ambiguous_groups_and_offline_members_are_not_claimed_playing(self):
        for connected in (True,False):
            rows=resolve_activity([row('kitchen',connected=connected),row('a','PLAYING',['kitchen']),row('b','PLAYING',['kitchen'])],
                                  dict(kitchen='audio',a='group',b='group'))
            self.assertEqual(rows[0]['state'],'UNKNOWN')

    def test_stale_media_and_volume_are_hidden(self):
        cast=Mock(cast_type='group',uuid='group')
        cast.status.app_id='receiver'
        cast.status.volume_level=.2
        cast.media_controller.status.player_state='PLAYING'
        entry=OutputEntry(cast)
        entry.receiver_at=entry.media_at=entry.members_at=time.monotonic()-20
        entry.members=['kitchen']
        result=entry.snapshot(time.monotonic())
        self.assertFalse(result['connected'])
        self.assertIsNone(result['volume'])
        self.assertEqual(result['state'],'UNKNOWN')
        self.assertEqual(result['members'],[])

    def test_cast_receipt_updates_current_volume_without_media_event(self):
        cast=Mock(cast_type='audio',uuid='kitchen')
        cast.status.volume_level=.2
        entry=OutputEntry(cast)
        OutputListener(entry).new_cast_status(cast.status)
        self.assertEqual(entry.snapshot(time.monotonic())['volume'],.2)
        cast.status.volume_level=.37
        OutputListener(entry).new_cast_status(cast.status)
        self.assertEqual(entry.snapshot(time.monotonic())['volume'],.37)

    def test_poll_installs_passive_guard_before_connection_and_never_mutates(self):
        lab=Mock()
        monitor=OutputMonitor(lab)
        info=SimpleNamespace(uuid='kitchen')
        cast=Mock(cast_type='audio',uuid='kitchen')
        events=[]
        cast.wait.side_effect=lambda **_:events.append('wait')
        with patch('outputs.pychromecast.get_chromecast_from_cast_info',return_value=cast), patch('outputs.install_passive_status',side_effect=lambda _:events.append('guard')):
            monitor.poll(info)
        self.assertEqual(events,['guard','wait'])
        cast.set_volume.assert_not_called()
        cast.start_app.assert_not_called()
        cast.media_controller.play_media.assert_not_called()
        cast.media_controller.stop.assert_not_called()

    def test_volume_is_bound_to_observed_device_and_fresh_confirmation(self):
        lab=Mock(generation=2,playback_gate=threading.RLock())
        monitor=OutputMonitor(lab)
        cast=Mock(cast_type='audio',uuid='kitchen')
        cast.status.volume_level=.2
        entry=OutputEntry(cast)
        entry.receiver_at=time.monotonic()
        monitor.entries['kitchen']=entry
        def confirm(context,value):
            self.assertIs(context.cast,cast)
            self.assertEqual(context.generation,2)
            lab.generation=3
            self.assertEqual(context.generation,3)
        entry.tracker.set_confirmed=Mock(side_effect=confirm)
        monitor.set_volume('kitchen',.2)
        self.assertEqual(entry.revision,1)
        with self.assertRaises(ValueError):monitor.set_volume('missing',.2)
