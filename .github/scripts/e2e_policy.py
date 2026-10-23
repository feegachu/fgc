"""Select E2E suites and validate the required playwright check without dependencies."""

import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


def classify(paths):
    """Only skip known documentation; unknown paths conservatively run both suites."""
    ui = server = False
    for path in paths:
        if path.startswith(("frontend/", "src/main/resources/templates/", "src/main/resources/static/")):
            ui = server = True
        elif path.startswith(("src/", "gradle/")) or path in {
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "gradlew", "gradlew.bat",
        }:
            server = True
        elif is_documentation(path):
            continue
        else:
            ui = server = True
    return {"ui": ui, "server": server, "stability": False}


def is_documentation(path):
    """Runtime folders are classified before this narrow documentation allowlist."""
    file = PurePosixPath(path)
    if file.suffix.lower() in {".md", ".rst", ".txt"}:
        root_document = len(file.parts) == 1 and (
            file.suffix.lower() in {".md", ".rst"} or path in {"LICENSE.txt", "NOTICE.txt"}
        )
        return (
            root_document
            or path.startswith(("docs/", ".github/ISSUE_TEMPLATE/"))
            or path == ".github/pull_request_template.md"
        )
    return path.startswith("docs/") and file.suffix.lower() in {
        ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".pdf",
        ".csv", ".xlsx", ".docx", ".pptx", ".drawio", ".mmd", ".puml",
    }


def git(*args):
    return subprocess.check_output(["git", *args])


def require_sha(revision):
    if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Expected a full Git commit SHA")


def commit_available(revision):
    require_sha(revision)
    try:
        git("cat-file", "-e", revision + "^{commit}")
    except subprocess.CalledProcessError:
        return False
    return True


def changed_paths(base, head, *, merge_base=False):
    """Use NUL-delimited paths and disable rename folding to inspect old and new names."""
    for revision in (base, head):
        require_sha(revision)
    if merge_base:
        base = git("merge-base", base, head).decode().strip()
    raw = git("diff", "--name-only", "--no-renames", "-z", base, head, "--")
    return raw.decode("utf-8", errors="surrogateescape").rstrip("\0").split("\0") if raw else []


def documentation_only_since(base, head):
    """Jenkins dev-fgc: a docs-only push reuses the previous images, so their revision must be a docs-only ancestor."""
    require_sha(base)
    require_sha(head)
    try:
        git("merge-base", "--is-ancestor", base, head)
    except subprocess.CalledProcessError:
        return False
    plan = classify(changed_paths(base, head))
    return not (plan["ui"] or plan["server"])


def plan_for_event(event_name, event):
    """PR diffs cover the entire PR; push diffs cover all commits in that push."""
    if event_name in {"schedule", "workflow_dispatch"}:
        return {"ui": True, "server": True, "stability": event_name == "schedule"}
    if event_name == "pull_request":
        pr = event["pull_request"]
        return classify(changed_paths(pr["base"]["sha"], pr["head"]["sha"], merge_base=True))
    if event_name == "push":
        before, after = event["before"], event["after"]
        require_sha(before)
        require_sha(after)
        # A new branch has no previous tree; never interpret it as documentation-only.
        # Full fetches cannot recover an unreachable before SHA after rewritten history.
        if before == "0" * 40 or not commit_available(before):
            return {"ui": True, "server": True, "stability": False}
        return classify(changed_paths(before, after))
    raise ValueError(f"Unsupported event: {event_name}")


def validate_gate(needs):
    """Fail closed on failed detection, malformed outputs or any unexpected job result."""
    changes = needs.get("changes", {})
    if changes.get("result") != "success":
        raise ValueError("E2E change detection did not succeed")
    outputs = changes.get("outputs", {})
    for flag in ("ui", "server", "stability"):
        if outputs.get(flag) not in {"true", "false"}:
            raise ValueError(f"Missing or invalid plan output: {flag}")
    if outputs["stability"] == "true" and outputs["server"] != "true":
        raise ValueError("Stability requires real-server tests")
    for job, flag in (("ui", "ui"), ("negative", "ui"), ("real-server", "server")):
        expected = "success" if outputs[flag] == "true" else "skipped"
        actual = needs.get(job, {}).get("result")
        if actual != expected:
            raise ValueError(f"{job}: expected {expected}, got {actual}")


def ci_plan(plan):
    """Build and frontend run for every non-documentation change."""
    return {"run_checks": plan["ui"] or plan["server"]}


def validate_ci_plan(needs):
    """Required jobs must fail if detection failed or did not report a valid plan."""
    changes = needs.get("changes", {})
    flag = changes.get("outputs", {}).get("run_checks")
    if changes.get("result") != "success" or flag not in {"true", "false"}:
        raise ValueError("CI change detection failed or returned an invalid plan")
    return flag == "true"


def main():
    if sys.argv[1:] in (["plan"], ["ci-plan"]):
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        plan = plan_for_event(os.environ["GITHUB_EVENT_NAME"], event)
        if sys.argv[1] == "ci-plan":
            plan = ci_plan(plan)
        output = "".join(f"{key}={str(value).lower()}\n" for key, value in plan.items())
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as destination:
            destination.write(output)
        print(output, end="")
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write("### Verification execution plan\n\n" + "\n".join(
                f"- {key}: {str(value).lower()}" for key, value in plan.items()) + "\n")
    elif sys.argv[1:] == ["gate"]:
        validate_gate(json.loads(os.environ["E2E_NEEDS"]))
        print("Required E2E suites passed; unneeded suites were explicitly skipped.")
    elif sys.argv[1:] == ["ci-gate"]:
        required = validate_ci_plan(json.loads(os.environ["CI_NEEDS"]))
        message = ("Code/configuration changes: normal verification is required."
                   if required else "Documentation-only change: build and frontend checks are intentionally omitted.")
        print(message)
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(message + "\n")
    else:
        raise ValueError("Usage: e2e_policy.py plan|gate|ci-plan|ci-gate")


if __name__ == "__main__":
    main()
