"""Private stdio bridge. No network control endpoint; only explicit actions play audio."""
import json
import os
import sys
import threading
import time
from pathlib import Path

# Finder launches do not inherit the interactive shell's Homebrew PATH.
os.environ['PATH'] = '/opt/homebrew/bin:/usr/local/bin:' + os.environ.get('PATH', '')
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'mac-spike'))
from engine import Lab, DATA, RefreshUnavailable, redacted, discovered_cast_infos
from groups import GroupMonitor
from volume import VolumeTracker
from seeking import SeekTracker
from catalog import Catalog
from following import TemporaryGroupFollower, ACTIVE
from membership import MembershipService
from outputs import OutputMonitor, temporary_group
from recovery import RemotePreferences, SessionRecovery
from inventory import DeviceInventory


class Bridge:
    def __init__(self, lab):
        self.lab = lab
        self.lock = threading.RLock()
        self.operation_busy = False
        self.volume_busy = False
        self.stopping = False
        self.connecting = False
        self.message = 'Discovering speakers…'
        self.target = None
        self.retry_at = 0
        self.retry_delay = 5
        self.closed = False
        self.epoch = 0
        self.group_monitor = None
        self.volume_tracker = None
        self.volume_revision = 0
        self.seek_tracker = None
        self.seek_revision = 0
        self.transport_revision = 0
        self.group_follower = TemporaryGroupFollower(self)
        self.membership = MembershipService(self)
        self.outputs = OutputMonitor(lab)
        self.catalog = Catalog(DATA / "channel-catalog.json", json.loads((Path(__file__).parent / "channels.json").read_text()))
        self.remote_preferences = RemotePreferences(DATA / 'preferences.json')
        self.target = self.remote_preferences.data.get('speaker_id')
        self.session_recovery = SessionRecovery()
        # Session following lives in memory; after a restart, look once for the mix already playing.
        self.startup_follow = True
        self.started_at = time.monotonic()
        self.inventory = DeviceInventory(DATA / 'known-devices.json')

    @property
    def busy(self):
        return self.operation_busy or self.stopping

    def snapshot(self):
        status = self.lab.status()
        # Never expose signed stream URLs or transport internals to the UI.
        receiver = {key: status.get(key) for key in
                    ('connected', 'device', 'title', 'state', 'position', 'duration', 'volume', 'source', 'mode', 'idle_reason')}
        receiver['volume_revision'] = self.volume_revision
        receiver['seek_revision'] = self.seek_revision
        receiver['transport_revision'] = self.transport_revision
        observed = next((r for r in self.recovery_rows() if r['id'] == self.target), None)
        group_target = getattr(self.lab.cast, 'cast_type', None) == 'group'
        group_average = self.group_monitor.average_volume() if (self.group_monitor and group_target) else None
        synchronized = bool(observed and observed['fresh'] and observed['state'] != 'UNKNOWN'
                            and self.lab.selected_id == self.target)
        if synchronized and status.get('connected'):
            for key in ('state', 'title', 'source', 'position', 'duration'):
                receiver[key] = observed.get(key)
            if group_average is not None:
                receiver['volume'] = round(group_average * 100)
            elif group_target:
                # A group's own device volume is not the members' average. Until every member
                # is readable, do not offer a main slider that claims otherwise.
                receiver['volume'] = None
            elif observed.get('volume') is not None:
                receiver['volume'] = round(observed['volume'] * 100)
            else:
                receiver['volume'] = None
        elif status.get('connected'):
            receiver.update(connected=False, state='UNKNOWN', volume=None)
        receiver.update(self.expiry_view(status, observed if synchronized else None))
        with self.lock:
            receiver = self.session_recovery.present(receiver, time.monotonic())
            recovering = self.session_recovery.recovering
        if self.target and not synchronized and not recovering:
            receiver.update(connected=False, state='UNKNOWN', recovery='Waiting for speaker…')
        infos = {str(info.uuid): info for info in discovered_cast_infos(self.lab.browser)}
        devices = self.inventory.snapshot(infos.values())
        temporary = self.membership.temporary_ids | self.outputs.temporary_ids()
        saved = self.outputs.saved_group_ids()
        saved_known = self.outputs.topology_loaded()
        for device in devices:
            device['temporary'] = temporary_group(device['id'], device.get('cast_type'),
                                                  temporary, saved, saved_known)
        return {**self.catalog.status(), 'devices': devices,
                'receiver': receiver, 'target': self.target, 'busy': self.busy,
                'connecting': self.connecting or bool(status.get('reconnecting')) or recovering or bool(self.target and not synchronized), 'message': self.message,
                'group': self.group_monitor.snapshot() if self.group_monitor else None,
                'membership': self.membership.snapshot(), 'outputs': self.outputs.snapshot()}

    @staticmethod
    def expiry_view(status, observed):
        """Signed-link expiry for the presented session; never the URL itself."""
        bound = not (observed and observed.get('content_key')
                     and observed['content_key'] != status.get('content_key'))
        expired = bool(bound and status.get('connected') and status.get('source_expired'))
        expires_at = status.get('expires_at') if bound else None
        view = {'source_expired': expired,
                'refresh_needed': bool(bound and status.get('connected') and status.get('refresh_needed')),
                'expires_in': round(expires_at - time.time()) if expires_at else None}
        if expired and status.get('state') == 'IDLE' and status.get('idle_reason') == 'ERROR':
            # The speaker failed on the expired link (not Stop or a finished mix). Keep the
            # mix and its position so the user can explicitly resume; monitors report IDLE
            # rows without media.
            view.update(state='EXPIRED', title=status.get('title'), source=status.get('source'),
                        duration=status.get('duration'), position=status.get('resume_position'))
        return view

    def link_state(self, status):
        connected = bool(status.get('connected'))
        return connected and bool(status.get('refresh_needed')), connected and bool(status.get('source_expired'))

    def tracker(self):
        cast = self.lab.cast
        if cast is None:
            raise ValueError('Connect to a speaker first.')
        if self.seek_tracker is None or self.seek_tracker.cast is not cast:
            self.seek_tracker = SeekTracker(cast)
        return self.seek_tracker

    def recovery_rows(self):
        rows = self.outputs.recovery_rows()
        confirmed = self.membership.confirmed_receiver()
        if confirmed:
            rows = [confirmed] + [r for r in rows if r['id'] != confirmed['id']]
        return rows

    def start(self, label, action, connecting=False):
        with self.lock:
            if self.busy:
                raise ValueError('Wait for the current operation or press Stop.')
            self.epoch += 1
            epoch = self.epoch
            self.operation_busy = True
            self.connecting = connecting
            self.message = label + '…'
        def run():
            try:
                action()
                if epoch == self.epoch:
                    self.message = label + ' completed.'
            except Exception as exc:
                if epoch == self.epoch:
                    self.message = 'Failed: ' + redacted(str(exc))
            finally:
                self.connecting = False
                self.operation_busy = False
        threading.Thread(target=run, daemon=True).start()

    def start_volume(self, label, action):
        """Volume runs in its own lane: one change at a time, never blocking transport or Stop.
        Each change keeps its own fresh-receiver confirmation."""
        with self.lock:
            if self.volume_busy:
                raise ValueError('Wait for the current volume change.')
            self.volume_busy = True
        def run():
            try:
                action()
            except Exception as exc:
                self.message = 'Failed: ' + label.lower() + ': ' + redacted(str(exc))
            finally:
                self.volume_busy = False
        threading.Thread(target=run, daemon=True).start()

    def connect(self):
        target = self.target
        def join():
            try:
                self.lab.connect(target)
                self.remember_remote()
                self.retry_at = 0
                self.retry_delay = 5
            except Exception:
                self.retry_at = time.monotonic() + self.retry_delay
                self.retry_delay = min(30, self.retry_delay * 2)
                raise
        self.start('Connecting', join, connecting=True)

    def remember_remote(self):
        infos = {str(i.uuid): i for i in discovered_cast_infos(self.lab.browser)}
        self.remote_preferences.remember(self.target, infos.get(self.target),
                                         self.membership.snapshot()['members'], infos)

    def maintain(self):
        while not self.closed:
            try:
                self.maintain_once()
            except Exception as exc:
                self.message = 'Connection: ' + redacted(str(exc))
            time.sleep(1)

    def maintain_once(self):
        # All evidence reads are cached. Serialize selection with user actions and Stop.
        with self.lock:
            self.inventory.observe(discovered_cast_infos(self.lab.browser))
            if self.busy:
                return
            devices = self.lab.devices()
            # No built-in default speaker: homes differ. Without a remembered remote the user
            # chooses one; until then nothing is connected.
            status = self.lab.status()
            if not self.session_recovery.last:
                self.group_follower.step(status)
            infos = {str(i.uuid): i for i in discovered_cast_infos(self.lab.browser)}
            rows = self.recovery_rows()
            replacement = self.session_recovery.step(self.target, rows, time.monotonic())
            if not replacement and not self.session_recovery.last:
                replacement = self.startup_candidate(rows, time.monotonic())
            if not replacement and not self.session_recovery.last:
                replacement = self.remote_preferences.replacement(
                    self.target, infos, bool(status.get('connected')), self.busy, time.monotonic())
            if replacement:
                self.membership.explicit_selection()
                self.target = replacement
                self.retry_at = 0
                self.retry_delay = 5
            if status.get('connected') and self.lab.selected_id == self.target:
                self.remember_remote()
            healthy = status.get('connected') and self.lab.selected_id == self.target
            if healthy:
                self.retry_at = 0
            elif self.target and any(uid == self.target for uid, _, _ in devices):
                now = time.monotonic()
                if self.lab.selected_id == self.target and self.retry_at == 0:
                    self.retry_at = now + 5
                elif now >= self.retry_at:
                    self.connect()

    def startup_candidate(self, rows, now):
        """After a restart, if the remembered remote is freshly idle and exactly one other output is
        playing a Moozaq-tagged mix, follow it. Read-only, once, within the first minute; any
        ambiguity or explicit choice ends the search without guessing."""
        if not self.startup_follow:
            return None
        if now - self.started_at > 60:
            self.startup_follow = False
            return None
        current = next((r for r in rows if r['id'] == self.target and r['fresh']), None)
        if self.target and current is None:
            return None  # The remembered remote has not been read yet.
        if current is not None and current['state'] in ACTIVE:
            self.startup_follow = False  # It is playing there; nothing to find.
            return None
        playing = [r for r in rows if r['id'] != self.target and r['fresh'] and r['state'] in ACTIVE
                   and r.get('content_key') and r.get('source')]
        if not playing:
            return None  # Other outputs may not have been read yet; keep looking until the deadline.
        self.startup_follow = False
        if len(playing) > 1:
            return None
        self.lab.log('startup-follow', f'remembered remote idle; following the one output playing a Moozaq mix ({playing[0].get("title") or "untitled"})')
        return playing[0]['id']

    def dispatch(self, request):
        op = request['op']
        if op == 'catalog':
            return self.catalog.snapshot()
        if op == 'import_channel':
            self.catalog.refresh(request.get('url'))
            return {'accepted': True}
        if op == 'state':
            return self.snapshot()
        if op == 'select':
            with self.lock:
                if self.busy:
                    raise ValueError('Wait for the current operation.')
                target = request['device']
                if not any(uid == target for uid, _, _ in self.lab.devices()):
                    raise ValueError('Speaker is no longer available.')
                self.membership.explicit_selection()
                self.session_recovery.clear()
                self.startup_follow = False
                self.target = target
                self.retry_at = 0
                self.retry_delay = 5
                self.remember_remote()
                self.connect()
        elif op == 'play':
            source = request['source']
            def play():
                self.lab.play_selection(source)
                self.transport_revision += 1
            self.start('Starting selected mix', play)
        elif op == 'set_playback_devices':
            with self.lock:
                if self.busy:
                    raise ValueError('Wait for the current operation or press Stop.')
                action = self.membership.prepare(request.get('devices'), request.get('token'))
                self.outputs.invalidate_topology()
                self.start('Changing playback speakers', action)
        elif op == 'dismiss_membership':
            self.membership.dismiss()
        elif op == 'output_volume':
            self.start_volume('Adjusting speaker volume', lambda: self.outputs.set_volume(request['device'], request['value']))
        elif op == 'member_volume':
            monitor = self.group_monitor
            if monitor is None:
                raise ValueError('Speaker group is not available.')
            self.start_volume('Adjusting speaker volume', lambda: monitor.set_member_volume(request['member'], request['value']))
        elif op == 'resume' and self.link_state(self.lab.status())[0]:
            # The link expired or would run out before the mix ends: resume on a fresh link.
            generation = self.lab.generation
            def refresh():
                try:
                    self.lab.refresh_source(paused=False, expected_generation=generation)
                except RefreshUnavailable:
                    # No new link, but the old one still works: plain Resume.
                    self.tracker().playback_confirmed(self.lab, paused=False, expected_generation=generation)
                self.transport_revision += 1
            self.start('Refreshing stream link', refresh)
        elif op in ('resume', 'pause'):
            cast = self.lab.cast
            if cast is None:
                raise ValueError('Connect to a speaker first.')
            if self.seek_tracker is None or self.seek_tracker.cast is not cast:
                self.seek_tracker = SeekTracker(cast)
            tracker = self.seek_tracker
            def transport():
                tracker.playback_confirmed(self.lab, paused=op == 'pause')
                self.transport_revision += 1
            self.start('Pausing' if op == 'pause' else 'Resuming', transport)
        elif op == 'volume':
            value = request.get('value')
            if not isinstance(value, (int, float)) or not 0 <= value <= 1:
                raise ValueError('Volume must be between 0 and 1.')
            cast = self.lab.cast
            if cast is None:
                raise ValueError('Connect to a speaker first.')
            def adjust_volume():
                if getattr(cast, 'cast_type', None) == 'group' and self.group_monitor is not None:
                    # The group volume is the confirmed average of its members; each member is
                    # written and confirmed individually, preserving their relative balance.
                    self.group_monitor.set_group_volume(value)
                else:
                    if self.volume_tracker is None or self.volume_tracker.cast is not cast:
                        self.volume_tracker = VolumeTracker(cast)
                    self.volume_tracker.set_confirmed(self.lab, value)
                self.volume_revision += 1
            self.start_volume('Adjusting volume', adjust_volume)
        elif op == 'seek':
            value = request.get('value')
            if not isinstance(value, (int, float)) or not 0 <= value <= 864000:
                raise ValueError('Invalid seek position.')
            status = self.lab.status()
            refresh, expired = self.link_state(status)
            if expired and status.get('state') not in ('PLAYING', 'PAUSED'):
                raise ValueError('The stream link expired. Press Resume to reload the mix first.')
            if refresh and status.get('state') in ('PLAYING', 'PAUSED'):
                paused = status.get('state') == 'PAUSED'
                generation = self.lab.generation
                # A seek is a new fetch; renew the link, reload at the target, keep play/pause.
                def refresh_at():
                    try:
                        self.lab.refresh_source(position=value, paused=paused, expected_generation=generation)
                        self.transport_revision += 1
                    except RefreshUnavailable:
                        self.tracker().seek_confirmed(self.lab, value, expected_generation=generation)
                    self.seek_revision += 1
                self.start('Refreshing stream link', refresh_at)
                return {'accepted': True}
            cast = self.lab.cast
            if cast is None:
                raise ValueError('Connect to a speaker first.')
            if self.seek_tracker is None or self.seek_tracker.cast is not cast:
                self.seek_tracker = SeekTracker(cast)
            tracker = self.seek_tracker
            def seek():
                tracker.seek_confirmed(self.lab, value)
                self.seek_revision += 1
            self.start('Seeking', seek)
        elif op == 'refresh':
            value = request.get('value')
            if op == 'volume' and not 0 <= float(value) <= 1:
                raise ValueError('Volume must be between 0 and 1.')
            if op == 'seek' and not 0 <= float(value) <= 864000:
                raise ValueError('Invalid seek position.')
            self.start(op.title(), lambda: self.lab.command(op, value))
        elif op == 'stop':
            # Stop invalidates acquisition immediately and is available while busy.
            with self.lock:
                if self.stopping:
                    return {'accepted': True}
                self.stopping = True
                self.epoch += 1
            self.lab.cancel()
            with self.lab.playback_gate:
                self.lab.generation += 1
            self.message = 'Stopping…'
            def stop():
                try:
                    self.membership.wait_for_stop()
                    self.lab.stop_verified()
                    with self.lock:
                        self.transport_revision += 1
                        self.session_recovery.clear()
                    self.message = 'Stopped: receiver confirmed playback ended.'
                except Exception as exc:
                    self.message = 'Stop unconfirmed: ' + redacted(str(exc))
                finally:
                    self.stopping = False
            threading.Thread(target=stop, daemon=True).start()
        else:
            raise ValueError('Unknown operation')
        return {'accepted': True}


def create_lab():
    # Seed before HostBrowser starts; adding afterwards can miss its first poll
    # and delay a reachable speaker by the full 30-second discovery interval.
    preferences = RemotePreferences(DATA / 'preferences.json')
    return Lab(known_hosts=preferences.known_hosts())


def start_bridge():
    """Discovery and monitors start here. Nothing on this path loads media or changes volume."""
    lab = create_lab()
    bridge = Bridge(lab)
    bridge.group_monitor = GroupMonitor(lab, bridge.membership)
    threading.Thread(target=bridge.group_monitor.run, daemon=True).start()
    threading.Thread(target=bridge.membership.run, daemon=True).start()
    threading.Thread(target=bridge.outputs.run, daemon=True).start()
    threading.Thread(target=bridge.maintain, daemon=True).start()
    return bridge


def handle_line(bridge, line):
    request = {}
    try:
        request = json.loads(line)
        result = {'id': request['id'], 'result': bridge.dispatch(request)}
    except Exception as exc:
        result = {'id': request.get('id'), 'error': redacted(str(exc))}
    return json.dumps(result)


def close_bridge(bridge):
    bridge.closed = True
    bridge.group_follower.close()
    bridge.membership.close()
    bridge.outputs.close()
    bridge.group_monitor.close()
    # Remote detachment only. Do not stop the Nest session on window close.
    bridge.lab.close()


def main():
    bridge = start_bridge()
    try:
        for line in sys.stdin:
            print(handle_line(bridge, line), flush=True)
    finally:
        close_bridge(bridge)


if __name__ == '__main__':
    main()
