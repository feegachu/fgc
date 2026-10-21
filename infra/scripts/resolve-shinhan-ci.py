"""Accept only successful CI pushes from this repository's current develop head."""
import json
import os
import re
import urllib.request


def validate_source(run, repository, latest_sha):
    if (run.get("path") != ".github/workflows/ci.yml"
            or run.get("event") != "push"
            or run.get("head_branch") != "develop"
            or run.get("status") != "completed"
            or run.get("conclusion") != "success"
            or (run.get("head_repository") or {}).get("full_name") != repository):
        raise ValueError("Only successful same-repository develop push CI runs may deploy")
    sha = run.get("head_sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid CI commit")
    return sha == latest_sha


def main():
    repository = os.environ["GITHUB_REPOSITORY"]
    run_id = os.environ["SOURCE_RUN_ID"]
    if not re.fullmatch(r"[0-9]+", run_id):
        raise ValueError("Invalid CI run ID")

    def get(path):
        request = urllib.request.Request(
            os.environ.get("GITHUB_API_URL", "https://api.github.com") + path,
            headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                     "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    run = get(f"/repos/{repository}/actions/runs/{run_id}")
    latest = get(f"/repos/{repository}/branches/develop")["commit"]["sha"]
    deploy = validate_source(run, repository, latest)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for key, value in {"deploy": str(deploy).lower(), "commit": run["head_sha"],
                           "run_url": run["html_url"], "run_id": run_id}.items():
            if "\n" in value or "\r" in value:
                raise ValueError("Invalid output")
            output.write(f"{key}={value}\n")
    print("Verified current develop CI" if deploy else "Skipped superseded develop commit")


if __name__ == "__main__":
    main()
