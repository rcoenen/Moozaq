"""Passive group observation and explicit, confirmed member volume controls."""
import threading
import time
import pychromecast
from engine import install_passive_status, discovered_cast_infos
from pychromecast.controllers.multizone import MultizoneController


class MemberListener:
    def __init__(self, monitor, uid, cast):
        self.monitor, self.uid, self.cast = monitor, uid, cast

    def new_media_status(self, status):
        self.monitor.record_member(self.uid, self.cast, status)

    def new_cast_status(self, status):
        with self.monitor.lock:
            self.monitor.receipts[self.uid] = self.monitor.receipts.get(self.uid, 0) + 1
        self.monitor.record_member(self.uid, self.cast, self.cast.media_controller.status)

    def load_media_failed(self, queue_item_id, error_code):
        self.monitor.lab.log('group-member-load-failed', f'{self.uid}: code {error_code}')


class GroupMonitor:
    def __init__(self, lab, membership_service=None):
        self.lab = lab
        self.membership_service = membership_service
        self.lock = threading.RLock()
        self.cast = None
        self.controller = None
        self.members = {}
        self.states = {}
        self.receipts = {}
        self.volume_revisions = {}
        self.closed = threading.Event()
        self.last_topology = None
        self.view = None

    def snapshot(self):
        # Never return details from a previously selected receiver.
        with self.lock:
            if self.cast is not self.lab.cast or self.view is None:
                return None
            return {**self.view, 'members': [dict(self.states.get(row['id'], row)) for row in self.view['members']]}

    def record_member(self, uid, cast, media):
        state = {'id': uid, 'name': cast.cast_info.friendly_name or uid,
                 'connected': bool(cast.socket_client.is_connected),
                 'state': media.player_state or 'UNKNOWN',
                 'muted': bool(cast.status.volume_muted),
                 'volume_revision': self.volume_revisions.get(uid, 0),
                 'volume': cast.status.volume_level if isinstance(cast.status.volume_level, (float, int)) else None}
        with self.lock:
            if self.members.get(uid) is not cast:
                return
            previous = self.states.get(uid)
            self.states[uid] = state
        if previous != state:
            self.lab.log('group-member-state', str(state))

    def set_member_volume(self, uid, value, timeout=6):
        if not isinstance(value, (float, int)) or not 0 <= value <= 1:
            raise ValueError('Volume must be between 0 and 1.')
        group, generation = self.lab.cast, self.lab.generation
        member = self.members.get(uid)

        def validate():
            if (self.closed.is_set() or group is None or self.cast is not group
                    or self.lab.cast is not group or generation != self.lab.generation
                    or member is None or self.members.get(uid) is not member
                    or uid not in self.controller.members
                    or not group.socket_client.is_connected
                    or not member.socket_client.is_connected):
                raise RuntimeError('Group or member changed; volume adjustment cancelled.')

        with self.lab.playback_gate:
            validate()
            with self.lock:
                receipt = self.receipts.get(uid, 0)
            member.set_volume(value)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            validate()
            member.socket_client.receiver_controller.update_status()
            with self.lock:
                fresh = self.receipts.get(uid, 0) > receipt
            level = member.status.volume_level
            if fresh and isinstance(level, (float, int)) and abs(level - value) < 0.01:
                with self.lock:
                    self.volume_revisions[uid] = self.volume_revisions.get(uid, 0) + 1
                self.record_member(uid, member, member.media_controller.status)
                return
            self.closed.wait(0.15)
        raise TimeoutError('Speaker volume was not confirmed. Read current volume before retrying.')

    def reset(self):
        with self.lock:
            members, self.members = self.members, {}
            self.states = {}
            self.view = None
        for cast in members.values():
            cast.disconnect()
        if self.cast and self.controller and self.membership_service is None:
            try:
                self.cast.unregister_handler(self.controller)
            except Exception:
                pass
        self.cast = self.controller = None
        self.last_topology = None

    def step(self):
        selected = self.lab.cast
        if selected is not self.cast:
            self.reset()
            if selected is None or selected.cast_type != 'group':
                return
            self.cast = selected
            if self.membership_service is not None:
                self.controller = self.membership_service.controller_for(selected)
            else:
                self.controller = MultizoneController(selected.uuid)
                selected.register_handler(self.controller)
        if self.cast is None:
            return
        cast = self.cast
        if not cast.socket_client.is_connected:
            with self.lock:
                self.view = {'leader': 'Unavailable', 'members': [], 'status': 'Group disconnected; membership unverified'}
            return
        self.controller.update_members()
        infos = {str(i.uuid): i for i in discovered_cast_infos(self.lab.browser)}
        ids = list(self.controller.members)
        host, port = cast.socket_client.host, cast.socket_client.port
        leader = next((i.friendly_name for i in infos.values() if i.cast_type != 'group' and i.host == host), host)
        topology = (str(cast.uuid), host, port, tuple(sorted(ids)))
        if topology != self.last_topology:
            self.lab.log('group-topology', f'group={cast.uuid}; leader={leader}; endpoint={host}:{port}; members={sorted(ids)}')
            self.last_topology = topology
        for uid in list(self.members):
            if uid not in ids:
                self.members.pop(uid).disconnect()
                self.states.pop(uid, None)
        for uid in ids:
            if self.closed.is_set() or self.lab.cast is not cast:
                return
            info = infos.get(uid)
            if uid not in self.members and info:
                member = None
                try:
                    member = pychromecast.get_chromecast_from_cast_info(info, self.lab.zconf, tries=1, timeout=3)
                    install_passive_status(member)
                    self.members[uid] = member
                    listener = MemberListener(self, uid, member)
                    member.media_controller.register_status_listener(listener)
                    member.register_status_listener(listener)
                    member.wait(timeout=4)
                except Exception:
                    if member: member.disconnect()
                    self.members.pop(uid, None)
            member = self.members.get(uid)
            if member:
                if member.status.app_id and member.socket_client.is_connected:
                    member.media_controller.update_status()
                self.record_member(uid, member, member.media_controller.status)
        with self.lock:
            rows = [dict(self.states.get(uid) or {'id': uid, 'name': getattr(infos.get(uid), 'friendly_name', None) or uid,
                    'connected': False, 'state': 'UNKNOWN', 'muted': False}) for uid in ids]
            self.view = {'leader': leader or 'Unknown', 'members': rows,
                         'status': 'Member output is not verified' if ids else 'Waiting for group membership'}

    def run(self):
        try:
            while not self.closed.is_set():
                try:
                    self.step()
                except Exception as exc:
                    self.lab.log('group-monitor', type(exc).__name__)
                self.closed.wait(3)
        finally:
            self.reset()

    def close(self):
        self.closed.set()
