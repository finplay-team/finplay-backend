import argparse
import json
import re
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workflow", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    arguments = parser.parse_args()

    workflow = arguments.workflow.read_text(encoding="utf-8")
    fixture = json.loads(arguments.fixture.read_text(encoding="utf-8"))
    failures: list[str] = []

    command = fixture["build_command"]
    command_count = workflow.count(command)
    if command_count != fixture["expected_build_command_count"]:
        failures.append(
            f"build command count: expected {fixture['expected_build_command_count']}, got {command_count}"
        )

    for fragment in fixture["required_workflow_fragments"]:
        count = workflow.count(fragment["text"])
        if count != fragment["count"]:
            failures.append(f"required fragment count: {fragment['text']!r}, expected {fragment['count']}, got {count}")

    for fragment in fixture["forbidden_workflow_fragments"]:
        if fragment in workflow:
            failures.append(f"forbidden workflow fragment: {fragment!r}")

    for step_id in fixture["required_step_ids"]:
        if re.search(rf"(?m)^\s+id: {re.escape(step_id)}\s*$", workflow) is None:
            failures.append(f"missing build step id: {step_id}")

    if failures:
        raise SystemExit("Standard build owner self-test failed:\n" + "\n".join(failures))
    print("Standard build owner self-test passed")


if __name__ == "__main__":
    main()
