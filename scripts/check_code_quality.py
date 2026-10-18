#!/usr/bin/env python3
"""
check_code_quality.py — structural gates adapted from the sibling repo.

Three checks, none of which need an Android SDK or a Gradle run:

  GATE 1  File-size budget (God-file prevention). Large files concentrate
          unrelated responsibilities, resist review, and are the #1 source of
          merge conflicts. Budgets inherited from Android-Guru-Agent's
          ``scripts/check_file_size.sh`` (main 1200 / test 1600) so the two
          repos stay consistent. v1.3.0: the budget counts **non-comment
          lines** (the same ``code_lines`` filter as GATE 2/3) — a file that
          grows only in KDoc is not growing in code, and documentation was
          never the hazard this gate exists for.

  GATE 2  ``printStackTrace()`` in main sources. Unroutable stdout; Android
          code should go through ``android.util.Log``.

  GATE 3  ``javaClass.getMethod`` reflective dispatch on a reference you
          already hold typed. Hides type errors until runtime and breaks
          silently on rename.

  AUDIT   Empty ``catch`` blocks and TODO/FIXME markers — reported for review,
          not gated (swallowing errors is sometimes correct).

Exit 0 = pass, 1 = fail.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAX_MAIN_LINES = int(os.environ.get("MAX_MAIN_LINES", "1200"))
MAX_TEST_LINES = int(os.environ.get("MAX_TEST_LINES", "1600"))

STACK_TRACE = re.compile(r"\.printStackTrace\s*\(")
REFLECT = re.compile(r"javaClass\.getMethod")
EMPTY_CATCH = re.compile(r"catch\s*\([\w. :]+\)\s*\{\s*\}")
TODO = re.compile(r"\b(TODO|FIXME|XXX)\b")
COMMENT_CONT = re.compile(r"^\s*(\*|//)")


def kt_files() -> list[Path]:
    return [
        p for p in ROOT.rglob("*.kt")
        if ".git" not in p.parts
        and "build" not in p.parts
        and ".gradle" not in p.parts
    ]


def in_source_set(path: Path, source_set: str) -> bool:
    """Portable 'is this file under src/<source_set>/' test.

    Uses as_posix() for BOTH sides: mixing as_posix() with os.sep silently
    matched nothing on Windows, which turned the file-size gate into a no-op
    there. Always compare in one separator flavour.
    """
    return f"/src/{source_set}/" in "/" + path.relative_to(ROOT).as_posix()


def code_lines(path: Path) -> list[str]:
    """Drop pure comment lines so documented anti-patterns do not trip gates."""
    return [
        ln for ln in path.read_text(encoding="utf-8").splitlines()
        if not COMMENT_CONT.match(ln)
    ]


def main() -> int:
    files = kt_files()
    main_files = [f for f in files if in_source_set(f, "main")]
    test_files = [
        f for f in files
        if in_source_set(f, "test") or in_source_set(f, "androidTest")
    ]
    if not main_files:
        print("FAIL GATE 1 — no main-source Kotlin files were discovered; "
              "the file-size gate would silently pass on an empty scan.")
        return 1
    failed = False

    # ── GATE 1: file-size budget (non-comment lines; KDoc is free) ───────────
    oversized_main: list[tuple[int, Path]] = []
    oversized_test: list[tuple[int, Path]] = []
    for f in main_files:
        n = len(code_lines(f))
        if n > MAX_MAIN_LINES:
            oversized_main.append((n, f))
    for f in test_files:
        n = len(code_lines(f))
        if n > MAX_TEST_LINES:
            oversized_test.append((n, f))

    if oversized_main or oversized_test:
        print(f"FAIL GATE 1 — file-size budget, non-comment lines "
              f"(main {MAX_MAIN_LINES} / test {MAX_TEST_LINES}):")
        for n, f in sorted(oversized_main, reverse=True):
            print(f"  - {f.relative_to(ROOT).as_posix()}: {n} lines (main)")
        for n, f in sorted(oversized_test, reverse=True):
            print(f"  - {f.relative_to(ROOT).as_posix()}: {n} lines (test)")
        print()
        print("   Fix: split along responsibility seams. Raising a budget is allowed")
        print("   only with a justification in the PR.")
        failed = True
    else:
        print(f"PASS GATE 1 — file-size budget "
              f"(main <= {MAX_MAIN_LINES}, test <= {MAX_TEST_LINES})")

    # ── GATE 2 / 3: anti-patterns in main sources ───────────────────────────
    stack_hits: list[str] = []
    reflect_hits: list[str] = []
    empty_catch = 0
    todo = 0

    for f in main_files:
        rel = f.relative_to(ROOT).as_posix()
        for i, ln in enumerate(code_lines(f), start=1):
            if STACK_TRACE.search(ln):
                stack_hits.append(f"{rel}: {ln.strip()}")
            if REFLECT.search(ln):
                reflect_hits.append(f"{rel}: {ln.strip()}")
        raw = f.read_text(encoding="utf-8")
        empty_catch += len(EMPTY_CATCH.findall(raw))
        for i, ln in enumerate(raw.splitlines(), start=1):
            if TODO.search(ln) and not COMMENT_CONT.match(ln):
                todo += 1

    if stack_hits:
        print("FAIL GATE 2 — printStackTrace() in main sources:")
        for h in stack_hits:
            print(f"  - {h}")
        print("   Fix: route through android.util.Log / a structured logger.")
        failed = True
    else:
        print("PASS GATE 2 — no printStackTrace() in main sources")

    if reflect_hits:
        print("FAIL GATE 3 — reflective dispatch on a held reference:")
        for h in reflect_hits:
            print(f"  - {h}")
        print("   Fix: extract an interface and do a type-safe `as?` cast.")
        failed = True
    else:
        print("PASS GATE 3 — no javaClass.getMethod reflective dispatch")

    total_lines = sum(
        len(f.read_text(encoding="utf-8").splitlines()) for f in files
    )
    print()
    print(f"AUDIT — {len(main_files)} main / {len(test_files)} test Kotlin files, "
          f"{total_lines} total lines")
    print(f"AUDIT — empty catch blocks: {empty_catch} (review-only)")
    print(f"AUDIT — TODO/FIXME markers in code: {todo} (review-only)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())