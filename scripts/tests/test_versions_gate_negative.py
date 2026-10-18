#!/usr/bin/env python3
"""
Negative tests for scripts/check_versions.py.

Each case injects a REAL drift into a scratch copy and asserts the gate fails
mentioning it - plus a positive control that the untouched tree passes.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent.parent  # repo root
PY = sys.executable


def current_train_version(repo: Path) -> str:
    """Read the release-train version from :browser-core on that tree."""
    text = (repo / "browser-core" / "build.gradle.kts").read_text(
        encoding="utf-8")
    m = re.search(r'^version\s*=\s*"([^"]+)"', text, re.MULTILINE)
    assert m, "no version in browser-core/build.gradle.kts"
    return m.group(1)

results: list[tuple[bool, str]] = []


def run_gate(repo: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [PY, str(repo / "scripts" / "check_versions.py")],
        capture_output=True, text=True, cwd=repo,
    )


def case(name: str, mutate, expect_substring: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo = Path(tmp) / "repo"
        shutil.copytree(
            SRC, repo,
            ignore=shutil.ignore_patterns(".git", "build", ".gradle", ".kotlin"),
        )
        mutate(repo)
        proc = run_gate(repo)
        out = proc.stdout + proc.stderr
        hits = expect_substring.lower() in out.lower()
        ok = (proc.returncode != 0) and hits
        results.append((ok, name))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            print(f"       exit={proc.returncode} (want non-zero)  "
                  f"mentions {expect_substring!r}")
            print("       --- gate output ---")
            for line in out.splitlines()[:12]:
                print("       " + line)


def mutate_split_train(repo: Path) -> None:
    f = repo / "browser-chrome" / "build.gradle.kts"
    s = f.read_text(encoding="utf-8")
    new = re.sub(r'^version\s*=\s*"[^"]+"', 'version = "9.9.9"', s,
                 count=1, flags=re.MULTILINE)
    assert new != s, "mutation did not apply"
    f.write_text(new, encoding="utf-8")


def mutate_stale_readme(repo: Path) -> None:
    ver = current_train_version(repo)
    f = repo / "README.md"
    s = f.read_text(encoding="utf-8")
    new = s.replace(f"com.apex.browser:browser-core:{ver}",
                    "com.apex.browser:browser-core:0.0.0-stale")
    assert new != s, "mutation did not apply"
    f.write_text(new, encoding="utf-8")


def mutate_matrix_drift(repo: Path) -> None:
    f = repo / "README.md"
    s = f.read_text(encoding="utf-8")
    # 兼容矩阵随工具链列车前进（2.0.21 -> 2.2.21 -> ...）—— 从 README 现值
    # 动态取 Kotlin 行再漂移，mutation 不再绑死某个具体版本号。
    m = re.search(r"\| Kotlin \| (\S+) \|", s)
    assert m, "README compat matrix missing the Kotlin row"
    new = s.replace(f"| Kotlin | {m.group(1)} |", "| Kotlin | 9.9.9 |", 1)
    assert new != s, "mutation did not apply"
    f.write_text(new, encoding="utf-8")


case("RULE 1 catches a split release train", mutate_split_train,
     "versions disagree")
case("RULE 2 catches a stale README coordinate", mutate_stale_readme,
     "consumer snippet")
case("RULE 3 catches compat-matrix drift", mutate_matrix_drift,
     "libs.versions.toml")


# ── Positive control: untouched tree passes ───────────────────────────────────
proc = run_gate(SRC)
ok = proc.returncode == 0
results.append((ok, "untouched tree passes the version gate"))
print(f"[{'PASS' if ok else 'FAIL'}] untouched tree passes the version gate")
if not ok:
    for line in (proc.stdout + proc.stderr).splitlines()[:12]:
        print("       " + line)

# ── Summary ───────────────────────────────────────────────────────────────────
ok = sum(1 for r, _ in results if r)
total = len(results)
print(f"\n{ok}/{total} version-gate negative-tests behaved correctly")
if ok != total:
    print("FAILURES:")
    for r, n in results:
        if not r:
            print(f"  - {n}")
    sys.exit(1)
print("ALL VERSION-GATE NEGATIVE-TESTS PASS")
