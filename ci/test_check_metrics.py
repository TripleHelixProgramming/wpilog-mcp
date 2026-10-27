# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
import json
import subprocess
import unittest
from unittest.mock import call, patch
from urllib.error import HTTPError
import struct
import tempfile
from pathlib import Path
from ci import check_metrics
from ci.check_metrics import grafana_sample, history_refusal, pinned_infinity, preload_images, recording_refusal


class MetricsReadinessTest(unittest.TestCase):
    def test_dashboard_readiness_does_not_imply_datasource_readiness(self):
        sample = {'data': {'result': [{'value': [1, '0']}]}}
        with patch('ci.check_metrics.get', side_effect=[
                HTTPError('synthetic', 404, 'Plugin reloading', None, None), json.dumps(sample)]
                ) as request, patch('ci.check_metrics.time.sleep') as pause:
            self.assertEqual(sample, grafana_sample())
            self.assertEqual(2, request.call_count)
            self.assertEqual(True, request.call_args.args[1])
            pause.assert_called_once()

    def test_a_datasource_that_never_appears_fails_with_its_reason(self):
        with patch('ci.check_metrics.get', side_effect=HTTPError('synthetic', 404, 'Plugin missing', None, None)), \
                patch('ci.check_metrics.time.monotonic', side_effect=[0, 0, 60]), \
                patch('ci.check_metrics.time.sleep'):
            with self.assertRaisesRegex(AssertionError, 'Grafana datasource.*404.*Plugin missing'):
                grafana_sample()


class MetricsImageTest(unittest.TestCase):
    def test_published_mirrors_keep_the_compose_versions_and_tags(self):
        with patch('ci.check_metrics.subprocess.check_output', return_value='prom/prometheus:v3.15.0\ngrafana/grafana:13.2.3\n') as config, \
                patch('ci.check_metrics.subprocess.run') as run:
            preload_images(['docker', 'compose', '-f', 'synthetic.yaml'])
            config.assert_called_once_with(['docker', 'compose', '-f', 'synthetic.yaml', 'config', '--images'], text=True)
            self.assertEqual([
                ['docker', 'pull', 'quay.io/prometheus/prometheus:v3.15.0'],
                ['docker', 'tag', 'quay.io/prometheus/prometheus:v3.15.0', 'prom/prometheus:v3.15.0'],
                ['docker', 'pull', 'mirror.gcr.io/grafana/grafana:13.2.3'],
                ['docker', 'tag', 'mirror.gcr.io/grafana/grafana:13.2.3', 'grafana/grafana:13.2.3'],
            ], [c.args[0] for c in run.call_args_list])
            self.assertTrue(all(c.kwargs['check'] for c in run.call_args_list))

    def test_a_refused_pull_is_tried_again_before_the_image_is_tagged(self):
        refused = subprocess.CalledProcessError(1, ['docker', 'pull'])
        with patch('ci.check_metrics.subprocess.check_output', return_value='prom/prometheus:v3.15.0\n'), \
                patch('ci.check_metrics.subprocess.run', side_effect=[refused, refused, None, None]) as run, \
                patch('ci.check_metrics.time.sleep') as sleep:
            preload_images(['docker', 'compose'])
            self.assertEqual(3 * [['docker', 'pull', 'quay.io/prometheus/prometheus:v3.15.0']]
                             + [['docker', 'tag', 'quay.io/prometheus/prometheus:v3.15.0', 'prom/prometheus:v3.15.0']],
                             [c.args[0] for c in run.call_args_list])
            self.assertEqual([call(10), call(10)], sleep.call_args_list)

    def test_a_pull_refused_three_times_fails_the_check_without_tagging(self):
        refused = subprocess.CalledProcessError(1, ['docker', 'pull'])
        with patch('ci.check_metrics.subprocess.check_output', return_value='prom/prometheus:v3.15.0\n'), \
                patch('ci.check_metrics.subprocess.run', side_effect=refused) as run, \
                patch('ci.check_metrics.time.sleep') as sleep:
            with self.assertRaises(subprocess.CalledProcessError):
                preload_images(['docker', 'compose'])
            self.assertEqual(3 * [['docker', 'pull', 'quay.io/prometheus/prometheus:v3.15.0']],
                             [c.args[0] for c in run.call_args_list])
            self.assertEqual(2, sleep.call_count)

    def test_an_unrecognized_repository_is_not_silently_substituted(self):
        with patch('ci.check_metrics.subprocess.check_output', return_value='unrecognized/server:1\n'), \
                patch('ci.check_metrics.subprocess.run') as run:
            with self.assertRaisesRegex(ValueError, 'Unrecognized.*unrecognized/server:1'):
                preload_images(['docker', 'compose'])
            run.assert_not_called()


class RecordedDashboardTest(unittest.TestCase):
    def dashboard(self):
        with open('doc/pit-server/metrics/pit-recording-dashboard.json', encoding='utf-8') as file:
            return json.load(file)

    def test_the_pinned_release_is_read_as_compose_reads_it(self):
        config = {'services': {'grafana': {'environment': {'GF_PLUGINS_PREINSTALL_SYNC': 'yesoreyeram-infinity-datasource@4.1.1'}}}}
        with patch('ci.check_metrics.subprocess.check_output', return_value=json.dumps(config)) as run:
            self.assertEqual('4.1.1', pinned_infinity(['docker', 'compose', '-f', 'synthetic.yaml']))
            run.assert_called_once_with(['docker', 'compose', '-f', 'synthetic.yaml', 'config', '--format', 'json'], text=True)

    def test_an_unpinned_plugin_is_refused(self):
        config = {'services': {'grafana': {'environment': {'GF_PLUGINS_PREINSTALL_SYNC': 'yesoreyeram-infinity-datasource'}}}}
        with patch('ci.check_metrics.subprocess.check_output', return_value=json.dumps(config)):
            with self.assertRaisesRegex(ValueError, 'pinned Infinity release'):
                pinned_infinity(['docker', 'compose'])

    def test_the_panels_own_query_goes_with_its_box_and_range_filled(self):
        dashboard = self.dashboard()
        answer = {'results': {'A': {'error': 'unsuccessful HTTP response\nstatus code : 404 Not Found'}}}
        with patch('ci.check_metrics.post', return_value=answer) as send:
            self.assertIn('404', recording_refusal(dashboard))
            body = send.call_args.args[1]
            params = {p['key']: p['value'] for p in body['queries'][0]['url_options']['params']}
            self.assertEqual('current', params['capture'])
            self.assertEqual('NT:/synthetic-unpublished', params['names'])
            self.assertEqual('2026-01-01T00:00:00Z', params['start_utc'])
            self.assertEqual('2026-01-01T00:05:00Z', params['end_utc'])
        # The dashboard keeps its own boxes and range.
        first = next(p for p in dashboard['panels'] if p['type'] == 'timeseries')
        self.assertIn('$battery_entry', [p['value'] for p in first['targets'][0]['url_options']['params']])

    def test_a_panel_that_answers_is_not_taken_for_the_refusal(self):
        with patch('ci.check_metrics.post', return_value={'results': {'A': {'frames': []}}}):
            with self.assertRaisesRegex(AssertionError, 'not refused'):
                recording_refusal(self.dashboard())


class HistoryDashboardTest(unittest.TestCase):
    def dashboard(self):
        with open('doc/pit-server/metrics/pit-history-dashboard.json', encoding='utf-8') as file:
            return json.load(file)

    def test_the_panels_query_carries_its_robot_source_and_both_boxes(self):
        answer = {'results': {'A': {'error': 'unsuccessful HTTP response\nstatus code : 404 Not Found'}}}
        with patch('ci.check_metrics.post', return_value=answer) as send:
            self.assertIn('404', history_refusal(self.dashboard()))
            params = {p['key']: p['value'] for p in send.call_args.args[1]['queries'][0]['url_options']['params']}
            self.assertEqual('SYNTHETIC', params['robot'])
            self.assertEqual('robot_log', params['source'])
            self.assertEqual('/synthetic-unpublished', params['names'])
            self.assertEqual('/synthetic-unpublished', params['capture_names'])
            self.assertEqual('2026-01-01T00:00:00Z', params['start_utc'])

    def test_a_history_panel_that_answers_is_not_taken_for_the_refusal(self):
        with patch('ci.check_metrics.post', return_value={'results': {'A': {'frames': []}}}):
            with self.assertRaisesRegex(AssertionError, 'not refused'):
                history_refusal(self.dashboard())


class FixtureStoreTest(unittest.TestCase):
    def test_the_fixture_logs_follow_the_format_specification(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'x.wpilog'
            check_metrics.wpilog(path, [('/x', 'double', [(2_000_000, 1.5)])])
            data = path.read_bytes()
        # The header: magic, version 1.0, an empty extra header.
        self.assertEqual(b'WPILOG', data[:6])
        self.assertEqual((0x0100, 0), struct.unpack('<HI', data[6:12]))
        # A start control record for entry 1, then its one sample.
        header, entry, length, timestamp = struct.unpack('<BIIQ', data[12:29])
        self.assertEqual((0x7F, 0, 0), (header, entry, timestamp))
        start = data[29:29 + length]
        self.assertEqual((0, 1), (start[0], struct.unpack('<I', start[1:5])[0]))
        self.assertEqual(b'/x', start[9:11])
        at = 29 + length
        header, entry, size, timestamp = struct.unpack('<BIIQ', data[at:at + 17])
        self.assertEqual((1, 8, 2_000_000), (entry, size, timestamp))
        self.assertEqual(1.5, struct.unpack('<d', data[at + 17:at + 25])[0])
        self.assertEqual(at + 25, len(data))

    def test_the_expected_rows_follow_the_stretch_rule(self):
        rows = check_metrics.expected_rows()
        self.assertEqual(11 + 20 + 50, len(rows))
        w = check_metrics.W
        # The recording answers until the roboRIO log's first sample, the log its span, the recording after.
        self.assertEqual((w + 100_000_000, 13 + 11 / 1000), rows[10])
        self.assertEqual((w + 101_000_000, 12 + 1 / 100), rows[11])
        self.assertEqual((w + 120_000_000, 12 + 20 / 100), rows[30])
        self.assertEqual((w + 121_000_000, 13 + 32 / 1000), rows[31])

    def test_the_expected_buckets_follow_the_bucket_rule(self):
        w = check_metrics.W
        # A window of 125 s in four buckets starting at 0, 31.25, 62.5 and 93.75 s; the last is empty.
        rows = [(w + 90_000_000, 1.0), (w + 121_250_000, 2.0), (w + 121_250_001, 4.0), (w + 215_000_000, 8.0)]
        self.assertEqual([(w + 90_000_000, 1, 1.0, 1.0, 1.0), (w + 121_250_000, 2, 2.0, 4.0, 3.0),
                          (w + 183_750_000, 1, 8.0, 8.0, 8.0)], check_metrics.expected_buckets(rows, 4))

    def dashboard(self):
        with open('doc/pit-server/metrics/pit-history-dashboard.json', encoding='utf-8') as file:
            return json.load(file)

    def answer(self, columns):
        """A frame as Infinity gives one: each field named by its column's display text."""
        texts = {}
        for target in [p['targets'][0] for p in self.dashboard()['panels'] if 'targets' in p] + [
                v['query']['infinityQuery'] for v in self.dashboard()['templating']['list'] if v['type'] == 'query']:
            for column in target.get('columns', []):
                texts[column['selector']] = column.get('text') or column['selector']
        names = [texts.get(c, c) for c in columns]
        return {'results': {'A': {'frames': [{'schema': {'fields': [{'name': n} for n in names]},
                                              'data': {'values': list(columns.values())}}]}}}

    def test_a_history_panel_must_answer_the_rows_worked_out_from_the_fixture(self):
        rows = check_metrics.expected_rows()
        right = {'timestamp_utc': [utc // 1000 for utc, _ in rows], 'min': [v for _, v in rows],
                 'max': [v for _, v in rows], 'mean': [v for _, v in rows]}
        with patch('ci.check_metrics.post', return_value=self.answer(right)) as send:
            self.assertEqual(len(rows), check_metrics.history_rows(self.dashboard()))
            params = {p['key']: p['value'] for p in send.call_args.args[1]['queries'][0]['url_options']['params']}
            self.assertEqual(('RIO', 'robot_log', check_metrics.RIO, check_metrics.PIT),
                             (params['robot'], params['source'], params['names'], params['capture_names']))
        wrong = dict(right, max=right['max'][:-1] + [right['max'][-1] + 1])
        with patch('ci.check_metrics.post', return_value=self.answer(wrong)):
            with self.assertRaisesRegex(AssertionError, 'expected'):
                check_metrics.history_rows(self.dashboard())
        with patch('ci.check_metrics.post', return_value={'results': {'A': {'frames': [{'schema': {'fields': []},
                                                                                       'data': {'values': []}}]}}}):
            with self.assertRaisesRegex(AssertionError, '0 rows answered'):
                check_metrics.history_rows(self.dashboard())
        with patch('ci.check_metrics.post', return_value={'results': {'A': {'error': 'status code : 404'}}}):
            with self.assertRaisesRegex(AssertionError, 'refused'):
                check_metrics.history_rows(self.dashboard())

    def grafana(self, *answers):
        """Answers as Grafana gives them: under the id each query carries."""
        remaining = list(answers)
        def send(url, body):
            ref = body['queries'][0].get('refId')
            return {'results': {ref: self.answer(remaining.pop(0))['results']['A']}}
        return send

    def test_the_choices_must_be_the_fixture_robot_and_sessions(self):
        with patch('ci.check_metrics.post', side_effect=self.grafana({'robot_id': ['RIO']}, {'session_id': ['c', 'a']})):
            self.assertEqual(2, check_metrics.history_choices(self.dashboard()))
        with patch('ci.check_metrics.post', side_effect=[self.answer({'robot_id': ['OTHER']})]):
            with self.assertRaisesRegex(AssertionError, 'fixture robot'):
                check_metrics.history_choices(self.dashboard())
