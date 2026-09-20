#!/usr/bin/env python3
"""Classify a change as the deterministic documentation Fast path or Standard."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
import unicodedata
from pathlib import Path
from urllib.parse import unquote, urlsplit


ALLOWED_SUFFIXES = (".md", ".markdown")
ALLOWED_PREFIXES = ("docs/", "ai/adr/")
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
INLINE_LINK_PATTERN = re.compile(r"(?<!!)(?:\[[^\]]*\])\(([^)\r\n]+)\)")
IMAGE_LINK_PATTERN = re.compile(r"!\[[^\]]*\]\(([^)\r\n]+)\)")
REFERENCE_LINK_PATTERN = re.compile(r"(?<!!)(?:\[([^\]]+)\])\[([^\]]*)\]")
IMAGE_REFERENCE_PATTERN = re.compile(r"!\[([^\]]+)\]\[([^\]]*)\]")
REFERENCE_DEFINITION_PATTERN = re.compile(
    r"(?m)^[ ]{0,3}\[([^\]]+)\]:[ \t]*(?:<([^>\r\n]+)>|(\S+))"
)
HEADING_PATTERN = re.compile(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$")
SETEXT_PATTERN = re.compile(r"^ {0,3}(?:=+|-+)\s*$")
INLINE_CODE_PATTERN = re.compile(r"(?P<ticks>`+)(?P<body>[\s\S]*?)(?P=ticks)")
FENCE_PATTERN = re.compile(r"^ {0,3}(`{3,}|~{3,})([^\r\n]*)$")


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
    return (
        not is_forbidden(value)
        and value.startswith(ALLOWED_PREFIXES)
        and value.endswith(ALLOWED_SUFFIXES)
    )


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
    value = unicodedata.normalize("NFC", value).casefold()
    value = re.sub(r"[^\w\s-]", "", value)
    return re.sub(r"[\s-]+", "-", value).strip("-")


def parse_fence(line: str) -> tuple[str, int, str] | None:
    match = FENCE_PATTERN.match(line)
    if not match:
        return None
    marker = match.group(1)
    info = match.group(2)
    if marker[0] == "`" and "`" in info:
        return None
    return marker[0], len(marker), info


def heading_slugs(path: Path) -> set[str]:
    slugs: set[str] = set()
    counts: dict[str, int] = {}
    lines = path.read_text(encoding="utf-8").splitlines()
    in_fence: tuple[str, int] | None = None

    def add_heading(value: str) -> None:
        slug = github_slug(value)
        if not slug:
            return
        occurrence = counts.get(slug, 0)
        candidate = slug if occurrence == 0 else f"{slug}-{occurrence}"
        while candidate in slugs:
            occurrence += 1
            candidate = f"{slug}-{occurrence}"
        counts[slug] = occurrence + 1
        slugs.add(candidate)

    for index, line in enumerate(lines):
        fence = parse_fence(line)
        if in_fence is not None:
            if fence and fence[0] == in_fence[0] and fence[1] >= in_fence[1] and not fence[2].strip():
                in_fence = None
            continue
        if fence:
            in_fence = (fence[0], fence[1])
            continue

        atx = HEADING_PATTERN.match(line)
        if atx:
            add_heading(atx.group(1))
            continue

        if index + 1 < len(lines) and line.strip() and SETEXT_PATTERN.match(lines[index + 1]):
            add_heading(line.strip())
    return slugs


def is_external(target: str) -> bool:
    scheme = urlsplit(target).scheme.lower()
    return target.startswith("//") or scheme in {"http", "https", "mailto", "tel"}


def normalize_reference_label(value: str) -> str:
    return " ".join(value.casefold().split())


def clean_link_target(raw_target: str) -> str:
    target = raw_target.strip()
    if target.startswith("<") and ">" in target:
        return target[1 : target.index(">")].strip()
    return target.split(None, 1)[0]


def without_fenced_code(contents: str) -> str:
    visible_lines: list[str] = []
    in_fence: tuple[str, int] | None = None
    for line in contents.splitlines():
        fence = parse_fence(line)
        if in_fence is not None:
            if fence and fence[0] == in_fence[0] and fence[1] >= in_fence[1] and not fence[2].strip():
                in_fence = None
            continue
        if fence:
            in_fence = (fence[0], fence[1])
            continue
        if in_fence is None:
            visible_lines.append(line)
    return "\n".join(visible_lines)


def without_inline_code(contents: str) -> str:
    def replace_code_span(match: re.Match[str]) -> str:
        return "".join("\n" if character == "\n" else " " for character in match.group(0))

    return INLINE_CODE_PATTERN.sub(replace_code_span, contents)


def check_links(root: Path, entries: list[dict[str, str]]) -> list[str]:
    broken: list[str] = []
    for entry in entries:
        if not is_allowed_doc(entry["path"]):
            continue
        source = root / entry["path"]
        if not source.is_file():
            continue
        contents = without_inline_code(without_fenced_code(source.read_text(encoding="utf-8")))
        references: dict[str, str] = {}
        for definition in REFERENCE_DEFINITION_PATTERN.finditer(contents):
            raw_target = definition.group(2) or definition.group(3)
            references[normalize_reference_label(definition.group(1))] = raw_target

        def validate_target(raw_target: str, raw_link: str) -> None:
            target = clean_link_target(raw_target)
            if not target or is_external(target):
                return
            parsed = urlsplit(target)
            fragment = unquote(parsed.fragment)
            target_path = unquote(parsed.path)
            document = (source.parent / target_path).resolve() if target_path else source.resolve()
            try:
                document.relative_to(root.resolve())
            except ValueError:
                broken.append(f"{entry['path']}: outside repository: {raw_link}")
                return
            if not document.is_file():
                broken.append(f"{entry['path']}: missing target: {raw_link}")
                return
            if fragment and fragment not in heading_slugs(document):
                broken.append(f"{entry['path']}: missing anchor: {raw_link}")

        for raw_target in INLINE_LINK_PATTERN.findall(contents):
            validate_target(raw_target, raw_target)

        for raw_target in IMAGE_LINK_PATTERN.findall(contents):
            validate_target(raw_target, raw_target)

        for reference in REFERENCE_LINK_PATTERN.finditer(contents):
            label = reference.group(2) or reference.group(1)
            key = normalize_reference_label(label)
            if key not in references:
                broken.append(f"{entry['path']}: missing reference definition: {reference.group(0)}")
            else:
                validate_target(references[key], reference.group(0))

        for reference in IMAGE_REFERENCE_PATTERN.finditer(contents):
            label = reference.group(2) or reference.group(1)
            key = normalize_reference_label(label)
            if key not in references:
                broken.append(f"{entry['path']}: missing reference definition: {reference.group(0)}")
            else:
                validate_target(references[key], reference.group(0))

        for label, raw_target in references.items():
            validate_target(raw_target, f"[{label}]: {raw_target}")
    return list(dict.fromkeys(broken))


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
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path, contents in case.get("documents", {}).items():
                document = root / path
                document.parent.mkdir(parents=True, exist_ok=True)
                document.write_text(contents, encoding="utf-8")
            result = classify(root, case["files"])
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
