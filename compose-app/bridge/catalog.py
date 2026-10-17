"""Persistent metadata-only YouTube channel catalog; never controls playback."""
import json
import re
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import urlparse


def channel_url(value):
    parsed = urlparse(value.strip())
    path = parsed.path.rstrip('/')
    if parsed.scheme != 'https' or parsed.hostname not in ('youtube.com', 'www.youtube.com'):
        raise ValueError('Use an https://www.youtube.com channel URL.')
    if not re.fullmatch(r'/(@[\w.\-]+|channel/UC[\w\-]{22})', path):
        raise ValueError('Use the channel link, without /videos or a video URL.')
    return 'https://www.youtube.com' + path


def entries(data):
    for item in data.get('entries') or []:
        if not isinstance(item, dict):
            continue
        if 'entries' in item:
            yield from entries(item)
        else:
            yield item


def classify(title, fallback):
    text = title.lower()
    rules = [('Psychedelic Dub', ('dub',)), ('Persian / Middle Eastern Jazz', ('persian', 'iranian', 'middle eastern', 'syrian')),
             ('Dark Jazz / Trip-Hop', ('trip-hop', 'trip hop')), ('Desert / Psychedelia', ('desert', 'psychedelic', 'stoner')),
             ('Spy / Space-Age Lounge', ('spy', 'space age', 'space-age', 'orbital', 'sci-fi')),
             ('Vintage Lounge / Exotica', ('exotica', 'lounge')), ('Noir Jazz', ('noir', 'crime jazz', 'dark jazz')),
             ('Cozy Jazz / Swing', ('cozy', 'swing', 'warm', 'jazz', 'vintage'))]
    return next((name for name, words in rules if any(w in text for w in words)), fallback)


class Catalog:
    def __init__(self, path, seeds):
        self.path = Path(path)
        self.lock = threading.RLock()
        self.busy = False
        self.refreshing_channels = []
        self.message = 'Catalog ready'
        self.revision = 1
        try:
            self.data = json.loads(self.path.read_text())
        except (OSError, ValueError):
            self.data = {'channels': [], 'mixes': []}
        for seed in seeds:
            if not any(c['url'] == seed['url'] for c in self.data['channels']):
                self.data['channels'].append(dict(seed, count=0, refreshed=None, error=None))
        self.save()

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_suffix('.tmp')
        temp.write_text(json.dumps(self.data, ensure_ascii=False))
        temp.replace(self.path)

    def status(self):
        with self.lock:
            return {'catalog_revision': self.revision, 'importing': self.busy, 'catalog_message': self.message, 'refreshing_channels': list(self.refreshing_channels)}

    def snapshot(self):
        with self.lock:
            return json.loads(json.dumps(self.data))

    def import_channel(self, url):
        result = subprocess.run([sys.executable, '-m', 'yt_dlp', '--flat-playlist', '--skip-download',
                                 '--ignore-errors', '--dump-single-json', '--socket-timeout', '20', url],
                                capture_output=True, text=True, timeout=300)
        if result.returncode or not result.stdout.strip():
            raise RuntimeError('YouTube metadata could not be read; previous entries were kept.')
        payload = json.loads(result.stdout)
        channel_id = payload.get('channel_id') or payload.get('id')
        if not re.fullmatch(r'UC[\w-]{22}', channel_id or ''):
            raise ValueError('YouTube did not return a valid channel identity.')
        creator = payload.get('channel') or payload.get('uploader') or payload.get('title') or url
        with self.lock:
            source = next(c for c in self.data['channels'] if c['url'] == url)
            fallback = source.get('category', 'Uncategorized')
        found = {}
        for item in entries(payload):
            vid = item.get('id', '')
            if not re.fullmatch(r'[\w-]{11}', vid):
                continue
            if not item.get('title') or item['title'] in ('[Private video]', '[Deleted video]'):
                continue
            thumbs = [t for t in item.get('thumbnails', []) if isinstance(t, dict) and urlparse(t.get('url', '')).hostname in ('i.ytimg.com', 'img.youtube.com')]
            thumbnail = max(thumbs, key=lambda t: t.get('width') or 0).get('url') if thumbs else f'https://i.ytimg.com/vi/{vid}/hqdefault.jpg'
            found[vid] = {'id': vid, 'title': item['title'], 'creator': creator, 'channel_id': channel_id,
                          'artwork': -1, 'thumbnail': thumbnail, 'duration': item.get('duration'),
                          'category': classify(item['title'], fallback)}
        if not found:
            raise RuntimeError('No public videos returned; previous entries were kept.')
        with self.lock:
            existing = {m['id']: m for m in self.data['mixes']}
            existing.update(found)
            self.data['mixes'] = list(existing.values())
            source.update(name=creator, id=channel_id, count=len(found), refreshed=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), error=None)
            # Handles and canonical URLs for the same channel collapse to one source.
            self.data['channels'] = [c for c in self.data['channels'] if c is source or c.get('id') != channel_id]
            self.save()
            self.revision += 1
        return len(found)

    def refresh(self, url=None):
        if url:
            url = channel_url(url)
        with self.lock:
            if self.busy:
                raise ValueError('A catalog import is already running.')
            if url and not any(c['url'] == url for c in self.data['channels']):
                self.data['channels'].append({'url': url, 'name': url.rsplit('/',1)[-1], 'category':'Uncategorized', 'count':0})
                self.save()
                self.revision += 1
            targets = [url] if url else [c['url'] for c in self.data['channels']]
            self.busy = True
            self.refreshing_channels = list(targets)
            self.message = 'Importing channel metadata…'
        def run():
            failed = 0
            try:
                for index, target in enumerate(targets):
                    with self.lock:
                        self.message = f'Importing channel {index+1} of {len(targets)}…'
                    try:
                        self.import_channel(target)
                    except Exception:
                        failed += 1
                        with self.lock:
                            source = next((c for c in self.data['channels'] if c['url'] == target), None)
                            if source:
                                source['error'] = 'Import incomplete. Retry to refresh; existing mixes retained.'
                            self.save()
                            self.revision += 1
                    finally:
                        with self.lock:
                            if target in self.refreshing_channels:
                                self.refreshing_channels.remove(target)
            finally:
                with self.lock:
                    self.busy = False
                    self.refreshing_channels = []
                    self.message = f'{len(self.data["mixes"])} videos cached' + (f' · {failed} channel imports need retry' if failed else ' · Up to date')
        threading.Thread(target=run, daemon=True).start()
