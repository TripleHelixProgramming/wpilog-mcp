# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""The parity gate must catch tiny changes rather than comparing rounded answers."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import weakref

from ci import compare_read_behavior
from ci.compare_read_behavior import compare_captures


class ReadBehaviorComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.baseline = self.root / 'baseline'
        self.candidate = self.root / 'candidate'
        self.manifest = {
            'format_version': 1, 'run': {'commit': 'old', 'seconds': 5},
            'cases': [{'id': 'fixture/read', 'request': {'name': 'read_entry', 'arguments': {}},
                       'result': 'result.json', 'artifacts': [
                           {'name': 'arrow', 'path': 'values.arrow', 'kind': 'arrow'}]}]}
        for directory in (self.baseline, self.candidate):
            directory.mkdir()
            self.write_manifest(directory, self.manifest)
            (directory / 'result.json').write_text(
                '{"status":"ok","value":1.00000000000000000001,"optional":null,'
                '"samples":[2,1],"warnings":["incomplete"]}', encoding='utf-8')
            (directory / 'values.arrow').write_bytes(b'ARROW\x00\x01')

    @staticmethod
    def write_manifest(directory, value):
        (directory / 'manifest.json').write_text(json.dumps(value), encoding='utf-8')

    def report(self, **kwargs):
        return compare_captures(self.baseline, self.candidate, **kwargs)

    def replace(self, old, new):
        path = self.candidate / 'result.json'
        path.write_text(path.read_text(encoding='utf-8').replace(old, new), encoding='utf-8')

    def assert_mismatch(self, path_fragment):
        result = self.report()
        self.assertFalse(result['equal'], result)
        self.assertGreater(result['difference_count'], 0)
        self.assertTrue(any(path_fragment in d['path'] for d in result['differences']), result)
        return result

    def test_identical_captures_and_only_run_metadata_may_differ(self):
        manifest = dict(self.manifest, run={'commit': 'new', 'seconds': 9})
        self.write_manifest(self.candidate, manifest)
        report = self.report()
        self.assertTrue(report['equal'], report)
        self.assertEqual(1, report['cases_compared'])
        self.assertEqual(1, report['artifacts_compared'])

    def test_object_key_order_and_whitespace_are_not_behavior(self):
        self.replace('{"status":"ok",', '{\n')
        self.replace('"warnings":["incomplete"]}', '"warnings":["incomplete"],"status":"ok"}')
        self.assertTrue(self.report()['equal'])

    def test_tiny_numeric_change_is_not_rounded_away(self):
        self.replace('1.00000000000000000001', '1.00000000000000000002')
        self.assert_mismatch('["value"]')

    def test_large_integer_and_exponent_changes_are_exact(self):
        for before, after in [('9007199254740992', '9007199254740993'),
                              ('1e99999', '2e99999'), ('-0.0', '0.0'), ('1', '1.0')]:
            with self.subTest(before=before, after=after):
                (self.baseline / 'result.json').write_text('{"value":' + before + '}')
                (self.candidate / 'result.json').write_text('{"value":' + after + '}')
                self.assert_mismatch('["value"]')

    def test_boolean_is_not_an_integer(self):
        self.replace('1.00000000000000000001', 'true')
        self.assert_mismatch('["value"]')

    def test_null_cannot_be_replaced_by_an_absent_field(self):
        self.replace('"optional":null,', '')
        self.assert_mismatch('["optional"]')

    def test_array_order_is_preserved(self):
        self.replace('[2,1]', '[1,2]')
        self.assert_mismatch('["samples"][0]')

    def test_warning_cannot_disappear(self):
        self.replace('["incomplete"]', '[]')
        self.assert_mismatch('["warnings"]')

    def test_binary_arrow_change_is_detected(self):
        (self.candidate / 'values.arrow').write_bytes(b'ARROW\x00\x02')
        self.assert_mismatch('artifacts["arrow"]')

    def test_missing_and_extra_cases_are_detected(self):
        self.manifest['cases'].append(dict(self.manifest['cases'][0], id='another/read'))
        self.write_manifest(self.baseline, self.manifest)
        self.assert_mismatch('cases["another/read"]')
        self.write_manifest(self.candidate, self.manifest)
        self.write_manifest(self.baseline, dict(self.manifest, cases=self.manifest['cases'][:1]))
        self.assert_mismatch('cases["another/read"]')

    def test_empty_captures_cannot_claim_behavioral_parity(self):
        for directory in (self.baseline, self.candidate):
            self.write_manifest(directory, dict(self.manifest, cases=[]))
        report = self.report()
        self.assertFalse(report['equal'], report)
        self.assertTrue(report['errors'], report)

    def test_result_memory_is_released_before_the_next_case(self):
        self.manifest['cases'] = [dict(self.manifest['cases'][0], id='case-' + str(i)) for i in range(6)]
        for directory in (self.baseline, self.candidate):
            self.write_manifest(directory, self.manifest)
        references = []
        high_water = []
        original = compare_read_behavior._json

        class TrackedResult(dict):
            pass

        def track(path):
            value = original(path)
            if path.name != 'manifest.json':
                value = TrackedResult(value)
                references.append(weakref.ref(value))
                high_water.append(sum(reference() is not None for reference in references))
            return value

        with patch.object(compare_read_behavior, '_json', side_effect=track):
            report = self.report()
        self.assertTrue(report['equal'], report)
        self.assertEqual(6, report['cases_compared'])
        self.assertLessEqual(max(high_water), 2, 'only the current pair of results may remain live')
        self.assertTrue(all(reference() is None for reference in references))

    def test_request_changes_are_not_hidden_by_identical_results(self):
        self.manifest['cases'][0]['request']['arguments']['limit'] = 10
        self.write_manifest(self.candidate, self.manifest)
        self.assert_mismatch('["request"]["arguments"]["limit"]')

    def test_timing_is_not_normalized_by_the_comparator(self):
        self.replace('"optional":null', '"optional":null,"_execution_time_ms":1')
        self.assert_mismatch('["_execution_time_ms"]')

    def test_mcp_json_text_is_compared_structurally_without_normalization(self):
        for directory, content in [(self.baseline, '{"value":1,"status":"ok"}'),
                                   (self.candidate, '{"status":"ok","value":1}')]:
            response = {'jsonrpc': '2.0', 'id': 7, 'result': {
                'content': [{'type': 'text', 'text': content}], 'isError': False}}
            (directory / 'result.json').write_text(json.dumps(response))
        self.assertTrue(self.report()['equal'])
        self.replace('value\\":1', 'value\\":1.00000000000000000001')
        self.assert_mismatch('["value"]')

    def test_duplicate_keys_invalid_json_and_missing_files_fail_closed(self):
        for malformed in ['{"value":1,"value":1}', '{"value":NaN}', '{"value":',
                          '{"value":Infinity}']:
            with self.subTest(malformed=malformed):
                (self.candidate / 'result.json').write_text(malformed)
                report = self.report()
                self.assertFalse(report['equal'])
                self.assertTrue(report['errors'], report)
        (self.candidate / 'result.json').unlink()
        self.assertTrue(self.report()['errors'])

    def test_duplicate_case_and_artifact_names_are_invalid(self):
        self.manifest['cases'].append(self.manifest['cases'][0])
        self.write_manifest(self.candidate, self.manifest)
        self.assertTrue(self.report()['errors'])
        self.manifest['cases'].pop()
        self.manifest['cases'][0]['artifacts'].append(self.manifest['cases'][0]['artifacts'][0])
        self.write_manifest(self.candidate, self.manifest)
        self.assertTrue(self.report()['errors'])

    def test_paths_cannot_escape_the_capture_including_symlinks(self):
        outside = self.root / 'outside.json'
        outside.write_text('{}')
        for relative in ['../outside.json', str(outside), 'C:\\outside.json', 'a/../../outside.json']:
            with self.subTest(path=relative):
                self.manifest['cases'][0]['result'] = relative
                self.write_manifest(self.candidate, self.manifest)
                self.assertTrue(self.report()['errors'])
        self.manifest['cases'][0]['result'] = 'link.json'
        try:
            (self.candidate / 'link.json').symlink_to(outside)
        except OSError:
            self.skipTest('Creating symlinks is unavailable on this platform')
        self.write_manifest(self.candidate, self.manifest)
        self.assertTrue(self.report()['errors'])

    def test_embedded_mcp_json_duplicate_keys_are_invalid(self):
        result = {'content': [{'type': 'text', 'text': '{"x":1,"x":2}'}]}
        (self.candidate / 'result.json').write_text(json.dumps(result))
        self.assertTrue(self.report()['errors'])

    def test_mcp_text_must_be_a_string_before_its_json_is_decoded(self):
        for directory, text in [(self.baseline, '{"value":1}'), (self.candidate, {'value': 1})]:
            result = {'content': [{'type': 'text', 'text': text}]}
            (directory / 'result.json').write_text(json.dumps(result))
        report = self.report()
        self.assertFalse(report['equal'], report)
        self.assertTrue(report['errors'], report)
        self.assertIn('text must be a string', report['errors'][0]['message'])

    def test_a_user_value_named_content_is_not_treated_as_an_mcp_envelope(self):
        for directory, content in [(self.baseline, '{"a":1,"b":2}'),
                                   (self.candidate, '{"b":2,"a":1}')]:
            value = {'value': {'content': [{'type': 'text', 'text': content}]}}
            (directory / 'result.json').write_text(json.dumps(value))
        self.assert_mismatch('["text"]')

    def test_report_cannot_overwrite_capture_evidence(self):
        path = self.candidate / 'result.json'
        before = path.read_bytes()
        command = [sys.executable, str(Path(__file__).with_name('compare_read_behavior.py')),
                   str(self.baseline), str(self.candidate), '--report', str(path)]
        completed = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(0, completed.returncode)
        self.assertIn('outside both capture directories', completed.stderr)
        self.assertEqual(before, path.read_bytes())

    def test_difference_count_is_not_capped_with_its_examples(self):
        self.replace('[2,1]', '[7,8]')
        report = self.report(max_diffs=1)
        self.assertEqual(2, report['difference_count'])
        self.assertEqual(1, len(report['differences']))

    def test_cli_fails_with_machine_readable_report_and_never_rewrites_captures(self):
        self.replace('["incomplete"]', '[]')
        path = self.candidate / 'result.json'
        before = path.read_bytes()
        report_path = self.root / 'comparison.json'
        command = [sys.executable, str(Path(__file__).with_name('compare_read_behavior.py')),
                   str(self.baseline), str(self.candidate), '--report', str(report_path)]
        completed = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(1, completed.returncode, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertFalse(report['equal'])
        self.assertEqual(report, json.loads(report_path.read_text()))
        self.assertEqual(before, path.read_bytes())


if __name__ == '__main__':
    unittest.main()
