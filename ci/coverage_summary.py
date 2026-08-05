#!/usr/bin/env python3
"""Print JaCoCo coverage percentages from a report XML and append to the job summary."""
import os
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/jacoco/merged.xml"
label = sys.argv[2] if len(sys.argv) > 2 else "App merged"

if not os.path.exists(path):
    print(f"{label}: no coverage XML at {path}")
    raise SystemExit(0)

# JaCoCo XML references an external DTD; don't try to resolve it.
parser = ET.XMLParser()
root = ET.parse(path, parser=parser).getroot()

lines = []
for counter in root.findall("counter"):
    ctype = counter.get("type")
    covered = int(counter.get("covered"))
    missed = int(counter.get("missed"))
    total = covered + missed
    if total == 0:
        continue
    pct = 100.0 * covered / total
    lines.append(f"{ctype:12} {pct:5.1f}%  ({covered}/{total})")

header = f"### {label} coverage"
print(header)
for line in lines:
    print("  " + line)

summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as fh:
        fh.write(header + "\n\n```\n" + "\n".join(lines) + "\n```\n")
