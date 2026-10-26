import hashlib
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from catalog_publish import validate_app_catalog


@dataclass(frozen=True)
class ReleaseBundle:
    database: Path
    manifest: Path
    data: dict
    tag: str


def prepare_release(database, manifest_path=None, *, repository="rcoenen/Moozaq-catalog", min_app_version="0.6.0", reuse_existing=False):
    database = Path(database)
    if database.name != "app-catalog.db":
        raise ValueError("Only a validated derived app-catalog.db may be released.")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid catalog repository.")
    if not re.fullmatch(r"\d+\.\d+\.\d+", min_app_version):
        raise ValueError("Minimum app version must use semantic version form.")
    info = validate_app_catalog(database)
    manifest_path = Path(manifest_path) if manifest_path is not None else database.with_name("catalog-manifest.json")
    digest = hashlib.sha256()
    with database.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    tag = f"catalog-v{info['revision']}"
    data = {
        "revision": info["revision"],
        "schema_version": info["schema_version"],
        "min_app_version": min_app_version,
        "generated_at": info["generated_at"],
        "download_url": f"https://github.com/{repository}/releases/download/{tag}/app-catalog.db",
        "size": database.stat().st_size,
        "sha256": digest.hexdigest(),
    }
    if manifest_path.exists():
        previous = json.loads(manifest_path.read_text())
        if reuse_existing and info["revision"] == previous["revision"] and previous == data:
            return ReleaseBundle(database, manifest_path, data, tag)
        if info["revision"] <= previous["revision"]:
            raise ValueError("Catalog revision must increase monotonically.")
    temporary = manifest_path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, sort_keys=True))
    temporary.replace(manifest_path)
    return ReleaseBundle(database, manifest_path, data, tag)


def publish_release(bundle, *, repository="rcoenen/Moozaq-catalog", confirmed=False):
    if confirmed is not True:
        raise PermissionError("Public catalog release requires explicit owner confirmation.")
    result = subprocess.run(
        ["gh", "release", "create", bundle.tag, str(bundle.database), str(bundle.manifest),
         "--repo", repository, "--title", bundle.tag,
         "--notes", f"Moozaq catalog revision {bundle.data['revision']}"],
        check=True, capture_output=True, text=True, timeout=60,
    )
    return {"tag": bundle.tag, "url": result.stdout.strip(),
            "revision": bundle.data["revision"], "assets": [bundle.database.name, bundle.manifest.name]}
