"""Silent signed-link expiry guards. Mocks only; nothing resolves, loads or plays."""
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

from engine import EXPIRY_MARGIN, Lab, url_expiry, youtube_url

SOURCE = youtube_url('7vy0H3ERRiA')


def signed(expires_at, host='rr1---sn-test.googlevideo.com'):
    return f'https://{host}/videoplayback?expire={int(expires_at)}&ip=203.0.113.1&itag=140'


class UrlExpiry(unittest.TestCase):
    def test_reads_signed_expire(self):
        self.assertEqual(url_expiry(signed(1792300000)), 1792300000)

    def test_unknown_for_non_youtube_media_hosts(self):
        self.assertIsNone(url_expiry('http://192.168.1.2:8000/media/abc?expire=1792300000'))
        self.assertIsNone(url_expiry('https://example.com/videoplayback?expire=1792300000'))
        self.assertIsNone(url_expiry('https://evilgooglevideo.com/videoplayback?expire=1792300000'))

    def test_unknown_when_missing_or_malformed(self):
        self.assertIsNone(url_expiry('https://rr1.googlevideo.com/videoplayback?itag=140'))
        self.assertIsNone(url_expiry('https://rr1.googlevideo.com/videoplayback?expire=soon'))
        self.assertIsNone(url_expiry(None))


def bare_lab():
    lab = Lab.__new__(Lab)
    lab.generation = 0
    lab.log = Mock()
    lab.last_position = None
    return lab


class FreshResolve(unittest.TestCase):
    def resolved(self, expires_at, duration=3 * 3600, age=0):
        return {'source_url': SOURCE, 'resolved_at': time.time() - age,
                'url': signed(expires_at), 'duration': duration, 'id': '7vy0H3ERRiA'}

    def test_reuses_cached_link_only_with_enough_lifetime(self):
        lab = bare_lab()
        lab.resolved = self.resolved(time.time() + 6 * 3600)
        lab.resolve = Mock()
        self.assertIs(lab.ensure_source(SOURCE), lab.resolved)
        lab.resolve.assert_not_called()

    def test_short_cached_link_is_resolved_again(self):
        lab = bare_lab()
        lab.resolved = self.resolved(time.time() + 3 * 3600)  # less than mix + margin
        fresh = self.resolved(time.time() + 6 * 3600)
        lab.resolve = Mock(return_value=fresh)
        self.assertIs(lab.ensure_source(SOURCE), fresh)
        lab.resolve.assert_called_once()

    def test_late_start_needs_less_lifetime(self):
        lab = bare_lab()
        lab.resolved = self.resolved(time.time() + 3600)
        lab.resolve = Mock()
        lab.ensure_source(SOURCE, start=3 * 3600 - 1800)
        lab.resolve.assert_not_called()

    def test_already_expiring_fresh_link_is_refused(self):
        lab = bare_lab()
        lab.resolved = None
        lab.resolve = Mock(return_value=self.resolved(time.time() + EXPIRY_MARGIN / 2))
        with self.assertRaises(RuntimeError):
            lab.ensure_source(SOURCE)

    def test_mix_longer_than_fresh_link_is_allowed_and_logged(self):
        lab = bare_lab()
        lab.resolved = None
        lab.resolve = Mock(return_value=self.resolved(time.time() + 6 * 3600, duration=10 * 3600))
        lab.ensure_source(SOURCE)
        self.assertIn('source-expiry-risk', [c.args[0] for c in lab.log.call_args_list])

    def test_unknown_expiry_does_not_block(self):
        lab = bare_lab()
        lab.resolved = dict(self.resolved(0), url='https://example.com/audio.m4a')
        lab.resolve = Mock()
        lab.ensure_source(SOURCE)
        lab.resolve.assert_not_called()


def receiver_lab(expires_at, state='PAUSED', idle_reason=None, position=4200.0):
    lab = bare_lab()
    url = signed(expires_at)
    media = SimpleNamespace(content_id=url, media_custom_data={}, title='Mix', player_state=state,
                            idle_reason=idle_reason, adjusted_current_time=position, current_time=position,
                            duration=3 * 3600, media_session_id=7, media_metadata={})
    cast = Mock()
    cast.socket_client.is_connected = True
    cast.status.app_id = 'CC1AD845'
    cast.status.volume_level = .4
    cast.media_controller.status = media
    lab.cast = cast
    lab.mode = 'direct'
    return lab, media


class ExpiryFromReceiver(unittest.TestCase):
    def setUp(self):
        import engine
        self.original = engine.receiver_identity
        engine.receiver_identity = lambda media: ('hash', SOURCE, 'direct')
        self.addCleanup(setattr, engine, 'receiver_identity', self.original)

    def test_status_reports_expiry_without_url(self):
        lab, _ = receiver_lab(time.time() - 5)
        status = lab.status()
        self.assertTrue(status['source_expired'])
        self.assertNotIn('googlevideo', str(status))

    def test_fresh_link_is_not_expired(self):
        lab, _ = receiver_lab(time.time() + 3600)
        self.assertFalse(lab.status()['source_expired'])

    def test_resume_position_uses_last_confirmed_position_after_error(self):
        lab, media = receiver_lab(time.time() - 5, state='IDLE', idle_reason='ERROR', position=0)
        lab.last_position = ('hash', 6130.0)
        self.assertEqual(lab.status()['resume_position'], 6130.0)

    def test_link_that_will_not_last_the_mix_needs_refresh(self):
        # 4200 s into a 3 h mix leaves 6600 s; a link with 1 h left will not last.
        lab, _ = receiver_lab(time.time() + 3600)
        status = lab.status()
        self.assertTrue(status['refresh_needed'])
        self.assertFalse(status['source_expired'])

    def test_link_that_outlasts_the_mix_needs_no_refresh(self):
        lab, _ = receiver_lab(time.time() + 5 * 3600)
        self.assertFalse(lab.status()['refresh_needed'])

    def test_refresh_reloads_at_position_and_keeps_pause(self):
        lab, _ = refreshable(time.time() - 5, position=4200.0)
        lab.refresh_source(paused=True)
        lab.ensure_source.assert_called_once_with(SOURCE, 4200.0)
        lab.play_test.assert_called_once_with('direct', SOURCE, .4, expected_generation=0,
                                              start=4200.0, autoplay=False)

    def test_refresh_seek_target(self):
        lab, _ = refreshable(time.time() - 5, state='PLAYING')
        lab.refresh_source(position=9000, paused=False)
        self.assertEqual(lab.play_test.call_args.kwargs['start'], 9000)
        self.assertTrue(lab.play_test.call_args.kwargs['autoplay'])

    def test_refresh_refuses_link_that_lasts(self):
        lab, _ = refreshable(time.time() + 5 * 3600)
        with self.assertRaises(ValueError):
            lab.refresh_source()
        lab.ensure_source.assert_not_called()
        lab.play_test.assert_not_called()

    def test_failed_lookup_with_valid_link_allows_plain_resume(self):
        from engine import RefreshUnavailable
        lab, _ = refreshable(time.time() + 3600)
        lab.ensure_source.side_effect = RuntimeError('YouTube lookup failed')
        with self.assertRaises(RefreshUnavailable):
            lab.refresh_source()
        lab.play_test.assert_not_called()

    def test_failed_lookup_with_expired_link_is_an_error(self):
        from engine import RefreshUnavailable
        lab, _ = refreshable(time.time() - 5)
        lab.ensure_source.side_effect = RuntimeError('YouTube lookup failed')
        with self.assertRaises(RuntimeError) as raised:
            lab.refresh_source()
        self.assertNotIsInstance(raised.exception, RefreshUnavailable)

    def test_stop_during_lookup_never_falls_back_to_resume(self):
        from engine import RefreshUnavailable
        lab, _ = refreshable(time.time() + 3600)
        def stopped(*_):
            lab.generation += 1
            raise RuntimeError('Operation cancelled; no new media loaded.')
        lab.ensure_source.side_effect = stopped
        with self.assertRaises(RuntimeError) as raised:
            lab.refresh_source(expected_generation=0)
        self.assertNotIsInstance(raised.exception, RefreshUnavailable)
        lab.play_test.assert_not_called()

    def test_stop_before_refresh_load_prevents_sound(self):
        lab, _ = refreshable(time.time() - 5)
        lab.generation = 1  # Stop pressed after the refresh request was accepted.
        with self.assertRaises(RuntimeError):
            lab.refresh_source(expected_generation=0)
        lab.ensure_source.assert_not_called()
        lab.play_test.assert_not_called()

    def test_paused_play_refreshes_short_link_instead_of_play_command(self):
        lab, _ = selection_lab(time.time() + 3600)
        lab.play_selection(SOURCE)
        lab.refresh_source.assert_called_once_with(expected_generation=0)
        lab.command.assert_not_called()

    def test_paused_play_falls_back_when_no_new_link(self):
        from engine import RefreshUnavailable
        lab, _ = selection_lab(time.time() + 3600)
        lab.refresh_source.side_effect = RefreshUnavailable('no link')
        lab.play_selection(SOURCE)
        lab.command.assert_called_once_with('play')

    def test_paused_play_with_long_link_is_plain_resume(self):
        lab, _ = selection_lab(time.time() + 5 * 3600)
        lab.play_selection(SOURCE)
        lab.refresh_source.assert_not_called()
        lab.command.assert_called_once_with('play')


def refreshable(expires_at, **kwargs):
    lab, media = receiver_lab(expires_at, **kwargs)
    lab.cancelled = threading.Event()
    lab.ensure_source = Mock()
    lab.play_test = Mock()
    return lab, media


def selection_lab(expires_at):
    lab, media = receiver_lab(expires_at)
    lab.control_timeout = 3
    lab.sync_media = Mock(return_value=True)
    lab.command = Mock()
    lab.refresh_source = Mock()
    return lab, media


class LoadAtPosition(unittest.TestCase):
    def test_play_test_passes_position_autoplay_and_expected_state(self):
        lab = bare_lab()
        lab.cast = Mock()
        lab.cast.status.app_id = 'CC1AD845'
        lab.cast.cast_info.host = '192.0.2.10'
        lab.ensure_source = Mock(return_value={'title': 'Mix', 'duration': 10800, 'ext': 'm4a',
                                               'url': signed(time.time() + 6 * 3600), 'source_url': SOURCE})
        lab.preflight_direct = Mock()
        lab.wait_for_cast_ready = Mock()
        lab.confirm_loaded_media = Mock()
        lab.playback_gate = threading.RLock()
        lab.media_updated = threading.Event()
        lab.status = Mock(return_value={})
        lab.play_test('direct', SOURCE, .3, expected_generation=0, start=4200, autoplay=False)
        lab.ensure_source.assert_called_once_with(SOURCE, 4200)
        kwargs = lab.cast.media_controller.play_media.call_args.kwargs
        self.assertEqual((kwargs['current_time'], kwargs['autoplay']), (4200, False))
        self.assertEqual(lab.confirm_loaded_media.call_args.kwargs['expected'], 'PAUSED')


if __name__ == '__main__':
    unittest.main()
