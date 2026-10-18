"""Repack a wheel whose native modules are optional as py3-none-any, dropping .so files."""
import base64, hashlib, sys, zipfile
from pathlib import Path

src, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)
name, version = src.name.split('-')[:2]
dest = out_dir / f'{name}-{version}-py3-none-any.whl'
with zipfile.ZipFile(src) as zin:
    items = [(n, zin.read(n)) for n in zin.namelist() if not n.endswith(('.so', '/', '.pyx', '.c')) and not n.endswith('/RECORD')]
dist_info = next(n for n, _ in items if n.endswith('.dist-info/WHEEL')).rsplit('/', 1)[0]
fixed = []
for n, data in items:
    if n == f'{dist_info}/WHEEL':
        lines = [l for l in data.decode().splitlines() if not l.startswith(('Tag:', 'Root-Is-Purelib:'))]
        data = ('\n'.join(lines) + '\nRoot-Is-Purelib: true\nTag: py3-none-any\n').encode()
    fixed.append((n, data))
record = []
with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as zout:
    for n, data in fixed:
        zout.writestr(n, data)
        digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b'=').decode()
        record.append(f'{n},sha256={digest},{len(data)}')
    record.append(f'{dist_info}/RECORD,,')
    zout.writestr(f'{dist_info}/RECORD', '\n'.join(record) + '\n')
print(dest)
