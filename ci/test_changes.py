# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
import unittest
from unittest.mock import patch
from ci.changes import changed_paths, flags


class ChangesTest(unittest.TestCase):
    def test_unknown_history_runs_everything(self):
        for base in ('', '0' * 40, '1' * 40, '--bad-ref'):
            with patch('ci.changes.subprocess.run') as run:
                run.return_value.returncode = 128
                self.assertEqual(dict(extension=True, harness=True, service=True, native=True), flags(changed_paths(base)))
                self.assertLessEqual(run.call_count, 1, 'Never diff an unreachable before SHA')

    def test_real_jar_mcp_tests_trigger_the_extension(self):
        self.assertTrue(flags(['src/main/java/org/triplehelix/wpilogmcp/mcp/ClientLeases.java'])['extension'])
        self.assertFalse(flags(['doc/TOOLS.md'])['extension'])

    def test_daemon_and_installer_fixes_recheck_the_editors_without_replaying_capture(self):
        for path in ('src/main/java/org/triplehelix/wpilogmcp/Main.java',
                     'src/main/java/org/triplehelix/wpilogmcp/config/DaemonManager.java',
                     'src/main/java/org/triplehelix/wpilogmcp/config/InstallCommand.java',
                     'src/main/resources/install/launcher.sh', 'ci/changes.py', 'ci/test_changes.py'):
            with self.subTest(path=path):
                selected = flags([path])
                self.assertTrue(selected['extension'], path)
                self.assertFalse(selected['harness'], path)
                self.assertFalse(selected['native'], path)

    def test_service_and_harness_scopes(self):
        for path in ('src/main/java/org/triplehelix/wpilogmcp/Main.java', 'src/main/java/org/triplehelix/wpilogmcp/config/ServiceUnit.java',
                     'src/main/resources/service/server.service', 'install.sh', 'install.ps1'):
            self.assertTrue(flags([path])['service'], path)
        self.assertTrue(flags(['src/main/java/org/triplehelix/wpilogmcp/capture/context/StatsProvider.java'])['harness'])
        self.assertFalse(flags(['doc/TOOLS.md'])['service'])

    def test_stats_changes_do_not_pay_for_native_replay(self):
        self.assertFalse(flags(['src/main/java/org/triplehelix/wpilogmcp/capture/context/StatsProvider.java'])['native'])
        for path in ('src/main/java/org/triplehelix/wpilogmcp/capture/CaptureWriter.java',
                     'src/main/java/org/triplehelix/wpilogmcp/nt4/client/Nt4Client.java', 'harness/robot/build.gradle'):
            self.assertTrue(flags([path])['native'], path)

    def test_known_history_uses_the_diff(self):
        with patch('ci.changes.subprocess.run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = b'doc/TOOLS.md\0vscode-extension/src/extension.ts\0'
            self.assertEqual(['doc/TOOLS.md', 'vscode-extension/src/extension.ts'], changed_paths('a' * 40))
            self.assertEqual(2, run.call_count)
