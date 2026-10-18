"""Runs the Mac's Python Cast bridge (compose-app/bridge/server.py) inside the Android app.

The bridge and engine modules are unchanged. The only Android substitution is process
execution: Android apps cannot spawn `python -m yt_dlp`, so yt-dlp invocations go to the
app's bundled yt-dlp runtime (the same release the Mac pins) through `runner`.
"""
import faulthandler
import json
import os
import sys
import types

_bridge = None
_runner = None
_stall_log = None


def _ytdlp_arguments(args):
    """Arguments after `python -m yt_dlp`, without the Mac-only JavaScript runtime flag."""
    args = list(args)
    if len(args) < 3 or args[1:3] != ['-m', 'yt_dlp']:
        return None
    rest = args[3:]
    if '--js-runtimes' in rest:
        index = rest.index('--js-runtimes')
        del rest[index:index + 2]
    return rest


def _run_ytdlp(arguments, timeout):
    return str(_runner.run(json.dumps(arguments), int(timeout * 1000)))


def _patch_engine(engine):
    def run_process(self, args, timeout=180):
        arguments = _ytdlp_arguments(args)
        if arguments is None:
            # ffmpeg test tones and other desktop tools do not exist on the phone.
            raise RuntimeError('This tool is not available on the phone; no media was loaded.')
        self.cancelled.clear()
        try:
            stdout = _run_ytdlp(arguments, timeout)
        except Exception as exc:
            if self.cancelled.is_set():
                raise RuntimeError('Operation cancelled; no new media loaded.')
            raise RuntimeError(engine.redacted(str(exc)))
        if self.cancelled.is_set():
            raise RuntimeError('Operation cancelled; no new media loaded.')
        return stdout

    def cancel(self):
        self.cancelled.set()
        _runner.cancel()

    engine.Lab.run_process = run_process
    engine.Lab.cancel = cancel


def _patch_catalog(catalog):
    def run(args, capture_output=True, text=True, timeout=300):
        arguments = _ytdlp_arguments(args)
        if arguments is None:
            raise RuntimeError('This tool is not available on the phone.')
        try:
            return types.SimpleNamespace(returncode=0, stdout=_run_ytdlp(arguments, timeout), stderr='')
        except Exception as exc:
            return types.SimpleNamespace(returncode=1, stdout='', stderr=str(exc))
    catalog.subprocess = types.SimpleNamespace(run=run)


def start(bridge_dir, runner):
    """Start discovery and the passive monitors. Nothing here loads media or changes volume."""
    global _bridge, _runner, _stall_log
    if _bridge is not None:
        return
    _runner = runner
    if bridge_dir not in sys.path:
        sys.path.insert(0, bridge_dir)
    # If startup stalls, write every thread's stack where diagnostics can read it.
    _stall_log = open(os.path.join(bridge_dir, 'startup-stall.log'), 'w')
    faulthandler.dump_traceback_later(20, file=_stall_log)
    try:
        import engine
        import catalog
        _patch_engine(engine)
        _patch_catalog(catalog)
        import server
        _bridge = server.start_bridge()
    finally:
        faulthandler.cancel_dump_traceback_later()


def request(line):
    import server
    if _bridge is None:
        return json.dumps({'id': None, 'error': 'Local playback engine is not running.'})
    return server.handle_line(_bridge, line)


def close():
    """Detach from speakers only. Never stops the speaker session."""
    global _bridge
    if _bridge is None:
        return
    import server
    bridge, _bridge = _bridge, None
    server.close_bridge(bridge)
