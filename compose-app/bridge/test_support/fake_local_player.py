#!/usr/bin/env python3
import json
import os
import sys


state = {"connected": True, "state": "IDLE", "position": 0.0, "duration": None,
         "volume": 0.15, "session": None, "command": None, "error": None, "idle_reason": None}
session = 0
pending_load = None
commands_path = os.environ.get("FAKE_PLAYER_COMMANDS")


def emit(command="keep"):
    if command != "keep":
        state["command"] = command
    message = {"event": "state", "state": dict(state)}
    if os.environ.get("FAKE_PLAYER_LEAK_STATE") == "1":
        message["state"]["url"] = "https://example.test/audio.mp3?signature=secret"
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def answer(request, ok=True, error=None):
    result = {"id": request.get("id"), "ok": ok}
    if error:
        result["error"] = error
    sys.stdout.write(json.dumps(result) + "\n")
    sys.stdout.flush()


def finish_load(command_id, autoplay):
    state["state"] = "PLAYING" if autoplay else "PAUSED"
    state["error"] = None
    state["idle_reason"] = None
    emit(command_id)


if os.environ.get("FAKE_PLAYER_STDERR") == "1":
    print("failed https://example.test/audio.mp3?signature=secret", file=sys.stderr, flush=True)

for line in sys.stdin:
    try:
        request = json.loads(line)
    except ValueError:
        continue
    operation = request.get("op")
    if commands_path:
        with open(commands_path, "a") as output:
            output.write(json.dumps(request) + "\n")
    if operation == "state":
        answer(request)
        emit()
    elif operation == "load":
        session += 1
        pending_load = request["id"]
        state.update(state="BUFFERING", position=request.get("start", 0.0),
                     duration=request.get("duration"), session=session,
                     volume=request.get("volume", state["volume"]), error=None, idle_reason=None)
        answer(request)
        emit(None)
        if os.environ.get("FAKE_PLAYER_FAIL_LOAD") == "1":
            pending_load = None
            state.update(state="ERROR", error="Local media could not be loaded.", idle_reason="ERROR")
            emit(request["id"])
        elif os.environ.get("FAKE_PLAYER_AUTO_LOAD", "1") == "1":
            finish_load(request["id"], request.get("autoplay", True))
            pending_load = None
    elif operation == "finish":
        answer(request)
        if pending_load is not None:
            finish_load(pending_load, True)
            pending_load = None
    elif operation == "play":
        answer(request)
        state.update(state="PLAYING", error=None, idle_reason=None)
        emit(request["id"])
    elif operation == "pause":
        answer(request)
        state["state"] = "PAUSED"
        emit(request["id"])
    elif operation == "seek":
        answer(request)
        state["position"] = request["position"]
        emit(request["id"])
    elif operation == "volume":
        answer(request)
        state["volume"] = request["value"]
        emit(request["id"])
    elif operation == "stop":
        pending_load = None
        answer(request)
        state.update(state="IDLE", position=0.0, duration=None, session=None,
                     error=None, idle_reason="CANCELLED")
        emit(request["id"])
    elif operation == "end":
        answer(request)
        state.update(state="IDLE", session=None, idle_reason="FINISHED")
        emit(None)
    elif operation == "crash":
        os._exit(1)
    elif operation == "close":
        answer(request)
        break
    else:
        answer(request, ok=False, error="Unsupported test command")

state["connected"] = False
state["state"] = "IDLE"
state["session"] = None
emit(None)
