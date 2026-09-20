#!/usr/bin/env python3
"""Classify a change as the deterministic documentation Fast path or Standard."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import unicodedata
from pathlib import Path
from urllib.parse import unquote, urlsplit


ALLOWED_SUFFIXES = (".md", ".markdown")
FORBIDDEN_EXACT = {
    "agents.md",
    "claude.md",
    "ai/agent-mistakes.md",
    "ai/context-router.md",
    "docs/conventions/code.md",
    "docs/conventions/git.md",
    "docs/conventions/team.md",
}
FORBIDDEN_PREFIXES = (".agents/", ".claude/", ".codex/", ".github/workflows/")
LINK_PATTERN = re.compile(r"(?<!!)(?:\[[^\]]*\])\(([^)]+)\)")
HEADING_PATTERN = re.compile(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$")


def normalize(path: str) -> str:
    value = path.replace("\\", "/")
    while value.startswith("./"):
        value = value[2:]
    return value.lower()


def is_forbidden(path: str) -> bool:
    value = normalize(path)
    return value in FORBIDDEN_EXACT or any(value.startswith(prefix) for prefix in FORBIDDEN_PREFIXES)


def is_allowed_doc(path: str) -> bool:
    value = normalize(path)
    return not is_forbidden(value) and value.endswith(ALLOWED_SUFFIXES)


def run_git(*args: str) -> str:
    result = subprocess.run(["git", *args], check=True, text=True, capture_output=True)
    return result.stdout


def changed_entries(base: str) -> list[dict[str, str]]:
    output = run_git("diff", "--name-status", "--find-renames", f"{base}...HEAD")
    entries = []
    for line in output.splitlines():
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        status = fields[0]
        entries.append({"status": status, "path": fields[-1]})
    return entries


def github_slug(value: str) -> str:
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode()
    value = value.lower()
    value = re.sub(r"[^a-z0-9 -]", "", value)
    return re.sub(r"[ -]+", "-", value).strip("-")


def heading_slugs(path: Path) -> set[str]:
    slugs: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = HEADING_PATTERN.match(line)
        if match:
            slug = github_slug(match.group(1))
            if slug:
                slugs.add(slug)
    return slugs


def is_external(target: str) -> bool:
    scheme = urlsplit(target).scheme.lower()
    return scheme in {"http", "https", "mailto", "tel"}


def check_links(root: Path, entries: list[dict[str, str]]) -> list[str]:
    broken: list[str] = []
    for entry in entries:
        if not is_allowed_doc(entry["path"]):
            continue
        source = root / entry["path"]
        if not source.is_file():
            continue
        for raw_target in LINK_PATTERN.findall(source.read_text(encoding="utf-8")):
            target = raw_target.strip().strip("<>").split(' "', 1)[0].split(" '", 1)[0]
            if not target or is_external(target):
                continue
            parsed = urlsplit(target)
            fragment = unquote(parsed.fragment)
            target_path = unquote(parsed.path)
            document = (source.parent / target_path).resolve() if target_path else source.resolve()
            try:
                document.relative_to(root.resolve())
            except ValueError:
                broken.append(f"{entry['path']}: outside repository: {raw_target}")
                continue
            if not document.is_file():
                broken.append(f"{entry['path']}: missing target: {raw_target}")
                continue
            if fragment and fragment not in heading_slugs(document):
                broken.append(f"{entry['path']}: missing anchor: {raw_target}")
    return broken


def classify(root: Path, entries: list[dict[str, str]]) -> dict:
    reasons: list[str] = []
    if not entries:
        reasons.append("변경 파일이 없습니다")
    for entry in entries:
        path = entry["path"]
        if entry["status"].startswith(("D", "R", "C")):
            reasons.append(f"삭제·이름 변경·복사는 Standard: {path}")
        elif not is_allowed_doc(path):
            reasons.append(f"allowlist 밖 파일: {path}")

    broken_links = check_links(root, entries)
    if broken_links:
        reasons.append(f"깨진 문서 링크 {len(broken_links)}건")

    path = "FAST" if not reasons else "STANDARD"
    return {
        "path": path,
        "changed_files": entries,
        "reasons": reasons,
        "broken_links": broken_links,
    }


def run_self_test(fixture_path: Path) -> None:
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    failures = []
    for case in fixture["cases"]:
        # Fixture cases exercise only the deterministic path allowlist. Link checking is
        # covered by the real classification run against the checked-out repository.
        result = classify(Path("/__h13_fixture_repository__"), case["files"])
        if result["path"] != case["expected"]:
            failures.append(f"{case['name']}: expected {case['expected']}, got {result['path']}")
    if failures:
        raise SystemExit("Fast route self-test failed:\n" + "\n".join(failures))
    print(f"Fast route self-test passed ({len(fixture['cases'])} cases)")


def write_evidence(result: dict, evidence_path: Path) -> None:
    lines = [
        "## H-13 문서 경로 Evidence",
        "",
        f"- 판정: **{result['path']}**",
        "- Fast 조건: Markdown/ADR allowlist, 삭제·이름 변경 없음, 문서 링크 검증 통과",
        "- 최종 리뷰와 merge: 사람만 수행",
        "",
        "### 변경 파일",
        "",
        "| 상태 | 파일 |",
        "| --- | --- |",
    ]
    lines.extend(f"| {entry['status']} | `{entry['path']}` |" for entry in result["changed_files"])
    if result["reasons"]:
        lines.extend(["", "### Standard 사유", ""])
        lines.extend(f"- {reason}" for reason in result["reasons"])
    if result["broken_links"]:
        lines.extend(["", "### 링크 검증 결과", ""])
        lines.extend(f"- {link}" for link in result["broken_links"])
    evidence_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="origin/dev")
    parser.add_argument("--result", type=Path)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--fixture", type=Path)
    args = parser.parse_args()

    if args.self_test:
        if not args.fixture:
            parser.error("--self-test requires --fixture")
        run_self_test(args.fixture)
        if not args.result:
            return 0

    if not args.result or not args.evidence:
        parser.error("classification requires --result and --evidence")
    result = classify(Path.cwd(), changed_entries(args.base))
    args.result.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_evidence(result, args.evidence)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
