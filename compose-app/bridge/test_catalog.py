import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace
from catalog import Catalog, channel_url

class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.url='https://www.youtube.com/@example'
        self.catalog=Catalog(Path(self.tmp.name)/'catalog.json',[{'url':self.url,'name':'Example','category':'Noir Jazz'}])
        self.payload={'channel_id':'UC'+'a'*22,'channel':'Example','entries':[{'id':'12345678901','title':'Short jazz','duration':120},{'entries':[{'id':'12345678901','title':'Short jazz','duration':120},{'id':'abcdefghijk','title':'Long noir jazz','duration':7200}]}]}
    def run_import(self):
        with patch('catalog.subprocess.run',return_value=SimpleNamespace(returncode=0,stdout=json.dumps(self.payload))) as run:
            self.catalog.import_channel(self.url)
            args=run.call_args.args[0]
            self.assertIn('--skip-download',args)
            self.assertNotIn('--playlist-end',args)
    def test_import_retains_short_videos_and_deduplicates_tabs(self):
        self.run_import()
        data=self.catalog.snapshot()
        self.assertEqual(len(data['mixes']),2)
        self.assertEqual(data['mixes'][0]['duration'],120)
        self.assertEqual(data['mixes'][1]['category'],'Noir Jazz')
        self.assertIn('i.ytimg.com',data['mixes'][1]['thumbnail'])
    def test_refresh_merges_new_and_updated_entries(self):
        self.run_import()
        self.payload['entries']=[{'id':'abcdefghijk','title':'Updated','duration':7200},{'id':'zyxwvutsrqp','title':'New','duration':300}]
        self.run_import()
        self.assertEqual(len(self.catalog.snapshot()['mixes']),3)
        self.assertEqual(next(m for m in self.catalog.snapshot()['mixes'] if m['id']=='abcdefghijk')['title'],'Updated')
    def test_failure_keeps_previous_cache(self):
        self.run_import();before=self.catalog.snapshot()
        self.payload['entries']=[]
        with self.assertRaises(RuntimeError): self.run_import()
        self.assertEqual(self.catalog.snapshot(),before)
        loaded=Catalog(self.catalog.path,[])
        self.assertEqual(loaded.snapshot(),before)
    def test_canonical_alias_collapses(self):
        self.run_import()
        alias='https://www.youtube.com/channel/UC'+'a'*22
        self.catalog.data['channels'].append({'url':alias,'name':'Alias'})
        with patch('catalog.subprocess.run',return_value=SimpleNamespace(returncode=0,stdout=json.dumps(self.payload))): self.catalog.import_channel(alias)
        self.assertEqual(len(self.catalog.snapshot()['channels']),1)
        self.assertEqual(len(self.catalog.snapshot()['mixes']),2)
    def test_channel_validation(self):
        for url in ['http://www.youtube.com/@example','https://evil.test/@example','https://www.youtube.com/watch?v=12345678901','https://www.youtube.com/@example/videos']:
            with self.assertRaises(ValueError):channel_url(url)
        self.assertEqual(channel_url(self.url+'/'),self.url)
