# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
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
