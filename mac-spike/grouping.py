"""Experimental native Cast playback membership protocol (Google Home 2026).

No persistent group setup, media LOAD, receiver launch or volume changes.
Only use set_devices for an explicit user playback-membership action.
"""
import threading
import uuid
from pychromecast.controllers import BaseController

NAMESPACE = 'urn:x-cast:com.google.cast.multizone'


def device_payload(info):
    if info.cast_type != 'audio':
        raise ValueError('Only individually discovered audio speakers are supported in this spike.')
    return {'deviceId': uuid.UUID(str(info.uuid)).hex, 'ipAddress': info.host, 'port': info.port}


class PlaybackMembershipController(BaseController):
    def __init__(self):
        super().__init__(NAMESPACE, target_platform=True)
        self.status = None
        self.status_event = threading.Event()
        self.response_event = threading.Event()
        self.response = None
        self.request_id = None
        self._serial = 1000
        self._lock = threading.RLock()

    def receive_message(self, message, data):
        kind = data.get('type')
        with self._lock:
            if kind == 'MULTIZONE_STATUS':
                self.status = data.get('status', {})
                self.status_event.set()
            if kind in ('PLAYBACK_SESSION_UPDATED', 'INVALID_REQUEST') and data.get('requestId') == self.request_id:
                self.response = data
                self.response_event.set()
        return True

    def refresh(self, timeout=5):
        self.status_event.clear()
        self.send_message({'type': 'GET_STATUS'})
        if not self.status_event.wait(timeout):
            raise TimeoutError('No fresh playback membership status.')
        return self.status

    def set_devices(self, session_id, infos, session_name=None):
        if not isinstance(session_id, str) or not session_id:
            raise ValueError('An active receiver session ID is required.')
        if not infos:
            raise ValueError('Use Stop to end playback; this spike requires at least one speaker.')
        rows = [device_payload(info) for info in infos]
        if len({row['deviceId'] for row in rows}) != len(rows):
            raise ValueError('Duplicate speaker.')
        with self._lock:
            self._serial += 1
            self.request_id = self._serial
            self.response = None
            self.response_event.clear()
            payload = {'type':'SET_PLAYBACK_DEVICES', 'requestId':self.request_id,
                       'sessionId':session_id, 'deviceList':rows}
            if session_name:
                payload['sessionName'] = session_name
            self.send_message(payload, no_add_request_id=True)
        return self.request_id
