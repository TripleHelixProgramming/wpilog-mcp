"""No network: prove cache hits are verified and a changed download is never installed."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import download


class DownloadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        folder = self.root / 'harness/photonvision'
        folder.mkdir(parents=True)
        self.payload = b'synthetic release bytes'
        (folder / 'release.json').write_text(json.dumps(dict(
            version='fixture', file='fixture.jar', url='https://example.invalid/release.jar',
            sha256=hashlib.sha256(self.payload).hexdigest())))
        self.cached = self.root / 'build/harness-downloads/fixture.jar'

    def test_verified_cache_never_downloads(self):
        self.cached.parent.mkdir(parents=True)
        self.cached.write_bytes(self.payload)
        with patch('urllib.request.urlopen') as request:
            self.assertEqual(self.cached, download.download(self.root))
        request.assert_not_called()

    def test_changed_cache_is_refused_before_execution(self):
        self.cached.parent.mkdir(parents=True)
        self.cached.write_bytes(b'changed release bytes!!')
        with patch('urllib.request.urlopen') as request:
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                download.download(self.root)
        request.assert_not_called()

    def test_download_is_installed_only_after_its_hash_matches(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'changed')):
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                download.download(self.root)
        self.assertFalse(self.cached.exists())
        self.assertFalse(self.cached.with_suffix('.part').exists())
        with patch('urllib.request.urlopen', return_value=io.BytesIO(self.payload)):
            self.assertEqual(self.cached, download.download(self.root))
        self.assertEqual(self.payload, self.cached.read_bytes())
