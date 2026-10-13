# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Fast checks for the frozen-build collector; no Gradle or robot is required."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from ci import capture_read_behavior as capture


class CaptureReadBehaviorTest(unittest.TestCase):
    def test_normalizes_only_top_level_tool_execution_time_and_preserves_number_lexemes(self):
        wire = b'{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"{\\"value\\":1.0000000000000000001,\\"_execution_time_ms\\":12.5,\\"nested\\":{\\"_execution_time_ms\\":7},\\"warning\\":\\"keep me\\"}"},{"type":"image","mimeType":"image/png","data":"ABCD"}],"isError":false}}'
        result = capture.mcp_payload(wire, 'application/json', tool_call=True)
        text = result['result']['content'][0]['text']
        self.assertIn('1.0000000000000000001', text)
        self.assertEqual(2, text.count('_execution_time_ms'))
        self.assertEqual('keep me', json.loads(text)['warning'])
        self.assertEqual('ABCD', result['result']['content'][1]['data'])
        self.assertEqual(0, json.loads(text)['_execution_time_ms'])
        self.assertIn('_execution_time_ms', capture.mcp_payload(wire, 'application/json')['result']['content'][0]['text'])

    def test_timing_normalization_cannot_hide_a_missing_field_or_wrong_type(self):
        def wire(text):
            return json.dumps({'result': {'content': [{'type': 'text', 'text': text}]}}).encode()
        present = capture.mcp_payload(wire('{"_execution_time_ms":1}'), 'application/json', tool_call=True)
        absent = capture.mcp_payload(wire('{}'), 'application/json', tool_call=True)
        self.assertNotEqual(present, absent)
        for value in ('"wrong"', '-1', 'null', 'true'):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'execution_time_ms'):
                capture.mcp_payload(wire('{"_execution_time_ms":' + value + '}'), 'application/json', tool_call=True)

    def test_millisecond_corpus_mtime_detects_touch_without_content_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / 'fixture.wpilog'
            path.write_bytes(b'fixed')
            os.utime(path, ns=(1700000000000000000, 1700000000000000000))
            manifest = {'format_version': 1, 'inputs': [{'path': 'fixture.wpilog', 'size_bytes': 5,
                'sha256': hashlib.sha256(b'fixed').hexdigest(), 'modified_ms': 1700000000000}], 'cases': []}
            capture.verify_inputs(root, manifest)
            os.utime(path, ns=(1700000000001000000, 1700000000001000000))
            with self.assertRaisesRegex(ValueError, 'mtime'):
                capture.verify_inputs(root, manifest)

    def test_sse_multiline_data_and_utf8_preserve_complete_rpc_response(self):
        wire = ': keepalive\r\nevent: message\r\ndata: {"jsonrpc":"2.0",\r\ndata: "id":3,"result":{"text":"caf\u00e9"}}\r\n\r\n'.encode()
        self.assertEqual('caf\u00e9', capture.mcp_payload(wire, 'text/event-stream')['result']['text'])
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            capture.mcp_payload(wire + wire, 'text/event-stream')

    def test_duplicate_keys_and_nonfinite_json_are_refused(self):
        for body in (b'{"id":1,"id":2}', b'{"value":NaN}'):
            with self.subTest(body=body), self.assertRaises(ValueError):
                capture.mcp_payload(body, 'application/json')

    def test_child_environment_isolated_without_losing_java_lookup(self):
        env = capture.child_environment({'PATH': '/bin', 'TBA_API_KEY': 'secret', 'WPILOG_DIR': '/private/logs',
                                         'WPILOG_HTTP_BIND': '0.0.0.0', 'JAVA_TOOL_OPTIONS': '-Duser.home=/real',
                                         'JDK_JAVA_OPTIONS': '-agentlib:jdwp', '_JAVA_OPTIONS': '-Doverride=true',
                                         'HTTP_PROXY': 'http://proxy', 'HOME': '/real'}, Path('/scratch'))
        self.assertEqual('/bin', env['PATH'])
        self.assertNotIn('TBA_API_KEY', env)
        self.assertNotIn('JAVA_TOOL_OPTIONS', env)
        self.assertNotIn('JDK_JAVA_OPTIONS', env)
        self.assertNotIn('_JAVA_OPTIONS', env)
        self.assertNotIn('HTTP_PROXY', env)
        self.assertEqual('/scratch', env['HOME'])
        self.assertEqual('127.0.0.1', env['WPILOG_HTTP_BIND'])
        self.assertEqual('/scratch/cache', env['WPILOG_DISK_CACHE_DIR'])
        self.assertNotIn('WPILOG_DIR', env)

    def test_corpus_hash_and_mtime_are_checked_and_escape_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / 'sample.wpilog'
            path.write_bytes(b'known bytes')
            manifest = {'format_version': 1, 'inputs': [{'path': 'sample.wpilog', 'size_bytes': 11,
                         'sha256': hashlib.sha256(b'known bytes').hexdigest(), 'mtime_ns': path.stat().st_mtime_ns}], 'cases': []}
            capture.verify_inputs(root, manifest)
            path.write_bytes(b'other bytes')
            with self.assertRaisesRegex(ValueError, 'hash|mtime'):
                capture.verify_inputs(root, manifest)
            manifest['inputs'][0]['path'] = '../elsewhere'
            with self.assertRaisesRegex(ValueError, 'outside|relative|traversal'):
                capture.verify_inputs(root, manifest)

    def test_headers_keep_semantic_fields_and_omit_only_named_runtime_fields(self):
        headers = {'Content-Type': 'application/json', 'Date': 'today', 'Mcp-Session-Id': 'random',
                   'Content-Length': '40', 'ETag': 'exact', 'X-New-Behavior': 'keep'}
        normalized = capture.response_headers(headers, mcp=True)
        self.assertEqual({'content-type': 'application/json', 'etag': 'exact', 'x-new-behavior': 'keep'}, normalized)
        self.assertEqual('40', capture.response_headers(headers, mcp=False)['content-length'])

    def test_request_cannot_leave_owned_loopback_server(self):
        for path in ('https://example.com/', '//example.com/', '/other', '/data/entries/../stop'):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'data/entries'):
                capture.data_url('http://127.0.0.1:1234', {'path': path, 'query': []})
        url = capture.data_url('http://127.0.0.1:1234', {'path': '/data/entries', 'query': [['name', 'a b'], ['name', 'a"b']]})
        self.assertEqual('http://127.0.0.1:1234/data/entries?name=a+b&name=a%22b', url)

    def test_existing_output_and_unowned_exports_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            existing = root / 'existing'
            existing.mkdir()
            with self.assertRaises(FileExistsError):
                capture.prepare_output(existing)
            exports = root / 'exports'
            exports.mkdir()
            (exports / 'mine.csv').write_text('keep')
            with self.assertRaisesRegex(ValueError, 'not empty'):
                capture.prepare_exports(exports)
            self.assertEqual('keep', (exports / 'mine.csv').read_text())

    def test_http_total_deadline_expires_even_when_the_reader_never_finishes(self):
        release = threading.Event()
        finished = threading.Event()
        def blocked(*args, **kwargs):
            try:
                release.wait()
                raise OSError('released')
            finally:
                finished.set()
        opener = unittest.mock.Mock()
        opener.open.side_effect = blocked
        try:
            with patch.object(capture.urllib.request, 'build_opener', return_value=opener), \
                 self.assertRaisesRegex(TimeoutError, 'exceeded'):
                capture.request('GET', 'http://127.0.0.1:1234/data/entries', timeout=.01)
        finally:
            release.set()
            self.assertTrue(finished.wait(1), 'The controlled reader must be released after its deadline')

    def test_http_error_status_is_captured_and_redirects_are_not_followed(self):
        import io
        headers = {'Content-Type': 'application/json'}
        error = capture.urllib.error.HTTPError('http://127.0.0.1:1234/data/entries', 403, 'Forbidden', headers,
                                             io.BytesIO(b'{"error":"outside directories"}'))
        opener = unittest.mock.Mock()
        opener.open.side_effect = error
        with patch.object(capture.urllib.request, 'build_opener', return_value=opener):
            answer = capture.request('GET', 'http://127.0.0.1:1234/data/entries')
        self.assertEqual(403, answer.status)
        self.assertEqual(b'{"error":"outside directories"}', answer.body)
        self.assertIsNone(capture.NoRedirect().redirect_request(None, None, 302, 'Found', {}, 'https://outside/'))

    def test_close_terminates_only_owned_process_and_kills_after_bound(self):
        process = unittest.mock.Mock()
        process.poll.return_value = None
        process.wait.side_effect = [capture.subprocess.TimeoutExpired('java', 5), 0]
        capture.stop_process(process)
        process.terminate.assert_called_once()
        process.kill.assert_called_once()
        self.assertEqual(2, process.wait.call_count)

    def test_setup_barriers_refuse_an_incomplete_or_error_result(self):
        def payload(text, **extra):
            return {'result': {'content': [{'type': 'text', 'text': text}], **extra}}
        self.assertTrue(capture._setup_ok(payload('{"status":"ok","completed":true}')))
        self.assertFalse(capture._setup_ok(payload('{"status":"ok","completed":false}')))
        self.assertFalse(capture._setup_ok(payload('{"status":"error"}')))
        self.assertFalse(capture._setup_ok(payload('{"status":"ok"}', isError=True)))
        self.assertFalse(capture._setup_ok({'error': {'message': 'no'}}))

    def test_outer_json_numeric_lexemes_survive_writing_as_well_as_tool_text(self):
        value = capture.parse_json('{"integer":9223372036854775807,"precise":1.0000000000000000001}')
        self.assertEqual('{"integer":9223372036854775807,"precise":1.0000000000000000001}', capture.json_text(value))

    def test_capture_records_discovery_cases_data_bytes_and_normalization_inventory(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            corpus = root / 'corpus'
            (corpus / 'files').mkdir(parents=True)
            jar = root / 'server.jar'
            jar.write_bytes(b'jar')
            (corpus / 'manifest.json').write_text(json.dumps({'format_version': 1, 'inputs': [], 'cases': [
                {'id': 'read', 'kind': 'mcp', 'tool': 'read_entry', 'arguments': {'path': '/fixture', 'name': 'a'}},
                {'id': 'csv', 'kind': 'http', 'path': '/data/entries', 'query': [['name', 'a'], ['format', 'csv']]},
                {'id': 'export', 'kind': 'mcp', 'tool': 'export_csv', 'arguments': {'output_path': 'result.csv'}},
                {'id': 'after-export', 'kind': 'mcp', 'tool': 'read_entry', 'arguments': {'path': '/fixture', 'name': 'a'}}
            ]}))
            calls = []
            def request(method, url, body=None, headers=None, timeout=60):
                calls.append((method, url, body, headers))
                if url.endswith('/health'):
                    return capture.Response(200, {'Content-Type': 'application/json'}, b'{"status":"ok","pid":123}')
                if method == 'DELETE':
                    return capture.Response(200, {}, b'')
                if method == 'GET':
                    return capture.Response(200, {'Content-Type': 'text/csv; charset=utf-8', 'ETag': 'frozen'}, b'timestamp,value\n1,2\n')
                message = json.loads(body)
                if message['method'] == 'initialize':
                    return capture.Response(200, {'Content-Type': 'application/json', 'Mcp-Session-Id': 'session'}, b'{"jsonrpc":"2.0","id":1,"result":{}}')
                if message['method'] == 'notifications/initialized':
                    return capture.Response(202, {}, b'')
                if message.get('params', {}).get('name') == 'export_csv':
                    (corpus / 'exports' / 'result.csv').write_bytes(b'timestamp,value\n1,2\n')
                content = '{"value":2,"_execution_time_ms":3}' if message['method'] == 'tools/call' else '{"tools":[]}'
                response = {'jsonrpc': '2.0', 'id': message['id'], 'result': {'content': [{'type': 'text', 'text': content}]}}
                return capture.Response(200, {'Content-Type': 'application/json'}, json.dumps(response).encode())
            process = unittest.mock.Mock(pid=123)
            process.poll.return_value = None
            with patch.object(capture.subprocess, 'Popen', return_value=process), \
                 patch.object(capture, 'request', side_effect=request), \
                 patch.object(capture, 'java_version', return_value='test java'):
                result = capture.run_capture(jar, corpus, root / 'capture', port=12345)
            self.assertEqual(['initialize', 'tools/list', 'resources/list', 'read', 'csv', 'export', 'after-export'], [case['id'] for case in result['cases']])
            self.assertEqual(hashlib.sha256((corpus / 'manifest.json').read_bytes()).hexdigest(), result['corpus_sha256'])
            self.assertEqual(12345, result['port'])
            self.assertTrue(result['normalization'])
            read = json.loads((root / 'capture' / result['cases'][3]['result']).read_text())
            self.assertEqual(0, json.loads(read['body']['result']['content'][0]['text'])['_execution_time_ms'])
            csv = result['cases'][4]['artifacts'][0]
            self.assertEqual(b'timestamp,value\n1,2\n', (root / 'capture' / csv['path']).read_bytes())
            self.assertFalse(any((corpus / 'exports').iterdir()))
            exported = result['cases'][5]['artifacts'][0]
            self.assertEqual('export/result.csv', exported['name'])
            self.assertEqual([], result['cases'][6]['artifacts'], 'A read must not recopy every earlier export')
            self.assertEqual(b'timestamp,value\n1,2\n', (root / 'capture' / exported['path']).read_bytes())
            self.assertTrue(any(call[0] == 'DELETE' for call in calls))
            process.terminate.assert_called_once()


if __name__ == '__main__':
    unittest.main()
