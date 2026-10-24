#!/usr/bin/env python3
"""Point this clone's git hooks at .githooks (the private-content guard runs before every commit and push). Run once per clone."""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

if __name__ == "__main__":
    subprocess.run(["git", "-C", str(ROOT), "config", "core.hooksPath", ".githooks"], check=True)
    print("hooks installed: git will run .githooks/pre-commit and .githooks/pre-push in this clone")
    sys.exit(0)
