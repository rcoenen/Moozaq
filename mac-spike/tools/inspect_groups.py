"""Read-only Cast group diagnostics. No LOAD, LAUNCH, transport or volume commands."""
import concurrent.futures
import json
import socket
import subprocess
import time
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from engine import install_passive_status
import pychromecast
import zeroconf
from pychromecast import dial
from pychromecast.controllers.multizone import MultizoneController


def inspect(info, z, ping_enabled=True):
    out = dict(name=info.friendly_name, model=info.model_name, type=info.cast_type,
               host=info.host, port=info.port, uuid=str(info.uuid))
    try:
        started = time.monotonic()
        with socket.create_connection((info.host, info.port), timeout=3):
            out['tcp_connect_ms'] = round((time.monotonic()-started)*1000, 1)
    except OSError as exc:
        out['tcp_error'] = str(exc)
    if info.cast_type != 'group':
        try:
            _, data = dial._get_status(info.services, z, '/setup/eureka_info?params=name,build_info,multizone', True, 5, None)
            build = data.get('build_info', {})
            out['firmware'] = {k: build[k] for k in ('cast_build_revision','system_build_number','release_track') if k in build}
            for source, target in [('groups', 'configured_groups'), ('dynamic_groups', 'dynamic_groups')]:
                out[target] = [{k:g[k] for k in ('name','uuid','elected_leader','cast_port') if k in g}
                               for g in data.get('multizone', {}).get(source, [])]
        except Exception as exc:
            out['firmware_error'] = type(exc).__name__ + ': ' + str(exc)
        if ping_enabled:
            ping = subprocess.run(['/sbin/ping','-n','-c','10','-W','1000',info.host],capture_output=True,text=True,timeout=20)
            out['ping_summary'] = ping.stdout.strip().splitlines()[-2:]
    cast = None
    try:
        cast = pychromecast.get_chromecast_from_cast_info(info,z,tries=1,timeout=5)
        install_passive_status(cast)
        mz = None
        if info.cast_type == 'group':
            mz=MultizoneController(info.uuid);cast.register_handler(mz)
        cast.wait(timeout=8)
        if mz: mz.update_members()
        if cast.status.app_id: cast.media_controller.update_status()
        time.sleep(2)
        m=cast.media_controller.status
        out['receiver'] = {'app':cast.status.display_name,'app_id':cast.status.app_id,'state':m.player_state,
                           'title':m.title,'media_session':m.media_session_id,'position':m.current_time,'volume':cast.status.volume_level,'muted':cast.status.volume_muted}
        if mz: out['members']=mz.members
    except Exception as exc:
        out['receiver_error']=type(exc).__name__ + ': ' + str(exc)
    finally:
        if cast: cast.disconnect()
    return out


def main():
    z=zeroconf.Zeroconf()
    b=pychromecast.CastBrowser(pychromecast.SimpleCastListener(lambda *_:None),z)
    b.start_discovery()
    try:
        time.sleep(8)
        infos=[i for i in list(b.devices.values()) if i.cast_type in ('audio','group')]
        before={str(i.uuid):[i.host,i.port] for i in infos}
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            results=list(pool.map(lambda i:inspect(i,z),infos))
        after={str(i.uuid):[i.host,i.port] for i in list(b.devices.values()) if i.cast_type in ('audio','group')}
        result={'time':time.strftime('%Y-%m-%d %H:%M:%S'),'devices':results,'discovery_before':before,'discovery_after':after}
        path=Path(__file__).resolve().parents[1]/'data'/'group-diagnostics.json'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(result,indent=2))
        print(json.dumps(result,indent=2))
    finally:
        b.stop_discovery();z.close()

if __name__=='__main__': main()
