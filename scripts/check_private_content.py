#!/usr/bin/env python3
"""Keep the privately licensed city-building content out of this public repository.

The owner has permission to use another creator's city-building content in a PRIVATE modpack (docs/legal/private-content.md). That
permission does not cover publishing it, so nothing of it may be tracked, committed, uploaded as a CI artifact, or fetched by a script
that lives here. .gitignore alone is not enough (a forced add, a different clone, a renamed folder); this check looks at what is really
tracked, at what a commit or a pull request adds, at the history, and at what the workflows upload.

    python scripts/check_private_content.py                 working tree + .gitignore + workflows + history (CI, before a push)
    python scripts/check_private_content.py --staged        what is about to be committed (the pre-commit hook)
    python scripts/check_private_content.py --base origin/x what a branch adds on top of a base (a pull request)
    python scripts/check_private_content.py --no-history    skip the history walk

Rules are data: content/private-content-policy.json. The check prints the rule and the path, never a line of any file's content, and
exits 1 when it finds anything. Standard library only.
"""
from __future__ import annotations

import fnmatch
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
POLICY_PATH = "content/private-content-policy.json"


def load_policy(root: Path) -> dict:
    return json.loads((root / POLICY_PATH).read_text(encoding="utf-8"))


def git(root: Path, *args: str) -> list[str]:
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError("git " + " ".join(args) + " failed: " + result.stderr.strip())
    return [line for line in result.stdout.splitlines() if line]


def matches_any(path: str, patterns: list[str]) -> str | None:
    """The first policy pattern the path matches. A pattern ending in /* matches everything below that folder wherever the folder is in the path; *.x matches a suffix."""
    lowered = path.lower().replace("\\", "/")
    for pattern in patterns:
        p = pattern.lower()
        if fnmatch.fnmatch(lowered, p) or fnmatch.fnmatch("/" + lowered, "*/" + p):
            return pattern
    return None


def check_paths(paths: list[str], policy: dict, where: str) -> list[str]:
    issues = []
    for path in paths:
        hit = matches_any(path, policy["forbidden_path_patterns"])
        if hit:
            issues.append(f"{where}: a path of private content ({hit}): {path}")
    return issues


def check_text(root: Path, paths: list[str], policy: dict, where: str) -> list[str]:
    """Names of the private namespaces, or of the source repository, inside data and script files that are not on the allowlist."""
    issues = []
    suffixes = tuple(policy["text_scan_suffixes"])
    allow = set(policy["allowlist"])
    tokens = [t.lower() for t in policy["private_namespaces"] + policy["private_source_markers"]]
    for path in paths:
        if path in allow or not path.lower().endswith(suffixes):
            continue
        file = root / path
        if not file.is_file() or file.stat().st_size > 5_000_000:
            continue
        try:
            text = file.read_text(encoding="utf-8", errors="ignore").lower()
        except OSError:
            continue
        for token in tokens:
            if token in text:
                issues.append(f"{where}: {path} names '{token}' (a private namespace or source); keep that out of tracked data and scripts")
                break
    return issues


def check_gitignore(root: Path, policy: dict) -> list[str]:
    ignore = root / ".gitignore"
    lines = {line.strip() for line in ignore.read_text(encoding="utf-8").splitlines()} if ignore.is_file() else set()
    return [f".gitignore: the required pattern {pattern} is missing" for pattern in policy["required_gitignore_patterns"] if pattern not in lines]


def check_workflows(root: Path, policy: dict) -> list[str]:
    """A workflow may not upload a private folder as an artifact, and nothing here may fetch the private source."""
    issues = []
    words = [w.lower() for w in policy["workflow_artifact_forbidden_words"]]
    markers = [m.lower() for m in policy["private_source_markers"]]
    folder = root / ".github" / "workflows"
    if not folder.is_dir():
        return issues
    for workflow in sorted(folder.glob("*.y*ml")):
        text = workflow.read_text(encoding="utf-8", errors="ignore")
        low = text.lower()
        for marker in markers:
            if marker in low:
                issues.append(f"workflow {workflow.name}: names the private source ({marker}); a workflow must never fetch it")
        for block in re.split(r"(?m)^\s*-\s+(?=name:|uses:|run:)", text):
            if "upload-artifact" not in block.lower():
                continue
            for word in words:
                if word in block.lower():
                    issues.append(f"workflow {workflow.name}: an artifact upload names '{word}'")
    return issues


def check_history(root: Path, policy: dict) -> list[str]:
    """Any commit, on any ref, that ever touched a private path, even if a later commit removed it."""
    issues = []
    try:
        lines = git(root, "log", "--all", "--name-only", "--pretty=format:%h")
    except RuntimeError as error:
        return [f"history: could not be read ({error})"]
    commit = ""
    seen = set()
    for line in lines:
        if re.fullmatch(r"[0-9a-f]{7,40}", line):
            commit = line
            continue
        hit = matches_any(line, policy["forbidden_path_patterns"])
        if hit and (commit, line) not in seen:
            seen.add((commit, line))
            issues.append(f"history: commit {commit} touched a path of private content ({hit}): {line}")
    return issues


def run(root: Path, staged: bool = False, base: str | None = None, history: bool = True) -> list[str]:
    policy = load_policy(root)
    issues: list[str] = []
    if staged:
        files = git(root, "diff", "--cached", "--name-only", "--diff-filter=ACMR")
        issues += check_paths(files, policy, "staged")
        issues += check_text(root, files, policy, "staged")
        issues += check_gitignore(root, policy)
        return issues
    if base:
        files = git(root, "diff", "--name-only", "--diff-filter=ACMR", base + "...HEAD")
        issues += check_paths(files, policy, "added since " + base)
        issues += check_text(root, files, policy, "added since " + base)
    tracked = git(root, "ls-files")
    issues += check_paths(tracked, policy, "tracked")
    issues += check_text(root, tracked, policy, "tracked")
    issues += check_gitignore(root, policy)
    issues += check_workflows(root, policy)
    if history:
        issues += check_history(root, policy)
    return issues


def main(argv: list[str]) -> int:
    staged = "--staged" in argv
    history = "--no-history" not in argv
    base = None
    if "--base" in argv:
        base = argv[argv.index("--base") + 1]
    issues = run(ROOT, staged=staged, base=base, history=history)
    if issues:
        print("PRIVATE-CONTENT GUARD FAILED (docs/legal/private-content.md):")
        for issue in dict.fromkeys(issues):
            print("  - " + issue)
        print("Nothing of the privately licensed content may be tracked, committed or published. Remove it from the index (git rm --cached), keep it in your private folder, and if it reached a commit tell the owner before pushing.")
        return 1
    print("Private-content guard passed: nothing private is tracked, staged, uploaded by a workflow or in the history.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
