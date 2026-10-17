"""Passive whole-home output state, independent of the selected playback remote."""
import concurrent.futures
import threading
import time

import pychromecast
from engine import install_passive_status, receiver_identity, discovered_cast_infos
from membership import ObservedController, member_ids
from volume import VolumeTracker

ACTIVE = {'PLAYING', 'PAUSED', 'BUFFERING'}
FRESH = 12


class OutputListener:
    def __init__(self, entry):
        self.entry = entry

    def new_cast_status(self, status):
        with self.entry.lock:
            self.entry.receiver_at = time.monotonic()

    def new_media_status(self, status):
        with self.entry.lock:
            self.entry.media_at = time.monotonic()


class OutputEntry:
    def __init__(self, cast):
        self.cast = cast
        self.lock = threading.RLock()
        self.receiver_at = self.media_at = self.members_at = 0
        self.members = []
        self.revision = 0
        self.tracker = VolumeTracker(cast)
        listener = OutputListener(self)
        cast.register_status_listener(listener)
        cast.media_controller.register_status_listener(listener)
        self.controller = ObservedController() if cast.cast_type == 'group' else None
        if self.controller:
            cast.register_handler(self.controller)

    def snapshot(self, now):
        with self.lock:
            cast = self.cast
            connected = bool(cast.socket_client.is_connected and now - self.receiver_at < FRESH)
            media_fresh = connected and now - self.media_at < FRESH
            media = cast.media_controller.status
            state = media.player_state if media_fresh else 'UNKNOWN'
            if connected and not cast.status.app_id:
                state = 'IDLE'
            level = cast.status.volume_level if connected else None
            return {'id': str(cast.uuid), 'connected': connected, 'state': state or 'UNKNOWN',
                    'title': media.title if media_fresh and state in ACTIVE else None,
                    'volume': level if isinstance(level, (float, int)) else None,
                    'muted': bool(cast.status.volume_muted) if connected else False,
                    'volume_revision': self.revision,
                    'members': list(self.members) if connected and now - self.members_at < FRESH else []}


def resolve_activity(rows, kinds):
    """Attribute group playback only from fresh membership, never a saved group name."""
    for row in rows:
        row['control_id'] = row['id']
        row['session_id'] = row['id'] if row['state'] in ACTIVE else None
    for row in rows:
        if kinds.get(row['id']) != 'audio':
            continue
        groups = [group for group in rows if kinds.get(group['id']) == 'group'
                  and group['state'] in ACTIVE and row['id'] in group['members']]
        if row['state'] in ACTIVE:
            continue  # A fresh individual session takes precedence over group inference.
        if len(groups) == 1 and row['connected']:
            group = groups[0]
            row.update(state=group['state'], title=group['title'],
                       control_id=group['id'], session_id=group['id'])
        elif len(groups) > 1:
            row.update(state='UNKNOWN', title=None, session_id=None)
    return rows


class OutputMonitor:
    def __init__(self, lab):
        self.lab = lab
        self.lock = threading.RLock()
        self.entries = {}
        self.closed = threading.Event()

    def snapshot(self):
        infos = {str(i.uuid): i for i in discovered_cast_infos(self.lab.browser)}
        with self.lock:
            rows = [entry.snapshot(time.monotonic()) for uid, entry in self.entries.items() if uid in infos]
        return resolve_activity(rows, {uid: info.cast_type for uid, info in infos.items()})

    def recovery_rows(self):
        """Private media identity evidence; no URLs or inferred group activity."""
        infos = {str(i.uuid): i for i in discovered_cast_infos(self.lab.browser)}
        now = time.monotonic()
        with self.lock:
            entries = [(uid, entry) for uid, entry in self.entries.items() if uid in infos]
        rows = []
        for uid, entry in entries:
            with entry.lock:
                row = entry.snapshot(now)
                media = entry.cast.media_controller.status
                fresh = row['connected'] and (not entry.cast.status.app_id or now - entry.media_at < FRESH)
                # Group recovery also requires current membership, never a cached topology.
                if entry.cast.cast_type == 'group':
                    fresh = fresh and now - entry.members_at < FRESH
                fingerprint, source, _ = receiver_identity(media) if fresh and row['state'] in ACTIVE else (None, None, None)
                rows.append(dict(row, fresh=fresh, content_key=fingerprint, source=source,
                                 position=media.adjusted_current_time if fresh and row['state'] in ACTIVE else None,
                                 duration=media.duration if fresh and row['state'] in ACTIVE else None))
        return rows

    def poll(self, info):
        uid = str(info.uuid)
        retired = None
        with self.lock:
            entry = self.entries.get(uid)
            if entry is not None and entry.cast.socket_client.stop.is_set():
                retired = self.entries.pop(uid)
                entry = None
        if retired is not None:
            retired.cast.disconnect()
        if entry is None:
            cast = pychromecast.get_chromecast_from_cast_info(info, self.lab.zconf, tries=1, timeout=3)
            install_passive_status(cast)
            entry = OutputEntry(cast)
            with self.lock:
                if self.closed.is_set():
                    cast.disconnect()
                    return
                self.entries[uid] = entry
            cast.wait(timeout=4)
        cast = entry.cast
        cast.socket_client.receiver_controller.update_status()
        if cast.status.app_id:
            cast.media_controller.update_status()
        if entry.controller:
            status = entry.controller.refresh(timeout=2)
            with entry.lock:
                entry.members = member_ids(status)
                entry.members_at = time.monotonic()

    def set_volume(self, uid, value):
        with self.lock:
            entry = self.entries.get(uid)
        if entry is None or not entry.snapshot(time.monotonic())['connected']:
            raise ValueError('Speaker volume is not currently available.')
        monitor = self
        class Context:
            playback_gate = monitor.lab.playback_gate
            @property
            def cast(self):
                with monitor.lock:
                    return entry.cast if not monitor.closed.is_set() and monitor.entries.get(uid) is entry else None
            @property
            def generation(self):
                return monitor.lab.generation
        entry.tracker.set_confirmed(Context(), value)
        with entry.lock:
            entry.revision += 1

    def run(self):
        def safe_poll(info):
            try:
                self.poll(info)
            except Exception as exc:
                self.lab.log('output-status', f'{info.uuid}: {type(exc).__name__}')
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
                while not self.closed.is_set():
                    infos = {str(i.uuid): i for i in discovered_cast_infos(self.lab.browser)}
                    with self.lock:
                        gone = [self.entries.pop(uid) for uid in list(self.entries) if uid not in infos]
                    for entry in gone:
                        entry.cast.disconnect()
                    list(pool.map(safe_poll, infos.values()))
                    self.closed.wait(3)
        finally:
            with self.lock:
                entries, self.entries = self.entries, {}
            for entry in entries.values():
                entry.cast.disconnect()

    def close(self):
        self.closed.set()
