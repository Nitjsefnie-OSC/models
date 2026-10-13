#!/usr/bin/env python3
"""Reject tracked paths that cannot be checked out by the Windows CI workers.

The native Windows job failed before compilation when archived benchmark XML paths grew to
261 characters. Keep 29 UTF-16 code units for the checkout directory and terminator within
Windows' legacy 260-character limit. Historical raw payloads belong in their external archive.
"""

import pathlib
import subprocess
import sys

MAX_RELATIVE_UNITS = 230


def oversized_paths(paths):
    return [path for path in paths if len(path.encode("utf-16-le")) // 2 > MAX_RELATIVE_UNITS]


def main():
    root = pathlib.Path(__file__).resolve().parents[2]
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    failures = oversized_paths(path for path in paths if path)
    for path in failures:
        print(f"Windows checkout path exceeds {MAX_RELATIVE_UNITS} UTF-16 units: {path}")
    if failures:
        return 1
    print("Tracked paths fit the Windows CI checkout budget")
    return 0


if __name__ == "__main__":
    sys.exit(main())
