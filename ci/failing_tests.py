#!/usr/bin/env python3
"""Print the failing tests from JUnit XML results, compactly.

Gradle reports the failure at the point it happens, which on a long build means the one
line that matters scrolls far away from the end of the log. This runs on failure and puts
the answer last, where anyone reading a red build (or tailing it) will find it.

Usage: failing_tests.py <directory> [<directory> ...]
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def failures(path: Path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return
    for case in root.iter("testcase"):
        for problem in list(case.findall("failure")) + list(case.findall("error")):
            message = (problem.get("message") or "").strip().splitlines()
            yield case.get("classname"), case.get("name"), message[0] if message else problem.get("type", "")


def main(directories):
    found = []
    for directory in directories:
        for xml in Path(directory).rglob("TEST-*.xml"):
            found.extend(failures(xml))

    if not found:
        print("### No failing tests found in the XML results (the failure was elsewhere).")
        return

    print(f"### Failing tests ({len(found)})")
    for classname, name, message in found:
        print(f"  {classname}.{name}")
        if message:
            print(f"      {message}")


if __name__ == "__main__":
    main(sys.argv[1:] or ["app/build/test-results", "verify/build/test-results"])
