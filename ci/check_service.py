#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Opt-in, root-only integration test on the disposable systemd CI runner, never on a developer host."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import stat
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


def unit_names(name):
    base = f'wpilog-mcp-{name}'
    return dict(unit=f'{base}.service', probe=f'{base}-health.service', timer=f'{base}-health.timer',
                restart=f'{base}-restart.service')


def split_units(text, name):
    files = {}
    for part in text.split('# file: ')[1:]:
        filename, body = part.split('\n', 1)
        if not filename.startswith('wpilog-mcp-') or '/' in filename or '\\' in filename:
            raise AssertionError('Unexpected unit filename: ' + filename)
        files[filename] = body
    if set(files) != set(unit_names(name).values()):
        raise AssertionError('Expected the service, its health probe and timer, and the restart service; got '
                             + (', '.join(sorted(files)) or 'none'))
    return files


# Each plant removes one thing the verifier must notice. An edit that finds nothing proves nothing.
PLANTS = {'unmanaged': ('unit', ' --managed', ''), 'no-restart': ('probe', '\nOnFailure=', '\n#OnFailure=')}


def plant_units(units, name, plant):
    if plant is None:
        return units
    role, old, new = PLANTS[plant]
    filename = unit_names(name)[role]
    if old not in units[filename]:
        raise AssertionError(f'The {plant} plant found nothing to change in {filename}')
    return {**units, filename: units[filename].replace(old, new, 1)}


def main_pid(unit):
    shown = subprocess.run(['systemctl', 'show', '--property=MainPID', '--value', unit],
                           capture_output=True, text=True, check=True).stdout.strip()
    return int(shown or 0)


def health_of(port):
    with urllib.request.urlopen(f'http://127.0.0.1:{port}/health', timeout=2) as response:
        return json.load(response)


def check_restart(unit, probe, port, version):
    """A server that stops answering is restarted. A paused process keeps its port open but silent,
    as a hung server does: the probe must use up its retries and fail, and its OnFailure= handler
    must leave a new process answering."""
    paused = main_pid(unit)
    if paused <= 0:
        raise AssertionError('The service has no main process to pause')
    os.kill(paused, signal.SIGSTOP)
    started = time.monotonic()
    probed = subprocess.run(['systemctl', 'start', probe], capture_output=True, text=True)
    probe_seconds = time.monotonic() - started
    if probed.returncode == 0:
        raise AssertionError('The health probe passed against a paused server')
    # Three attempts of at most 5 s, 10 s apart: a failure in under 30 s skipped the retries.
    if probe_seconds < 30:
        raise AssertionError(f'The probe failed after {probe_seconds:.1f} s, before using its retries: {probed.stderr}')
    deadline = time.monotonic() + 30
    while True:
        current = main_pid(unit)
        if current > 0 and current != paused:
            try:
                health = health_of(port)
                break
            except (OSError, urllib.error.URLError):
                pass
        if time.monotonic() >= deadline:
            raise AssertionError(f'systemd did not restart the paused server (PID {paused}) after its probe failed')
        time.sleep(0.2)
    check_health(health, version)
    return dict(paused_pid=paused, restarted_pid=current, probe_failed_after_seconds=round(probe_seconds, 1),
                answered_after_seconds=round(time.monotonic() - started, 1))


def run(args, **kwargs):
    print('+', ' '.join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), check=True, text=True, **kwargs)


def service_environment(java):
    # systemd does not inherit the runner shell's hosted-toolcache PATH.
    home = str(Path(java).resolve().parent.parent).replace('\\', '\\\\').replace('"', '\\"')
    return f'LANG=C.UTF-8\nLC_ALL=C.UTF-8\nJAVA_HOME="{home}"\n'


def check_program_access(launcher, jar):
    facts = []
    for path, checks in ((launcher, ('r', 'x')), (jar, ('r',))):
        mode = path.stat().st_mode
        mode_text = f'{stat.filemode(mode)} ({stat.S_IMODE(mode):04o})'
        for check in checks:
            result = subprocess.run(['runuser', '-u', 'wpilog-mcp', '--', 'test', '-' + check, str(path)],
                                    capture_output=True, text=True)
            if result.returncode:
                action = 'read' if check == 'r' else 'execute'
                raise AssertionError(f'Service user wpilog-mcp cannot {action} {path}; mode {mode_text}; {result.stderr.strip()}')
        facts.append(dict(path=str(path), mode=mode_text))
    return facts


def verify(jar, plant):
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.geteuid() != 0:
        raise RuntimeError('This installs a test service: run only as root on a disposable GitHub Actions runner')
    report = Path('build/reports/systemd').resolve()
    report.mkdir(parents=True, exist_ok=True)
    label = f'{plant}-plant' if plant else 'service'
    name = 'ci-service'
    names = unit_names(name)
    unit, timer, probe, restart = names['unit'], names['timer'], names['probe'], names['restart']
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
    (config_dir / 'environment').write_text(service_environment(java))
    (config_dir / 'environment').chmod(0o600)
    # The printer embeds the config path; the resulting files are installed without rewriting it.
    text = run([java, '-jar', jar, 'service-unit', name, '--config', config], capture_output=True).stdout
    (report / f'{label}-units.txt').write_text(text)
    units = plant_units(split_units(text, name), name, plant)
    for filename, content in units.items():
        (Path('/etc/systemd/system') / filename).write_text(content)
    result = dict(version=version, plant=plant)
    start = time.monotonic()
    try:
        result['program_access'] = check_program_access(Path('/opt/wpilog-mcp/bin/wpilog-mcp'),
                                                       Path(f'/opt/wpilog-mcp/jars/wpilog-mcp-{version}.jar'))
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
        # The timer would race the probe started below; the probe and its handler are under test.
        run(['systemctl', 'stop', timer])
        result['restart'] = check_restart(unit, probe, port, version)
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
            subprocess.run(['journalctl', '--no-pager', '-u', unit, '-u', timer, '-u', probe, '-u', restart],
                           stdout=output, stderr=subprocess.STDOUT)
        subprocess.run(['systemctl', 'stop', timer, probe, restart, unit], timeout=95)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('jar', type=Path)
    plants = parser.add_mutually_exclusive_group()
    plants.add_argument('--plant-unmanaged', action='store_true')
    plants.add_argument('--plant-no-restart', action='store_true')
    options = parser.parse_args()
    verify(options.jar.resolve(), 'unmanaged' if options.plant_unmanaged else 'no-restart' if options.plant_no_restart else None)
