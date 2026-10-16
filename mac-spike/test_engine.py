import tempfile
from pathlib import Path
import unittest
import requests
from engine import MediaServer, youtube_url, byte_range


class Inputs(unittest.TestCase):
    def test_normalize_watch_and_short_urls(self):
        expected='https://www.youtube.com/watch?v=7vy0H3ERRiA'
        for value in ('7vy0H3ERRiA', expected+'&list=abc', 'https://youtu.be/7vy0H3ERRiA?t=12'):
            self.assertEqual(youtube_url(value),expected)

    def test_reject_non_youtube_or_multiple_links(self):
        for value in ('https://youtube.com.attacker.test/watch?v=7vy0H3ERRiA', 'file:///etc/passwd', 'https://www.youtube.com/watch?v=bad', '7vy0H3ERRiAhttps://youtu.be/MpoIuYCfEvQ'):
            with self.assertRaises(ValueError): youtube_url(value)

    def test_invalid_ranges(self):
        for value in ('bytes=-0','bytes=100-','bytes=8-3','bytes=0-1,3-4','bytes=-'):
            with self.assertRaises(ValueError): byte_range(value,100)


class MediaDelivery(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.payload=bytes(range(256))*8
        path=Path(self.temp.name)/'audio.mp3';path.write_bytes(self.payload)
        self.server=MediaServer(lambda *args:None)
        self.url=self.server.add({'kind':'file','path':path,'mime':'audio/mpeg'},'127.0.0.1')

    def tearDown(self):
        self.server.close();self.temp.cleanup()

    def test_full_and_head(self):
        response=requests.get(self.url,timeout=3)
        self.assertEqual(response.content,self.payload)
        response=requests.head(self.url,timeout=3)
        self.assertEqual(int(response.headers['Content-Length']),len(self.payload))
        self.assertEqual(response.content,b'')

    def test_seek_ranges(self):
        for header,expected in [('bytes=0-99',self.payload[:100]),('bytes=100-',self.payload[100:]),('bytes=-50',self.payload[-50:])]:
            response=requests.get(self.url,headers={'Range':header},timeout=3)
            self.assertEqual(response.status_code,206)
            self.assertEqual(response.content,expected)
            self.assertIn('Content-Range',response.headers)

    def test_unsatisfiable_and_no_directory_access(self):
        response=requests.get(self.url,headers={'Range':'bytes=999999-'},timeout=3)
        self.assertEqual(response.status_code,416)
        for path in ('/','/etc/passwd','/unknown'):
            response=requests.get(self.url.rsplit('/',1)[0]+path,timeout=3)
            self.assertEqual(response.status_code,404)

    def test_relay_preserves_range(self):
        upstream=self.url
        relay=self.server.add({'kind':'relay','url':upstream,'mime':'audio/mpeg','headers':{}},'127.0.0.1')
        response=requests.get(relay,headers={'Range':'bytes=10-39'},timeout=3)
        self.assertEqual(response.status_code,206)
        self.assertEqual(response.content,self.payload[10:40])
        self.assertEqual(response.headers['Content-Range'],f'bytes 10-39/{len(self.payload)}')


class ControlReadiness(unittest.TestCase):
    def lab(self, active=True, session=None):
        import threading
        from types import SimpleNamespace
        from unittest.mock import Mock
        from engine import Lab
        lab=Lab.__new__(Lab)
        lab.media_updated=threading.Event()
        lab.playback_gate=threading.RLock()
        lab.generation=0
        lab.control_timeout=0.01
        lab.selected_id='bedroom'
        lab.cancel=Mock()
        lab.log=Mock()
        lab.cast=Mock()
        lab.cast.status=SimpleNamespace(app_id='CC1AD845' if active else None)
        lab.cast.socket_client.is_connected=True
        lab.cast.media_controller.status=SimpleNamespace(media_session_id=session,player_state='PLAYING')
        return lab

    def test_connected_without_media_state_uses_receiver_stop(self):
        lab=self.lab(session=None)
        lab.cast.quit_app.side_effect=lambda **kw:setattr(lab.cast.status,'app_id',None)
        lab.stop_verified()
        lab.cast.media_controller.stop.assert_not_called()
        lab.cast.quit_app.assert_called_once()
        self.assertEqual(lab.generation,1)
        lab.cancel.assert_called_once()

    def test_unconfirmed_media_stop_falls_back(self):
        lab=self.lab(session=1)
        lab.cast.quit_app.side_effect=lambda **kw:setattr(lab.cast.status,'app_id',None)
        lab.stop_verified()
        lab.cast.quit_app.assert_called_once()

    def test_fresh_idle_confirms_without_quitting_receiver(self):
        lab=self.lab(session=1)
        def status():
            lab.cast.media_controller.status.player_state='IDLE'
            lab.media_updated.set()
        lab.cast.media_controller.update_status.side_effect=status
        lab.stop_verified()
        lab.cast.quit_app.assert_not_called()

    def test_stop_does_not_claim_success_if_receiver_still_active(self):
        lab=self.lab(session=None)
        with self.assertRaisesRegex(RuntimeError,'could not be confirmed'):
            lab.stop_verified()

    def test_play_does_not_use_unsynchronized_media_state(self):
        lab=self.lab(session=None)
        with self.assertRaisesRegex(RuntimeError,'not synchronized'):
            lab.command('play')
        lab.cast.media_controller.play.assert_not_called()

    def test_idle_stop_is_idempotent(self):
        lab=self.lab(active=False)
        lab.stop_verified()
        lab.cast.quit_app.assert_not_called()

if __name__=='__main__': unittest.main()
