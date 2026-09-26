import argparse
import json
import re
from pathlib import Path


STEP_PATTERN = re.compile(r"(?ms)^(?P<indent>[ ]+)- (?:name|uses):.*?(?=^(?P=indent)- |\Z)")


def workflow_steps(workflow: str) -> dict[str, str]:
    steps: dict[str, str] = {}
    for index, match in enumerate(STEP_PATTERN.finditer(workflow)):
        block = match.group(0)
        identifier = re.search(r"(?m)^[ ]+id: ([^\s]+)\s*$", block)
        steps[identifier.group(1) if identifier else f"<anonymous-{index}>"] = block
    return steps


def run_body(step: str) -> str:
    match = re.search(r"(?m)^(?P<indent>[ ]+)run: \|[ \t]*$", step)
    if not match:
        single_line = re.search(r"(?m)^[ ]+run:[ \t]+(.+)$", step)
        return single_line.group(1) if single_line else ""
    content_indent = len(match.group("indent")) + 2
    body: list[str] = []
    for line in step[match.end() :].splitlines():
        if line.strip() and len(line) - len(line.lstrip()) <= len(match.group("indent")):
            break
        body.append(line[content_indent:] if len(line) >= content_indent else "")
    return "\n".join(body)


def executable_lines(run: str) -> str:
    return "\n".join(line for line in run.splitlines() if line.strip() and not line.lstrip().startswith("#"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workflow", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    arguments = parser.parse_args()

    workflow = arguments.workflow.read_text(encoding="utf-8")
    fixture = json.loads(arguments.fixture.read_text(encoding="utf-8"))
    failures: list[str] = []
    steps = workflow_steps(workflow)

    command_pattern = re.compile(fixture["build_command_pattern"], re.MULTILINE)
    command_steps = []
    for step_id, step in steps.items():
        matches = command_pattern.findall(executable_lines(run_body(step)))
        if matches:
            command_steps.append(step_id)
    if len(command_steps) != fixture["expected_build_command_count"]:
        failures.append(
            f"build command count: expected {fixture['expected_build_command_count']}, got {len(command_steps)}"
        )
    for step_id in fixture["build_step_ids"]:
        if step_id not in command_steps:
            failures.append(f"build command missing from step: {step_id}")
    unexpected_steps = sorted(set(command_steps) - set(fixture["build_step_ids"]))
    if unexpected_steps:
        failures.append(f"build command outside owner steps: {', '.join(unexpected_steps)}")

    for fragment in fixture["required_workflow_fragments"]:
        count = workflow.count(fragment["text"])
        if count != fragment["count"]:
            failures.append(f"required fragment count: {fragment['text']!r}, expected {fragment['count']}, got {count}")

    for fragment in fixture["forbidden_workflow_fragments"]:
        if fragment in workflow:
            failures.append(f"forbidden workflow fragment: {fragment!r}")

    for step_id in fixture["required_step_ids"]:
        if step_id not in steps:
            failures.append(f"missing build step id: {step_id}")

    for step_id, rule in fixture["prompt_rules"].items():
        step = steps.get(step_id)
        if step is None:
            failures.append(f"missing prompt step id: {step_id}")
            continue
        for fragment in rule["required_fragments"]:
            if step.count(fragment) != 1:
                failures.append(f"prompt fragment must appear once in {step_id}: {fragment!r}")
        for fragment in rule["forbidden_fragments"]:
            if fragment in step:
                failures.append(f"forbidden prompt fragment in {step_id}: {fragment!r}")

    if failures:
        raise SystemExit("Standard build owner self-test failed:\n" + "\n".join(failures))
    print(f"Standard build owner self-test passed ({len(steps)} workflow steps)")


if __name__ == "__main__":
    main()
