# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
import unittest
from pathlib import Path
import stat
from types import SimpleNamespace
from unittest.mock import call, patch
from ci.check_service import check_health, split_units, check_program_access, service_environment


class ServiceChecksTest(unittest.TestCase):
    def test_access_is_checked_as_the_service_user_with_the_mode_in_the_failure(self):
        launcher = Path('/opt/synthetic/bin/wpilog-mcp')
        jar = Path('/opt/synthetic/jars/server.jar')
        with patch.object(Path, 'stat', return_value=SimpleNamespace(st_mode=stat.S_IFREG | 0o711)), \
                patch('ci.check_service.subprocess.run') as run:
            run.return_value = SimpleNamespace(returncode=1, stderr='')
            with self.assertRaisesRegex(AssertionError, r'Service user wpilog-mcp cannot read .*; mode -rwx--x--x \(0711\)'):
                check_program_access(launcher, jar)
            run.assert_called_once_with(['runuser', '-u', 'wpilog-mcp', '--', 'test', '-r', str(launcher)],
                                        capture_output=True, text=True)
        with patch.object(Path, 'stat', return_value=SimpleNamespace(st_mode=stat.S_IFREG | 0o644)), \
                patch('ci.check_service.subprocess.run') as run:
            run.side_effect = [SimpleNamespace(returncode=0)] * 2 + [SimpleNamespace(returncode=1, stderr='denied')]
            with self.assertRaisesRegex(AssertionError, r'cannot read .*server.jar; mode -rw-r--r-- \(0644\); denied'):
                check_program_access(launcher, jar)
            self.assertEqual([
                call(['runuser', '-u', 'wpilog-mcp', '--', 'test', '-r', str(launcher)], capture_output=True, text=True),
                call(['runuser', '-u', 'wpilog-mcp', '--', 'test', '-x', str(launcher)], capture_output=True, text=True),
                call(['runuser', '-u', 'wpilog-mcp', '--', 'test', '-r', str(jar)], capture_output=True, text=True)], run.call_args_list)

    def test_environment_points_the_service_at_the_runners_jdk_without_shell_expansion(self):
        with patch.object(Path, 'resolve', return_value=Path('/opt/toolcache/JDK 17/bin/java')):
            self.assertEqual('LANG=C.UTF-8\nLC_ALL=C.UTF-8\nJAVA_HOME="/opt/toolcache/JDK 17"\n',
                             service_environment('/usr/bin/java'))

    def test_unmanaged_service_is_a_failure_even_with_the_right_version(self):
        for managed in (None, False, 'true'):
            with self.assertRaisesRegex(AssertionError, 'Expected managed: true'):
                check_health(dict(status='ok', version='synthetic-version', managed=managed), 'synthetic-version')

    def test_version_and_status_are_checked(self):
        check_health(dict(status='ok', managed=True, version='synthetic-version'), 'synthetic-version')
        with self.assertRaisesRegex(AssertionError, 'Expected version'):
            check_health(dict(status='ok', managed=True, version='old'), 'synthetic-version')
        with self.assertRaisesRegex(AssertionError, 'health status'):
            check_health(dict(status='failed', managed=True, version='synthetic-version'), 'synthetic-version')

    def test_only_printed_unit_files_are_installed(self):
        with self.assertRaises(AssertionError):
            split_units('# file: ../../other\n[Unit]\n')
        with self.assertRaises(AssertionError):
            split_units('no units')
