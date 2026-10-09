#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Opt-in, root-only integration test on the disposable systemd CI runner, never on a developer host."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request


def check_health(health, version):
    if health.get('status') != 'ok':
        raise AssertionError(f'health status: {health!r}')
    if health.get('managed') is not True:
        raise AssertionError(f'Expected managed: true from the service, got {health.get("managed")!r}')
    if health.get('version') != version:
        raise AssertionError(f'Expected version {version}, got {health.get("version")!r}')


def split_units(text):
    files = {}
    for part in text.split('# file: ')[1:]:
        name, body = part.split('\n', 1)
        if not name.startswith('wpilog-mcp-') or '/' in name or '\\' in name:
            raise AssertionError('Unexpected unit filename: ' + name)
        files[name] = body
    if len(files) != 3:
        raise AssertionError('Expected the printed service, probe service and timer')
    return files


def run(args, **kwargs):
    print('+', ' '.join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), check=True, text=True, **kwargs)


def verify(jar, planted):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.geteuid() != 0:
        raise RuntimeError('This installs a test service: run only as root on a disposable GitHub Actions runner')
    report = Path('build/reports/systemd').resolve()
    report.mkdir(parents=True, exist_ok=True)
    label = 'unmanaged-plant' if planted else 'service'
    name = 'ci-service'
    unit = f'wpilog-mcp-{name}.service'
    timer = f'wpilog-mcp-{name}-health.timer'
    probe = f'wpilog-mcp-{name}-health.service'
    java = shutil.which('java')
    if not java:
        raise RuntimeError('Java must be on PATH')
    version = run([java, '-jar', jar, '-version'], capture_output=True).stdout.strip().split()[-1]
    with socket.socket() as reserve:
        reserve.bind(('127.0.0.1', 0))
        port = reserve.getsockname()[1]
    if subprocess.run(['id', '-u', 'wpilog-mcp'], capture_output=True).returncode:
        run(['useradd', '--system', '--user-group', '--home-dir', '/var/lib/wpilog-mcp', '--shell', '/usr/sbin/nologin', 'wpilog-mcp'])
    run([java, '-jar', jar, 'install', '--install-dir', '/opt/wpilog-mcp', '--force'])
    config_dir = Path('/etc/wpilog-mcp'); config_dir.mkdir(exist_ok=True)
    config = config_dir / 'servers.yaml'
    config.write_text(f'logdir: /var/lib/wpilog-mcp/logs\nservers:\n  {name}:\n    transport: http\n    port: {port}\n')
    config.chmod(0o644)
    (config_dir / 'environment').write_text('LANG=C.UTF-8\nLC_ALL=C.UTF-8\n')
    (config_dir / 'environment').chmod(0o600)
    # The printer embeds the config path; the resulting files are installed without rewriting it.
    text = run([java, '-jar', jar, 'service-unit', name, '--config', config], capture_output=True).stdout
    (report / f'{label}-units.txt').write_text(text)
    units = split_units(text)
    if planted:
        units[unit] = units[unit].replace(' --managed', '').replace('[Service]', '[Service]\nUnsetEnvironment=INVOCATION_ID')
    for filename, content in units.items():
        (Path('/etc/systemd/system') / filename).write_text(content)
    result = dict(version=version, plant=planted)
    start = time.monotonic()
    try:
        run(['systemd-analyze', 'verify', *[str(Path('/etc/systemd/system') / f) for f in units]])
        run(['systemctl', 'daemon-reload'])
        run(['systemctl', 'start', unit, timer])
        deadline = time.monotonic() + 30
        health = None
        while True:
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=2) as response:
                    health = json.load(response)
                break
            except (OSError, urllib.error.URLError):
                if time.monotonic() >= deadline:
                    raise AssertionError('Service never answered health')
                # Readiness only: return on the first answer, never wait a fixed boot delay.
                time.sleep(0.05)
        result['health'] = health
        check_health(health, version)
        if list(Path('/var/lib/wpilog-mcp').rglob('*.pid')):
            raise AssertionError('Foreground service wrote a PID file')
        stopped = subprocess.run([java, '-jar', str(jar), 'stop', name, '--config', str(config)], capture_output=True, text=True)
        result['stop_refusal'] = stopped.stderr
        if stopped.returncode == 0 or f'systemctl stop {unit}' not in stopped.stderr:
            raise AssertionError('stop must refuse a managed service and name systemctl: ' + stopped.stderr)
        run(['systemctl', 'is-active', '--quiet', unit])
        run(['systemctl', 'start', probe])
        run(['systemctl', 'is-active', '--quiet', timer])
        stop_start = time.monotonic()
        run(['systemctl', 'stop', unit], timeout=90)
        result['stop_seconds'] = time.monotonic() - stop_start
        if result['stop_seconds'] >= 90:
            raise AssertionError('systemctl stop exceeded TimeoutStopSec')
        if subprocess.run(['systemctl', 'is-active', '--quiet', unit]).returncode == 0:
            raise AssertionError('Service remained active after stop')
        result['result'] = 'passed'
    except Exception as error:
        result['result'] = 'failed'; result['reason'] = str(error)
        raise
    finally:
        result['seconds'] = time.monotonic() - start
        (report / f'{label}.json').write_text(json.dumps(result, indent=2) + '\n')
        with (report / f'{label}.journal').open('w') as output:
            subprocess.run(['journalctl', '--no-pager', '-u', unit, '-u', timer, '-u', probe], stdout=output, stderr=subprocess.STDOUT)
        subprocess.run(['systemctl', 'stop', timer, probe, unit], timeout=95)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('jar', type=Path)
    parser.add_argument('--plant-unmanaged', action='store_true')
    options = parser.parse_args()
    verify(options.jar.resolve(), options.plant_unmanaged)
