#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Unknown history must run checks, never break or silently narrow the workflow."""
import os
import re
import subprocess


def changed_paths(base):
    if not re.fullmatch(r'[0-9a-fA-F]{40,64}', base or '') or set(base) == {'0'}:
        return None
    if subprocess.run(['git', 'cat-file', '-e', base + '^{commit}'],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        return None
    result = subprocess.run(['git', 'diff', '--name-only', '-z', base, 'HEAD'], capture_output=True)
    if result.returncode:
        return None
    return result.stdout.decode('utf-8', errors='replace').strip('\0').split('\0')


def flags(files):
    if files is None:
        return dict(extension=True, harness=True, service=True, native=True)
    workflow = '.github/workflows/ci.yml' in files
    selected = dict(
        extension=any(p.startswith(('vscode-extension/', 'src/main/java/org/triplehelix/wpilogmcp/mcp/',
            'src/main/java/org/triplehelix/wpilogmcp/config/', 'src/main/resources/install/'))
            or p in ('src/main/java/org/triplehelix/wpilogmcp/Main.java', 'install.sh', 'install.ps1',
                'ci/changes.py', 'ci/test_changes.py') for p in files),
        harness=any(p.startswith(('harness/', 'src/main/java/org/triplehelix/wpilogmcp/capture/',
            'src/main/java/org/triplehelix/wpilogmcp/nt4/', 'src/main/java/org/triplehelix/wpilogmcp/sync/',
            'src/test/java/org/triplehelix/wpilogmcp/conformance/', 'src/test/java/org/triplehelix/wpilogmcp/harness/'))
            or p == 'build.gradle' for p in files),
        service=any(p.startswith(('src/main/java/org/triplehelix/wpilogmcp/config/', 'src/main/resources/install/',
            'src/main/resources/service/', 'src/test/java/org/triplehelix/wpilogmcp/config/Service', 'ci/check_service', 'ci/test_check_service'))
            or p in ('src/main/java/org/triplehelix/wpilogmcp/Main.java', 'install.sh', 'install.ps1', 'build.gradle',
                'ci/changes.py', 'ci/test_changes.py') for p in files),
        native=any(p.startswith(('harness/', 'src/main/java/org/triplehelix/wpilogmcp/nt4/',
            'src/test/java/org/triplehelix/wpilogmcp/harness/')) or p in (
            'src/main/java/org/triplehelix/wpilogmcp/capture/CaptureWriter.java',
            'src/main/java/org/triplehelix/wpilogmcp/capture/WpilogOutput.java') or (
            p.startswith('src/test/java/org/triplehelix/wpilogmcp/conformance/') and not p.endswith('WiringTest.java') and any(word in p for word in ('Replay', 'Replayer', 'Harness'))) for p in files))
    if workflow:
        selected.update(extension=True, harness=True, service=True)
    return selected


if __name__ == '__main__':
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        for key, value in flags(changed_paths(os.environ.get('BEFORE', ''))).items():
            setting = ('sample' if value else 'none') if key == 'native' else str(value).lower()
            output.write(f'{key}={setting}\n')
