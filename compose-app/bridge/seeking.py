"""Session-bound seeking, confirmed by fresh receiver media status."""
import threading
import time

class SeekTracker:
    def __init__(self, cast):
        self.cast = cast
        self.lock = threading.Lock()
        self.sequence = 0
        self.report = None
        cast.media_controller.register_status_listener(self)

    def new_media_status(self, status):
        with self.lock:
            self.sequence += 1
            self.report = (status.media_session_id, status.current_time, status.player_state)

    def load_media_failed(self, queue_item_id, error_code):
        pass

    def seek_confirmed(self, lab, value, timeout=6, expected_generation=None):
        if not isinstance(value, (int, float)) or not 0 <= value <= 864000:
            raise ValueError('Invalid seek position.')
        cast = self.cast
        generation = lab.generation if expected_generation is None else expected_generation
        mc = cast.media_controller
        session = mc.status.media_session_id
        state = mc.status.player_state
        if session is None or state not in ('PLAYING', 'PAUSED') or not mc.status.supports_seek:
            raise ValueError('Receiver is not ready for seeking.')
        def validate():
            if (lab.cast is not cast or lab.generation != generation or not cast.socket_client.is_connected
                    or mc.status.media_session_id != session or mc.namespace not in cast.socket_client.app_namespaces):
                raise RuntimeError('Playback session changed; seek cancelled.')
        with lab.playback_gate:
            validate()
            with self.lock:
                sequence = self.sequence
            # Library seek() forces PLAYBACK_START; preserve paused playback instead.
            mc.send_message_nocheck({'type': 'SEEK', 'mediaSessionId': session, 'currentTime': value,
                                     'resumeState': 'PLAYBACK_PAUSE' if state == 'PAUSED' else 'PLAYBACK_START'})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            validate()
            mc.update_status()
            with self.lock:
                report = self.report
                if (self.sequence > sequence and report and report[0] == session
                        and report[1] is not None and abs(report[1] - value) <= 2
                        and report[2] == state):
                    return
            time.sleep(.1)
        raise TimeoutError('Seek position was not confirmed.')

    def playback_confirmed(self, lab, paused, timeout=6, expected_generation=None):
        cast = self.cast
        # A fallback after a failed link refresh passes the generation from its request, so
        # a Stop in between cancels it.
        generation = lab.generation if expected_generation is None else expected_generation
        mc = cast.media_controller
        session = mc.status.media_session_id
        expected = 'PAUSED' if paused else 'PLAYING'
        def validate():
            if (lab.cast is not cast or lab.generation != generation or session is None
                    or mc.status.media_session_id != session or not cast.socket_client.is_connected
                    or mc.namespace not in cast.socket_client.app_namespaces):
                raise RuntimeError('Playback session changed; command cancelled.')
        with lab.playback_gate:
            validate()
            with self.lock:
                sequence = self.sequence
            mc.send_message_nocheck({'type': 'PAUSE' if paused else 'PLAY', 'mediaSessionId': session})
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            validate()
            mc.update_status()
            with self.lock:
                if self.sequence > sequence and self.report and self.report[0] == session and self.report[2] == expected:
                    return
            time.sleep(.1)
        raise TimeoutError('Speaker did not confirm ' + expected.lower() + '.')
