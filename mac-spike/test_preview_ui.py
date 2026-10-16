"""Silent checks for preview selection; no Tk window or Cast connection."""
import unittest
from unittest.mock import Mock, patch
from app import App
from engine import youtube_url


class PreviewSelection(unittest.TestCase):
    def make_app(self):
        app = App.__new__(App)
        app.source = Mock()
        app.source.get.return_value = '7vy0H3ERRiA'
        app.preview_source = youtube_url('MpoIuYCfEvQ')
        app.preview_generation = 4
        source = youtube_url('7vy0H3ERRiA')
        app.preview_memory = {source: ({'source': source, 'title': 'Saved'}, Mock())}
        app.show_preview = Mock()
        app.preview_title = Mock()
        app.preview_channel = Mock()
        app.preview_channel.get.return_value = 'Creator'
        app.poster = Mock()
        return app

    def test_switch_back_displays_saved_preview_without_worker_or_loading_state(self):
        app = self.make_app()
        with patch('app.threading.Thread') as worker:
            app.load_preview()
            worker.assert_not_called()
        app.show_preview.assert_called_once_with(*app.preview_memory[app.preview_source])
        app.preview_title.set.assert_not_called()
        app.poster.configure.assert_not_called()
        self.assertEqual(app.preview_generation, 5)

    def test_explicit_refresh_keeps_saved_artwork_and_starts_worker(self):
        app = self.make_app()
        with patch('app.threading.Thread') as worker:
            app.load_preview(force=True)
            worker.return_value.start.assert_called_once()
        app.show_preview.assert_called_once()
        app.poster.configure.assert_not_called()
