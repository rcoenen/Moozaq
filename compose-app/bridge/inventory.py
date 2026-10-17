"""Keep physical speakers visible through transient discovery gaps."""
import json
import threading


class DeviceInventory:
    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.known = {}
        try:
            rows = json.loads(path.read_text())
            self.known = {r['id']: r for r in rows if isinstance(r, dict)
                          and r.get('cast_type') in ('audio', 'cast')
                          and isinstance(r.get('id'), str) and isinstance(r.get('name'), str)}
        except (OSError, ValueError, TypeError):
            pass

    @staticmethod
    def row(info):
        return dict(id=str(info.uuid), name=info.friendly_name,
                    model=info.model_name or 'Cast', cast_type=info.cast_type or '')

    def observe(self, infos):
        with self.lock:
            changed = False
            for info in infos:
                if info.cast_type not in ('audio', 'cast'):
                    continue  # Temporary groups must be allowed to disappear.
                row = self.row(info)
                if self.known.get(row['id']) != row:
                    self.known[row['id']] = row
                    changed = True
            if changed:
                temporary = self.path.with_suffix('.tmp')
                temporary.write_text(json.dumps(list(self.known.values())))
                temporary.replace(self.path)

    def snapshot(self, infos):
        current = {str(i.uuid): self.row(i) for i in infos}
        with self.lock:
            rows = {uid: dict(row, available=False) for uid, row in self.known.items()}
        rows.update({uid: dict(row, available=True) for uid, row in current.items()})
        return sorted(rows.values(), key=lambda row: row['name'].casefold())
