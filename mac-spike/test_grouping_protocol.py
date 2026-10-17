import unittest
from types import SimpleNamespace
from unittest.mock import Mock
from grouping import PlaybackMembershipController

class MembershipProtocolTests(unittest.TestCase):
    def setUp(self):
        self.c=PlaybackMembershipController();self.c.send_message=Mock()
        self.info=SimpleNamespace(uuid='9818a09f-2077-5ca6-79d7-9f88c7aca98f',host='192.0.2.1',port=8009,cast_type='audio')

    def test_payload_is_membership_only_and_uses_app_session(self):
        rid=self.c.set_devices('app-session',[self.info],'Bedroom')
        payload=self.c.send_message.call_args.args[0]
        self.assertEqual(payload,{'type':'SET_PLAYBACK_DEVICES','requestId':rid,'sessionId':'app-session','deviceList':[{'deviceId':'9818a09f20775ca679d79f88c7aca98f','ipAddress':'192.0.2.1','port':8009}],'sessionName':'Bedroom'})

    def test_response_must_match_request(self):
        rid=self.c.set_devices('session',[self.info])
        self.c.receive_message(None,{'type':'PLAYBACK_SESSION_UPDATED','requestId':rid-1})
        self.assertFalse(self.c.response_event.is_set())
        self.c.receive_message(None,{'type':'PLAYBACK_SESSION_UPDATED','requestId':rid,'sessionEndpoint':{'deviceId':'x'}})
        self.assertTrue(self.c.response_event.is_set())

    def test_reject_empty_duplicate_saved_group_and_missing_session(self):
        for sid,rows in [('',[self.info]),('s',[]),('s',[self.info,self.info]),('s',[SimpleNamespace(cast_type='group')])]:
            with self.assertRaises(ValueError):self.c.set_devices(sid,rows)
        self.c.send_message.assert_not_called()

if __name__=='__main__':unittest.main()
