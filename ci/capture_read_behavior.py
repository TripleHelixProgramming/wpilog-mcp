#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Collect a frozen JAR's externally visible read behavior without rebuilding it.

The corpus is immutable input, not a golden file generator. Use the same corpus and
--port for baseline and candidate: paths and advertised endpoint URLs are behavior.
Every output is retained in a new directory; failed runs cannot masquerade as captures.
"""
import argparse
from dataclasses import dataclass
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


NORMALIZATION = [
    'Replace only top-level nonnegative numeric _execution_time_ms with numeric zero in tools/call JSON text; preserve presence and reject wrong types.',
    'Omit HTTP Date: response generation time is run-specific.',
    'Omit Mcp-Session-Id: the random session is used for transport, not compared as an identity.',
    'Omit MCP Content-Length: normalizing execution duration changes the original response length.',
    'Compare JSON objects by members; preserve arrays, strings and every number lexeme.',
    'Startup health and explicit before barriers are setup; their responses are retained under raw/ but not compared.',
]


class JsonNumber(str):
    """Keep wire numeric spelling, including int64 and decimals Python float cannot hold."""


def _object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError('Duplicate JSON object member: ' + name)
        result[name] = value
    return result


def _invalid_constant(value):
    raise ValueError('Non-finite JSON constant: ' + value)


def parse_json(text):
    return json.loads(text, parse_int=JsonNumber, parse_float=JsonNumber,
                      parse_constant=_invalid_constant, object_pairs_hook=_object)


def json_text(value):
    if isinstance(value, JsonNumber):
        return str(value)
    if isinstance(value, dict):
        return '{' + ','.join(json.dumps(key, ensure_ascii=False) + ':' + json_text(item)
                              for key, item in value.items()) + '}'
    if isinstance(value, (list, tuple)):
        return '[' + ','.join(json_text(item) for item in value) + ']'
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def write_json(path, value):
    path.write_text(json_text(value) + '\n', encoding='utf-8')


def mcp_payload(body, content_type, tool_call=False):
    text = body.decode('utf-8', errors='strict')
    if content_type.split(';', 1)[0].strip().lower() == 'text/event-stream':
        messages = []
        data = []
        for line in text.replace('\r\n', '\n').replace('\r', '\n').split('\n') + ['']:
            if not line:
                if data:
                    messages.append('\n'.join(data))
                    data = []
            elif line.startswith('data:'):
                value = line[5:]
                data.append(value[1:] if value.startswith(' ') else value)
        if len(messages) != 1:
            raise ValueError('Expected exactly one MCP response in SSE stream')
        text = messages[0]
    elif content_type.split(';', 1)[0].strip().lower() != 'application/json':
        raise ValueError('Unexpected MCP response content type: ' + content_type)
    result = parse_json(text)
    if not isinstance(result, dict):
        raise ValueError('Expected MCP response object')
    if tool_call and isinstance(result.get('result'), dict):
        for block in result['result'].get('content', []):
            if isinstance(block, dict) and block.get('type') == 'text' and isinstance(block.get('text'), str):
                try:
                    parsed = parse_json(block['text'])
                except (ValueError, TypeError):
                    continue  # Non-JSON text is itself part of the public MCP contract.
                if isinstance(parsed, dict) and '_execution_time_ms' in parsed:
                    timing = parsed['_execution_time_ms']
                    if not isinstance(timing, JsonNumber) or Decimal(timing) < 0:
                        raise ValueError('_execution_time_ms must be a nonnegative JSON number')
                    parsed['_execution_time_ms'] = JsonNumber('0')
                    block['text'] = json_text(parsed)
    return result


def response_headers(headers, mcp=False):
    omitted = {'date', 'mcp-session-id'}
    if mcp:
        omitted.add('content-length')
    return {key.lower(): value for key, value in headers.items() if key.lower() not in omitted}


@dataclass
class Response:
    status: int
    headers: dict
    body: bytes

    def header(self, name):
        return next((value for key, value in self.headers.items() if key.lower() == name.lower()), '')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(method, url, body=None, headers=None, timeout=60):
    """A total deadline, not just a per-read idle timeout; redirects never escape localhost."""
    answer = queue.Queue(maxsize=1)
    def perform():
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
            req = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
            try:
                response = opener.open(req, timeout=timeout)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                answer.put(Response(response.status, dict(response.headers.items()), response.read()))
        except Exception as error:
            answer.put(error)
    # A timeout aborts the run and closes the owned server, releasing this reader too.
    threading.Thread(target=perform, name='read-behavior-http', daemon=True).start()
    try:
        result = answer.get(timeout=timeout)
    except queue.Empty as error:
        raise TimeoutError('HTTP request exceeded %.1f seconds: %s %s' % (timeout, method, url)) from error
    if isinstance(result, Exception):
        raise result
    return result


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def within(root, relative):
    name = Path(relative)
    if name.is_absolute() or '..' in name.parts or not name.parts:
        raise ValueError('Input path must be relative without traversal: ' + str(relative))
    path = root / name
    if root.resolve() not in path.resolve().parents:
        raise ValueError('Input path is outside corpus: ' + str(relative))
    return path


def verify_inputs(root, manifest):
    if int(manifest.get('format_version', 0)) != 1:
        raise ValueError('Unknown corpus format version')
    for item in manifest['inputs']:
        path = within(root, item['path'])
        stat = path.stat()
        if not path.is_file() or stat.st_size != int(item['size_bytes']):
            raise ValueError('Frozen input size differs: ' + item['path'])
        if sha256(path) != item['sha256']:
            raise ValueError('Frozen input hash differs: ' + item['path'])
        if 'modified_ms' in item and stat.st_mtime_ns // 1000000 != int(item['modified_ms']):
            raise ValueError('Frozen input mtime differs: ' + item['path'])
        if 'mtime_ns' in item and stat.st_mtime_ns != int(item['mtime_ns']):
            raise ValueError('Frozen input mtime differs: ' + item['path'])


def child_environment(source, home):
    excluded = {'TBA_API_KEY', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS',
                'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY'}
    result = {key: value for key, value in source.items()
              if not key.upper().startswith('WPILOG') and key.upper() not in excluded}
    result.update(HOME=str(home), USERPROFILE=str(home), WPILOG_HTTP_BIND='127.0.0.1',
                  WPILOG_DISK_CACHE_DIR=str(home / 'cache'))
    return result


def prepare_output(path):
    path.mkdir(parents=True, exist_ok=False)
    for child in ('results', 'artifacts', 'raw'):
        (path / child).mkdir()


def prepare_exports(path):
    if path.is_symlink():
        raise ValueError('Export directory must not be a symlink')
    path.mkdir(exist_ok=True)
    if any(path.iterdir()):
        raise ValueError('Corpus exports directory is not empty; refusing to remove unowned files: ' + str(path))


def data_url(base, case):
    if case.get('path') != '/data/entries':
        raise ValueError('Only /data/entries is admitted for HTTP corpus cases')
    return base + '/data/entries?' + urllib.parse.urlencode([tuple(pair) for pair in case.get('query', [])])


def reserve_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def java_version(java, env):
    completed = subprocess.run([java, '-version'], env=env, capture_output=True, text=True, timeout=15, check=True)
    return (completed.stdout + completed.stderr).strip()


def stop_process(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def await_health(base, process):
    deadline = time.monotonic() + 30
    last = 'no health response'
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Owned server exited before health; see server.stderr.log')
        try:
            response = request('GET', base + '/health', timeout=min(1, max(.01, deadline - time.monotonic())))
            body = parse_json(response.body.decode('utf-8'))
            if response.status == 200 and int(body.get('pid', -1)) == process.pid:
                return response
            last = 'health did not identify owned PID %s: %s' % (process.pid, response.body[:200])
        except (OSError, ValueError, TimeoutError) as error:
            last = str(error)
        time.sleep(.025)
    raise TimeoutError('Owned server health deadline expired: ' + last)


def _setup_ok(payload):
    result = payload.get('result')
    if 'error' in payload or not isinstance(result, dict) or result.get('isError'):
        return False
    for block in result.get('content', []):
        if block.get('type') != 'text':
            continue
        try:
            body = parse_json(block['text'])
        except ValueError:
            return False
        if isinstance(body, dict) and (body.get('status') == 'error' or body.get('completed') is False):
            return False
    return True


def run_capture(jar, corpus, output, port=None, java='java'):
    jar, corpus, output = jar.resolve(), corpus.resolve(), output.resolve()
    corpus_file = corpus / 'manifest.json'
    corpus_bytes = corpus_file.read_bytes()
    corpus_hash = hashlib.sha256(corpus_bytes).hexdigest()
    manifest = parse_json(corpus_bytes.decode('utf-8'))
    verify_inputs(corpus, manifest)
    cases = manifest['cases']
    reserved = {'initialize', 'tools/list', 'resources/list'}
    ids = [case['id'] for case in cases]
    if len(ids) != len(set(ids)) or reserved.intersection(ids):
        raise ValueError('Corpus case ids must be unique and not reserved discovery ids')
    prepare_output(output)
    exports = corpus / 'exports'
    prepare_exports(exports)
    port = port or reserve_port()
    if not 1 <= port <= 65535:
        raise ValueError('Port must be between 1 and 65535')
    base = 'http://127.0.0.1:' + str(port)
    started = time.monotonic()
    result = {'format_version': 1, 'normalization': NORMALIZATION, 'corpus_sha256': corpus_hash,
              'port': port, 'cases': [],
              'run': {'jar_sha256': sha256(jar), 'corpus_sha256': corpus_hash, 'port': port,
                      'case_timings_sec': {}, 'setup_timings_sec': {}}}
    session = None
    process = None
    request_id = 0
    owned_exports = set()
    def rpc(method, params=None, tool_call=False):
        nonlocal request_id, session
        request_id += 1
        message = {'jsonrpc': '2.0', 'id': request_id, 'method': method}
        if params is not None:
            message['params'] = params
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
        if session:
            headers['Mcp-Session-Id'] = session
        response = request('POST', base + '/mcp', json_text(message).encode('utf-8'), headers)
        (output / 'raw' / ('rpc-%04d.body' % request_id)).write_bytes(response.body)
        write_json(output / 'raw' / ('rpc-%04d.headers.json' % request_id), response.headers)
        if method == 'initialize':
            session = response.header('Mcp-Session-Id')
            if response.status != 200 or not session:
                raise RuntimeError('MCP initialize failed or returned no session id')
        payload = mcp_payload(response.body, response.header('Content-Type'), tool_call=tool_call)
        if str(payload.get('id')) != str(request_id):
            raise ValueError('MCP response id does not match request')
        return {'status': response.status, 'headers': response_headers(response.headers, mcp=True), 'body': payload}
    def store(case, response, artifacts=None):
        ordinal = len(result['cases']) + 1
        path = 'results/%04d.json' % ordinal
        write_json(output / path, response)
        result['cases'].append({'id': case['id'], 'request': case, 'result': path, 'artifacts': artifacts or []})
    try:
        with tempfile.TemporaryDirectory(prefix='wpilog-read-behavior-') as scratch:
            home = Path(scratch)
            env = child_environment(os.environ, home)
            result['run']['java_version'] = java_version(java, env)
            args = [java, '-Duser.home=' + str(home), '-Djava.io.tmpdir=' + str(home), '-jar', str(jar),
                    '--http', '--port', str(port), '-logdir', str(corpus / 'files'), '-exportdir', str(exports)]
            with (output / 'server.stderr.log').open('wb') as stderr, (output / 'server.stdout.log').open('wb') as stdout:
                process = subprocess.Popen(args, env=env, cwd=home, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr)
                try:
                    health = await_health(base, process)
                    (output / 'raw' / 'health.json').write_bytes(health.body)
                    for method in ('initialize', 'tools/list', 'resources/list'):
                        params = {'protocolVersion': '2025-03-26', 'capabilities': {},
                                  'clientInfo': {'name': 'frozen-read-behavior', 'version': '1'}} if method == 'initialize' else {}
                        case = {'id': method, 'kind': 'mcp', 'method': method, 'params': params}
                        store(case, rpc(method, params))
                        if method == 'initialize':
                            notification = {'jsonrpc': '2.0', 'method': 'notifications/initialized'}
                            initialized = request('POST', base + '/mcp', json_text(notification).encode('utf-8'),
                                                  {'Content-Type': 'application/json', 'Mcp-Session-Id': session})
                            if initialized.status != 202:
                                raise RuntimeError('Initialized notification refused: ' + str(initialized.status))
                    for index, case in enumerate(cases):
                        start = time.monotonic()
                        for setup in case.get('before', []):
                            setup_start = time.monotonic()
                            answer = rpc('tools/call', {'name': setup['tool'], 'arguments': setup.get('arguments', {})}, tool_call=True)
                            if answer['status'] != 200 or not _setup_ok(answer['body']):
                                raise RuntimeError('Setup failed before case ' + case['id'] + ': ' + json_text(answer))
                            result['run']['setup_timings_sec'][case['id']] = time.monotonic() - setup_start
                        artifacts = []
                        export_target = None
                        if case.get('tool') == 'export_csv' and case.get('arguments', {}).get('output_path'):
                            named = Path(case['arguments']['output_path'])
                            export_target = named if named.is_absolute() else exports / named
                            if exports.resolve() not in export_target.resolve().parents or export_target.is_symlink():
                                raise ValueError('Export output_path is outside the owned exports directory')
                            owned_exports.add(export_target)
                        if case['kind'] == 'mcp':
                            response = rpc('tools/call', {'name': case['tool'], 'arguments': case.get('arguments', {})}, tool_call=True)
                        elif case['kind'] == 'http':
                            headers = case.get('headers', {})
                            if any(key.lower() in ('host', 'authorization', 'proxy-authorization') for key in headers):
                                raise ValueError('Corpus cannot override host or credentials')
                            wire = request('GET', data_url(base, case), headers=headers)
                            artifact = 'artifacts/%04d.http-body' % index
                            (output / artifact).write_bytes(wire.body)
                            content_type = wire.header('Content-Type').split(';', 1)[0]
                            kind = 'arrow' if 'arrow' in content_type else 'csv' if content_type == 'text/csv' else 'json' if content_type == 'application/json' else 'binary'
                            artifacts.append({'name': 'http-body', 'path': artifact, 'kind': kind})
                            response = {'status': wire.status, 'headers': response_headers(wire.headers)}
                        else:
                            raise ValueError('Unknown corpus case kind: ' + str(case['kind']))
                        if export_target is not None and export_target.exists():
                            if export_target.is_symlink() or not export_target.is_file():
                                raise ValueError('Unexpected non-file export target')
                            relative = export_target.relative_to(exports).as_posix()
                            artifact = 'artifacts/%04d.export' % index
                            (output / artifact).write_bytes(export_target.read_bytes())
                            artifacts.append({'name': 'export/' + relative, 'path': artifact,
                                              'kind': 'csv' if export_target.suffix.lower() == '.csv' else 'binary'})
                        store(case, response, artifacts)
                        result['run']['case_timings_sec'][case['id']] = time.monotonic() - start
                finally:
                    if session and process.poll() is None:
                        try:
                            request('DELETE', base + '/mcp', headers={'Mcp-Session-Id': session}, timeout=3)
                        except Exception as error:
                            result['run']['session_close_error'] = str(error)
                    stop_process(process)
                    process = None
        unexpected = [path for path in exports.rglob('*') if path.is_symlink() or (path.is_file() and path not in owned_exports)]
        if unexpected:
            raise ValueError('Unexpected unowned output in exports directory: ' + str(unexpected[0]))
        if corpus_file.read_bytes() != corpus_bytes:
            raise ValueError('Frozen corpus manifest changed during capture')
        verify_inputs(corpus, manifest)
        result['run']['wall_sec'] = time.monotonic() - started
        result['run']['case_count'] = len(result['cases'])
        write_json(output / 'manifest.json', result)
        return result
    except BaseException as error:
        write_json(output / 'failure.json', {'error': type(error).__name__, 'reason': str(error),
                                           'completed_cases': len(result['cases']), 'wall_sec': time.monotonic() - started})
        raise
    finally:
        if process is not None:
            stop_process(process)
        # The directory was empty before this run; never clear unrelated prior exports.
        for path in owned_exports:
            if path.is_file() and not path.is_symlink():
                path.unlink()
        for path in sorted(exports.rglob('*'), reverse=True):
            if path.is_dir() and not path.is_symlink():
                try:
                    path.rmdir()
                except OSError:
                    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', type=int, help='Use the SAME unused loopback port for both captures')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    result = run_capture(args.jar, args.corpus, args.output, port=args.port, java=args.java)
    print('Captured %d cases in %.3f s on port %d' %
          (result['run']['case_count'], result['run']['wall_sec'], result['run']['port']))


if __name__ == '__main__':
    main()
