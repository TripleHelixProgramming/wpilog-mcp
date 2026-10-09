# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from ci.check_metrics import grafana_sample


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
