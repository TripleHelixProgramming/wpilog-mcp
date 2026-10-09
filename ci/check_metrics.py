#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Optional CI smoke check: packaged server, the documented Compose stack, and its dashboard."""
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request


def get(url, authenticated=False):
    headers = {}
    if authenticated:
        # Grafana's initial credentials, in this test's newly created private volume only.
        headers['Authorization'] = 'Basic ' + base64.b64encode(b'admin:admin').decode('ascii')
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=2) as response:
        return response.read()


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


def main():
    jars = list(Path('build/libs').glob('*-all.jar'))
    assert len(jars) == 1, 'Build one shadow JAR first'
    output = Path('build/metrics-smoke')
    output.mkdir(parents=True, exist_ok=True)
    compose = ['docker', 'compose', '-p', f'wpilog-metrics-check-{os.getpid()}', '-f', 'doc/metrics/compose.yaml']
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
            (output / 'result.json').write_text(json.dumps({'promtool': 'passed', 'scrape': 'passed', 'dashboard_panels': 12,
                'queries': 12, 'grafana_datasource': 'passed'}, indent=2) + '\n', encoding='utf-8')
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
