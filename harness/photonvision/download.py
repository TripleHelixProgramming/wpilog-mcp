#!/usr/bin/env python3
"""Fetch one upstream release, verifying fresh downloads and cache hits before execution."""
import hashlib
import json
from pathlib import Path
import sys
import urllib.request


def verify(path, digest):
    with path.open('rb') as source:
        actual = hashlib.file_digest(source, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else sha256(source)
    if actual != digest:
        raise ValueError(f'PhotonVision SHA-256 mismatch for {path.name}: expected {digest}, got {actual}; remove the cached file and run again')


def sha256(source):
    digest = hashlib.sha256()
    for block in iter(lambda: source.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def download(root):
    release = json.loads((root / 'harness/photonvision/release.json').read_text())
    folder = root / 'build/harness-downloads'
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / release['file']
    if path.exists():
        verify(path, release['sha256'])
    else:
        temporary = path.with_suffix('.part')
        try:
            print(f"Downloading pinned PhotonVision {release['version']}", file=sys.stderr)
            with urllib.request.urlopen(release['url'], timeout=60) as source, temporary.open('wb') as target:
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    target.write(block)
            verify(temporary, release['sha256'])
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    return path


if __name__ == '__main__':
    print(download(Path(__file__).resolve().parents[2]))
