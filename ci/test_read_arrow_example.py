# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""The runnable example must not teach clients to discard decode failures."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch


class ReadArrowExampleTest(unittest.TestCase):
    def test_decode_problems_are_reported_beside_the_valid_row_count(self):
        spec = importlib.util.spec_from_file_location(
            'read_arrow_example', Path(__file__).parents[1] / 'examples/read-api/read_arrow.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        problem = {'code': 'MALFORMED_RECORD', 'entry': 'x', 'record_offset': '37', 'message': 'bad size'}
        batches = iter([
            SimpleNamespace(batch=SimpleNamespace(num_rows=1), custom_metadata={b'problems': json.dumps([problem]).encode()}),
            SimpleNamespace(batch=SimpleNamespace(num_rows=0), custom_metadata={b'status': b'ok'})])
        reader = SimpleNamespace(read_next_batch_with_custom_metadata=lambda: next(batches))
        arrow = SimpleNamespace(ipc=SimpleNamespace(open_stream=lambda response: reader))
        responses = iter([io.BytesIO(b'{"id":"fixture"}'), io.BytesIO(), io.BytesIO()])
        opener = SimpleNamespace(open=lambda *a, **k: next(responses))
        output = io.StringIO()
        with patch.dict(sys.modules, {'pyarrow': arrow, 'pyarrow.ipc': arrow.ipc}), \
             patch.object(module.urllib.request, 'build_opener', return_value=opener), \
             patch.object(sys, 'argv', ['read_arrow.py', 'http://localhost', '/test.wpilog', 'x']), \
             contextlib.redirect_stdout(output):
            module.main()
        text = output.getvalue()
        self.assertIn('samples=1 decode_problems=1', text)
        self.assertIn('bad size', text)
        self.assertIn('37', text)


if __name__ == '__main__':
    unittest.main()
