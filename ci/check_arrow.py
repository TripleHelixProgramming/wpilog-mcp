#!/usr/bin/env python3
"""Reads the data endpoint's Arrow streams with pyarrow, the reference implementation, and
compares them to the CSV of the same request.

The server writes Arrow at the format level with no Arrow library (doc/ARCHITECTURE.md), and
its own tests read the streams back with a reader written from the specification. That reader
and the writer could agree on a shared misreading; pyarrow cannot. DataEndpointTest leaves every
stream it produced, and the CSV of the same request, under build/arrow-samples/, and CI runs
this script on them after the Java tests, so Python is not needed for `./gradlew test`.

Usage: python ci/check_arrow.py build/arrow-samples
"""
import csv
import json
import math
import sys
from pathlib import Path

import pyarrow as pa
import pyarrow.ipc


def read_stream(path: Path):
    """The stream's schema metadata and its batches, each with its message metadata."""
    with pa.ipc.open_stream(path) as reader:
        metadata = {k.decode(): v.decode() for k, v in (reader.schema.metadata or {}).items()}
        batches = []
        while True:
            try:
                batch, custom = reader.read_next_batch_with_custom_metadata()
            except StopIteration:
                break
            tag = {k.decode(): v.decode() for k, v in (custom or {}).items()}
            batches.append((tag, batch))
        return reader.schema, metadata, batches


def read_csv(path: Path):
    """The CSV's sections: entry name to (columns, rows), in order. The reader is the csv
    module's, so a quoted cell that holds a newline (a console message) stays one cell."""
    sections = []
    columns = None
    rows = None
    entry = None
    with open(path, newline="", encoding="utf-8") as f:
        for record in csv.reader(f):
            if not record:
                continue
            first = record[0]
            if first.startswith("# entry: "):
                if entry is not None:
                    sections.append((entry, columns, rows))
                entry, columns, rows = first[len("# entry: "):], None, []
            elif first.startswith("#"):
                continue
            elif columns is None:
                columns = record
            else:
                rows.append(record)
    if entry is not None:
        sections.append((entry, columns, rows))
    return sections


def flatten(value, prefix=""):
    """A struct value as the CSV flattens it: dot paths, arrays as field[i]."""
    if isinstance(value, dict):
        out = {}
        for k, v in value.items():
            out.update(flatten(v, f"{prefix}.{k}" if prefix else k))
        return out
    if isinstance(value, list):
        out = {}
        for i, v in enumerate(value):
            out.update(flatten(v, f"{prefix}[{i}]"))
        return out
    return {prefix or "value": value}


def same_number(a, b):
    if a is None or a == "":
        return b is None or b == ""
    try:
        x, y = float(a), float(b)
    except (TypeError, ValueError):
        return str(a) == str(b)
    if math.isnan(x) or math.isnan(y):
        return math.isnan(x) and math.isnan(y)
    return math.isclose(x, y, rel_tol=1e-9, abs_tol=1e-9)


def check(arrow_path: Path) -> list[str]:
    problems = []
    schema, metadata, batches = read_stream(arrow_path)
    csv_path = arrow_path.with_suffix(".csv")
    if not csv_path.exists():
        # A stream with no CSV (the writer's own round-trip sample): readable, every batch opened
        for _tag, batch in batches:
            batch.validate(full=True)
        return problems
    sections = read_csv(csv_path)
    for key in ("server_version", "inputs", "entries", "time_range_sec", "bucketed"):
        if key not in metadata:
            problems.append(f"schema metadata lacks {key}")
    entries = json.loads(metadata.get("entries", "[]"))
    bucketed = metadata.get("bucketed") == "true"
    if schema.field(0).name != "timestamp" or schema.field(0).type != pa.timestamp("us"):
        problems.append(f"first field is {schema.field(0)}, not timestamp[us]")

    # Rows per entry, as pyarrow reads them
    by_entry = {}
    for tag, batch in batches:
        if "file_changed" in tag:
            problems.append("the file changed during the stream")
            continue
        entry = tag.get("entry")
        if entry is None:
            problems.append("a batch without an entry tag")
            continue
        by_entry.setdefault(entry, []).extend(batch.to_pylist())
    expected_entries = [e["name"] for e in entries]
    if list(by_entry.keys()) != expected_entries:
        problems.append(f"batches for {list(by_entry.keys())}, metadata names {expected_entries}")
    if [s[0] for s in sections] != expected_entries:
        problems.append(f"CSV sections {[s[0] for s in sections]}, metadata names {expected_entries}")

    for entry, columns, rows in sections:
        arrow_rows = by_entry.get(entry, [])
        if bucketed:
            if columns != ["timestamp_sec", "count", "min", "max", "mean", "first", "last"]:
                problems.append(f"{entry}: bucketed CSV columns {columns}")
                continue
            if len(rows) != len(arrow_rows):
                problems.append(f"{entry}: {len(arrow_rows)} buckets in Arrow, {len(rows)} in CSV")
                continue
            for i, (row, arrow) in enumerate(zip(rows, arrow_rows)):
                ts = arrow["timestamp"].timestamp() if hasattr(arrow["timestamp"], "timestamp") else arrow["timestamp"]
                if not same_number(row[0], ts):
                    problems.append(f"{entry} bucket {i}: timestamp {ts} vs {row[0]}")
                for col, val in zip(columns[1:], row[1:]):
                    if not same_number(val, arrow[col]):
                        problems.append(f"{entry} bucket {i}: {col} {arrow[col]} vs {val}")
            continue
        # Exact: one CSV row per sample, or per element of an array sample
        flat_rows = []
        for arrow in arrow_rows:
            ts = arrow["timestamp"].timestamp() if hasattr(arrow["timestamp"], "timestamp") else arrow["timestamp"]
            value = arrow["value"]
            if "index" in columns and isinstance(value, list):
                for idx, element in enumerate(value):
                    flat_rows.append((ts, idx, flatten(element)))
            else:
                flat_rows.append((ts, None, flatten(value)))
        if len(flat_rows) != len(rows):
            problems.append(f"{entry}: {len(flat_rows)} rows in Arrow, {len(rows)} in CSV")
            continue
        for i, ((ts, idx, fields), row) in enumerate(zip(flat_rows, rows)):
            cells = dict(zip(columns, row))
            if not same_number(cells["timestamp_sec"], ts):
                problems.append(f"{entry} row {i}: timestamp {ts} vs {cells['timestamp_sec']}")
            if idx is not None and not same_number(cells.get("index"), idx):
                problems.append(f"{entry} row {i}: index {idx} vs {cells.get('index')}")
            for col in columns:
                if col in ("timestamp_sec", "index"):
                    continue
                got = fields.get(col)
                # CSV keeps an enum's number at the field name and its label at .label;
                # Arrow represents both as children, including a null label for unnamed values.
                if col not in fields and f"{col}.value" in fields and f"{col}.label" in fields:
                    got = fields[f"{col}.value"]
                if isinstance(got, bool):
                    got = str(got).lower()
                if not same_number(cells[col], got) and str(cells[col]) != str(got):
                    problems.append(f"{entry} row {i}: {col} {got!r} vs {cells[col]!r}")
                    break
    return problems


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    samples = sorted(Path(argv[1]).glob("*.arrow"))
    if not samples:
        print(f"no .arrow samples under {argv[1]}; run DataEndpointTest first")
        return 1
    failed = 0
    for sample in samples:
        try:
            problems = check(sample)
        except Exception as e:  # a stream pyarrow cannot open is the worst case
            problems = [f"pyarrow could not read it: {type(e).__name__}: {e}"]
        status = "ok" if not problems else "FAIL"
        print(f"{status:4} {sample.name}")
        for p in problems[:20]:
            print(f"       {p}")
        failed += bool(problems)
    print(f"{len(samples) - failed} of {len(samples)} streams agree with pyarrow and the CSV")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
