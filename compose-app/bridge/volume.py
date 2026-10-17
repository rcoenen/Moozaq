"""Explicit receiver volume commands with fresh readback confirmation."""
import threading
import time


class VolumeTracker:
    def __init__(self, cast):
        self.cast = cast
        self.lock = threading.Lock()
        self.sequence = 0
        self.level = None
        cast.register_status_listener(self)

    def new_cast_status(self, status):
        with self.lock:
            self.sequence += 1
            self.level = status.volume_level

    def set_confirmed(self, lab, value, timeout=6):
        if not isinstance(value, (int, float)) or not 0 <= value <= 1:
            raise ValueError('Volume must be between 0 and 1.')
        cast, generation = self.cast, lab.generation
        def validate():
            if lab.cast is not cast or lab.generation != generation or not cast.socket_client.is_connected:
                raise RuntimeError('Receiver changed; volume adjustment cancelled.')
        with lab.playback_gate:
            validate()
            with self.lock:
                sequence = self.sequence
            cast.set_volume(value)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            validate()
            cast.socket_client.receiver_controller.update_status()
            with self.lock:
                if self.sequence > sequence and self.level is not None and abs(self.level - value) < .01:
                    return
            time.sleep(.1)
        raise TimeoutError('Speaker volume was not confirmed.')
