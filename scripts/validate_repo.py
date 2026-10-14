#!/usr/bin/env python3
"""Quick standards-library-only validation of the docs and mod candidate list."""
import csv
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md", "AGENTS.md",
    "docs/README.md", "docs/design/vision.md", "docs/design/professions.md",
    "docs/design/general-skills.md", "docs/technical/mod-audit.md",
    "docs/technical/mod-candidates.csv", "docs/production/mvp-v0.1.md",
    "docs/legal/third-party-policy.md", "docs/legal/asset-register.csv",
]
issues = []
for name in REQUIRED:
    if not (ROOT / name).is_file():
        issues.append("Required file missing: " + name)

link_pattern = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
for document in ROOT.rglob("*.md"):
    for raw in link_pattern.findall(document.read_text(encoding="utf-8")):
        target = raw.split()[0].strip("<>")
        if target.startswith(("#", "mailto:")):
            continue
        parsed = urlsplit(target)
        if parsed.scheme or target.startswith("//") or not parsed.path:
            continue
        dest = (document.parent / unquote(parsed.path)).resolve()
        if not dest.is_relative_to(ROOT.resolve()) or not dest.exists():
            issues.append("Broken link: " + str(document.relative_to(ROOT)) + " -> " + target)

csv_path = ROOT / "docs/technical/mod-candidates.csv"
if csv_path.exists():
    with csv_path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.DictReader(stream))
    ids = set()
    for row in rows:
        candidate_id = (row.get("id") or "").strip()
        if not candidate_id or candidate_id in ids:
            issues.append("Blank or duplicate mod candidate ID: " + candidate_id)
        ids.add(candidate_id)
        if not (row.get("project_url") or "").startswith("https://"):
            issues.append("Invalid source URL for " + candidate_id)
        if (row.get("status") or "") not in ("candidate", "testing", "approved_for_local_use", "retired"):
            issues.append("Invalid status for " + candidate_id)

if issues:
    print("Repository validation failed:")
    for item in issues:
        print(" -", item)
    sys.exit(1)
print("Repository validation passed: required files, local links, and candidate IDs.")
