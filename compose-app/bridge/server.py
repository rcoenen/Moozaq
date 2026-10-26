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
from local_player import LOCAL_OUTPUT_ID, create_local_player


class Bridge:
    def __init__(self, lab, catalog=None, profile=None, updater=None, local=None):
        self.lab = lab
        self.local = local
        self.lock = threading.RLock()
        self.operation_busy = False
        self.volume_busy = False
        self.stopping = False
        self.connecting = False
        self.connection_error = None
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
        self.catalog = catalog if catalog is not None else Catalog(DATA / "channel-catalog.json", json.loads((Path(__file__).parent / "channels.json").read_text()))
        self.profile = profile if profile is not None else getattr(catalog, 'personal', None)
        self.updater = updater if updater is not None else getattr(catalog, 'updater', None)
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

    @property
    def local_selected(self):
        return self.local is not None and self.target == LOCAL_OUTPUT_ID

    def local_capability(self):
        return LOCAL_OUTPUT_ID if self.local is not None and self.local.available else None

    def devices_snapshot(self):
        infos = {str(info.uuid): info for info in discovered_cast_infos(self.lab.browser)}
        devices = self.inventory.snapshot(infos.values())
        temporary = self.membership.temporary_ids | self.outputs.temporary_ids()
        saved = self.outputs.saved_group_ids()
        saved_known = self.outputs.topology_loaded()
        for device in devices:
            device['temporary'] = temporary_group(device['id'], device.get('cast_type'),
                                                  temporary, saved, saved_known)
        if self.local is not None:
            devices.insert(0, {'id': LOCAL_OUTPUT_ID, 'name': 'This Mac', 'model': 'Mac',
                               'cast_type': 'local', 'available': bool(self.local.available), 'temporary': False})
        return devices

    def local_snapshot(self):
        status = self.local.status()
        receiver = {key: status.get(key) for key in
                    ('connected', 'device', 'title', 'state', 'position', 'duration', 'volume', 'source', 'mode', 'idle_reason', 'recovery')}
        receiver['volume_revision'] = self.volume_revision
        receiver['seek_revision'] = self.seek_revision
        receiver['transport_revision'] = self.transport_revision + status.get('event_transport_revision', 0)
        receiver.update(self.expiry_view(status, None))
        if receiver.get('state') == 'EXPIRED':
            receiver['recovery'] = None
        membership = dict(self.membership.snapshot(), members=[], available=False,
                          token=None, pending=None, error=None)
        return {**self.catalog.status(), 'devices': self.devices_snapshot(),
                'receiver': receiver, 'target': self.target, 'busy': self.busy,
                'connecting': self.connecting, 'message': self.message,
                'connection_error': self.connection_error,
                'local_output': self.local_capability(),
                'catalog_backend': getattr(self.catalog, 'backend_name', 'legacy'),
                'catalog_curation': bool(getattr(self.catalog, 'supports_curation', False)),
                'catalog_updates': self.updater is not None,
                'personal_profile': self.profile is not None,
                'personal_revision': self.profile.revision() if self.profile is not None else 0,
                'group': None, 'membership': membership, 'outputs': self.outputs.snapshot()}

    def snapshot(self):
        if self.local_selected:
            return self.local_snapshot()
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
        devices = self.devices_snapshot()
        if getattr(self.catalog, 'listening', None) is not None:
            self.catalog.observe_playback({
                'connected': receiver.get('connected'), 'state': receiver.get('state'),
                'source': receiver.get('source'), 'position': receiver.get('position'),
                'media_session': status.get('media_session'),
                'fresh': synchronized and not recovering and receiver.get('state') == 'PLAYING',
            })
        return {**self.catalog.status(), 'devices': devices,
                'receiver': receiver, 'target': self.target, 'busy': self.busy,
                'connecting': self.connecting or bool(status.get('reconnecting')) or recovering or bool(self.target and not synchronized), 'message': self.message,
                'connection_error': self.connection_error,
                'local_output': self.local_capability(),
                'catalog_backend': getattr(self.catalog, 'backend_name', 'legacy'),
                'catalog_curation': bool(getattr(self.catalog, 'supports_curation', False)),
                'catalog_updates': self.updater is not None,
                'personal_profile': self.profile is not None,
                'personal_revision': self.profile.revision() if self.profile is not None else 0,
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
        self.connection_error = None
        def join():
            joined = False
            try:
                if self.local_selected:
                    self.local.select()
                else:
                    self.lab.connect(target)
                joined = True
                if not self.local_selected:
                    self.remember_remote()
                self.connection_error = None
                self.retry_at = 0
                self.retry_delay = 5
            except Exception as exc:
                if not joined:
                    self.connection_error = redacted(str(exc))
                self.retry_at = time.monotonic() + self.retry_delay
                self.retry_delay = min(30, self.retry_delay * 2)
                raise
        self.start('Connecting', join, connecting=True)

    def remember_remote(self):
        if self.local_selected:
            return
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
            if self.busy or self.local_selected:
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

    def assert_output_change_allowed(self, target):
        if target == LOCAL_OUTPUT_ID:
            if self.local is None or not self.local.available:
                raise ValueError('This Mac audio helper is unavailable.')
            if not self.local_selected and self.target is not None:
                if self.snapshot()['receiver'].get('state') != 'IDLE':
                    raise ValueError('Stop casting before choosing This Mac.')
        elif self.local_selected and self.local.status().get('state') != 'IDLE':
            raise ValueError('Stop on this Mac before choosing a speaker.')

    def dispatch(self, request):
        op = request['op']
        if op == 'catalog':
            return self.catalog.snapshot()
        if op == 'master_catalog':
            if not getattr(self.catalog, 'supports_curation', False):
                raise ValueError('Master catalog access is not available for this catalog.')
            return self.catalog.master.snapshot()
        if op == 'personal_profile':
            if self.profile is None:
                raise ValueError('Personal data is not available for this catalog.')
            return self.profile.profile()
        if op == 'rate_song':
            if self.profile is None:
                raise ValueError('Personal data is not available for this catalog.')
            return self.profile.set_rating(request['song'], request['rating'])
        if op == 'reset_personal':
            if self.profile is None:
                raise ValueError('Personal data is not available for this catalog.')
            reset = getattr(self.catalog, 'reset_personal', None)
            if callable(reset) and self.profile is getattr(self.catalog, 'personal', None):
                return reset()
            return self.profile.reset()
        if op == 'publish_release':
            if not getattr(self.catalog, 'supports_curation', False):
                raise ValueError('Catalog releases are not available for this catalog.')
            return self.catalog.publish_release(
                confirmed=request.get('confirmed'),
                expected_revision=request.get('expected_revision'),
            )
        if op == 'check_updates':
            if self.updater is None:
                raise ValueError('Catalog updates are not available for this catalog.')
            return self.catalog.check_updates()
        if op == 'rebuild_catalog':
            if not getattr(self.catalog, 'supports_curation', False):
                raise ValueError('Catalog rebuild is not available for this catalog.')
            return self.catalog.rebuild()
        if op in ('preview_video', 'import_video', 'follow_channel'):
            if not getattr(self.catalog, 'supports_curation', False):
                raise ValueError('Catalog curation is not available for this catalog.')
            if op == 'preview_video':
                return self.catalog.preview_video(request['source'])
            if op == 'import_video':
                return self.catalog.import_video(request['source'], recommend=bool(request.get('recommend')))
            return self.catalog.follow_channel(request['url'], refresh=bool(request.get('refresh')))
        if op == 'curate':
            if not getattr(self.catalog, 'supports_curation', False):
                raise ValueError('Catalog curation is not available for this catalog.')
            fields = {key: value for key, value in request.items() if key not in ('op', 'id', 'action')}
            return self.catalog.curate(request['action'], **fields)
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
                self.assert_output_change_allowed(target)
                if target != LOCAL_OUTPUT_ID and not any(uid == target for uid, _, _ in self.lab.devices()):
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
                if self.local_selected:
                    self.local.play_selection(source)
                else:
                    self.lab.play_selection(source)
                self.transport_revision += 1
            self.start('Starting on this Mac' if self.local_selected else 'Starting selected mix', play)
        elif op == 'set_playback_devices':
            if self.local_selected:
                raise ValueError('Speaker membership is not available on This Mac.')
            with self.lock:
                if self.busy:
                    raise ValueError('Wait for the current operation or press Stop.')
                action = self.membership.prepare(request.get('devices'), request.get('token'))
                self.outputs.invalidate_topology()
                self.start('Changing playback speakers', action)
        elif op == 'dismiss_membership':
            self.membership.dismiss()
        elif op == 'output_volume':
            if self.local_selected:
                raise ValueError('Use the main This Mac volume control.')
            self.start_volume('Adjusting speaker volume', lambda: self.outputs.set_volume(request['device'], request['value']))
        elif op == 'member_volume':
            if self.local_selected:
                raise ValueError('Speaker group volume is not available on This Mac.')
            monitor = self.group_monitor
            if monitor is None:
                raise ValueError('Speaker group is not available.')
            self.start_volume('Adjusting speaker volume', lambda: monitor.set_member_volume(request['member'], request['value']))
        elif op in ('resume', 'pause') and self.local_selected:
            def local_transport():
                if op == 'resume':
                    self.local.resume()
                else:
                    self.local.pause()
                self.transport_revision += 1
            self.start('Pausing on this Mac' if op == 'pause' else 'Resuming on this Mac', local_transport)
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
            if self.local_selected:
                def adjust_local_volume():
                    self.local.volume(value)
                    self.volume_revision += 1
                self.start_volume('Adjusting This Mac volume', adjust_local_volume)
                return {'accepted': True}
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
            if self.local_selected:
                previous_session = self.local.status().get('media_session')
                def seek_local():
                    self.local.seek(value)
                    if self.local.status().get('media_session') != previous_session:
                        self.transport_revision += 1
                    self.seek_revision += 1
                self.start('Seeking on this Mac', seek_local)
                return {'accepted': True}
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
        elif op == 'refresh' and self.local_selected:
            self.start('Reading local playback', lambda: self.local.status())
        elif op == 'refresh':
            value = request.get('value')
            if op == 'volume' and not 0 <= float(value) <= 1:
                raise ValueError('Volume must be between 0 and 1.')
            if op == 'seek' and not 0 <= float(value) <= 864000:
                raise ValueError('Invalid seek position.')
            self.start(op.title(), lambda: self.lab.command(op, value))
        elif op == 'stop':
            # Stop invalidates acquisition immediately and is available while busy.
            local_stop = self.local is not None and self.target in (LOCAL_OUTPUT_ID, None)
            with self.lock:
                if self.stopping:
                    return {'accepted': True}
                self.stopping = True
                self.epoch += 1
            self.lab.cancel()
            if not local_stop:
                with self.lab.playback_gate:
                    self.lab.generation += 1
            self.message = 'Stopping…'
            def stop():
                try:
                    if local_stop:
                        self.local.stop()
                    else:
                        self.membership.wait_for_stop()
                        self.lab.stop_verified()
                    with self.lock:
                        self.transport_revision += 1
                        self.session_recovery.clear()
                    self.message = 'Stopped on this Mac.' if local_stop else 'Stopped: receiver confirmed playback ended.'
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


def start_bridge(catalog=None, profile=None, updater=None, enable_local=False):
    """Discovery and monitors start here. Nothing on this path loads media or changes volume."""
    lab = create_lab()
    arguments = {'catalog': catalog, 'profile': profile, 'updater': updater}
    if enable_local:
        helper = os.environ.get('MOOZAQ_MAC_AUDIO_PLAYER', str(ROOT / 'compose-app/composeApp/build/macos/MoozaqAudioPlayer'))
        arguments['local'] = create_local_player(lab, helper)
    bridge = Bridge(lab, **arguments)
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
    if bridge.group_monitor is not None:
        bridge.group_monitor.close()
    if bridge.local is not None:
        bridge.local.close()
    if hasattr(bridge.catalog, 'close'):
        bridge.catalog.close()
    # Cast detaches without stopping; local playback stops with its owning app.
    bridge.lab.close()


def main():
    if os.environ.get('MOOZAQ_LEGACY_CATALOG') == '1':
        bridge = start_bridge(enable_local=True)
    else:
        from desktop_catalog import create_desktop_catalog
        catalog = create_desktop_catalog(
            DATA, Path(__file__).parent / "channels.json",
            automatic_updates=os.environ.get('MOOZAQ_CATALOG_AUTOMATIC_UPDATES') != '0',
        )
        bridge = start_bridge(catalog=catalog, enable_local=True)
    try:
        for line in sys.stdin:
            print(handle_line(bridge, line), flush=True)
    finally:
        close_bridge(bridge)


if __name__ == '__main__':
    main()
