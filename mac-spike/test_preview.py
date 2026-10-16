"""Preview cache checks: no receivers, extraction, or playback."""
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from PIL import Image
import requests
from engine import fetch_preview


class PreviewCache(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        buffer = io.BytesIO()
        Image.new('RGB', (4, 4)).save(buffer, format='PNG')
        self.preview = dict(source='https://www.youtube.com/watch?v=7vy0H3ERRiA',
                            title='Mix', channel='Creator', image=buffer.getvalue())

    def fetch(self, **kwargs):
        return fetch_preview('7vy0H3ERRiA', cache_dir=self.directory, **kwargs)

    def test_saved_preview_survives_new_call_without_network(self):
        with patch('engine._download_preview', return_value=self.preview) as download:
            self.fetch()
            self.assertEqual(self.fetch()['image'], self.preview['image'])
            download.assert_called_once()
        self.assertEqual(json.loads(next(self.directory.glob('*.json')).read_text())['title'], 'Mix')

    def test_refresh_replaces_saved_metadata(self):
        with patch('engine._download_preview', return_value=self.preview):
            self.fetch()
        with patch('engine._download_preview', return_value={**self.preview, 'title': 'Updated'}) as download:
            self.assertEqual(self.fetch(force=True)['title'], 'Updated')
            self.assertEqual(self.fetch()['title'], 'Updated')
            download.assert_called_once()

    def test_failed_refresh_keeps_saved_preview(self):
        with patch('engine._download_preview', return_value=self.preview):
            self.fetch()
        with patch('engine._download_preview', side_effect=requests.ConnectionError('Offline')):
            self.assertIn('Refresh failed', self.fetch(force=True)['cache_notice'])
            self.assertEqual(self.fetch()['title'], 'Mix')

    def test_corrupt_cache_recovers(self):
        (self.directory / '7vy0H3ERRiA.json').write_text('{broken')
        with patch('engine._download_preview', return_value=self.preview) as download:
            self.assertEqual(self.fetch()['title'], 'Mix')
            download.assert_called_once()

    def test_invalid_artwork_does_not_replace_good_cache(self):
        with patch('engine._download_preview', return_value=self.preview):
            self.fetch()
        with patch('engine._download_preview', return_value={**self.preview, 'image': b'bad'}):
            self.assertEqual(self.fetch(force=True)['image'], self.preview['image'])

    def test_legacy_title_restores_unique_catalog_source(self):
        from engine import cached_source_for_title, receiver_preview
        with patch('engine._download_preview', return_value=self.preview):
            self.fetch()
        self.assertEqual(cached_source_for_title('Mix', self.directory), self.preview['source'])
        self.assertIsNone(cached_source_for_title('Other song', self.directory))
        preview = receiver_preview({'title': 'Mix'}, cache_dir=self.directory)
        self.assertEqual(preview['image'], self.preview['image'])
        self.assertEqual(preview['source'], '')  # Artwork lookup itself never selects a source.
        duplicate = json.loads(next(self.directory.glob('*.json')).read_text())
        duplicate['source'] = 'https://www.youtube.com/watch?v=MpoIuYCfEvQ'
        (self.directory / 'MpoIuYCfEvQ.json').write_text(json.dumps(duplicate))
        self.assertIsNone(cached_source_for_title('Mix', self.directory))
