"""Silent regression checks for the grouping research tools."""
import sys
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
sys.path.insert(0, str(Path(__file__).parent / 'tools'))
import inspect_groups
from grouping_probe import compare


class GroupingProbeTests(unittest.TestCase):
    def test_passive_installed_before_wait(self):
        cast = Mock()
        cast.status = SimpleNamespace(app_id=None, display_name=None, volume_level=.5, volume_muted=False)
        cast.media_controller.status = SimpleNamespace(player_state='UNKNOWN', title=None, media_session_id=None, current_time=0)
        info = SimpleNamespace(friendly_name='Group', model_name='Google Cast Group', cast_type='group', host='localhost', port=1234, uuid='group')
        sequence = []
        cast.wait.side_effect = lambda **kw: sequence.append('wait')
        with patch.object(inspect_groups.pychromecast, 'get_chromecast_from_cast_info', return_value=cast), patch.object(inspect_groups, 'install_passive_status', side_effect=lambda c: sequence.append('passive')), patch.object(inspect_groups, 'MultizoneController') as mz, patch.object(inspect_groups.socket, 'create_connection'), patch.object(inspect_groups.time, 'sleep'):
            mz.return_value.members = ['bedroom']
            result = inspect_groups.inspect(info, None, False)
        self.assertEqual(sequence, ['passive', 'wait'])
        self.assertEqual(result['members'], ['bedroom'])
        cast.play_media.assert_not_called()
        cast.set_volume.assert_not_called()
        cast.quit_app.assert_not_called()
        cast.disconnect.assert_called_once()
        self.assertNotIn('content_id', result['receiver'])

    def test_temporary_membership_separate_from_saved_group(self):
        device = {'uuid':'a', 'name':'Kitchen', 'configured_groups':[{'uuid':'saved'}], 'dynamic_groups':[]}
        before = {'devices':[device]}
        after = {'devices':[{**device, 'dynamic_groups':[{'uuid':'temporary'}]}]}
        changes = compare(before, after)
        self.assertEqual([r['field'] for r in changes], ['dynamic_groups'])
        self.assertEqual(device['dynamic_groups'], [])

    def test_member_order_is_not_a_membership_change(self):
        a = {'uuid':'g', 'name':'Group', 'members':['a','b']}
        self.assertEqual(compare({'devices':[a]}, {'devices':[{**a, 'members':['b','a']}]}), [])

    def test_missing_discovery_is_not_reported_as_stop(self):
        self.assertEqual(compare({'devices':[{'uuid':'a','name':'Kitchen'}]}, {'devices':[]}), [{'device':'Kitchen','discovery':'missing'}])

if __name__ == '__main__': unittest.main()
