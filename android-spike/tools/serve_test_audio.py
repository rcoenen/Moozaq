#!/usr/bin/env python3
"""Generate original test tones and serve MP3 with byte-range support on the LAN."""
import argparse
import functools
import http.server
import pathlib
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--seconds', type=int, default=10800)
parser.add_argument('--port', type=int, default=8765)
args = parser.parse_args()
if not 1 <= args.seconds <= 86400:
    parser.error('--seconds must be 1..86400')
folder = pathlib.Path(__file__).resolve().parent.parent / 'build' / 'test-audio'
folder.mkdir(parents=True, exist_ok=True)
media = folder / f'moozaq-{args.seconds}s.mp3'
if not media.exists():
    subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi',
        '-i', 'sine=frequency=220:sample_rate=44100', '-t', str(args.seconds),
        '-af', 'volume=0.12', '-codec:a', 'libmp3lame', '-b:a', '64k', str(media)], check=True)

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Accept-Ranges', 'bytes')
        super().end_headers()

    def send_head(self):
        self.remaining = None
        path = pathlib.Path(self.translate_path(self.path))
        if path != media:
            self.send_error(404)
            return None
        size = path.stat().st_size
        start, end = 0, size - 1
        value = self.headers.get('Range')
        if value:
            match = re.fullmatch(r'bytes=(\d*)-(\d*)', value)
            if not match or not any(match.groups()):
                self.send_error(416)
                return None
            a, b = match.groups()
            if not a:
                start = max(0, size - int(b))
            else:
                start = int(a)
                end = min(size - 1, int(b)) if b else size - 1
            if start > end or start >= size:
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{size}')
                self.end_headers()
                return None
        self.send_response(206 if value else 200)
        self.send_header('Content-Type', 'audio/mpeg')
        self.send_header('Content-Length', str(end-start+1))
        if value:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        stream = path.open('rb')
        stream.seek(start)
        self.remaining = end-start+1
        return stream

    def copyfile(self, source, output):
        try:
            while self.remaining:
                chunk = source.read(min(65536, self.remaining))
                if not chunk:
                    break
                output.write(chunk)
                self.remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

print(f'In Moozaq enter http://<Mac-LAN-IP>:{args.port}/{media.name}', flush=True)
print('Keep this Mac awake and on the same network as Nest. Ctrl-C stops serving.', flush=True)
server = http.server.ThreadingHTTPServer(('0.0.0.0', args.port), functools.partial(Handler, directory=str(folder)))
try:
    server.serve_forever()
except KeyboardInterrupt:
    server.server_close()
