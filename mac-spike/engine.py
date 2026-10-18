"""Cast experiments: extraction, direct handoff, HTTP relay, and completed-file cache."""
from __future__ import annotations

import json
import hashlib
import base64
import io
import tempfile
from PIL import Image
import mimetypes
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import pychromecast
import requests
import zeroconf

ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'data'
STARTERS = ['7vy0H3ERRiA', 'MpoIuYCfEvQ', 'yYkRaDUMavk', 'zdIaNCDA4ZY', 'jsLX0Kp1Zpg', 'eJA9E7RXf5U']


def youtube_url(value):
    value = value.strip()
    if re.fullmatch(r'[\w-]{11}', value):
        video_id = value
    else:
        parsed = urlparse(value)
        if parsed.scheme != 'https' or parsed.hostname not in ('youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be', 'music.youtube.com'):
            raise ValueError('Enter a YouTube HTTPS link or an 11-character video ID.')
        video_id = parsed.path.strip('/') if parsed.hostname == 'youtu.be' else parse_qs(parsed.query).get('v', [''])[0]
    if not re.fullmatch(r'[\w-]{11}', video_id):
        raise ValueError('The link must identify a single YouTube video.')
    return f'https://www.youtube.com/watch?v={video_id}'


def fetch_preview(value, force=False, cache_dir=None):
    """Persist public title/artwork only. Refresh explicitly; normal reads work offline."""
    source = youtube_url(value)
    directory = Path(cache_dir) if cache_dir is not None else DATA / 'metadata'
    path = directory / (parse_qs(urlparse(source).query)['v'][0] + '.json')
    cached = None
    try:
        record = json.loads(path.read_text())
        if record['version'] != 1 or record['source'] != source:
            raise ValueError('Invalid cache entry')
        if not all(isinstance(record[key], str) for key in ('title', 'channel', 'image')):
            raise ValueError('Invalid metadata')
        record['image'] = base64.b64decode(record['image'], validate=True)
        with Image.open(io.BytesIO(record['image'])) as image:
            image.verify()
        cached = record
    except (OSError, ValueError, KeyError, TypeError):
        pass
    if cached and not force:
        return cached
    try:
        preview = _download_preview(source)
        with Image.open(io.BytesIO(preview['image'])) as image:
            image.verify()
    except (requests.RequestException, ValueError, OSError):
        if cached:
            return {**cached, 'cache_notice': 'Refresh failed · showing saved preview'}
        raise
    record = {**preview, 'version': 1, 'image': base64.b64encode(preview['image']).decode('ascii')}
    temporary = None
    try:
        directory.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode='w', dir=directory, delete=False) as output:
            temporary = Path(output.name)
            json.dump(record, output)
        temporary.replace(path)
    except OSError:
        preview['cache_notice'] = 'Preview loaded · could not save cache'
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)
    return preview


def _download_preview(source):
    """Read public metadata/artwork only; never resolve or play an audio stream."""
    response = requests.get('https://www.youtube.com/oembed',
                            params={'url': source, 'format': 'json'}, timeout=15)
    response.raise_for_status()
    metadata = response.json()
    thumbnail = metadata.get('thumbnail_url', '')
    parsed = urlparse(thumbnail)
    if parsed.scheme != 'https' or not (parsed.hostname or '').endswith('.ytimg.com'):
        raise ValueError('YouTube did not return a supported thumbnail URL.')
    image = requests.get(thumbnail, timeout=15)
    image.raise_for_status()
    if len(image.content) > 5 * 1024 * 1024:
        raise ValueError('Thumbnail exceeds the preview size limit.')
    return {'source': source, 'title': metadata.get('title', 'Untitled video'),
            'channel': metadata.get('author_name', ''), 'image': image.content}


def receiver_preview(status, cache_dir=None):
    """Artwork enrichment only: never infer playback source identity from a title."""
    title = status.get('title') or 'Title unavailable from receiver'
    preview = {'source': '', 'title': title,
               'channel': status.get('artist') or status.get('app') or 'External playback', 'image': b''}
    artwork = status.get('artwork')
    if artwork and urlparse(artwork).scheme in ('https', 'http'):
        try:
            with requests.get(artwork, timeout=10, stream=True) as response:
                response.raise_for_status()
                chunks, size = [], 0
                for chunk in response.iter_content(65536):
                    size += len(chunk)
                    if size > 5 * 1024 * 1024:
                        raise ValueError('Artwork too large')
                    chunks.append(chunk)
                payload = b''.join(chunks)
            with Image.open(io.BytesIO(payload)) as image:
                image.verify()
            return {**preview, 'image': payload}
        except (requests.RequestException, OSError, ValueError):
            pass
    directory = Path(cache_dir) if cache_dir is not None else DATA / 'metadata'
    matches = []
    for path in directory.glob('*.json'):
        try:
            record = json.loads(path.read_text())
            if record.get('title') != title:
                continue
            payload = base64.b64decode(record['image'], validate=True)
            with Image.open(io.BytesIO(payload)) as image:
                image.verify()
            matches.append(payload)
        except (OSError, ValueError, KeyError, TypeError):
            continue
    if len(matches) == 1:
        preview.update(image=matches[0], cache_notice='Cached artwork matched by title')
    return preview


def cached_source_for_title(title, cache_dir=None):
    """Compatibility for older casts without source tags: exact, unique catalog title."""
    if not title:
        return None
    directory = Path(cache_dir) if cache_dir is not None else DATA / 'metadata'
    matches = set()
    for path in directory.glob('*.json'):
        try:
            record = json.loads(path.read_text())
            if record.get('title') == title:
                source = youtube_url(record['source'])
                if source.rsplit('=', 1)[-1] in STARTERS:
                    matches.add(source)
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            continue
    return next(iter(matches)) if len(matches) == 1 else None


def receiver_identity(media):
    """Prefer an exact media-bound source tag; recognize legacy catalog titles."""
    content = getattr(media, 'content_id', None) or ''
    fingerprint = hashlib.sha256(content.encode()).hexdigest() if content else None
    data = getattr(media, 'media_custom_data', None) or {}
    tag = data.get('moozaq', {}) if isinstance(data, dict) else {}
    source, route = None, 'External / unknown'
    if isinstance(tag, dict) and fingerprint and tag.get('content_hash') == fingerprint:
        try:
            source = youtube_url(tag.get('source', ''))
            route = tag.get('route', 'Unknown')
        except (ValueError, AttributeError):
            pass
    if source is None:
        source = cached_source_for_title(getattr(media, 'title', None))
        if source:
            route = 'Rejoined · matched saved mix'
    return fingerprint, source, route


def byte_range(value, size):
    if not value:
        return 0, size - 1
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', value)
    if not match or not any(match.groups()):
        raise ValueError('Invalid range')
    a, b = match.groups()
    if not a:
        if int(b) <= 0:
            raise ValueError('Invalid suffix range')
        return max(0, size - int(b)), size - 1
    start, end = int(a), min(int(b), size - 1) if b else size - 1
    if start >= size or start > end:
        raise ValueError('Unsatisfiable range')
    return start, end


def redacted(message):
    return re.sub(r'https?://[^\s\]\)]+', '[media URL omitted]', str(message))


# Reusing a resolved source requires this much lifetime beyond what the load needs.
EXPIRY_MARGIN = 600


def url_expiry(url):
    """Epoch seconds when a signed YouTube media URL stops working; None if unknown.

    Reads only the signed `expire` parameter so callers never keep or log the URL.
    """
    try:
        parsed = urlparse(url or '')
        if parsed.scheme != 'https' or not (parsed.hostname or '').endswith('.googlevideo.com'):
            return None
        value = parse_qs(parsed.query).get('expire', [''])[0]
        return int(value) if value.isdigit() else None
    except (ValueError, TypeError, AttributeError):
        return None


class RefreshUnavailable(RuntimeError):
    """A new link could not be fetched before any LOAD; the old link is still valid."""


def remaining_need(duration, start=0):
    """Seconds of media a load starting at `start` still has to fetch."""
    if not duration:
        return 0
    return max(0, float(duration) - float(start or 0))


class MediaServer:
    """Only random-token media routes are exposed on the LAN; there is no control API."""
    def __init__(self, log):
        self.entries = {}
        self.log = log
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_HEAD(self):
                self.serve(False)

            def do_GET(self):
                self.serve(True)

            def serve(self, body):
                entry = owner.entries.get(self.path)
                if entry is None:
                    self.send_error(404)
                    return
                try:
                    if entry['kind'] == 'file':
                        self.file(entry, body)
                    else:
                        self.relay(entry, body)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as exc:
                    owner.log('delivery-error', redacted(exc))
                    self.close_connection = True

            def common_headers(self):
                self.send_header('Access-Control-Allow-Origin', '*')
                self.send_header('Access-Control-Expose-Headers', 'Content-Range, Accept-Ranges, Content-Length')

            def file(self, entry, body):
                path = entry['path']
                size = path.stat().st_size
                try:
                    start, end = byte_range(self.headers.get('Range'), size)
                except ValueError:
                    self.send_response(416)
                    self.send_header('Content-Range', f'bytes */{size}')
                    self.end_headers()
                    return
                self.send_response(206 if self.headers.get('Range') else 200)
                self.common_headers()
                self.send_header('Content-Type', entry['mime'])
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Length', str(end - start + 1))
                if self.headers.get('Range'):
                    self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                self.end_headers()
                owner.log('media-fetch', f'File request from {self.client_address[0]}, bytes {start}-{end}')
                if body:
                    with path.open('rb') as source:
                        source.seek(start)
                        remaining = end - start + 1
                        while remaining:
                            chunk = source.read(min(65536, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)

            def relay(self, entry, body):
                headers = dict(entry['headers'])
                headers['Accept-Encoding'] = 'identity'
                if self.headers.get('Range'):
                    headers['Range'] = self.headers['Range']
                method = 'GET' if body else 'HEAD'
                with requests.request(method, entry['url'], headers=headers, stream=True, timeout=(15, 30)) as upstream:
                    owner.log('media-fetch', f'Relay upstream HTTP {upstream.status_code}; receiver {self.client_address[0]}')
                    self.send_response(upstream.status_code)
                    self.common_headers()
                    self.send_header('Content-Type', entry['mime'])
                    for name in ('Content-Length', 'Content-Range', 'Accept-Ranges'):
                        if name in upstream.headers:
                            self.send_header(name, upstream.headers[name])
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    self.close_connection = True
                    if body:
                        for chunk in upstream.iter_content(65536):
                            self.wfile.write(chunk)

        self.server = ThreadingHTTPServer(('0.0.0.0', 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def add(self, entry, receiver_host):
        key = '/' + secrets.token_urlsafe(24)
        self.entries[key] = entry
        # Pick the interface routed to the selected speaker, not a VPN/default guess.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
            route.connect((receiver_host, 8009))
            address = route.getsockname()[0]
        return f'http://{address}:{self.server.server_port}{key}'

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def install_passive_status(cast):
    """GET_STATUS must never auto-launch an app, including channel callbacks."""
    controller = cast.media_controller
    def update_status(*, callback_function=None):
        socket_client = cast.socket_client
        if (not socket_client.is_connected or not socket_client.destination_id
                or controller.namespace not in socket_client.app_namespaces):
            if callback_function:
                callback_function(False, None)
            return
        # Bypass BaseController.send_message's automatic receiver launch.
        controller.send_message_nocheck({'type': 'GET_STATUS'}, callback_function=callback_function)
    controller.update_status = update_status


def discovered_cast_infos(browser):
    """PyChromecast retains records after removing their final usable service."""
    return [info for info in list(browser.devices.values()) if getattr(info, 'services', True)]


class Lab:
    def __init__(self, known_hosts=None):
        DATA.mkdir(exist_ok=True)
        self.events = []
        self.lock = threading.RLock()
        self.process_lock = threading.Lock()
        self.process = None
        self.media_updated = threading.Event()
        self.playback_gate = threading.RLock()
        self.generation = 0
        self.control_timeout = 3.0
        self.cancelled = threading.Event()
        self.cast = None
        self.selected_id = None
        self.resolved = None
        # Last confirmed position per content, in memory only; never the URL.
        self.last_position = None
        self.mode = 'None'
        self.run_id = time.strftime('%Y%m%d-%H%M%S')
        self.event_file = DATA / f'events-{self.run_id}.jsonl'
        self.zconf = zeroconf.Zeroconf()
        self.browser = pychromecast.CastBrowser(pychromecast.SimpleCastListener(lambda *_: None), self.zconf,
                                               known_hosts=known_hosts)
        self.browser.start_discovery()
        self.media = MediaServer(self.log)
        self.log('ready', 'No cookies or account credentials used. Playback requires an explicit test action.')

    def log(self, kind, detail):
        event = {'time': time.strftime('%Y-%m-%d %H:%M:%S'), 'event': kind, 'detail': redacted(detail)}
        with self.lock:
            self.events.append(event)
            self.events = self.events[-500:]
            with self.event_file.open('a') as out:
                out.write(json.dumps(event) + '\n')

    def devices(self):
        return sorted([(str(value.uuid), value.friendly_name, value.model_name) for value in discovered_cast_infos(self.browser)], key=lambda d: d[1])

    def connect(self, device_id):
        if self.cast and str(self.cast.cast_info.uuid) == device_id and self.cast.socket_client.is_connected:
            return
        self.detach()
        info = next((value for value in discovered_cast_infos(self.browser) if str(value.uuid) == device_id), None)
        if info is None:
            raise ValueError('Speaker no longer discovered. Wait a few seconds and refresh.')
        self.selected_id = device_id
        cast = pychromecast.get_chromecast_from_cast_info(info, self.zconf)
        install_passive_status(cast)
        try:
            cast.wait(timeout=15)
        except Exception:
            cast.disconnect()
            raise
        self.cast = cast
        self.cast.media_controller.register_status_listener(self)
        self.sync_media(timeout=5)
        self.log('connected', f'{info.friendly_name}; joined existing receiver, no media loaded')

    def new_media_status(self, status):
        if status.player_state in ('PLAYING', 'PAUSED') and status.content_id:
            self.last_position = (hashlib.sha256(status.content_id.encode()).hexdigest(),
                                  status.adjusted_current_time or status.current_time or 0)
        self.media_updated.set()
        self.log('media-status', f'target={getattr(self, "selected_id", None)} · {status.player_state} · position {status.current_time} · idle {status.idle_reason}')

    def load_media_failed(self, queue_item_id, error_code):
        self.log('cast-load-failed', f'target={getattr(self, "selected_id", None)} · Receiver rejected media: queue {queue_item_id}, code {error_code}')

    def detach(self):
        if self.cast:
            self.cast.disconnect()
            self.cast = None
            self.log('detached', 'Control connection closed. Mac media server remains running.')

    def sync_media(self, timeout=3, required=False):
        self.media_updated.clear()
        if not self.cast or not self.cast.status.app_id:
            if required:
                raise ValueError('No active receiver playback session.')
            return False
        self.cast.media_controller.update_status()
        fresh = self.media_updated.wait(timeout)
        if required and (not fresh or self.cast.media_controller.status.media_session_id is None):
            raise RuntimeError('Playback state has not synchronized; command was not sent.')
        return fresh

    def stop_verified(self):
        # Cancel acquisition and invalidate pending loads before touching the receiver.
        self.cancel()
        with self.playback_gate:
            self.generation += 1
        if not self.cast or not self.cast.socket_client.is_connected:
            if self.selected_id:
                self.connect(self.selected_id)
            else:
                raise ValueError('No known speaker to stop. Connect to a speaker first.')
        cast = self.cast
        mc = cast.media_controller
        if not cast.status.app_id:
            self.log('stop-confirmed', 'Receiver has no active app; no playback to stop.')
            return
        # A control socket can be connected before a media session is known.
        if mc.status.media_session_id is not None:
            self.media_updated.clear()
            try:
                mc.stop(timeout=self.control_timeout)
                mc.update_status()
                self.media_updated.wait(self.control_timeout)
                if self.media_updated.is_set() and mc.status.player_state == 'IDLE':
                    self.log('stop-confirmed', 'Fresh receiver media status is IDLE.')
                    return
            except Exception as exc:
                self.log('stop-fallback', 'Media stop failed: ' + str(exc))
        self.log('stop-fallback', 'Ending receiver app to ensure playback stops.')
        cast.quit_app(timeout=5)
        deadline = time.monotonic() + self.control_timeout
        while cast.status.app_id:
            if time.monotonic() >= deadline:
                raise RuntimeError('Stop could not be confirmed; receiver still reports an active app.')
            time.sleep(0.05)
        self.log('stop-confirmed', 'Receiver confirms no active app.')

    def play_selection(self, value):
        """Explicit Play: load a selection or resume a confirmed existing session."""
        if not self.cast or not self.cast.socket_client.is_connected:
            raise ValueError('Connect to a speaker first, then press Play.')
        source = youtube_url(value) if value else None
        generation = self.generation
        fresh = self.sync_media(timeout=self.control_timeout)
        status = self.status()
        active = status.get('state') in ('PLAYING', 'PAUSED', 'BUFFERING')
        if not fresh and (active or status.get('media_session') is not None):
            raise RuntimeError('Receiver state is not synchronized. Rejoin before pressing Play; existing playback was not replaced.')
        if generation != self.generation:
            raise RuntimeError('Playback cancelled by Stop.')
        if active and (not source or source == status.get('source')):
            if status['state'] == 'PAUSED' and status.get('refresh_needed'):
                # The link would not last the rest of the mix: resume on a fresh one.
                try:
                    self.refresh_source(expected_generation=generation)
                except RefreshUnavailable:
                    self.command('play')
            elif status['state'] == 'PAUSED':
                self.command('play')
            # Already playing or buffering: do not restart or send redundant commands.
            return
        if not source:
            raise ValueError('Select a mix to play; there is no active session to resume.')
        volume = max(0, min(1, status.get('volume', 15) / 100))
        self.play_test('direct', source, volume, expected_generation=generation)

    def command(self, name, value=None):
        if name == 'stop':
            self.stop_verified()
            return
        if not self.cast:
            raise ValueError('Connect to a speaker first.')
        mc = self.cast.media_controller
        if name in ('play', 'pause', 'seek'):
            self.sync_media(timeout=self.control_timeout, required=True)
        if name in ('play', 'pause'):
            getattr(mc, name)()
            expected = 'PLAYING' if name == 'play' else 'PAUSED'
            if not self.sync_media(timeout=self.control_timeout) or mc.status.player_state != expected:
                raise RuntimeError(f'{name.title()} sent, but receiver has not confirmed {expected}.')
        elif name == 'seek':
            if not mc.status.supports_seek:
                raise ValueError('Receiver does not advertise seek support for this media.')
            mc.seek(max(0, float(value)))
            self.sync_media(timeout=self.control_timeout)
        elif name == 'volume':
            self.cast.set_volume(max(0, min(1, float(value))))
        elif name == 'refresh':
            self.sync_media(timeout=self.control_timeout)
        self.log('control', f'{name} {value if value is not None else ""}')

    def status(self):
        cast = self.cast
        if cast is None:
            return {'connected': False, 'mode': self.mode}
        media = cast.media_controller.status
        if not cast.status.app_id:
            return {'connected': cast.socket_client.is_connected, 'device': cast.cast_info.friendly_name,
                    'reconnecting': not cast.socket_client.is_connected,
                    'app': None, 'title': None, 'state': 'IDLE', 'position': 0, 'duration': None,
                    'volume': round(cast.status.volume_level * 100), 'idle_reason': 'No active receiver app',
                    'mode': self.mode, 'media_session': None}
        fingerprint, source, route = receiver_identity(media)
        metadata = getattr(media, 'media_metadata', None) or {}
        # The receiver's own content ID carries the signed expiry, so this survives reconnects.
        expires_at = url_expiry(getattr(media, 'content_id', None))
        source_expired = bool(source and expires_at and time.time() >= expires_at)
        remembered = getattr(self, 'last_position', None)
        resume_position = (remembered[1] if remembered and remembered[0] == fingerprint
                           else media.adjusted_current_time)
        # Resume/seek should renew a link that would run out before the mix ends.
        refresh_needed = bool(source and expires_at and expires_at - time.time()
                              < remaining_need(media.duration, resume_position) + EXPIRY_MARGIN)
        return {'connected': cast.socket_client.is_connected, 'device': cast.cast_info.friendly_name,
                'expires_at': expires_at, 'source_expired': source_expired, 'refresh_needed': refresh_needed,
                'resume_position': round(resume_position or 0, 1),
                'reconnecting': not cast.socket_client.is_connected,
                'source': source, 'content_key': fingerprint,
                'artwork': next((item.get('url') for item in metadata.get('images', []) if isinstance(item, dict) and item.get('url')), None),
                'artist': metadata.get('artist') or metadata.get('subtitle') or '',
                'app': cast.status.display_name, 'title': media.title, 'state': media.player_state,
                'position': round(media.adjusted_current_time or 0, 1), 'duration': media.duration,
                'volume': round(cast.status.volume_level * 100), 'idle_reason': media.idle_reason,
                'mode': route, 'media_session': media.media_session_id}

    def run_process(self, args, timeout=180):
        self.cancelled.clear()
        with self.process_lock:
            self.process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            process = self.process
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate()
            raise RuntimeError(f'Operation timed out after {timeout} seconds.')
        except BaseException:
            process.kill()
            process.communicate()
            raise
        finally:
            with self.process_lock:
                self.process = None
        if self.cancelled.is_set():
            raise RuntimeError('Operation cancelled; no new media loaded.')
        if process.returncode:
            raise RuntimeError(redacted(stderr[-5000:] or stdout[-1000:] or f'Process exited {process.returncode}'))
        if stderr.strip():
            self.log('tool-note', stderr[-3000:])
        return stdout

    def cancel(self):
        self.cancelled.set()
        with self.process_lock:
            if self.process:
                self.process.terminate()

    def ytdlp(self):
        args = [sys.executable, '-m', 'yt_dlp', '--ignore-config', '--no-playlist', '--no-progress',
                '--socket-timeout', '15', '--retries', '1', '--fragment-retries', '1']
        runtime = shutil.which('node')
        if runtime:
            args += ['--js-runtimes', f'node:{runtime}']
        return args

    def resolve(self, value):
        source = youtube_url(value)
        self.resolved = None
        self.log('resolve-start', source.rsplit('=', 1)[-1])
        result = json.loads(self.run_process(self.ytdlp() + ['--skip-download', '--dump-single-json', '-f', 'bestaudio[ext=m4a]/bestaudio', source]))
        if not result.get('url'):
            raise RuntimeError('Extractor returned no direct audio URL.')
        if result.get('protocol') not in ('http', 'https'):
            raise RuntimeError('This spike requires a progressive HTTP audio format; extractor returned ' + str(result.get('protocol')))
        if result.get('vcodec') != 'none':
            raise RuntimeError('Extractor did not return audio-only media.')
        result['source_url'] = source
        result['resolved_at'] = time.time()
        self.resolved = result
        self.log('resolve-success', f'{result.get("id")} · {result.get("title")} · {result.get("format_id")} / {result.get("ext")} / {result.get("acodec")}')
        return result

    def ensure_source(self, value, start=0):
        """Return a source whose signed link outlives the media this load will fetch."""
        source = youtube_url(value)
        cached = self.resolved
        if (not cached or cached['source_url'] != source or time.time() - cached['resolved_at'] > 600
                or not self.lifetime_covers(cached, start, EXPIRY_MARGIN)):
            cached = self.resolve(value)
        expires_at = url_expiry(cached.get('url'))
        if expires_at is not None and expires_at - time.time() < EXPIRY_MARGIN:
            raise RuntimeError('YouTube issued an already-expiring stream link. No new media was loaded; try again later.')
        if not self.lifetime_covers(cached, start):
            # Long mixes can outlast one link. A running fetch usually continues; Resume or
            # seek after expiry refreshes the link. Record the risk instead of refusing.
            self.log('source-expiry-risk', f'{cached.get("id")} · link lifetime is shorter than the remaining mix')
        return cached

    @staticmethod
    def lifetime_covers(resolved, start=0, margin=0):
        expires_at = url_expiry(resolved.get('url'))
        if expires_at is None:
            return True
        return expires_at - time.time() >= remaining_need(resolved.get('duration'), start) + margin

    def wait_for_cast_ready(self, cast, generation, require_receiver=False, timeout=15):
        deadline = time.monotonic() + timeout
        while True:
            if generation != self.generation or self.cast is not cast:
                raise RuntimeError('Playback cancelled or speaker changed.')
            socket_client = cast.socket_client
            ready = socket_client.is_connected
            if require_receiver:
                ready = (ready and cast.status.app_id == 'CC1AD845'
                         and socket_client.destination_id is not None
                         and cast.media_controller.namespace in socket_client.app_namespaces)
            if ready:
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('Speaker is reconnecting; no new media was sent. Rejoin and try again.')
            time.sleep(0.1)

    def confirm_loaded_media(self, cast, media_url, generation, timeout=20, expected='PLAYING'):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if generation != self.generation or self.cast is not cast:
                raise RuntimeError('Playback cancelled or speaker changed.')
            media = cast.media_controller.status
            if media.content_id == media_url:
                if media.player_state == expected:
                    self.log('load-confirmed', f'Receiver confirms the requested media is {expected}.')
                    return
                # During replacement Nest can report INTERRUPTED with the new
                # content ID before BUFFERING -> PLAYING. Keep observing the
                # same load; never retry or treat interruption as success.
                if media.player_state == 'IDLE' and media.idle_reason in ('ERROR', 'CANCELLED'):
                    raise RuntimeError('Nest could not play the selected stream (' + media.idle_reason + '). No automatic retry was started.')
            self.media_updated.wait(min(0.25, max(0, deadline - time.monotonic())))
            self.media_updated.clear()
        raise RuntimeError('Nest did not confirm the selected track started. Check receiver state; no automatic retry was started.')

    def preflight_direct(self, media_url):
        # Match the header-free direct receiver route; do not download the full file.
        try:
            with requests.get(media_url, headers={'Range': 'bytes=0-0'}, stream=True, timeout=15) as response:
                response.raise_for_status()
                if not next(response.iter_content(1), b''):
                    raise ValueError('The media endpoint returned no audio data.')
        except (requests.RequestException, ValueError) as exc:
            raise RuntimeError('Direct stream is not accessible. Existing receiver playback was not replaced.') from exc

    def refresh_source(self, position=None, paused=False, expected_generation=None):
        """Explicit Resume/seek when the signed link expired or would not last the rest of
        the mix: resolve again and load at the same position. Never called by reconnection
        or monitoring. Raises RefreshUnavailable, before any LOAD, when a new link cannot be
        fetched but the old one still works, so the caller can fall back to plain control."""
        generation = self.generation if expected_generation is None else expected_generation
        status = self.status()
        if not status.get('refresh_needed'):
            raise ValueError('The current stream link lasts the rest of the mix; nothing to refresh.')
        source = status.get('source')
        if not source:
            raise ValueError('Cannot identify the current mix. Choose it again to play.')
        if position is None:
            position = status.get('resume_position') or 0
        if generation != self.generation:
            raise RuntimeError('Playback cancelled by Stop.')
        try:
            self.ensure_source(source, position)
        except Exception as exc:
            # Stop cancels the lookup too; that must never fall back to plain Resume.
            if generation != self.generation or self.cancelled.is_set():
                raise RuntimeError('Playback cancelled by Stop.') from exc
            if status.get('source_expired'):
                raise
            self.log('source-refresh-unavailable', 'New link unavailable; existing link still valid: ' + str(exc))
            raise RefreshUnavailable('A new stream link could not be fetched.') from exc
        volume = max(0, min(1, (status.get('volume') or 15) / 100))
        self.log('source-refresh', f'{source.rsplit("=", 1)[-1]} · reload at {round(position)}s · {"paused" if paused else "playing"}')
        self.play_test('direct', source, volume, expected_generation=generation, start=position, autoplay=not paused)

    def play_test(self, mode, value, volume=0.15, expected_generation=None, start=0, autoplay=True):
        generation = self.generation if expected_generation is None else expected_generation
        if generation != self.generation:
            raise RuntimeError('Playback cancelled by Stop.')
        if not self.cast:
            raise ValueError('Connect to Bedroom speaker first.')
        if mode == 'tone':
            path = DATA / 'original-test-tone.mp3'
            if not path.exists():
                self.log('generate', 'Creating a quiet three-minute original test tone')
                temporary = path.with_name('tone-incomplete.mp3')
                self.run_process(['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                    'sine=frequency=220:sample_rate=44100', '-t', '180', '-af', 'volume=0.12',
                    '-c:a', 'libmp3lame', '-b:a', '64k', str(temporary)])
                temporary.replace(path)
            entry = {'kind': 'file', 'path': path, 'mime': 'audio/mpeg'}
            title, duration = 'Moozaq · original test tone', 180
            media_url = self.media.add(entry, self.cast.cast_info.host)
            mime = entry['mime']
        else:
            resolved = self.ensure_source(value, start) if mode == 'direct' else self.ensure_source(value)
            title, duration = resolved['title'], resolved.get('duration')
            mime = {'m4a': 'audio/mp4', 'webm': 'audio/webm', 'mp3': 'audio/mpeg'}.get(resolved['ext'], 'application/octet-stream')
            if mode == 'direct':
                media_url = resolved['url']
            elif mode == 'relay':
                media_url = self.media.add({'kind': 'relay', 'url': resolved['url'], 'headers': resolved.get('http_headers', {}), 'mime': mime}, self.cast.cast_info.host)
            elif mode == 'cache':
                cache_id = f'{resolved["id"]}-{resolved["format_id"]}'
                path = DATA / f'{cache_id}.{resolved["ext"]}'
                if not path.exists():
                    self.log('download-start', f'{resolved["id"]} · completing audio download before Cast load')
                    self.run_process(self.ytdlp() + ['-f', str(resolved['format_id']), '-o', str(path), resolved['source_url']], timeout=1800)
                if not path.exists() or path.stat().st_size == 0:
                    raise RuntimeError('Download did not produce a completed audio file.')
                self.log('cache-ready', f'{path.name} · {path.stat().st_size} bytes')
                media_url = self.media.add({'kind': 'file', 'path': path, 'mime': mime}, self.cast.cast_info.host)
            else:
                raise ValueError('Unknown playback test')
        if mode == 'direct':
            self.preflight_direct(media_url)
        self.mode = mode
        self.log('cast-load', f'target={getattr(self, "selected_id", None)} · {mode} · {title} · start volume {round(volume * 100)}%; waiting for receiver evidence')
        media_info = {'duration': duration} if duration else {}
        if mode != 'tone':
            media_info['customData'] = {'moozaq': {
                'source': resolved['source_url'], 'route': mode,
                'content_hash': hashlib.sha256(media_url.encode()).hexdigest()}}
        cast = self.cast
        self.wait_for_cast_ready(cast, generation)
        # Reuse the existing Default Media Receiver instead of relaunching it on every switch.
        if cast.status.app_id != 'CC1AD845':
            cast.start_app('CC1AD845', timeout=15)
        self.wait_for_cast_ready(cast, generation, require_receiver=True)
        with self.playback_gate:
            if generation != self.generation or self.cast is not cast:
                raise RuntimeError('Playback cancelled or speaker changed.')
            cast.set_volume(max(0, min(1, volume)))
            self.media_updated.clear()
            cast.media_controller.play_media(media_url, mime, title=title,
                thumb=resolved.get('thumbnail') if mode != 'tone' else None,
                stream_type='BUFFERED', media_info=media_info,
                current_time=start or None, autoplay=autoplay)
        self.confirm_loaded_media(cast, media_url, generation, expected='PLAYING' if autoplay else 'PAUSED')
        self.log('receiver-after-load', json.dumps(self.status()))

    def export(self, path, notes=''):
        path = Path(path)
        payload = {'time': time.strftime('%Y-%m-%d %H:%M:%S'), 'notes': notes, 'receiver': self.status(),
                   'source_id': self.resolved.get('id') if self.resolved else None,
                   'events': self.events, 'limits': 'Receiver state is not audible confirmation. No three-hour validation implied.'}
        path.write_text(json.dumps(payload, indent=2))

    def close(self):
        self.cancel()
        self.detach()
        self.media.close()
        self.browser.stop_discovery()
        self.zconf.close()
