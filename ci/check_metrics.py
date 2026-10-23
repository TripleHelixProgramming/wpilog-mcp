#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Optional CI smoke check: packaged server, the documented Compose stack, and its dashboard."""
import base64
import copy
import json
import os
from pathlib import Path
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
        server = subprocess.Popen(['java', '-Xmx256m', '-Duser.home=' + directory, '-jar', str(jars[0].resolve()),
                                   '--internal-daemon', 'metrics', '--config', str(config)], env=env, stdout=log, stderr=log)
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
            (output / 'result.json').write_text(json.dumps({'promtool': 'passed', 'scrape': 'passed', 'dashboard_panels': 12,
                'queries': 12, 'grafana_datasource': 'passed', 'infinity_version': version, 'recorded_panels': len(plots),
                'recorded_refusal': refusal, 'history_panels': len(history_plots), 'history_refusal': history_answer},
                indent=2) + '\n', encoding='utf-8')
        finally:
            try:
                with (output / 'containers.log').open('w') as containers:
                    subprocess.run(compose + ['logs', '--no-color'], stdout=containers, stderr=subprocess.STDOUT)
                subprocess.run(compose + ['down', '--volumes'], check=True)
            finally:
                server.terminate()
                try:
                    server.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)


if __name__ == '__main__':
    main()
