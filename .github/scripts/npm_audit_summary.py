"""Report advisory audit results without treating unavailable scans as clean."""

import json
import os
from pathlib import Path


SEVERITIES = ("info", "low", "moderate", "high", "critical")


def classify(report, exit_code):
    if exit_code not in (0, 1) or not isinstance(report, dict) or report.get("error"):
        return "unavailable", None
    metadata = report.get("metadata")
    counts = metadata.get("vulnerabilities") if isinstance(metadata, dict) else None
    if not isinstance(counts, dict):
        return "unavailable", None
    if any(type(counts.get(key)) is not int or counts[key] < 0 for key in (*SEVERITIES, "total")):
        return "unavailable", None
    if sum(counts[key] for key in SEVERITIES) != counts["total"]:
        return "unavailable", None
    if (counts["total"] == 0) != (exit_code == 0):
        return "unavailable", None
    return ("findings" if counts["total"] else "clean"), counts


def main():
    try:
        report = json.loads(Path("frontend/npm-audit.json").read_text(encoding="utf-8"))
        exit_code = int(os.environ.get("AUDIT_EXIT_CODE", ""))
    except (OSError, ValueError):
        report, exit_code = None, -1
    state, counts = classify(report, exit_code)
    lines = ["### npm dependency audit (informational)", "",
             "This report does not block merge or publish, matching the previous install audit policy.", ""]
    if state == "unavailable":
        message = "Audit unavailable: registry/command error, timeout, or invalid report. Dependencies were NOT confirmed clean."
        print(f"::warning title=npm audit unavailable::{message}")
        lines.append(message)
    elif state == "findings":
        message = f"Audit completed: {counts['total']} vulnerabilities reported. Review npm-audit-report before remediation."
        print(f"::warning title=npm audit findings::{message}")
        lines.append(message)
    else:
        lines.append("Audit completed: no known vulnerabilities reported for the locked dependencies.")
    if counts is not None:
        lines.extend(["", "| Severity | Count |", "| --- | ---: |"])
        lines.extend(f"| {key} | {counts[key]} |" for key in SEVERITIES)
    lines.extend(["", "Raw JSON and stderr are preserved in the npm-audit-report artifact.", ""])
    summary = "\n".join(lines)
    print(summary)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as destination:
        destination.write(summary)


if __name__ == "__main__":
    main()
