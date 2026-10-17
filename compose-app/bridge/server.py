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
from engine import Lab, DATA, redacted, discovered_cast_infos
from groups import GroupMonitor
from volume import VolumeTracker
from seeking import SeekTracker
from catalog import Catalog
from following import TemporaryGroupFollower
from membership import MembershipService
from outputs import OutputMonitor
from recovery import RemotePreferences, SessionRecovery
from inventory import DeviceInventory


class Bridge:
    def __init__(self, lab):
        self.lab = lab
        self.lock = threading.RLock()
        self.operation_busy = False
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
        observed = next((r for r in self.outputs.recovery_rows() if r['id'] == self.target), None)
        synchronized = bool(observed and observed['fresh'] and observed['state'] != 'UNKNOWN'
                            and self.lab.selected_id == self.target)
        if synchronized and status.get('connected'):
            for key in ('state', 'title', 'source', 'position', 'duration'):
                receiver[key] = observed.get(key)
            receiver['volume'] = round(observed['volume'] * 100) if observed.get('volume') is not None else None
        elif status.get('connected'):
            receiver.update(connected=False, state='UNKNOWN', volume=None)
        with self.lock:
            receiver = self.session_recovery.present(receiver, time.monotonic())
            recovering = self.session_recovery.recovering
        if self.target and not synchronized and not recovering:
            receiver.update(connected=False, state='UNKNOWN', recovery='Waiting for speaker…')
        infos = {str(info.uuid): info for info in discovered_cast_infos(self.lab.browser)}
        return {**self.catalog.status(), 'devices': self.inventory.snapshot(infos.values()),
                'receiver': receiver, 'target': self.target, 'busy': self.busy,
                'connecting': self.connecting or bool(status.get('reconnecting')) or recovering or bool(self.target and not synchronized), 'message': self.message,
                'group': self.group_monitor.snapshot() if self.group_monitor else None,
                'membership': self.membership.snapshot(), 'outputs': self.outputs.snapshot()}

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
            if not self.target:
                self.target = next((uid for uid, name, _ in devices if name == 'Bedroom speaker'), None)
            status = self.lab.status()
            if not self.session_recovery.last:
                self.group_follower.step(status)
            infos = {str(i.uuid): i for i in discovered_cast_infos(self.lab.browser)}
            replacement = self.session_recovery.step(self.target, self.outputs.recovery_rows(), time.monotonic())
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
                self.start('Changing playback speakers', action)
        elif op == 'dismiss_membership':
            self.membership.dismiss()
        elif op == 'output_volume':
            self.start('Adjusting speaker volume', lambda: self.outputs.set_volume(request['device'], request['value']))
        elif op == 'member_volume':
            monitor = self.group_monitor
            if monitor is None:
                raise ValueError('Speaker group is not available.')
            self.start('Adjusting speaker volume', lambda: monitor.set_member_volume(request['member'], request['value']))
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
            if self.volume_tracker is None or self.volume_tracker.cast is not cast:
                self.volume_tracker = VolumeTracker(cast)
            tracker = self.volume_tracker
            def adjust_volume():
                tracker.set_confirmed(self.lab, request['value'])
                self.volume_revision += 1
            self.start('Adjusting volume', adjust_volume)
        elif op == 'seek':
            value = request.get('value')
            if not isinstance(value, (int, float)) or not 0 <= value <= 864000:
                raise ValueError('Invalid seek position.')
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


def main():
    lab = create_lab()
    bridge = Bridge(lab)
    bridge.group_monitor = GroupMonitor(lab, bridge.membership)
    threading.Thread(target=bridge.group_monitor.run, daemon=True).start()
    threading.Thread(target=bridge.membership.run, daemon=True).start()
    threading.Thread(target=bridge.outputs.run, daemon=True).start()
    threading.Thread(target=bridge.maintain, daemon=True).start()
    try:
        for line in sys.stdin:
            request = {}
            try:
                request = json.loads(line)
                result = {'id': request['id'], 'result': bridge.dispatch(request)}
            except Exception as exc:
                result = {'id': request.get('id'), 'error': redacted(str(exc))}
            print(json.dumps(result), flush=True)
    finally:
        bridge.closed = True
        bridge.group_follower.close()
        bridge.membership.close()
        bridge.outputs.close()
        bridge.group_monitor.close()
        # Remote detachment only. Do not stop the Nest session on window close.
        lab.close()


if __name__ == '__main__':
    main()
