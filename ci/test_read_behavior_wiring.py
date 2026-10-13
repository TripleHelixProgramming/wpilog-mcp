# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""The frozen-read comparison gate must keep running when CI wiring changes."""
from pathlib import Path
import re
import shlex
import unittest


REQUIRED = frozenset({
    'ci.test_compare_read_behavior',
    'ci.test_capture_read_behavior',
    'ci.test_read_behavior_wiring',
})


def changes_test_modules(workflow):
    """Read the changes job's inline unittest command, never comments or other jobs."""
    job = re.search(r'^  changes:\s*\n(.*?)(?=^  [\w-]+:\s*$|\Z)',
                    workflow, re.MULTILINE | re.DOTALL)
    if job is None:
        return set()
    modules = set()
    for command in re.findall(r'^        run: (.+)$', job.group(1), re.MULTILINE):
        words = shlex.split(command, comments=True)
        if words[:3] == ['python3', '-m', 'unittest']:
            modules.update(words[3:])
    return modules


class ReadBehaviorWiringTest(unittest.TestCase):
    def test_changes_job_runs_every_read_behavior_check(self):
        workflow = Path(__file__).resolve().parents[1] / '.github/workflows/ci.yml'
        missing = REQUIRED - changes_test_modules(workflow.read_text(encoding='utf-8'))
        self.assertEqual(set(), missing, 'Read-behavior checks absent from the changes job: '
                         + ', '.join(sorted(missing)))

    def test_comments_and_another_job_do_not_count_as_scheduled_checks(self):
        workflow = '''jobs:
  changes:
    steps:
      - name: Tests
        # run: python3 -m unittest ci.test_compare_read_behavior
        run: python3 -m unittest ci.test_changes # ci.test_capture_read_behavior
  build:
    steps:
      - run: python3 -m unittest ci.test_read_behavior_wiring
'''
        self.assertEqual({'ci.test_changes'}, changes_test_modules(workflow))


if __name__ == '__main__':
    unittest.main()
