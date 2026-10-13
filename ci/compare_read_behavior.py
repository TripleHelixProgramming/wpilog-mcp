#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Compare frozen read captures without rounding numbers or removing result fields.

Only manifest.run describes the execution rather than its behavior. Capture writers
must explicitly control nondeterminism; this gate never regenerates a baseline.
"""
import argparse
from dataclasses import dataclass
import json
from pathlib import Path, PurePosixPath, PureWindowsPath
import sys


@dataclass(frozen=True)
class JsonNumber:
    """Retain the token: float conversion would hide differences before comparison."""
    lexeme: str


class CaptureError(ValueError):
    pass


def _object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise CaptureError('duplicate JSON key: ' + name)
        result[name] = value
    return result


def _constant(token):
    raise CaptureError('invalid JSON numeric constant: ' + token)


def _parse(text):
    return json.loads(text, parse_int=JsonNumber, parse_float=JsonNumber,
                      parse_constant=_constant, object_pairs_hook=_object)


def _embedded(value):
    """MCP's JSON text blocks retain JSON semantics inside HTTP/RPC envelopes."""
    envelope = value
    if isinstance(envelope, dict) and 'status' in envelope and 'headers' in envelope:
        envelope = envelope.get('body')
    if isinstance(envelope, dict) and envelope.get('jsonrpc') == '2.0':
        envelope = envelope.get('result')
    if isinstance(envelope, dict) and isinstance(envelope.get('content'), list):
        for block in envelope['content']:
            if isinstance(block, dict) and block.get('type') == 'text':
                text = block.get('text')
                if not isinstance(text, str):
                    raise CaptureError('MCP text content: text must be a string')
                if text.lstrip().startswith(('{', '[')):
                    block['text'] = _parse(text)
    return value


def _json(path):
    try:
        return _parse(path.read_text(encoding='utf-8'))
    except (OSError, UnicodeError, ValueError, RecursionError) as error:
        raise CaptureError(str(path) + ': ' + str(error)) from error


def _safe_file(root, name):
    if (not isinstance(name, str) or not name or '\\' in name
            or PurePosixPath(name).is_absolute() or PureWindowsPath(name).drive
            or '..' in PurePosixPath(name).parts):
        raise CaptureError('unsafe capture-relative path: ' + repr(name))
    path = root / name
    try:
        path.resolve(strict=True).relative_to(root)
        if not path.is_file():
            raise CaptureError('not a capture file: ' + str(path))
    except (OSError, ValueError, RuntimeError) as error:
        raise CaptureError('missing or unsafe capture file: ' + str(path)) from error
    return path


def _required_string(value, field, location):
    if not isinstance(value.get(field), str) or not value[field]:
        raise CaptureError(location + ': ' + field + ' must be a nonempty string')


def _load(directory):
    root = Path(directory).resolve(strict=True)
    manifest = _json(_safe_file(root, 'manifest.json'))
    if not isinstance(manifest, dict) or manifest.get('format_version') != JsonNumber('1'):
        raise CaptureError(str(root) + ': format_version must be integer 1')
    if not isinstance(manifest.get('cases'), list):
        raise CaptureError(str(root) + ': cases must be an array')
    if not manifest['cases']:
        raise CaptureError(str(root) + ': an empty capture cannot prove behavioral parity')
    cases = {}
    for case in manifest['cases']:
        if not isinstance(case, dict):
            raise CaptureError(str(root) + ': case must be an object')
        _required_string(case, 'id', str(root))
        identifier = case['id']
        location = str(root) + ': case ' + identifier
        if identifier in cases:
            raise CaptureError(location + ': duplicate case id')
        if 'request' not in case or 'result' not in case:
            raise CaptureError(location + ': request and result are required')
        result_path = _safe_file(root, case['result'])
        if not isinstance(case.get('artifacts'), list):
            raise CaptureError(location + ': artifacts must be an array')
        artifacts = {}
        for artifact in case['artifacts']:
            if not isinstance(artifact, dict):
                raise CaptureError(location + ': artifact must be an object')
            for field in ('name', 'path', 'kind'):
                _required_string(artifact, field, location)
            if artifact['name'] in artifacts:
                raise CaptureError(location + ': duplicate artifact name: ' + artifact['name'])
            artifacts[artifact['name']] = (artifact, _safe_file(root, artifact['path']))
        cases[identifier] = (case, result_path, artifacts)
    return manifest, cases


def _part(key):
    return '[' + json.dumps(key, ensure_ascii=True) + ']'


def _describe(value):
    return json.dumps(value, ensure_ascii=True, default=lambda number: {
        'json_number': number.lexeme})[:240]


class Comparison:
    def __init__(self, maximum):
        self.maximum = maximum
        self.report = dict(equal=True, cases_compared=0, artifacts_compared=0,
                           difference_count=0, differences=[], errors=[])

    def difference(self, path, reason, baseline=None, candidate=None):
        self.report['equal'] = False
        self.report['difference_count'] += 1
        if len(self.report['differences']) < self.maximum:
            self.report['differences'].append(dict(path=path, reason=reason,
                                                  baseline=baseline, candidate=candidate))

    def json(self, baseline, candidate, path):
        if type(baseline) is not type(candidate):
            self.difference(path, 'JSON type differs', _describe(baseline), _describe(candidate))
        elif isinstance(baseline, dict):
            for key in sorted(baseline.keys() | candidate.keys()):
                child_path = path + _part(key)
                if key not in baseline:
                    self.difference(child_path, 'unexpected field', candidate=_describe(candidate[key]))
                elif key not in candidate:
                    self.difference(child_path, 'missing field', baseline=_describe(baseline[key]))
                else:
                    self.json(baseline[key], candidate[key], child_path)
        elif isinstance(baseline, list):
            if len(baseline) != len(candidate):
                self.difference(path, 'array length differs', len(baseline), len(candidate))
            for index, (left, right) in enumerate(zip(baseline, candidate)):
                self.json(left, right, path + '[' + str(index) + ']')
        elif baseline != candidate:
            self.difference(path, 'value differs', _describe(baseline), _describe(candidate))

    def results(self, baseline, candidate, path):
        # Retain only one case pair; a season's capture can contain thousands of large answers.
        results = []
        for side, file in [('baseline', baseline), ('candidate', candidate)]:
            try:
                results.append(_embedded(_json(file)))
            except (OSError, ValueError, RecursionError) as error:
                self.report['equal'] = False
                self.report['errors'].append(dict(side=side, path=path, message=str(file) + ': ' + str(error)))
        if len(results) == 2:
            self.json(results[0], results[1], path)

    def binary(self, baseline, candidate, path):
        self.report['artifacts_compared'] += 1
        offset = 0
        with baseline.open('rb') as left, candidate.open('rb') as right:
            while True:
                left_bytes, right_bytes = left.read(65536), right.read(65536)
                if left_bytes != right_bytes:
                    first = next((i for i, pair in enumerate(zip(left_bytes, right_bytes))
                                  if pair[0] != pair[1]), min(len(left_bytes), len(right_bytes)))
                    self.difference(path, 'artifact bytes differ at offset ' + str(offset + first))
                    return
                if not left_bytes:
                    return
                offset += len(left_bytes)


def compare_captures(baseline, candidate, max_diffs=20):
    """Return an exact comparison report; malformed captures can never pass the gate."""
    if isinstance(max_diffs, bool) or not isinstance(max_diffs, int) or max_diffs < 1:
        raise ValueError('max_diffs must be a positive integer')
    comparison = Comparison(max_diffs)
    loaded = []
    for label, directory in [('baseline', baseline), ('candidate', candidate)]:
        try:
            loaded.append(_load(directory))
        except (OSError, ValueError, RecursionError) as error:
            comparison.report['errors'].append(dict(side=label, message=str(error)))
    if comparison.report['errors']:
        comparison.report['equal'] = False
        return comparison.report
    (left_manifest, left_cases), (right_manifest, right_cases) = loaded
    comparison.json({k: v for k, v in left_manifest.items() if k not in ('run', 'cases')},
                    {k: v for k, v in right_manifest.items() if k not in ('run', 'cases')}, '$manifest')
    try:
        for identifier in sorted(left_cases.keys() | right_cases.keys()):
            path = 'cases' + _part(identifier)
            if identifier not in left_cases:
                comparison.difference(path, 'unexpected case')
                continue
            if identifier not in right_cases:
                comparison.difference(path, 'missing case')
                continue
            left, left_result, left_artifacts = left_cases[identifier]
            right, right_result, right_artifacts = right_cases[identifier]
            comparison.report['cases_compared'] += 1
            comparison.json({k: v for k, v in left.items() if k != 'artifacts'},
                            {k: v for k, v in right.items() if k != 'artifacts'}, path)
            comparison.results(left_result, right_result, path + '.result')
            for name in sorted(left_artifacts.keys() | right_artifacts.keys()):
                artifact_path = path + '.artifacts' + _part(name)
                if name not in left_artifacts:
                    comparison.difference(artifact_path, 'unexpected artifact')
                elif name not in right_artifacts:
                    comparison.difference(artifact_path, 'missing artifact')
                else:
                    left_meta, left_file = left_artifacts[name]
                    right_meta, right_file = right_artifacts[name]
                    comparison.json(left_meta, right_meta, artifact_path)
                    comparison.binary(left_file, right_file, artifact_path)
    except (OSError, RecursionError) as error:
        comparison.report['equal'] = False
        comparison.report['errors'].append(dict(side='comparison', message=str(error)))
    return comparison.report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--max-diffs', type=int, default=20)
    parser.add_argument('--report', type=Path)
    arguments = parser.parse_args()
    if arguments.max_diffs < 1:
        parser.error('--max-diffs must be positive')
    report = compare_captures(arguments.baseline, arguments.candidate, arguments.max_diffs)
    output = json.dumps(report, indent=2, ensure_ascii=True) + '\n'
    sys.stdout.write(output)
    if arguments.report:
        # A report must never replace evidence, even through a symlinked parent.
        destination = arguments.report.resolve()
        for root in (arguments.baseline.resolve(), arguments.candidate.resolve()):
            if destination == root or root in destination.parents:
                parser.error('--report must be outside both capture directories')
        arguments.report.write_text(output, encoding='utf-8')
    return 0 if report['equal'] else 1


if __name__ == '__main__':
    sys.exit(main())
