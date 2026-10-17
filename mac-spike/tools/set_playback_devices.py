"""Explicit native membership experiment. Default is read-only; --apply mutates current playback."""
import argparse
import json
import sys
import time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pychromecast
import zeroconf
from engine import install_passive_status
from grouping import PlaybackMembershipController


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--target', required=True, help='Exact discovered active receiver UUID')
    p.add_argument('--member', action='append', default=[], help='Physical speaker UUID to include')
    p.add_argument('--apply', action='store_true')
    args=p.parse_args()
    z=zeroconf.Zeroconf(); b=pychromecast.CastBrowser(pychromecast.SimpleCastListener(lambda *_:None),z)
    cast=None
    b.start_discovery()
    try:
        time.sleep(10)
        infos={str(i.uuid):i for i in list(b.devices.values())}
        info=infos[args.target]
        cast=pychromecast.get_chromecast_from_cast_info(info,z,tries=1,timeout=5)
        install_passive_status(cast)
        control=PlaybackMembershipController();cast.register_handler(control)
        cast.wait(timeout=8)
        status=control.refresh()
        print(json.dumps({'target':info.friendly_name,'session_id':cast.status.session_id,
                         'membership_status':status},indent=2))
        if args.apply:
            session=cast.status.session_id
            if not session: raise RuntimeError('No active receiver session; refusing mutation.')
            members=[infos[uid] for uid in args.member]
            name=members[0].friendly_name if len(members)==1 else members[0].friendly_name+' + '+str(len(members)-1)
            control.set_devices(session,members,name)
            if not control.response_event.wait(15):
                raise TimeoutError('No membership acknowledgement. Do not automatically retry; inspect topology.')
            print(json.dumps(control.response,indent=2))
    finally:
        if cast:cast.disconnect()
        b.stop_discovery();z.close()

if __name__=='__main__':main()
