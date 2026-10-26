import math
import re
import threading
import time
from urllib.parse import parse_qs, urlparse


def video_id_from_source(value):
    if not isinstance(value, str):
        return None
    value = value.strip()
    if re.fullmatch(r"[\w-]{11}", value):
        return value
    parsed = urlparse(value)
    if parsed.scheme != "https" or parsed.hostname not in ("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"):
        return None
    video_id = parsed.path.strip("/") if parsed.hostname == "youtu.be" else parse_qs(parsed.query).get("v", [None])[0]
    return video_id if video_id and re.fullmatch(r"[\w-]{11}", video_id) else None


class ListeningTracker:
    def __init__(self, personal, flush_interval=30):
        self.personal = personal
        self.lock = threading.RLock()
        self.flush_interval = flush_interval
        self.pending_seconds = 0.0
        self.pending_since = None
        self.session_seconds = 0.0
        self.last = personal.last_observation()
        if self.last:
            self.session_seconds = self.last.get("session_seconds") or 0.0
        self.closed = False

    def observe(self, status, now=None):
        with self.lock:
            return self._observe(status, now)

    def _observe(self, status, now=None):
        now = time.time() if now is None else now
        if self.closed:
            return {"credited": 0.0, "qualified": False}
        video_id = status.get("video_id") or video_id_from_source(status.get("source"))
        session = status.get("media_session")
        position = status.get("position")
        if (not status.get("fresh", True) or not status.get("connected") or status.get("state") != "PLAYING"
                or not video_id or session is None or position is None or not math.isfinite(position)):
            self._reset(now)
            return {"credited": 0.0, "qualified": False}
        position = float(position)
        previous = self.last
        if previous and previous.get("video_id") == video_id and previous.get("media_session") == str(session):
            wall_delta = now - previous["observed_at"]
            position_delta = position - previous["position"]
            if wall_delta < 0 or position_delta < 0 or position_delta > wall_delta + 2:
                self._reset(now)
                return {"credited": 0.0, "qualified": False}
            credit = min(position_delta, wall_delta)
            if credit > 0:
                self.pending_seconds += credit
                self.session_seconds += credit
                if self.pending_since is None:
                    self.pending_since = now
                qualified_now = not previous.get("qualified") and self.session_seconds >= 300
                self.last = self._observation(video_id, session, position, now, qualified_now or previous.get("qualified", False))
                self._save_observation()
                if qualified_now:
                    self._flush(now, qualified=True)
                else:
                    self._flush_if_due(now)
                return {"credited": credit, "qualified": qualified_now}
        self.last = self._observation(video_id, session, position, now, False)
        self.session_seconds = 0.0
        self._save_observation()
        return {"credited": 0.0, "qualified": False}

    def reset(self):
        with self.lock:
            self.pending_seconds = 0.0
            self.pending_since = None
            self.session_seconds = 0.0
            self.last = None

    def close(self):
        with self.lock:
            if not self.closed:
                self._flush(time.time(), qualified=False)
                self.closed = True

    def _observation(self, video_id, session, position, observed_at, qualified):
        return {"video_id": video_id, "media_session": str(session), "position": position,
                "observed_at": observed_at, "qualified": bool(qualified), "session_seconds": self.session_seconds}

    def _save_observation(self):
        fields = ("video_id", "media_session", "position", "observed_at", "qualified", "session_seconds")
        self.personal.save_observation(**{key: self.last.get(key) for key in fields})

    def _flush_if_due(self, now):
        if self.pending_seconds > 0 and self.pending_since is not None and now - self.pending_since >= self.flush_interval:
            self._flush(now, qualified=False)

    def _flush(self, now, qualified):
        if self.pending_seconds > 0:
            self.personal.record_listening(self.last["video_id"], self.pending_seconds, now=now, qualified_session=qualified)
        self.pending_seconds = 0.0
        self.pending_since = None
        if self.last is not None:
            self._save_observation()

    def _reset(self, now):
        if self.last is not None:
            self._flush(now, qualified=False)
        self.last = None
        self.session_seconds = 0.0
        self.personal.save_observation(None, None, None, now, qualified=False, session_seconds=0.0)
