"""Complete existing Notion rows only with an explicitly closing, merged PR.

No third-party Python packages, GitHub writes, page creation, or rollup writes.
"""

import json
import os
import re
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


NOTION_VERSION = "2025-09-03"
ISSUE_PROPERTY = "GitHub 이슈"
STATUS_PROPERTY = "상태"
DONE = "완료"
IN_PROGRESS = "진행 중"


class SyncError(RuntimeError):
    pass


class SameOriginRedirect(HTTPRedirectHandler):
    """Keep authenticated API requests on the same HTTPS origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        original, target = urlsplit(req.full_url), urlsplit(newurl)
        if (target.scheme != "https" or target.hostname != original.hostname
                or (target.port or 443) != (original.port or 443) or target.username or target.password):
            raise SyncError("Cross-origin API redirect rejected")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class API:
    def __init__(self, base, token, headers=None, sleep=time.sleep):
        self.base = base
        self.token = token
        self.headers = headers or {}
        self.sleep = sleep
        self.opener = build_opener(SameOriginRedirect())

    def request(self, method, path, payload=None):
        body = None if payload is None else json.dumps(payload).encode()
        headers = {"Authorization": "Bearer " + self.token,
                   "Content-Type": "application/json", "User-Agent": "fgc-notion-wbs-sync",
                   **self.headers}
        for attempt in range(5):
            request = Request(self.base + path, data=body, headers=headers, method=method)
            try:
                with self.opener.open(request, timeout=30) as response:
                    return json.load(response)
            except HTTPError as error:
                if error.code in (429, 500, 502, 503, 504) and attempt < 4:
                    try:
                        delay = float(error.headers.get("Retry-After", 2 ** attempt))
                    except (ValueError, TypeError):
                        delay = 2 ** attempt
                    self.sleep(min(max(delay, 1), 60))
                    continue
                # Do not print response bodies, request headers, or tokens.
                raise SyncError(f"API {method} {path}: HTTP {error.code}") from None
            except (URLError, TimeoutError):
                if attempt < 4:
                    self.sleep(2 ** attempt)
                    continue
                raise SyncError(f"API {method} {path}: network failure") from None


def notion_id(value):
    try:
        return str(uuid.UUID(value))
    except (ValueError, AttributeError):
        raise SyncError("Notion ID must be a UUID") from None


def resolve_source(notion, database_id, configured_source=""):
    database = notion.request("GET", "/databases/" + notion_id(database_id))
    sources = {notion_id(source["id"]) for source in database.get("data_sources", [])}
    if configured_source:
        selected = notion_id(configured_source)
        if selected not in sources:
            raise SyncError("Configured data source does not belong to the WBS issue database")
    elif len(sources) == 1:
        selected = next(iter(sources))
    else:
        raise SyncError("Set NOTION_WBS_DATA_SOURCE_ID to the issue data source in this database")
    schema = notion.request("GET", "/data_sources/" + selected)
    props = schema.get("properties", {})
    if props.get(ISSUE_PROPERTY, {}).get("type") != "url":
        raise SyncError("GitHub 이슈 must be a URL property")
    status = props.get(STATUS_PROPERTY, {})
    if status.get("type") != "status":
        raise SyncError("상태 must be a Notion status property")
    options = {option["name"]: option["id"] for option in status["status"]["options"]}
    complete_ids = {option_id for group in status["status"].get("groups", [])
                    if group["name"].lower() in ("complete", "completed", "done", "완료")
                    for option_id in group["option_ids"]}
    if DONE not in options or IN_PROGRESS not in options or options[DONE] not in complete_ids:
        raise SyncError("완료 must belong to the Complete status group; 진행 중 must exist")
    return selected


def read_rows(notion, source):
    rows = []
    cursor = None
    seen_cursors = set()
    while True:
        payload = {"page_size": 100}
        if cursor:
            payload["start_cursor"] = cursor
        result = notion.request("POST", f"/data_sources/{source}/query", payload)
        rows.extend(row for row in result["results"]
                    if not row.get("archived") and not row.get("in_trash"))
        if not result.get("has_more"):
            return rows
        cursor = result.get("next_cursor")
        if not cursor:
            raise SyncError("Notion pagination cursor is missing")
        if cursor in seen_cursors:
            raise SyncError("Notion pagination cursor repeated; no updates applied")
        seen_cursors.add(cursor)


def tracked_rows(rows, repository):
    pattern = re.compile(r"/" + re.escape(repository) + r"/issues/([1-9][0-9]*)/?$", re.IGNORECASE)
    tracked = {}
    for row in rows:
        url = row.get("properties", {}).get(ISSUE_PROPERTY, {}).get("url") or ""
        if not url:
            continue
        parsed = urlsplit(url)
        match = pattern.fullmatch(parsed.path)
        if (not match or parsed.scheme != "https" or parsed.netloc.lower() != "github.com"):
            raise SyncError(f"Invalid GitHub 이슈 URL on Notion page {row['id']}; use an issue URL in {repository}")
        number = int(match[1])
        if number in tracked:
            raise SyncError(f"Duplicate Notion rows for GitHub issue #{number}; no updates applied")
        tracked[number] = row
    if not tracked:
        raise SyncError("No issue rows found; check the configured database and GitHub 이슈 URLs")
    return tracked


def read_evidence(github, repository, numbers):
    owner, name = repository.split("/")
    evidence = {}
    numbers = sorted(numbers)
    for start in range(0, len(numbers), 20):
        fields = []
        for number in numbers[start:start + 20]:
            fields.append(f"""i{number}: issue(number: {number}) {{
              number state stateReason
              closedByPullRequestsReferences(first: 100) {{
                pageInfo {{ hasNextPage }}
                nodes {{ number url mergedAt state baseRefName repository {{ nameWithOwner }} }}
              }}
            }}""")
        query = ("query { repository(owner: " + json.dumps(owner) + ", name: "
                 + json.dumps(name) + ") { " + " ".join(fields) + " } }")
        response = github.request("POST", "/graphql", {"query": query})
        if response.get("errors"):
            raise SyncError("GitHub GraphQL evidence query failed; no updates applied")
        result = response.get("data", {}).get("repository")
        if not result:
            raise SyncError("GitHub repository evidence is unavailable")
        for issue in result.values():
            if not issue:
                raise SyncError("A tracked GitHub issue is unavailable")
            if issue["closedByPullRequestsReferences"]["pageInfo"]["hasNextPage"]:
                raise SyncError("Closing PR evidence exceeds 100 items; manual review required")
            evidence[issue["number"]] = issue
    return evidence


def plan_updates(tracked, evidence, repository, target_branch, reopened_issue=None):
    """Complete merge-backed issues, preserving manual status except an explicit reopen."""
    updates = []
    for number, row in sorted(tracked.items()):
        issue = evidence[number]
        current = row["properties"][STATUS_PROPERTY].get("status")
        current_name = (current or {}).get("name")
        closing_prs = issue["closedByPullRequestsReferences"]["nodes"]
        merged = [pr for pr in closing_prs if pr.get("mergedAt")
                  and pr["repository"]["nameWithOwner"].lower() == repository.lower()
                  and pr["baseRefName"] == target_branch]
        if issue["state"] == "CLOSED" and issue.get("stateReason") == "COMPLETED" and merged:
            if current_name != DONE:
                pr = max(merged, key=lambda item: item["mergedAt"])
                updates.append({"number": number, "page": row["id"], "status": DONE,
                                "pr": pr["url"]})
        elif number == reopened_issue and issue["state"] == "OPEN" and current_name == DONE:
            # Only the actual reopened event may demote this row, not unrelated manual work.
            updates.append({"number": number, "page": row["id"], "status": IN_PROGRESS,
                            "pr": None})
    return updates


def reopened_issue_from_event(repository):
    """Return the single issue explicitly reopened in this repository's event."""
    if os.environ.get("GITHUB_EVENT_NAME") != "issues":
        return None
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    number = event.get("issue", {}).get("number")
    if (event.get("action") == "reopened"
            and event.get("repository", {}).get("full_name", "").lower() == repository.lower()
            and type(number) is int and number > 0):
        return number
    return None


def apply_updates(notion, updates, dry_run):
    for update in updates:
        if not dry_run:
            expected = update["status"]
            notion.request("PATCH", "/pages/" + notion_id(update["page"]),
                           {"properties": {STATUS_PROPERTY: {"status": {"name": expected}}}})
            # Read back independently; a successful request alone is not verification.
            saved = notion.request("GET", "/pages/" + notion_id(update["page"]))
            actual = saved["properties"][STATUS_PROPERTY].get("status") or {}
            if actual.get("name") != expected:
                raise SyncError(f"Notion issue #{update['number']} status verification failed")
        print(f"{'PREVIEW' if dry_run else 'SAVED'} #{update['number']} -> {update['status']}")


def run():
    token = os.environ.get("NOTION_TOKEN", "")
    if not token:
        raise SyncError("NOTION_TOKEN is missing. Add the repository Actions secret and share the 2차 page with that Notion connection.")
    gh_token = os.environ.get("GH_TOKEN", "")
    if not gh_token:
        raise SyncError("GH_TOKEN is missing")
    repository = os.environ.get("GITHUB_REPOSITORY", "feegachu/fgc")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise SyncError("Invalid GITHUB_REPOSITORY")
    dry_run = os.environ.get("NOTION_DRY_RUN", "true").lower() == "true"
    notion = API("https://api.notion.com/v1", token, {"Notion-Version": NOTION_VERSION})
    github = API("https://api.github.com", gh_token)
    source = resolve_source(notion, os.environ["NOTION_DATABASE_ID"],
                            os.environ.get("NOTION_DATA_SOURCE_ID", ""))
    tracked = tracked_rows(read_rows(notion, source), repository)
    evidence = read_evidence(github, repository, tracked)
    updates = plan_updates(tracked, evidence, repository, "develop", reopened_issue_from_event(repository))
    print(f"Tracked {len(tracked)} issues; planned {len(updates)} status changes")
    apply_updates(notion, updates, dry_run)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write("## Notion WBS sync\n\n")
            stream.write(f"Tracked issues: {len(tracked)}. {'Preview' if dry_run else 'Verified updates'}: {len(updates)}.\n\n")
            for update in updates:
                stream.write(f"- #{update['number']}: {update['status']}"
                             + (f" ([merged PR]({update['pr']}))" if update["pr"] else " (reopened)") + "\n")
            stream.write("\nWBS progress is calculated by the existing Notion rollup; it is never overwritten.\n")


if __name__ == "__main__":
    try:
        run()
    except (SyncError, KeyError, ValueError) as error:
        print(f"WBS sync failed: {error}", file=sys.stderr)
        sys.exit(1)
