# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
import unittest
from ci.check_service import check_health, split_units


class ServiceChecksTest(unittest.TestCase):
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
