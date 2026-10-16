"""Receiver/selection synchronization without connecting to any speaker."""
import hashlib
from types import SimpleNamespace
import unittest
from unittest.mock import Mock
from app import App
from engine import receiver_identity, youtube_url


class ReceiverIdentity(unittest.TestCase):
    def test_tag_survives_rejoin_and_matches_exact_content(self):
        content = 'https://example.test/media'
        media = SimpleNamespace(content_id=content, media_custom_data={'moozaq': {
            'source': youtube_url('7vy0H3ERRiA'), 'route': 'direct',
            'content_hash': hashlib.sha256(content.encode()).hexdigest()}})
        self.assertEqual(receiver_identity(media)[1:], (youtube_url('7vy0H3ERRiA'), 'direct'))
        media.content_id = 'https://example.test/other'
        self.assertEqual(receiver_identity(media)[1:], (None, 'External / unknown'))

    def test_no_guess_from_title(self):
        media = SimpleNamespace(content_id='external', title='No Rush. Just Coffee.')
        self.assertIsNone(receiver_identity(media)[1])


class ReceiverSelection(unittest.TestCase):
    def setUp(self):
        self.app = App.__new__(App)
        self.app.receiver_track = None
        self.app.player_generation = 0
        for name in ('source', 'preview_title', 'poster', 'playing_artwork', 'load_receiver_artwork'):
            setattr(self.app, name, Mock())
        self.status = dict(connected=True, device='Office', app='Receiver', media_session=1,
                           content_key='abc', title='External song', state='PLAYING')

    def test_external_track_updates_player_without_touching_browser(self):
        self.app.sync_receiver_track(self.status)
        self.app.load_receiver_artwork.assert_called_once_with(self.status)
        self.app.source.set.assert_not_called()
        self.app.preview_title.set.assert_not_called()
        self.assertEqual(self.app.player_generation, 1)

    def test_known_track_also_leaves_browser_selection_alone(self):
        self.app.sync_receiver_track({**self.status, 'source': youtube_url('MpoIuYCfEvQ')})
        self.app.source.set.assert_not_called()
        self.app.load_receiver_artwork.assert_called_once()

    def test_position_updates_do_not_reload_artwork(self):
        self.app.sync_receiver_track(self.status)
        self.app.sync_receiver_track({**self.status, 'position': 120, 'state': 'PAUSED'})
        self.app.load_receiver_artwork.assert_called_once()
        self.app.sync_receiver_track({**self.status, 'content_key': 'new', 'title': 'Next track'})
        self.assertEqual(self.app.load_receiver_artwork.call_count, 2)
        self.app.source.set.assert_not_called()

    def test_disconnect_invalidates_late_player_artwork(self):
        self.app.sync_receiver_track(self.status)
        self.app.sync_receiver_track({'connected': False})
        self.assertEqual(self.app.player_generation, 2)
        self.app.playing_artwork.configure.assert_called_with(image='', text='No playback')
