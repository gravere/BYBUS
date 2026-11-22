#!/usr/bin/env python3
"""Reject local collaboration files in a staged or committed Git tree."""

import argparse
import fnmatch
import subprocess
import sys
from pathlib import PurePosixPath


MARKER = "# Local collaboration and private working records"
ALWAYS_PRIVATE = (
    "AGENTS.md", "AGENTS.override.md", "CLAUDE.md", "GEMINI.md",
    ".cursorrules", ".codex/", ".claude/", ".agents/", ".private/", ".local/",
)


def git(*args):
    return subprocess.check_output(["git", *args])


def matches(path, pattern):
    path, pattern = path.casefold(), pattern.casefold()
    if pattern.startswith("/"):
        pattern = pattern[1:]
        return (path.startswith(pattern) if pattern.endswith("/")
                else fnmatch.fnmatchcase(path, pattern))
    if pattern.endswith("/"):
        return pattern[:-1] in PurePosixPath(path).parts[:-1]
    return fnmatch.fnmatchcase(PurePosixPath(path).name, pattern)


def find_private_paths(paths, ignore_text):
    if MARKER not in ignore_text:
        raise ValueError("Local-only file rules are missing from .gitignore")
    patterns = list(ALWAYS_PRIVATE)
    for line in ignore_text.split(MARKER, 1)[1].splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            patterns.append(line)
    return sorted({p for p in paths if any(matches(p, rule) for rule in patterns)})


def inspect_tree(revision=None):
    if revision:
        tree = git("rev-parse", "--verify", "--end-of-options",
                   revision + "^{tree}").decode().strip()
        paths = git("ls-tree", "-r", "--name-only", "-z", tree)
        ignore = git("show", tree + ":.gitignore")
    else:
        paths = git("ls-files", "--cached", "-z")
        ignore = git("show", ":.gitignore")
    names = [p.decode("utf-8", errors="surrogateescape")
             for p in paths.split(b"\0") if p]
    return find_private_paths(names, ignore.decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", help="Check a commit; default: staged files")
    args = parser.parse_args()
    try:
        blocked = inspect_tree(args.revision)
    except (subprocess.CalledProcessError, ValueError, UnicodeError) as error:
        print(f"Cannot verify public files: {error}", file=sys.stderr)
        return 2
    if blocked:
        print("Local-only files must be untracked before publishing:", file=sys.stderr)
        for path in blocked:
            print(f"  {path}", file=sys.stderr)
        return 1
    print("Public tree check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
