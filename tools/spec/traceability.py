#!/usr/bin/env python3
"""
Builds docs/blueprint/traceability.csv (blueprint 30 §3): one row per blueprint sub-feature, never per module.

Rows come from three places, as the consensus (§10.4) asks: every v1 §31 sub-feature and new v2 item in 90-COVERAGE.md,
and every pairwise contract between modules in 20-INTEGRATION.md §1. A cell that lists several features ("a; b; c" or
"a, b, c", commas inside parentheses kept) becomes one row each.

The generated columns are id, source, sub_feature and blueprint. The others (spec, requirements, tables, flow, hooks,
tests, evidence, status) are written by hand as specs land; running this again keeps them, matched by id, and fails if a
row that has them would disappear.

Usage: tools/spec/traceability.py   (from the repository root)
"""

import csv
import re
import sys
from pathlib import Path

COVERAGE = Path("docs/blueprint/90-COVERAGE.md")
INTEGRATION = Path("docs/blueprint/20-INTEGRATION.md")
OUT = Path("docs/blueprint/traceability.csv")

COLUMNS = ["id", "source", "sub_feature", "blueprint", "spec", "requirements", "tables", "flow", "hooks", "tests", "evidence", "status"]
FILLED = COLUMNS[4:]


def split_top_level(text, separator):
    """Splits on separator outside parentheses."""
    parts, depth, current = [], 0, ""
    i = 0
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if depth == 0 and text.startswith(separator, i):
            parts.append(current)
            current = ""
            i += len(separator)
            continue
        current += c
        i += 1
    parts.append(current)
    return [p.strip() for p in parts if p.strip()]


def features(cell):
    """'Module: a; b' -> ['Module: a', 'Module: b']; 'a; b' or 'a, b' -> ['a', 'b']."""
    prefix = ""
    match = re.match(r"^([A-Z][A-Za-z' ]+): (.*)$", cell)
    if match:
        prefix, cell = match.group(1) + ": ", match.group(2)
    separator = "; " if ";" in cell else ", "
    return [prefix + part for part in split_top_level(cell, separator)]


def slug(text):
    text = re.sub(r"`|\([^)]*\)", "", text.lower())
    text = re.sub(r"[^a-z0-9]+", "-", text).strip("-")
    return text[:48].rstrip("-")


def rows():
    out = []
    section = None
    paragraph = []
    for line in COVERAGE.read_text().splitlines():
        heading = re.match(r"^## (\d)\. ", line)
        if heading:
            section = heading.group(1)
            continue
        if section in ("1", "2", "3") and line.startswith("|") and not line.startswith("|---") and not line.startswith("| v1 item"):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            for feature in features(cells[0]):
                out.append(("90 §" + section, feature, cells[1]))
        if section == "4":
            # One paragraph, wrapped over several lines; it ends at the first blank line.
            if line.strip():
                paragraph.append(line.strip())
            elif paragraph:
                section = "4-done"
    for item in split_top_level(" ".join(paragraph).rstrip("."), "; "):
        match = re.match(r"^(.*?) \(([^)]*)\)$", item)
        feature, ref = (match.group(1), match.group(2)) if match else (item, "")
        for part in split_top_level(feature, ", "):
            out.append(("90 §4", part, ref))
    in_pairs = False
    for line in INTEGRATION.read_text().splitlines():
        if line.startswith("## "):
            in_pairs = line.startswith("## 1.")
            continue
        if in_pairs and line.startswith("|") and not line.startswith(("|---", "| Pair")):
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            out.append(("20 §1", cells[0] + ": " + cells[1], "20 §1"))
    return out


def spec_statuses():
    """Each spec's status word from its "- Status:" line (draft or reviewed)."""
    statuses = {}
    for spec in sorted(Path("docs/spec").glob("*.md")):
        if spec.name == "README.md":
            continue
        match = re.search(r"(?m)^- Status: (\w+)", spec.read_text(encoding="utf-8"))
        if not match:
            sys.exit("traceability: " + spec.name + " has no status line")
        statuses[spec.name] = match.group(1)
    return statuses


def main():
    statuses = spec_statuses()
    existing = {}
    if OUT.exists():
        with OUT.open(newline="") as f:
            for row in csv.DictReader(f):
                existing[row["id"]] = row
    generated, seen = [], set()
    for source, feature, ref in rows():
        base = "TR-" + slug(feature)
        row_id, n = base, 2
        while row_id in seen:
            row_id = base + "-" + str(n)
            n += 1
        seen.add(row_id)
        row = {"id": row_id, "source": source, "sub_feature": feature, "blueprint": ref}
        old = existing.get(row_id, {})
        for column in FILLED:
            row[column] = old.get(column, "")
        if not row["status"]:
            row["status"] = "blueprint"
        if row["status"] in ("specified", "reviewed"):
            # A row is as reviewed as the least reviewed spec it cites (docs/spec/README.md).
            specs = [s for s in row["spec"].split(";") if s]
            row["status"] = "reviewed" if specs and all(statuses.get(s) == "reviewed" for s in specs) else "specified"
        generated.append(row)
    lost = [i for i, r in existing.items() if i not in seen and any(r.get(c) for c in FILLED if c != "status" or r.get(c) != "blueprint")]
    if lost:
        sys.exit("traceability: rows with filled columns would disappear: " + ", ".join(lost))
    with OUT.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=COLUMNS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(generated)
    print(f"traceability: {len(generated)} rows")


if __name__ == "__main__":
    main()
