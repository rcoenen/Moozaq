import unittest
from unittest.mock import Mock
from engine import install_passive_status

class PassiveStatus(unittest.TestCase):
    def make(self):
        cast=Mock()
        cast.media_controller.namespace='media'
        cast.socket_client.is_connected=True
        cast.socket_client.destination_id='destination'
        cast.socket_client.app_namespaces=['media']
        install_passive_status(cast)
        return cast
    def test_available_channel_only_sends_get_status(self):
        cast=self.make()
        cast.media_controller.update_status()
        cast.media_controller.send_message_nocheck.assert_called_once_with({'type':'GET_STATUS'},callback_function=None)
        cast.media_controller.send_message.assert_not_called()
        cast.start_app.assert_not_called()
    def test_missing_channel_cannot_launch_receiver(self):
        cast=self.make();cast.socket_client.app_namespaces=[]
        callback=Mock();cast.media_controller.update_status(callback_function=callback)
        callback.assert_called_once_with(False,None)
        cast.media_controller.send_message_nocheck.assert_not_called()
        cast.media_controller.send_message.assert_not_called()
        cast.media_controller.launch.assert_not_called()
    def test_disconnected_or_no_destination_sends_nothing(self):
        for field,value in [('is_connected',False),('destination_id',None)]:
            cast=self.make();setattr(cast.socket_client,field,value)
            cast.media_controller.update_status()
            cast.media_controller.send_message_nocheck.assert_not_called()
