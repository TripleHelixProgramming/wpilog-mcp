#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Optional CI smoke check: packaged server, the documented Compose stack, and its dashboard."""
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

GRAFANA = 'http://127.0.0.1:3000'
INFINITY = 'yesoreyeram-infinity-datasource'


def get(url, authenticated=False):
    headers = {}
    if authenticated:
        # Grafana's initial credentials, in this test's newly created private volume only.
        headers['Authorization'] = 'Basic ' + base64.b64encode(b'admin:admin').decode('ascii')
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=2) as response:
        return response.read()


def post(url, body):
    request = urllib.request.Request(url, data=json.dumps(body).encode('utf-8'), headers={
        'Authorization': 'Basic ' + base64.b64encode(b'admin:admin').decode('ascii'),
        'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as error:
        # Grafana answers a query its data source could not run with an error status and the reason.
        return json.loads(error.read())


def until(description, check):
    deadline = time.monotonic() + 60
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (OSError, ValueError, AssertionError) as error:
            last = error
        time.sleep(.1)  # Service readiness only; no robot clock is simulated here.
    raise AssertionError(f'{description} did not become ready: {last}')


def grafana_sample():
    # Dashboard provisioning can finish while Grafana restarts an updated datasource plugin.
    return until('Grafana datasource', lambda: json.loads(get(
        'http://127.0.0.1:3000/api/datasources/proxy/uid/pit-prometheus/api/v1/query?query=wpilog_nt_connected', True)))


def pinned_infinity(compose):
    # The Infinity release the Compose file pins, as Compose itself reads it.
    config = json.loads(subprocess.check_output(compose + ['config', '--format', 'json'], text=True))
    plugin = config['services']['grafana']['environment']['GF_PLUGINS_PREINSTALL_SYNC']
    name, _, version = plugin.partition('@')
    if name != INFINITY or not version:
        raise ValueError('Grafana must preinstall a pinned Infinity release, not ' + plugin)
    return version


def recording_refusal(dashboard):
    # With nothing recording, the first recorded panel's own query, sent through Grafana, comes back
    # refused by the server: Grafana, Infinity and the data endpoint are wired end to end.
    panel = next(p for p in dashboard['panels'] if p['type'] == 'timeseries')
    query = copy.deepcopy(panel['targets'][0])
    times = {'${__from:date:iso}': '2026-01-01T00:00:00Z', '${__to:date:iso}': '2026-01-01T00:05:00Z'}
    for param in query['url_options']['params']:
        value = param['value']
        param['value'] = times.get(value, 'NT:/synthetic-unpublished' if value.startswith('$') else value)
    answer = post(GRAFANA + '/api/ds/query', {'queries': [query], 'from': 'now-5m', 'to': 'now'})
    error = answer.get('results', {}).get('A', {}).get('error', '')
    if '404' not in error:
        raise AssertionError('The recorded panel was not refused for want of a capture: ' + json.dumps(answer))
    return error


def history_refusal(dashboard):
    # With no store configured, a history panel's own query, sent through Grafana, comes back refused
    # by the server for want of a store: the robot, the source and both entry boxes reach it.
    panel = next(p for p in dashboard['panels'] if p['type'] == 'timeseries')
    query = copy.deepcopy(panel['targets'][0])
    filled = {'${__from:date:iso}': '2026-01-01T00:00:00Z', '${__to:date:iso}': '2026-01-01T00:05:00Z',
              '$robot': 'SYNTHETIC', '$source': 'robot_log'}
    for param in query['url_options']['params']:
        value = param['value']
        param['value'] = filled.get(value, '/synthetic-unpublished' if value.startswith('$') else value)
    answer = post(GRAFANA + '/api/ds/query', {'queries': [query], 'from': 'now-5m', 'to': 'now'})
    error = answer.get('results', {}).get('A', {}).get('error', '')
    if '404' not in error:
        raise AssertionError('The history panel was not refused for want of a store: ' + json.dumps(answer))
    return error


# The fixture store: a robot's roboRIO log and the pit's recording of the same minutes, with clocks
# the fixture sets, so every row a history panel answers is worked out here, apart from the server.
W = 1_767_225_600_000_000  # 2026-01-01T00:00:00Z, in epoch microseconds
RIO, PIT = '/SystemStats/BatteryVoltage', 'NT:/AdvantageKit/SystemStats/BatteryVoltage'
FIXTURE = {
    # session: (provenance, clock entry, clock offset in seconds, battery entry, log times, battery at t)
    'a': ('pulled', '/SystemStats/EpochTimeMicros', 100, RIO, range(1, 21), lambda t: 12 + t / 100),
    'c': ('captured', 'NT:/AdvantageKit/SystemStats/EpochTimeMicros', 89, PIT, range(1, 82), lambda t: 13 + t / 1000),
}
WINDOW = (W + 90_000_000, W + 215_000_000)


def iso(epoch_us):
    seconds, micros = divmod(epoch_us, 1_000_000)
    return time.strftime('%Y-%m-%dT%H:%M:%S', time.gmtime(seconds)) + f'.{micros:06d}Z'


def wpilog(path, entries):
    """A WPILOG from [(name, type, [(time_us, value)])], written from the format's specification
    alone, so the server reads a file it did not write: int64 and double payloads only."""
    def record(entry, time_us, payload):
        # Header byte: 4-byte entry id, 4-byte payload size, 8-byte timestamp.
        return bytes([0x7F]) + struct.pack('<IIQ', entry, len(payload), time_us) + payload
    def text(value):
        data = value.encode('utf-8')
        return struct.pack('<I', len(data)) + data
    out = bytearray(b'WPILOG' + struct.pack('<HI', 0x0100, 0))
    samples = []
    for entry, (name, kind, values) in enumerate(entries, start=1):
        out += record(0, 0, bytes([0]) + struct.pack('<I', entry) + text(name) + text(kind) + text(''))
        samples += [(time_us, entry, struct.pack('<q' if kind == 'int64' else '<d', value)) for time_us, value in values]
    for time_us, entry, payload in sorted(samples, key=lambda s: (s[0], s[1])):
        out += record(entry, time_us, payload)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(out))


def write_fixture_store(root):
    """The store's manifests as the store writes them, and the logs they name."""
    def write(path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    write(root / 'store.json', {'format_version': 1, 'created_at': iso(W), 'id': 'metrics-fixture', 'moves': [],
                                'addresses': {}, 'mirror': False, 'peers': [], 'origin': None})
    write(root / 'robots/RIO/robot.json', {'id': 'RIO', 'serial_number': 'RIO-1', 'name': 'Fixture', 'comments': '',
                                           'basis': 'device', 'contacts': []})
    for session, (kind, clock, offset, battery, times, value) in FIXTURE.items():
        folder = root / 'robots/RIO/sessions/2026-01-01' / session
        log = folder / (session + '.wpilog')
        micros = [t * 1_000_000 for t in times]
        wpilog(log, [(clock, 'int64', [(m, W + offset * 1_000_000 + m) for m in micros]),
                     (battery, 'double', [(m, value(t)) for m, t in zip(micros, times)])])
        start, end = iso(W + (offset + times[0]) * 1_000_000), iso(W + (offset + times[-1]) * 1_000_000)
        provenance = {'kind': kind, 'original_path': None, 'original_name': log.name, 'imported_at': iso(W),
                      'moved': False, 'source_robot_serial': None, 'copied_from': []}
        basis = 'pit_clock' if kind == 'captured' else 'logged:' + clock
        write(folder / 'session.json', {
            'id': session, 'started_at': start, 'ended_at': end, 'start_basis': basis, 'event': None,
            'match_type': None, 'match_number': None, 'team_number': None, 'open_capture': None,
            'files': [{'path': log.name, 'sha256': hashlib.sha256(log.read_bytes()).hexdigest(),
                       'size_bytes': log.stat().st_size, 'kind': 'wpilog', 'provenance': provenance, 'verified': True,
                       'min_timestamp_sec': times[0], 'max_timestamp_sec': times[-1], 'started_at': start,
                       'ended_at': end, 'start_basis': basis, 'truncated': False, 'matching': None}]})


def expected_rows():
    """The window's samples by the stretch rule, from the fixture alone: each roboRIO log answers
    its own span, and the recording the rest; as (UTC microseconds, value), in time order."""
    placed = {}
    for session, (kind, _, offset, _, times, value) in FIXTURE.items():
        placed[session] = [(W + (offset + t) * 1_000_000, value(t)) for t in times]
    spans = [(rows[0][0], rows[-1][0]) for session, rows in placed.items() if FIXTURE[session][0] != 'captured']
    rows = []
    for session, samples in placed.items():
        for utc, value in samples:
            if not WINDOW[0] <= utc <= WINDOW[1]: continue
            if FIXTURE[session][0] == 'captured' and any(a <= utc <= b for a, b in spans): continue
            rows.append((utc, value))
    return sorted(rows)


def expected_buckets(rows, points):
    """The bucket rule, worked apart from the server: a sample's bucket is
    floor((t - start) * points / span), clamped; a bucket starts at start + ceil(span * index /
    points); a bucket with no sample is left out. As (start, count, min, max, mean)."""
    start, end = WINDOW
    span = end - start
    buckets = {}
    for utc, value in rows:
        index = 0 if utc <= start else points - 1 if utc >= end else (utc - start) * points // span
        buckets.setdefault(index, []).append(value)
    return [(start + -(-span * index // points), len(values), min(values), max(values), sum(values) / len(values))
            for index, values in sorted(buckets.items())]


def frame(answer, query):
    """A Grafana answer's first frame, as columns by the query's selectors: Infinity names each
    field by its column's display text, which a dashboard may choose apart from the selector."""
    result = answer.get('results', {}).get('A', {})
    if 'error' in result:
        raise AssertionError('The panel was refused: ' + result['error'])
    if not result.get('frames'):
        raise AssertionError('Grafana answered no frame: ' + json.dumps(answer)[:2000])
    data = result['frames'][0]
    fields = dict(zip([field['name'] for field in data['schema']['fields']], data['data']['values']))
    return {c['selector']: fields[c.get('text') or c['selector']] for c in query['columns']
            if (c.get('text') or c['selector']) in fields}


def filled(target, values):
    """A panel's own query, with the dashboard's variables and time range filled in."""
    query = copy.deepcopy(target)
    for param in query.get('url_options', {}).get('params', []):
        param['value'] = values.get(param['value'], param['value'])
    return query


def history_rows(dashboard, points=None):
    """A history panel's own query through Grafana, on the fixture store: every row the server
    answers, checked against the rows worked out from the fixture."""
    panel = next(p for p in dashboard['panels'] if p['type'] == 'timeseries')
    query = filled(panel['targets'][0], {'$robot': 'RIO', '$source': 'robot_log', '$battery_rio': RIO,
                                         '$battery_pit': PIT, '${__from:date:iso}': iso(WINDOW[0]),
                                         '${__to:date:iso}': iso(WINDOW[1])})
    if points is not None:
        for param in query['url_options']['params']:
            if param['key'] == 'max_points': param['value'] = str(points)
    columns = frame(post(GRAFANA + '/api/ds/query', {'queries': [query], 'from': 'now-5m', 'to': 'now'}), query)
    rows = expected_rows()
    if points is None:
        expected = [(utc // 1000, value, value, value) for utc, value in rows]
    else:
        expected = [(start // 1000, low, high, mean) for start, _, low, high, mean in expected_buckets(rows, points)]
    # A panel that reads nothing answers no columns: zero rows, against the rows expected.
    answered = list(zip(*(columns.get(name, []) for name in ('timestamp_utc', 'min', 'max', 'mean'))))
    if len(answered) != len(expected):
        raise AssertionError(f'{len(answered)} rows answered, {len(expected)} expected: {answered}')
    for row, want in zip(answered, expected):
        if row[0] != want[0] or row[1] != want[1] or row[2] != want[2] or abs(row[3] - want[3]) > 1e-12 * abs(want[3]):
            raise AssertionError(f'Row {row} answered where {want} was expected')
    return len(answered)


def history_choices(dashboard):
    """The robot choice and the sessions table, each through Grafana, on the fixture store."""
    robot = next(v for v in dashboard['templating']['list'] if v['name'] == 'robot')
    # The variable's own query names itself; Grafana answers under the id a query carries.
    query = {**robot['query']['infinityQuery'], 'refId': 'A', 'datasource': robot['datasource']}
    robots = frame(post(GRAFANA + '/api/ds/query', {'queries': [query], 'from': 'now-5m', 'to': 'now'}), query)
    if robots.get('robot_id') != ['RIO']:
        raise AssertionError('The robot choice is not the fixture robot: ' + json.dumps(robots))
    table = next(p for p in dashboard['panels'] if p['type'] == 'table')
    listing = filled(table['targets'][0], {'$robot': 'RIO', '${__from:date:iso}': iso(WINDOW[0]),
                                           '${__to:date:iso}': iso(WINDOW[1])})
    sessions = frame(post(GRAFANA + '/api/ds/query', {'queries': [listing], 'from': 'now-5m', 'to': 'now'}), listing)
    if sorted(sessions.get('session_id', [])) != sorted(FIXTURE):
        raise AssertionError('The sessions table is not the fixture sessions: ' + json.dumps(sessions))
    return len(sessions['session_id'])


def preload_images(compose):
    # Shared CI egress can exhaust Docker Hub's anonymous quota. Fetch the same versions
    # from Prometheus's own registry and Google's Docker Hub cache, retaining Compose's tags.
    images = subprocess.check_output(compose + ['config', '--images'], text=True).splitlines()
    mirrors = {'prom/prometheus:': 'quay.io/prometheus/prometheus:',
               'grafana/grafana:': 'mirror.gcr.io/grafana/grafana:'}
    for image in images:
        prefix = next((p for p in mirrors if image.startswith(p)), None)
        if prefix is None:
            raise ValueError('Unrecognized metrics image: ' + image)
        source = mirrors[prefix] + image[len(prefix):]
        subprocess.run(['docker', 'pull', source], check=True)
        subprocess.run(['docker', 'tag', source, image], check=True)


def main():
    jars = list(Path('build/libs').glob('*-all.jar'))
    assert len(jars) == 1, 'Build one shadow JAR first'
    output = Path('build/metrics-smoke')
    output.mkdir(parents=True, exist_ok=True)
    compose = ['docker', 'compose', '-p', f'wpilog-metrics-check-{os.getpid()}', '-f', 'doc/pit-server/metrics/compose.yaml']
    with tempfile.TemporaryDirectory(prefix='wpilog-metrics-') as directory, (output / 'server.log').open('w') as log:
        config = Path(directory) / 'servers.yaml'
        config.write_text('servers:\n  metrics: {transport: http, port: 2363}\n', encoding='utf-8')
        env = dict(os.environ, WPILOG_HTTP_BIND='0.0.0.0', WPILOG_DISK_CACHE_DIR=str(Path(directory) / 'cache'))
        def start():
            return subprocess.Popen(['java', '-Xmx256m', '-Duser.home=' + directory, '-jar', str(jars[0].resolve()),
                                     '--internal-daemon', 'metrics', '--config', str(config)], env=env, stdout=log, stderr=log)
        server = start()
        try:
            exposition = until('metrics HTTP', lambda: get('http://127.0.0.1:2363/metrics'))
            (output / 'scrape.prom').write_bytes(exposition)
            subprocess.run(compose + ['config', '--quiet'], check=True)
            preload_images(compose)
            subprocess.run(compose + ['up', '-d'], check=True)
            subprocess.run(compose + ['exec', '-T', 'prometheus', 'promtool', 'check', 'config', '/etc/prometheus/prometheus.yml'], check=True)
            subprocess.run(compose + ['exec', '-T', 'prometheus', 'promtool', 'check', 'metrics'], input=exposition, check=True)
            query = 'http://127.0.0.1:9090/api/v1/query?query='
            result = until('Prometheus scrape', lambda: json.loads(get(query + 'wpilog_nt_connected'))['data']['result'])
            assert len(result) == 1 and result[0]['value'][1] == '0', result
            dashboard = until('Grafana provisioned dashboard', lambda: json.loads(get(
                'http://127.0.0.1:3000/api/dashboards/uid/wpilog-pit', True))['dashboard'])
            assert len(dashboard['panels']) == 12
            assert dashboard['panels'][0]['datasource']['uid'] == 'pit-prometheus'
            # Parse every starter query with the real Prometheus server, including empty panels.
            for panel in dashboard['panels']:
                expression = panel['targets'][0]['expr']
                for variable in dashboard['templating']['list']:
                    expression = expression.replace('$' + variable['name'], 'synthetic-unpublished')
                assert json.loads(get(query + urllib.parse.quote(expression, safe='')))['status'] == 'success'
            proxy = grafana_sample()
            assert proxy['data']['result'][0]['value'][1] == '0'
            version = pinned_infinity(compose)
            plugin = until('Infinity plugin', lambda: json.loads(get(GRAFANA + '/api/plugins/' + INFINITY + '/settings', True)))
            assert plugin['info']['version'] == version, plugin['info']
            recording = until('Grafana recorded dashboard', lambda: json.loads(get(
                GRAFANA + '/api/dashboards/uid/wpilog-pit-recording', True))['dashboard'])
            plots = [p for p in recording['panels'] if p['type'] == 'timeseries']
            assert len(plots) == 5 and all(p['datasource']['uid'] == 'pit-recording' for p in plots), recording['panels']
            refusal = until('recorded panel through Infinity', lambda: recording_refusal(recording))
            history = until('Grafana history dashboard', lambda: json.loads(get(
                GRAFANA + '/api/dashboards/uid/wpilog-pit-history', True))['dashboard'])
            history_plots = [p for p in history['panels'] if p['type'] == 'timeseries']
            assert len(history_plots) == 5 and all(p['datasource']['uid'] == 'pit-recording' for p in history_plots), history['panels']
            history_answer = until('history panel through Infinity', lambda: history_refusal(history))
            # The same stack on a store of logs whose clocks the fixture sets: the server restarts
            # with the store as its log directory, and the history dashboard reads it through Grafana.
            stop(server)
            write_fixture_store(Path(directory) / 'store')
            config.write_text(f"logdir: {Path(directory) / 'store'}\nservers:\n  metrics: {{transport: http, port: 2363}}\n",
                              encoding='utf-8')
            server = start()
            until('metrics HTTP on the fixture store', lambda: get('http://127.0.0.1:2363/metrics'))
            sessions = until('history choices on the fixture store', lambda: history_choices(history))
            rows = until('history panel on the fixture store', lambda: history_rows(history))
            buckets = until('history buckets on the fixture store', lambda: history_rows(history, 4))
            (output / 'result.json').write_text(json.dumps({'promtool': 'passed', 'scrape': 'passed', 'dashboard_panels': 12,
                'queries': 12, 'grafana_datasource': 'passed', 'infinity_version': version, 'recorded_panels': len(plots),
                'recorded_refusal': refusal, 'history_panels': len(history_plots), 'history_refusal': history_answer,
                'fixture_sessions': sessions, 'fixture_rows': rows, 'fixture_buckets': buckets},
                indent=2) + '\n', encoding='utf-8')
        finally:
            try:
                with (output / 'containers.log').open('w') as containers:
                    subprocess.run(compose + ['logs', '--no-color'], stdout=containers, stderr=subprocess.STDOUT)
                subprocess.run(compose + ['down', '--volumes'], check=True)
            finally:
                stop(server)


def stop(server):
    server.terminate()
    try:
        server.wait(timeout=10)
    except subprocess.TimeoutExpired:
        server.kill()
        server.wait(timeout=5)


if __name__ == '__main__':
    main()
