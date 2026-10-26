import hashlib
import json
import math
import os
import subprocess
import threading
import time
from pathlib import Path

from engine import EXPIRY_MARGIN, redacted, remaining_need, url_expiry, youtube_url
from local_relay import LocalRelay


LOCAL_OUTPUT_ID = "mac-local"


class LocalPlayer:
    def __init__(self, lab, helper_path):
        self.lab = lab
        self.helper_path = Path(helper_path)
        self.condition = threading.Condition()
        self.writer_lock = threading.Lock()
        self.operation_lock = threading.RLock()
        self.process = None
        self.closed = False
        self.counter = 0
        self.acks = {}
        self.confirmed = {}
        self.operations = {}
        self.state_serial = 0
        self.event_transport_revision = 0
        self.state = self.empty_state()
        self.session = None
        self.pending = None
        self.relay = None
        self.resolved = None
        self.source = None
        self.title = None
        self.expires_at = None
        self.position_offset = 0.0

    @staticmethod
    def empty_state():
        return {"connected": False, "state": "IDLE", "position": 0.0, "duration": None,
                "volume": 0.15, "session": None, "command": None, "error": None, "idle_reason": None}

    @property
    def available(self):
        return self.helper_path.is_file() and os.access(self.helper_path, os.X_OK)

    def alive(self):
        return self.process is not None and self.process.poll() is None and not self.closed

    def select(self):
        with self.operation_lock:
            self.ensure_process()
            return self.status()

    @staticmethod
    def close_files(process):
        if process is None:
            return
        for stream in (process.stdin, process.stdout, process.stderr):
            try:
                stream.close()
            except (OSError, ValueError):
                pass

    def ensure_process(self):
        if self.alive():
            return
        if self.closed:
            raise RuntimeError("Local player is closed.")
        if not self.available:
            raise RuntimeError("The Mac audio helper is unavailable. Rebuild Moozaq and try again.")
        self.close_relay()
        self.close_files(self.process)
        self.state = self.empty_state()
        self.position_offset = 0.0
        self.session = None
        self.pending = None
        self.resolved = None
        self.source = None
        self.title = None
        self.expires_at = None
        self.process = subprocess.Popen(
            [str(self.helper_path)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1,
        )
        threading.Thread(target=self._read_stdout, daemon=True).start()
        threading.Thread(target=self._read_stderr, daemon=True).start()
        try:
            self.request("state", timeout=3)
        except Exception:
            self.terminate()
            raise

    def _read_stdout(self):
        process = self.process
        try:
            for line in process.stdout:
                try:
                    message = json.loads(line)
                except (ValueError, TypeError):
                    continue
                if "id" in message:
                    with self.condition:
                        self.acks[message["id"]] = None if message.get("ok") else message.get("error") or "Local player command failed."
                        self.condition.notify_all()
                elif message.get("event") == "state" and isinstance(message.get("state"), dict):
                    self._state_event(message["state"], process)
        except (OSError, ValueError):
            pass
        finally:
            self._process_ended(process)

    def _read_stderr(self):
        process = self.process
        try:
            for line in process.stderr:
                if line.strip():
                    self.lab.log("local-player-note", redacted(line.strip()))
        except (OSError, ValueError):
            pass

    def _process_ended(self, process):
        with self.condition:
            if process is not self.process:
                return
            was_connected = self.state.get("connected")
            self.state["connected"] = False
            if not self.closed and was_connected:
                self.state["error"] = "Local player stopped unexpectedly."
                self.event_transport_revision += 1
            self.condition.notify_all()
        self.close_relay()

    def _state_event(self, value, process):
        if process is not self.process:
            return
        allowed = {"connected", "state", "position", "duration", "volume", "session", "command", "error", "idle_reason"}
        event = {key: value.get(key) for key in allowed}
        with self.condition:
            previous = self.state.get("state")
            session = event.get("session")
            if session is not None and self.pending is not None and session != self.session:
                self.session = session
                self.resolved = self.pending
                self.source = self.pending["source_url"]
                self.title = self.pending.get("title")
                self.expires_at = url_expiry(self.pending.get("url"))
            self.state.update(event)
            self.state_serial += 1
            command = event.get("command")
            if command is not None:
                self.confirmed[command] = dict(self.state)
                if len(self.confirmed) > 100:
                    self.confirmed.pop(next(iter(self.confirmed)))
            elif event.get("state") in ("IDLE", "ERROR") and previous != event.get("state"):
                self.event_transport_revision += 1
            if event.get("state") == "IDLE":
                self.session = None
                if event.get("idle_reason") == "CANCELLED":
                    self._clear_metadata()
            self.condition.notify_all()
        if event.get("state") == "ERROR":
            self.close_relay()

    def _clear_metadata(self):
        self.session = None
        self.pending = None
        self.resolved = None
        self.source = None
        self.title = None
        self.expires_at = None
        self.position_offset = 0.0

    def close_relay(self):
        relay, self.relay = self.relay, None
        if relay is not None:
            relay.close()

    def send(self, operation, fields=None):
        if not self.alive():
            raise RuntimeError("Local player is not running.")
        with self.condition:
            self.counter += 1
            command_id = self.counter
            self.operations[command_id] = operation
        message = {"id": command_id, "op": operation, **(fields or {})}
        with self.writer_lock:
            try:
                self.process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
                self.process.stdin.flush()
            except (BrokenPipeError, OSError) as exc:
                raise RuntimeError("Local player is not responding.") from exc
        return command_id

    def request(self, operation, fields=None, timeout=10):
        with self.condition:
            starting_serial = self.state_serial
        command_id = self.send(operation, fields)
        return self.wait_for(command_id, operation, timeout, starting_serial)

    def wait_for(self, command_id, operation, timeout, starting_serial, generation=None):
        deadline = time.monotonic() + timeout
        with self.condition:
            while True:
                if generation is not None and generation != self.lab.generation:
                    raise RuntimeError("Playback cancelled by Stop.")
                if command_id in self.acks:
                    error = self.acks[command_id]
                    if error:
                        raise RuntimeError(redacted(error))
                    if operation == "close":
                        return None
                    if operation == "state" and self.state_serial > starting_serial:
                        return dict(self.state)
                    if operation not in ("state", "close") and command_id in self.confirmed:
                        confirmed = self.confirmed[command_id]
                        if confirmed.get("error"):
                            raise RuntimeError(redacted(confirmed["error"]))
                        return confirmed
                if not self.alive():
                    raise RuntimeError("Local player is not running.")
                if time.monotonic() >= deadline:
                    break
                self.condition.wait(min(0.1, max(0, deadline - time.monotonic())))
        if operation in ("load", "play"):
            try:
                self.request("stop", timeout=1)
            except Exception:
                self.terminate()
        raise TimeoutError(f"Local player did not confirm {operation}.")

    def command(self, operation, fields=None, timeout=10, generation=None):
        if generation is not None:
            with self.lab.playback_gate:
                if generation != self.lab.generation:
                    raise RuntimeError("Playback cancelled by Stop.")
                command_id = self.send(operation, fields)
        else:
            command_id = self.send(operation, fields)
        result = self.wait_for(command_id, operation, timeout, self.state_serial, generation)
        if generation is not None and generation != self.lab.generation:
            raise RuntimeError("Playback cancelled by Stop.")
        return result

    def resolve(self, source, start, generation):
        if generation != self.lab.generation:
            raise RuntimeError("Playback cancelled by Stop.")
        resolved = self.lab.ensure_source(source, start)
        if generation != self.lab.generation:
            raise RuntimeError("Playback cancelled by Stop.")
        self.lab.preflight_direct(resolved["url"])
        if generation != self.lab.generation:
            raise RuntimeError("Playback cancelled by Stop.")
        return resolved

    @staticmethod
    def public_headers(headers):
        allowed = {"User-Agent", "Accept", "Accept-Language", "Sec-Fetch-Mode", "Referer", "Origin"}
        return {key: value for key, value in (headers or {}).items()
                if key in allowed and isinstance(value, str) and "\r" not in value and "\n" not in value}

    def load(self, source, start=0, autoplay=True, expected_generation=None, resolved=None):
        with self.operation_lock:
            generation = self.lab.generation if expected_generation is None else expected_generation
            resolved = resolved or self.resolve(source, start, generation)
            self.ensure_process()
            self.pending = resolved
            self.position_offset = start
            try:
                self.relay = LocalRelay(self.lab, resolved, start)
                playback_url = self.relay.wait_ready()
                volume = self.status().get("volume")
                fields = {"url": playback_url, "start": 0, "autoplay": autoplay,
                          "volume": 0.15 if volume is None else volume / 100,
                          "duration": resolved.get("duration"), "headers": {}}
                self.command("load", fields, timeout=30, generation=generation)
                if self.source != resolved["source_url"] or self.session is None:
                    raise RuntimeError("Local player did not confirm the requested mix.")
                return self.status()
            except Exception:
                self.close_relay()
                raise
            finally:
                self.pending = None

    def play_selection(self, value):
        with self.operation_lock:
            source = youtube_url(value) if value else None
            current = self.status()
            if current["state"] in ("PLAYING", "PAUSED", "BUFFERING") and (not source or source == current.get("source")):
                if current["state"] == "PAUSED":
                    return self.resume()
                return current
            if not source:
                raise ValueError("Select a mix to play on this Mac.")
            return self.load(source, autoplay=True)

    def resume(self):
        with self.operation_lock:
            current = self.status()
            if not current["connected"]:
                raise RuntimeError("Local player is not ready. Choose the mix again.")
            if current["state"] == "PLAYING":
                return current
            source = current.get("source")
            if not source:
                raise ValueError("Choose a mix to play on this Mac.")
            generation = self.lab.generation
            position = current.get("position") or 0
            failed = bool(current.get("error"))
            if current.get("refresh_needed") or failed:
                try:
                    resolved = self.resolve(source, position, generation)
                except Exception as exc:
                    if generation != self.lab.generation or current.get("source_expired") or failed:
                        raise
                    self.lab.log("local-source-refresh-unavailable", redacted(exc))
                else:
                    return self.load(source, start=position, autoplay=True, expected_generation=generation, resolved=resolved)
            self.command("play", timeout=10, generation=generation)
            return self.status()

    def pause(self):
        with self.operation_lock:
            self.command("pause", timeout=5, generation=self.lab.generation)
            return self.status()

    def seek(self, position):
        with self.operation_lock:
            current = self.status()
            if not current["connected"] or current.get("state") not in ("PLAYING", "PAUSED", "BUFFERING"):
                raise ValueError("Local playback is not ready to seek.")
            if current.get("source_expired"):
                raise ValueError("The stream link expired. Press Resume to reload the mix first.")
            source = current.get("source")
            if not source:
                raise ValueError("Cannot identify the current mix.")
            generation = self.lab.generation
            paused = current["state"] == "PAUSED"
            if current.get("refresh_needed"):
                try:
                    resolved = self.resolve(source, position, generation)
                except Exception as exc:
                    if generation != self.lab.generation:
                        raise
                    self.lab.log("local-source-refresh-unavailable", redacted(exc))
                else:
                    return self.load(source, start=position, autoplay=not paused, expected_generation=generation, resolved=resolved)
            if current.get("refresh_needed"):
                try:
                    self.command("seek", {"position": position}, timeout=10, generation=generation)
                except RuntimeError as exc:
                    if str(exc) != "Seek unavailable":
                        raise
                    self.close_relay()
                    return self.load(source, start=position, autoplay=not paused, expected_generation=generation)
                if abs((self.status().get("position") or 0) - position) > 2:
                    raise RuntimeError("Local player did not confirm the requested position.")
                return self.status()
            return self.load(source, start=position, autoplay=not paused, expected_generation=generation)

    def volume(self, value):
        if not math.isfinite(value) or not 0 <= value <= 1:
            raise ValueError("Volume must be between 0 and 1.")
        self.command("volume", {"value": value}, timeout=3)
        if abs((self.status().get("volume") or 0) - value * 100) > 1:
            raise RuntimeError("Local player did not confirm the requested volume.")
        return self.status()

    def stop(self):
        self.lab.cancel()
        with self.lab.playback_gate:
            self.lab.generation += 1
        try:
            if self.alive():
                self.request("stop", timeout=5)
        finally:
            self.close_relay()
        self._clear_metadata()
        with self.condition:
            self.state.update(state="IDLE", position=0.0, duration=None, session=None,
                              command=None, error=None, idle_reason="CANCELLED")
        return self.status()

    def terminate(self):
        process = self.process
        if process is not None and process.poll() is None:
            process.kill()
        if process is not None:
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                pass
        self.close_files(process)
        self.close_relay()
        with self.condition:
            self.state["connected"] = False
            self.condition.notify_all()

    def close(self):
        if self.closed:
            return
        try:
            self.stop()
        except Exception:
            self.terminate()
        self.closed = True
        process = self.process
        if process is None:
            return
        try:
            if process.poll() is None:
                process.stdin.close()
                process.wait(timeout=2)
        except Exception:
            self.terminate()
        finally:
            self.close_files(process)
            with self.condition:
                self.state["connected"] = False
                self.condition.notify_all()

    def status(self):
        with self.condition:
            raw = dict(self.state)
            event_revision = self.event_transport_revision
        connected = self.alive() and bool(raw.get("connected"))
        expired = bool(self.expires_at and time.time() >= self.expires_at)
        position = self.position_offset + (raw.get("position") or 0.0)
        duration = raw.get("duration") or (self.resolved or {}).get("duration")
        error = raw.get("error")
        state = raw.get("state") if connected or raw.get("idle_reason") == "CANCELLED" else "UNKNOWN"
        idle_reason = raw.get("idle_reason")
        if state == "ERROR":
            state = "IDLE"
            idle_reason = "ERROR"
        return {
            "connected": connected, "device": "This Mac", "mode": "local",
            "title": self.title, "source": self.source,
            "content_key": hashlib.sha256(self.resolved["url"].encode()).hexdigest() if self.resolved else None,
            "state": state, "position": position, "duration": duration,
            "volume": round(raw["volume"] * 100) if connected and raw.get("volume") is not None else None,
            "media_session": raw.get("session"), "idle_reason": idle_reason,
            "expires_at": self.expires_at, "source_expired": expired,
            "refresh_needed": bool(self.source and self.expires_at and self.expires_at - time.time() < remaining_need(duration, position) + EXPIRY_MARGIN),
            "resume_position": position, "error": error,
            "recovery": None if expired else error or (None if connected else "Local player unavailable"),
            "event_transport_revision": event_revision,
        }


def create_local_player(lab, helper_path):
    return LocalPlayer(lab, helper_path)
