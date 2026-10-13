#!/usr/bin/env python3
# Copyright (c) 2026 Christopher Larrieu and Triple Helix Robotics
# SPDX-License-Identifier: MIT
"""Read a bounded native Arrow stream; require the final status before using its result.

Needs pyarrow. No MCP session, installer, robot connection or client framework is involved.
"""
import argparse
import json
import tempfile
import urllib.request
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", help="HTTP server origin, for example http://127.0.0.1:2363")
    parser.add_argument("path", help="path on the server, not on this computer")
    parser.add_argument("entry")
    args = parser.parse_args()
    import pyarrow.ipc

    origin = urlsplit(args.server)
    if (origin.scheme not in ("http", "https") or not origin.hostname or origin.username
            or origin.password or origin.path not in ("", "/") or origin.query or origin.fragment):
        parser.error("server must be an http(s) origin without credentials, path, query or fragment")
    base = args.server.rstrip("/") + "/read/v1"

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, message, headers, new_url):
            return None

    opener = urllib.request.build_opener(NoRedirect())

    def request(path, method="POST", body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        return opener.open(urllib.request.Request(base + path, data=data, method=method,
                            headers={"Content-Type": "application/json"}), timeout=30)

    with request("/snapshots", body={"path": args.path}) as response:
        descriptor = json.load(response)
    lease = "/snapshots/" + descriptor["id"]
    problems = None
    try:
        count, problem_count, terminal = 0, 0, None
        problems = tempfile.SpooledTemporaryFile(max_size=1 << 20, mode="w+t", encoding="utf-8")
        with request(lease + "/query", body={"entry": args.entry, "format": "arrow"}) as response:
            reader = pyarrow.ipc.open_stream(response)
            while True:
                try:
                    item = reader.read_next_batch_with_custom_metadata()
                except StopIteration:
                    break
                metadata = item.custom_metadata
                status = metadata.get(b"status") if metadata else None
                if terminal is not None:
                    raise RuntimeError("data followed the terminal batch")
                if status is not None:
                    terminal = status
                    if status != b"ok":
                        raise RuntimeError(f"read failed: {metadata}")
                else:
                    recorded = json.loads(metadata.get(b"problems", b"[]")) if metadata else []
                    if not isinstance(recorded, list):
                        raise RuntimeError("invalid decode-problem metadata")
                    problem_count += len(recorded)
                    for problem in recorded:
                        problems.write(json.dumps(problem, ensure_ascii=False) + "\n")
                    # Work with item.batch here, but commit results only after the terminal proof.
                    count += item.batch.num_rows
            if terminal != b"ok":
                raise RuntimeError("incomplete stream: discard the partial result")
        print(f"samples={count} decode_problems={problem_count}")
        problems.seek(0)
        for line in problems:
            print(line, end="")
    finally:
        if problems is not None:
            problems.close()
        with request(lease, method="DELETE"):
            pass


if __name__ == "__main__":
    main()
