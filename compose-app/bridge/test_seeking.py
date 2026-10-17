import threading
import unittest
from types import SimpleNamespace
from unittest.mock import Mock
from seeking import SeekTracker

class SeekTests(unittest.TestCase):
    def setUp(self):
        self.cast=Mock()
        self.mc=self.cast.media_controller
        self.mc.namespace='media'
        self.cast.socket_client.app_namespaces=['media']
        self.mc.status=SimpleNamespace(media_session_id=7, player_state='PAUSED', supports_seek=True, current_time=10)
        self.lab=SimpleNamespace(cast=self.cast,generation=0,playback_gate=threading.RLock())
        self.tracker=SeekTracker(self.cast)
    def test_confirmed_seek_preserves_paused_state(self):
        def update():
            self.mc.status.current_time=120
            self.tracker.new_media_status(self.mc.status)
        self.mc.update_status.side_effect=update
        self.tracker.seek_confirmed(self.lab,120)
        payload=self.mc.send_message_nocheck.call_args.args[0]
        self.assertEqual(payload['resumeState'],'PLAYBACK_PAUSE')
        self.assertEqual(payload['mediaSessionId'],7)
        self.mc.play_media.assert_not_called()
    def test_stale_matching_status_does_not_confirm(self):
        self.mc.status.current_time=120
        self.tracker.new_media_status(self.mc.status)
        with self.assertRaises(TimeoutError): self.tracker.seek_confirmed(self.lab,120,timeout=.01)
    def test_stop_cancels_confirmation(self):
        def stop(message): self.lab.generation+=1
        self.mc.send_message_nocheck.side_effect=stop
        with self.assertRaises(RuntimeError): self.tracker.seek_confirmed(self.lab,120)
    def test_session_change_cancels_confirmation(self):
        def changed(message): self.mc.status.media_session_id=8
        self.mc.send_message_nocheck.side_effect=changed
        with self.assertRaises(RuntimeError): self.tracker.seek_confirmed(self.lab,120)
    def test_unsupported_seek_sends_nothing(self):
        self.mc.status.supports_seek=False
        with self.assertRaises(ValueError): self.tracker.seek_confirmed(self.lab,120)
        self.mc.send_message_nocheck.assert_not_called()
    def test_pause_requires_fresh_confirmation(self):
        def update(): self.tracker.new_media_status(self.mc.status)
        self.mc.update_status.side_effect=update
        self.tracker.playback_confirmed(self.lab,True)
        self.assertEqual(self.mc.send_message_nocheck.call_args.args[0]['type'],'PAUSE')
    def test_resume_requires_matching_state(self):
        self.mc.update_status.side_effect=lambda: self.tracker.new_media_status(self.mc.status)
        with self.assertRaises(TimeoutError): self.tracker.playback_confirmed(self.lab,False,timeout=.01)
    def test_stale_pause_does_not_confirm(self):
        self.tracker.new_media_status(self.mc.status)
        with self.assertRaises(TimeoutError): self.tracker.playback_confirmed(self.lab,True,timeout=.01)
    def test_resume_confirmed(self):
        def update():
            self.mc.status.player_state='PLAYING'
            self.tracker.new_media_status(self.mc.status)
        self.mc.update_status.side_effect=update
        self.tracker.playback_confirmed(self.lab,False)
        self.assertEqual(self.mc.send_message_nocheck.call_args.args[0]['type'],'PLAY')
