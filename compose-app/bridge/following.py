"""Passively follow a known session into a receiver-reported temporary group.

No media loads, launches, membership changes or volume writes. A matching title
is deliberately insufficient: follow only a fresh group containing the original
speaker, with the exact same content fingerprint and plausible position.
"""
import threading
import time

import pychromecast
from pychromecast import dial
from pychromecast.controllers.multizone import MultizoneController
from engine import install_passive_status, receiver_identity, discovered_cast_infos


ACTIVE = ('PLAYING', 'PAUSED', 'BUFFERING')


def matches_session(previous, media, members, origin, elapsed):
    fingerprint, _, _ = receiver_identity(media)
    if origin not in members or not previous.get('content_key'):
        return False
    if fingerprint != previous['content_key'] or media.player_state not in ACTIVE:
        return False
    position = media.adjusted_current_time
    if position is None or previous.get('position') is None:
        return False
    # Transfer changes the mediaSessionId; compare media identity and continuity.
    return abs(position - previous['position']) <= max(0, elapsed) + 20


class EvidenceListener:
    def __init__(self):
        self.media = threading.Event()
        self.members = threading.Event()

    def new_media_status(self, status):
        self.media.set()

    def load_media_failed(self, queue_item_id, error_code):
        pass

    def multizone_member_added(self, uuid):
        pass

    def multizone_member_removed(self, uuid):
        pass

    def multizone_status_received(self):
        self.members.set()


class TemporaryGroupFollower:
    def __init__(self, bridge, clock=time.monotonic):
        self.bridge, self.clock = bridge, clock
        self.last = None
        self.probing = False
        self.retry_at = 0
        self.closed = threading.Event()

    def step(self, status):
        bridge, lab = self.bridge, self.bridge.lab
        cast = lab.cast
        if self.closed.is_set() or bridge.busy or cast is None:
            return
        if lab.selected_id != bridge.target or cast.cast_type == 'group':
            self.last = None
            return
        now = self.clock()
        if status.get('connected') and status.get('state') in ACTIVE and status.get('content_key'):
            self.last = (bridge.target, cast, dict(status), now)
            return
        if not self.last or self.probing or now < self.retry_at:
            return
        origin, old_cast, previous, seen = self.last
        if origin != bridge.target or old_cast is not cast or now - seen > 60:
            self.last = None
            return
        self.probing = True
        self.retry_at = now + 5
        epoch = bridge.epoch
        threading.Thread(target=self._probe, args=(origin, old_cast, previous, seen, epoch), daemon=True).start()

    def _probe(self, origin, old_cast, previous, seen, epoch):
        candidate = None
        lab = self.bridge.lab
        try:
            infos = {str(i.uuid): i for i in discovered_cast_infos(lab.browser)}
            info = infos.get(origin)
            if info is None:
                return
            _, data = dial._get_status(info.services, lab.zconf,
                                      '/setup/eureka_info?params=multizone', True, 4, None)
            # Explicit receiver dynamic_groups evidence, never a saved group or
            # merely a friendly name ending in '+ 1'. Ambiguity means no follow.
            dynamic = data.get('multizone', {}).get('dynamic_groups', [])
            ids = {str(row.get('uuid')) for row in dynamic if isinstance(row, dict)}
            eligible = [infos[uid] for uid in ids if uid in infos and infos[uid].cast_type == 'group']
            if len(eligible) != 1 or self.closed.is_set():
                return
            info = eligible[0]
            candidate = pychromecast.get_chromecast_from_cast_info(info, lab.zconf, tries=1, timeout=3)
            install_passive_status(candidate)
            evidence = EvidenceListener()
            mz = MultizoneController(info.uuid)
            mz.register_listener(evidence)
            candidate.register_handler(mz)
            candidate.media_controller.register_status_listener(evidence)
            candidate.wait(timeout=5)
            evidence.members.clear()
            evidence.media.clear()
            mz.update_members()
            candidate.media_controller.update_status()
            if not evidence.members.wait(3) or not evidence.media.wait(3):
                return
            if not matches_session(previous, candidate.media_controller.status, mz.members,
                                   origin, self.clock() - seen):
                return
            candidate.unregister_handler(mz)
            if self._adopt(origin, old_cast, candidate, epoch):
                candidate = None  # Main Lab now owns this already-synchronized socket.
                self.last = None
        except Exception as exc:
            # URLs and protocol payloads never enter diagnostics.
            lab.log('temporary-group-follow', type(exc).__name__)
        finally:
            if candidate is not None:
                candidate.disconnect()
            self.probing = False

    def _adopt(self, origin, old_cast, candidate, epoch):
        bridge, lab = self.bridge, self.bridge.lab
        with bridge.lock, lab.playback_gate:
            if (self.closed.is_set() or bridge.closed or bridge.busy or bridge.epoch != epoch
                    or bridge.target != origin or lab.cast is not old_cast
                    or not candidate.socket_client.is_connected):
                return False
            # Recheck the old endpoint: a new individual session must win.
            current = lab.status()
            if current.get('state') in ACTIVE and current.get('content_key'):
                return False
            candidate.media_controller.register_status_listener(lab)
            lab.generation += 1
            lab.cast = candidate
            lab.selected_id = str(candidate.uuid)
            bridge.target = str(candidate.uuid)
            bridge.retry_at = 0
            bridge.retry_delay = 5
            bridge.epoch += 1
            # Do not persist an ephemeral group as the startup speaker preference.
            bridge.message = 'Following existing playback on the temporary speaker group.'
        old_cast.disconnect()
        lab.log('temporary-group-follow', f'origin={origin}; group={candidate.uuid}; passive session handoff')
        return True

    def close(self):
        self.closed.set()
