"""Remember a physical remote to recover from an expired temporary group.

This only chooses a control connection. It never recreates a group or loads media.
"""
import json
import ipaddress


class RemotePreferences:
    def __init__(self, path):
        self.path = path
        self.data = {}
        self.missing_since = None
        try:
            loaded = json.loads(path.read_text())
            if isinstance(loaded, dict):
                self.data = loaded
        except (OSError, ValueError):
            pass

    def remember(self, target, info, members, infos):
        if info is None:
            return
        data = dict(self.data, speaker_id=target, speaker_kind=info.cast_type)
        if info.cast_type == 'audio':
            data['fallback_speaker_id'] = target
            data['fallback_host'] = info.host
        elif info.cast_type == 'group' and members:
            physical = [uid for uid in members if uid in infos and infos[uid].cast_type == 'audio']
            if data.get('fallback_speaker_id') not in physical:
                data['fallback_speaker_id'] = next((uid for uid in physical if infos[uid].host == info.host),
                                                  next(iter(sorted(physical)), None))
            fallback = infos.get(data.get('fallback_speaker_id'))
            if fallback:
                data['fallback_host'] = fallback.host
        if data != self.data:
            self.path.write_text(json.dumps(data))
            self.data = data
        self.missing_since = None

    def seed_discovery(self, browser):
        # An address is only a discovery hint. HostBrowser reads device identity;
        # Lab.connect still selects by UUID, never by this cached address alone.
        hosts = self.known_hosts()
        if hosts:
            browser.host_browser.add_hosts(hosts)

    def known_hosts(self):
        try:
            return [str(ipaddress.ip_address(self.data.get('fallback_host', '')))]
        except ValueError:
            return []

    def replacement(self, target, infos, connected, busy, now):
        # A short discovery gap must not steal a healthy or pending session.
        if busy or connected or target in infos or self.data.get('speaker_id') != target:
            self.missing_since = None
            return None
        if self.data.get('speaker_kind') != 'group':
            return None
        if self.missing_since is None:
            self.missing_since = now
        fallback = self.data.get('fallback_speaker_id')
        info = infos.get(fallback)
        if now - self.missing_since >= 15 and info and info.cast_type == 'audio':
            self.missing_since = None
            return fallback
        return None


ACTIVE = {'PLAYING', 'PAUSED', 'BUFFERING'}


class SessionRecovery:
    """Follow fresh, unambiguous session evidence; never issue playback commands."""
    def __init__(self):
        self.clear()

    def clear(self):
        self.last = None
        self.seen = None
        self.lost_at = None
        self.pending_target = None

    @property
    def recovering(self):
        return self.lost_at is not None

    def step(self, target, rows, now):
        current = next((r for r in rows if r['id'] == target and r['fresh']), None)
        if current and current['state'] in ACTIVE and current.get('content_key'):
            # A candidate connection must still identify the session we followed.
            if self.pending_target is None or self.matches(current, now):
                self.last = dict(current)
                self.seen = now
                self.lost_at = self.pending_target = None
                return None
        if not self.last:
            return None
        if self.lost_at is None:
            self.lost_at = now
        matches = [r for r in rows if r['id'] != target and r['fresh'] and self.matches(r, now)]
        # A long outage or ambiguous duplicate must never guess which music to control.
        if now - self.lost_at <= 120 and len(matches) == 1:
            self.pending_target = matches[0]['id']
            return self.pending_target
        if now - self.lost_at >= 15 and not matches:
            members = set(self.last.get('members') or [self.last['id']])
            idle = [r for r in rows if r['id'] in members and r['fresh'] and r['state'] == 'IDLE']
            if len(idle) == len(members):
                # Every remembered participant is freshly idle: the old session ended.
                replacement = None if current and current['state'] == 'IDLE' else sorted(r['id'] for r in idle)[0]
                self.clear()
                return replacement
        return None

    def matches(self, row, now):
        old = self.last
        if not old or row['state'] not in ACTIVE or row.get('content_key') != old.get('content_key'):
            return False
        old_members = set(old.get('members') or [old['id']])
        new_members = set(row.get('members') or [row['id']])
        if not old_members.intersection(new_members):
            return False
        before, after = old.get('position'), row.get('position')
        if before is None or after is None:
            return False
        elapsed = max(0, now - self.seen)
        # A rewind/restart is not continuity. Allow advancing playback and small clock jitter.
        return before - 10 <= after <= before + elapsed + 20

    def present(self, receiver, now):
        if not self.recovering:
            return receiver
        last = self.last
        return dict(receiver, connected=False, state='UNKNOWN',
                    title=last.get('title'), source=last.get('source'),
                    position=last.get('position'), duration=last.get('duration'),
                    recovery='Reconnecting…' if now - self.lost_at < 60 else 'Playback unavailable · last known track')
