"""Read-only Mac grouping spike. Capture before/after Google Home membership changes.

Never sends LOAD, LAUNCH, STOP, volume, join_group or disband_group.
Snapshots contain allowlisted state, never media URLs or credentials.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import time
from inspect_groups import inspect
import pychromecast
import zeroconf


def compare(before, after):
    old = {d['uuid']: d for d in before['devices']}
    new = {d['uuid']: d for d in after['devices']}
    changes = []
    for uid in sorted(old.keys() | new.keys()):
        a, b = old.get(uid), new.get(uid)
        if a is None or b is None:
            changes.append({'device': (b or a)['name'], 'discovery': 'appeared' if b else 'missing'})
            continue
        for key in ('configured_groups', 'dynamic_groups', 'members', 'host', 'port'):
            av, bv = a.get(key), b.get(key)
            # Membership order is not semantic; receivers can reorder status replies.
            if key == 'members':
                av = sorted(av) if isinstance(av, list) else av
                bv = sorted(bv) if isinstance(bv, list) else bv
            elif key in ('configured_groups', 'dynamic_groups'):
                av = sorted(av, key=lambda g: g.get('uuid', '')) if isinstance(av, list) else av
                bv = sorted(bv, key=lambda g: g.get('uuid', '')) if isinstance(bv, list) else bv
            if av != bv:
                changes.append({'device': b['name'], 'field': key, 'before': a.get(key), 'after': b.get(key)})
        ar, br = a.get('receiver', {}), b.get('receiver', {})
        for key in ('app_id', 'media_session', 'state', 'position'):
            if ar.get(key) != br.get(key):
                changes.append({'device': b['name'], 'field': key, 'before': ar.get(key), 'after': br.get(key)})
    return changes


def capture():
    z = zeroconf.Zeroconf()
    browser = pychromecast.CastBrowser(pychromecast.SimpleCastListener(lambda *_: None), z)
    browser.start_discovery()
    try:
        time.sleep(10)
        infos = list(browser.devices.values())
        with ThreadPoolExecutor(max_workers=4) as pool:
            rows = list(pool.map(lambda info: inspect(info, z, ping_enabled=False), infos))
        return {'captured_at': time.time(), 'devices': sorted(rows, key=lambda d: d['name']),
                'note': 'Membership is not proof of audible output; snapshots are not simultaneous.'}
    finally:
        browser.stop_discovery()
        z.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', default='baseline', choices=['baseline', 'expanded', 'contracted', 'rejoined'])
    parser.add_argument('--compare', type=Path, help='Previous snapshot; compares observations, never changes devices')
    args = parser.parse_args()
    report = capture()
    if args.compare:
        report['changes'] = compare(json.loads(args.compare.read_text()), report)
    folder = Path(__file__).resolve().parents[1] / 'data' / 'grouping-spike'
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f'{time.time_ns()}-{args.label}.json'
    path.write_text(json.dumps(report, indent=2))
    print(f'Saved {path}')
    for row in report['devices']:
        print(row['name'], row['type'], row.get('receiver', {}).get('state', 'unavailable'),
              'saved=', len(row.get('configured_groups', [])), 'temporary=', len(row.get('dynamic_groups', [])))
    if 'changes' in report:
        print(json.dumps(report['changes'], indent=2))


if __name__ == '__main__':
    main()
