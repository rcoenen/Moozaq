"""Confirmed native temporary playback membership; never writes saved groups."""
import ipaddress
import threading
import time
import uuid

import pychromecast
from pychromecast.models import CastInfo, HostServiceInfo
from engine import install_passive_status, receiver_identity, discovered_cast_infos
from grouping import PlaybackMembershipController
from following import ACTIVE, EvidenceListener


def canonical(value):
    return str(uuid.UUID(str(value)))


def member_ids(status):
    return sorted({canonical(row['deviceId']) for row in status.get('devices', [])})


class ObservedController(PlaybackMembershipController):
    def __init__(self):
        super().__init__()
        self.query_lock = threading.Lock()

    def refresh(self, timeout=4):
        with self.query_lock:
            return super().refresh(timeout)

    @property
    def members(self):
        return member_ids(self.status or {})

    def update_members(self):
        self.refresh()


def endpoint_info(endpoint, desired, infos, previous):
    """Only connect to a private endpoint on a known requested speaker host."""
    uid = canonical(endpoint['deviceId'])
    host, port = endpoint['ipAddress'], endpoint['port']
    address = ipaddress.ip_address(host)
    allowed_hosts = {infos[uid].host for uid in desired}
    if not address.is_private or address.is_loopback or host not in allowed_hosts:
        raise ValueError('Returned group endpoint is not on a requested local speaker.')
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError('Invalid returned group port.')
    if len(desired) == 1:
        if uid != desired[0]:
            raise ValueError('Returned individual endpoint is not the requested speaker.')
        known = infos[uid]
        name, model, kind = known.friendly_name, known.model_name, known.cast_type
    else:
        known = infos.get(uid)
        name = known.friendly_name if known else ' + '.join(infos[i].friendly_name for i in desired)
        model, kind = 'Google Cast Group', 'group'
    return CastInfo({HostServiceInfo(host, port)}, uuid.UUID(uid), model, name,
                    host, port, kind, previous.cast_info.manufacturer)


class MembershipService:
    def __init__(self, bridge):
        self.bridge, self.lab = bridge, bridge.lab
        self.lock = threading.RLock()
        self.cast = self.controller = None
        self.same_endpoint_evidence = None
        self.handoff = None
        self.temporary_ids = set()
        self.observed = None
        self.binding = None
        self.token = None
        self.revision = 0
        self.pending = None
        self.error = None
        self.active = False
        self.sent = False
        self.uncertain = False
        self.settled = threading.Event()
        self.settled.set()
        self.closed = threading.Event()

    def controller_for(self, cast):
        with self.lock:
            if cast is not self.lab.cast:
                raise RuntimeError('Receiver changed before membership observation attached.')
            if self.cast is not cast:
                if self.cast is not None and self.controller is not None:
                    try:
                        self.cast.unregister_handler(self.controller)
                    except Exception:
                        pass
                self.cast = cast
                self.controller = ObservedController()
                cast.register_handler(self.controller)
                self.observed = None
                self.binding = self.token = None
            return self.controller

    def _record(self, cast, status):
        ids = member_ids(status)
        fingerprint, _, _ = receiver_identity(cast.media_controller.status)
        binding = (id(cast), cast.status.session_id, fingerprint, tuple(ids))
        with self.lock:
            if self.lab.cast is not cast:
                return
            if binding != self.binding:
                self.revision += 1
                self.token = uuid.uuid4().hex
                self.binding = binding
            self.observed = (cast, status, time.monotonic())

    def snapshot(self):
        with self.lock:
            observed = self.observed
            current = self.lab.cast
            fresh = bool(observed and observed[0] is current and time.monotonic() - observed[2] < 12)
            status = observed[1] if fresh else {}
            media = current.media_controller.status if current is not None else None
            active = bool(current and current.socket_client.is_connected and media.player_state in ACTIVE
                          and isinstance(current.status.session_id, str) and current.status.session_id)
            fingerprint = receiver_identity(media)[0] if active else None
            same_session = bool(self.binding and current and self.binding[:3] ==
                                (id(current), current.status.session_id, fingerprint))
            supports = status.get('playbackSession', {})
            available = bool(fresh and active and same_session and fingerprint
                             and supports.get('appAllowsGrouping') is True
                             and supports.get('isVideoContent') is False
                             and supports.get('streamTransferSupported') is True
                             and not self.uncertain)
            return {'members': member_ids(status) if active and same_session else [],
                    'available': available, 'token': self.token if available else None,
                    'pending': list(self.pending) if self.pending is not None else None,
                    'error': self.error, 'revision': self.revision}

    def run(self):
        while not self.closed.is_set():
            try:
                cast = self.lab.cast
                if cast is not None and cast.socket_client.is_connected and not self.bridge.busy:
                    controller = self.controller_for(cast)
                    if self.handoff and self.handoff[0] is cast:
                        # Keep the adopted endpoint authoritative even if its
                        # multicast announcement has not appeared yet.
                        cast.media_controller.update_status()
                    status = controller.refresh()
                    self._record(cast, status)
            except Exception as exc:
                self.lab.log('playback-membership-status', type(exc).__name__)
            self.closed.wait(3)

    def confirmed_receiver(self):
        """Fresh evidence from the verified handoff socket, before mDNS catches up."""
        with self.lock:
            if self.handoff is None:
                return None
            cast, evidence, session, fingerprint = self.handoff
            now = time.monotonic()
            media = cast.media_controller.status
            if (cast is not self.lab.cast or not cast.socket_client.is_connected
                    or now - evidence.media_at >= 12 or cast.status.session_id != session
                    or receiver_identity(media)[0] != fingerprint
                    or media.player_state not in ACTIVE):
                return None
            observed = self.observed
            if not observed or observed[0] is not cast or now - observed[2] >= 12:
                return None
            return dict(id=str(cast.uuid), fresh=True, state=media.player_state,
                        content_key=fingerprint, title=media.title,
                        source=receiver_identity(media)[1], position=media.adjusted_current_time,
                        duration=media.duration, volume=cast.status.volume_level,
                        members=member_ids(observed[1]))

    def prepare(self, devices, token):
        # Caller holds bridge.lock, making preparation and start indivisible.
        if not isinstance(devices, list) or not devices:
            raise ValueError('Keep at least one speaker selected, or use Stop.')
        desired = sorted(canonical(value) for value in devices)
        if len(set(desired)) != len(desired):
            raise ValueError('Duplicate speaker.')
        infos = {str(info.uuid): info for info in discovered_cast_infos(self.lab.browser)}
        if any(uid not in infos or infos[uid].cast_type != 'audio' for uid in desired):
            raise ValueError('Only discovered individual audio speakers can join playback.')
        view = self.snapshot()
        if not view['available'] or not token or token != view['token']:
            raise ValueError('Playback membership changed. Read current speakers before changing it.')
        cast = self.lab.cast
        controller = self.controller_for(cast)
        expected = (cast, self.lab.generation, self.bridge.epoch + 1,
                    cast.status.session_id, receiver_identity(cast.media_controller.status)[0], view['members'])
        with self.lock:
            if self.active:
                raise ValueError('A speaker change is already pending.')
            if self.uncertain:
                raise ValueError('Previous speaker change location is unconfirmed. Reconnect to the actual playback first.')
            self.active, self.sent = True, False
            self.pending, self.error = desired, None
            self.settled.clear()
        return lambda: self._change(desired, infos, controller, expected)

    def _validate_before_send(self, expected):
        cast, generation, epoch, session, fingerprint, _ = expected
        if (self.closed.is_set() or self.bridge.closed or self.bridge.stopping
                or self.lab.cast is not cast or self.lab.generation != generation
                or self.bridge.epoch != epoch or cast.status.session_id != session
                or receiver_identity(cast.media_controller.status)[0] != fingerprint):
            raise RuntimeError('Playback changed; speaker change cancelled before sending.')

    def _change(self, desired, infos, controller, expected):
        cast, _, _, session, fingerprint, old_members = expected
        candidate = None
        try:
            # Recheck capabilities and membership from the receiver before mutation.
            status = controller.refresh()
            flags = status.get('playbackSession', {})
            if (flags.get('appAllowsGrouping') is not True or flags.get('isVideoContent') is not False
                    or flags.get('streamTransferSupported') is not True):
                raise RuntimeError('This receiver session does not allow audio grouping.')
            if member_ids(status) != old_members:
                self._record(cast, status)
                raise RuntimeError('Playback speakers changed before sending. Read the current membership.')
            with self.lab.playback_gate:
                self._validate_before_send(expected)
                self.sent = self.uncertain = True
                controller.set_devices(session, [infos[uid] for uid in desired])
            # After send, even Stop must collect the reply and find the moved session.
            # Never retry SET_PLAYBACK_DEVICES after a timeout or cancellation.
            if not controller.response_event.wait(15):
                raise TimeoutError('Speaker change response missing; playback location is uncertain.')
            response = controller.response or {}
            if response.get('type') != 'PLAYBACK_SESSION_UPDATED':
                raise RuntimeError('Speaker change was not confirmed; playback location is uncertain.')
            endpoint = response.get('sessionEndpoint')
            if not endpoint:
                # An in-place group expansion has no endpoint handoff. Verify
                # that the original session still owns this connection before
                # accepting fresh membership; an omitted endpoint isn't failure.
                if self.same_endpoint_evidence is None or self.same_endpoint_evidence[0] is not cast:
                    evidence = EvidenceListener()
                    cast.media_controller.register_status_listener(evidence)
                    self.same_endpoint_evidence = (cast, evidence)
                evidence = self.same_endpoint_evidence[1]
                evidence.media.clear()
                cast.media_controller.update_status()
                if not evidence.media.wait(5):
                    raise TimeoutError('Current session media was not freshly confirmed.')
                new_status = controller.refresh()
                with self.bridge.lock, self.lab.playback_gate, self.lock:
                    if (self.closed.is_set() or self.bridge.closed or self.lab.cast is not cast
                            or not cast.socket_client.is_connected
                            or cast.status.session_id != session
                            or cast.media_controller.status.player_state not in ACTIVE
                            or receiver_identity(cast.media_controller.status)[0] != fingerprint):
                        raise RuntimeError('Original session was not confirmed after the speaker change.')
                    self.uncertain = False
                    self._record(cast, new_status)
                new_controller = controller
                confirmed_session = session
                self.handoff = (cast, evidence, session, fingerprint)
            else:
                info = endpoint_info(endpoint, desired, infos, cast)
                if info.cast_type == 'group':
                    self.temporary_ids.add(str(info.uuid))
                candidate = pychromecast.get_chromecast_from_cast_info(info, self.lab.zconf, tries=1, timeout=3)
                install_passive_status(candidate)
                evidence = EvidenceListener()
                candidate.media_controller.register_status_listener(evidence)
                new_controller = ObservedController()
                candidate.register_handler(new_controller)
                candidate.wait(timeout=5)
                evidence.media.clear()
                candidate.media_controller.update_status()
                if not evidence.media.wait(5):
                    raise TimeoutError('Moved session media was not freshly confirmed.')
                new_status = new_controller.refresh()
                if (candidate.status.session_id != endpoint.get('sessionId')
                        or receiver_identity(candidate.media_controller.status)[0] != fingerprint):
                    raise RuntimeError('Returned endpoint does not match the original playback.')
                with self.bridge.lock, self.lab.playback_gate, self.lock:
                    if self.closed.is_set() or self.bridge.closed or self.lab.cast is not cast:
                        raise RuntimeError('Remote changed before the new session endpoint was attached.')
                    # Stop may have incremented generation/epoch. Adoption is still
                    # required so its waiting worker stops the real moved session.
                    candidate.media_controller.register_status_listener(self.lab)
                    self.lab.cast = candidate
                    self.lab.selected_id = str(candidate.uuid)
                    self.lab.generation += 1
                    self.bridge.target = str(candidate.uuid)
                    self.bridge.retry_at = 0
                    self.cast, self.controller = candidate, new_controller
                    self.observed = None
                    self.uncertain = False
                    self._record(candidate, new_status)
                    self.handoff = (candidate, evidence, endpoint.get('sessionId'), fingerprint)
                    candidate = None
                cast.disconnect()
                confirmed_session = endpoint.get('sessionId')
            if response.get('failedDevices'):
                raise RuntimeError('Some speakers did not join or leave; read the confirmed membership.')
            # The receiver endpoint can be ready before all members finish
            # joining. Observe convergence; never resend the mutation.
            self._await_members(self.lab.cast, new_controller, new_status, desired,
                                confirmed_session, fingerprint)
            with self.lock:
                self.pending = None
                self.error = None
                self.revision += 1
        except Exception as exc:
            with self.lock:
                # Deliberately keep the desired checkmarks until explicit dismissal.
                self.error = str(exc) if isinstance(exc, (ValueError, RuntimeError, TimeoutError)) else type(exc).__name__
            raise
        finally:
            if candidate is not None:
                candidate.disconnect()
            with self.lock:
                self.active = False
                self.settled.set()

    def _await_members(self, cast, controller, status, desired, session, fingerprint, timeout=12):
        deadline = time.monotonic() + timeout
        while True:
            if self.bridge.stopping:
                # Location is already verified and adopted. Release the waiting
                # Stop worker immediately instead of waiting for member joins.
                raise RuntimeError('Speaker change cancelled by Stop.')
            if self.closed.is_set() or self.bridge.closed:
                raise RuntimeError('Speaker change monitoring closed.')
            if (self.lab.cast is not cast or cast.status.session_id != session
                    or receiver_identity(cast.media_controller.status)[0] != fingerprint):
                with self.lock:
                    self.uncertain = True
                raise RuntimeError('Playback changed while speaker membership was settling.')
            if member_ids(status) == desired:
                return
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('Speakers did not confirm the requested membership in time.')
            self.closed.wait(min(.25, remaining))
            if self.bridge.stopping:
                continue
            try:
                status = controller.refresh(timeout=min(2, max(.01, deadline - time.monotonic())))
            except TimeoutError:
                continue
            self._record(cast, status)

    def wait_for_stop(self):
        if not self.settled.wait(35):
            raise TimeoutError('Speaker change is still settling; Stop cannot yet locate playback.')
        with self.lock:
            if self.uncertain:
                raise RuntimeError('Speaker change endpoint is unconfirmed; Stop cannot confirm the moved session ended.')

    def dismiss(self):
        with self.lock:
            if self.active:
                raise ValueError('Wait for the speaker change to finish.')
            self.pending = self.error = None

    def explicit_selection(self):
        """A deliberate remote selection establishes a new control context."""
        with self.lock:
            if self.active:
                raise ValueError('Wait for the speaker change to finish.')
            self.pending = self.error = None
            self.uncertain = False
            self.handoff = None
            self.observed = None
            self.binding = self.token = None

    def close(self):
        self.closed.set()
