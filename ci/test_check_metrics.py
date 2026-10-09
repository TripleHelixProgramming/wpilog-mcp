# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from ci.check_metrics import grafana_sample, preload_images


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
