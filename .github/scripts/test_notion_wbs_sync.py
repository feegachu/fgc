import copy
import io
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from notion_wbs_sync import (API, DONE, IN_PROGRESS, SyncError, apply_updates,
                             plan_updates, read_evidence, read_rows, resolve_source,
                             tracked_rows)


SOURCE = "4022f20a-c4dd-801d-b1c6-cf502f956f76"
DATABASE = "37b2f20a-c4dd-8210-b93b-01c8ea93ec5c"
REPO = "feegachu/fgc"


def row(number=632, status="시작 전"):
    return {"id": SOURCE, "properties": {
        "GitHub 이슈": {"url": f"https://github.com/{REPO}/issues/{number}"},
        "상태": {"status": {"name": status}},
        "WBS": {"relation": [{"id": "gayo-wbs"}]}}}


def issue(number=632, state="CLOSED", reason="COMPLETED", merged=True):
    return {"number": number, "state": state, "stateReason": reason,
            "closedByPullRequestsReferences": {"pageInfo": {"hasNextPage": False}, "nodes": [{
                "number": 684, "url": f"https://github.com/{REPO}/pull/684",
                "mergedAt": "2026-10-23T01:20:34Z" if merged else None,
                "state": "MERGED" if merged else "OPEN", "baseRefName": "develop",
                "repository": {"nameWithOwner": REPO}}]}}


class FakeAPI:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def request(self, *args):
        self.calls.append(args)
        return self.responses.pop(0)


class CompletionTests(unittest.TestCase):
    def plan(self, data, status="시작 전"):
        return plan_updates({632: row(status=status)}, {632: data}, REPO, "develop")

    def test_merged_684_completes_632_and_repeat_is_idempotent(self):
        changes = self.plan(issue())
        self.assertEqual([(x["number"], x["status"]) for x in changes], [(632, DONE)])
        self.assertEqual(self.plan(issue(), DONE), [])

    def test_open_540_does_not_complete_534(self):
        data = issue(number=534, state="OPEN", reason=None, merged=False)
        self.assertEqual(plan_updates({534: row(534)}, {534: data}, REPO, "develop"), [])

    def test_closed_without_merged_closing_pr_is_not_completion(self):
        for data in [issue(merged=False), issue(reason="NOT_PLANNED")]:
            self.assertEqual(self.plan(data), [])
        data = issue()
        data["closedByPullRequestsReferences"]["nodes"] = []
        self.assertEqual(self.plan(data), [])

    def test_doc_only_mentions_and_other_fun_ids_are_not_completion_evidence(self):
        data = issue()
        data["body"] = "Merged docs PR #686 mentions #536 and FUN-014"
        data["closedByPullRequestsReferences"]["nodes"] = []
        self.assertEqual(self.plan(data), [])

    def test_wrong_repository_or_non_delivery_branch_is_not_completion(self):
        for key, value in [("baseRefName", "feature/integration"),
                           ("repository", {"nameWithOwner": "other/fgc"})]:
            data = issue()
            data["closedByPullRequestsReferences"]["nodes"][0][key] = value
            self.assertEqual(self.plan(data), [])

    def test_reopened_issue_stops_counting_as_complete(self):
        self.assertEqual(self.plan(issue(state="OPEN", reason="REOPENED"), DONE)[0]["status"],
                         IN_PROGRESS)
        self.assertEqual(self.plan(issue(state="OPEN", reason=None), IN_PROGRESS), [])

    def test_combined_fun_row_is_one_issue_not_two_requirements(self):
        tracked = {679: row(679)}
        tracked[679]["properties"]["이슈 ID"] = {"rich_text": [{"plain_text": "FUN-058·059"}]}
        changes = plan_updates(tracked, {679: issue(679)}, REPO, "develop")
        self.assertEqual(len(changes), 1)


class MappingTests(unittest.TestCase):
    def test_exact_issue_url_only_and_duplicates_fail_before_writes(self):
        self.assertEqual(list(tracked_rows([row()], REPO)), [632])
        for suffix in ["/pull/632", "/issues/632?ref=other", "/issues/6320/evil"]:
            bad = row()
            bad["properties"]["GitHub 이슈"]["url"] = "https://github.com/" + REPO + suffix
            with self.subTest(suffix=suffix), self.assertRaises(SyncError):
                tracked_rows([bad], REPO)
        with self.assertRaises(SyncError):
            tracked_rows([row(), row()], REPO)

    def test_notion_pagination_and_archived_rows(self):
        archived = row(534)
        archived["in_trash"] = True
        api = FakeAPI({"results": [row(), archived], "has_more": True, "next_cursor": "next"},
                      {"results": [row(538)], "has_more": False})
        self.assertEqual(len(read_rows(api, SOURCE)), 2)
        self.assertEqual(api.calls[1][2]["start_cursor"], "next")

    def test_source_is_discovered_and_complete_group_is_validated(self):
        schema = {"properties": {"GitHub 이슈": {"type": "url"}, "상태": {
            "type": "status", "status": {
                "options": [{"name": DONE, "id": "done"}, {"name": IN_PROGRESS, "id": "doing"}],
                "groups": [{"name": "Complete", "option_ids": ["done"]}]}}}}
        database = {"data_sources": [{"id": SOURCE}]}
        self.assertEqual(resolve_source(FakeAPI(database, schema), DATABASE), SOURCE)
        bad = copy.deepcopy(schema)
        bad["properties"]["상태"]["status"]["groups"] = []
        with self.assertRaises(SyncError):
            resolve_source(FakeAPI(database, bad), DATABASE)
        with self.assertRaises(SyncError):
            resolve_source(FakeAPI(database), DATABASE, DATABASE)

    def test_graphql_errors_or_truncated_evidence_cannot_complete_rows(self):
        with self.assertRaises(SyncError):
            read_evidence(FakeAPI({"errors": [{"message": "denied"}]}), REPO, [632])
        data = issue()
        data["closedByPullRequestsReferences"]["pageInfo"]["hasNextPage"] = True
        with self.assertRaises(SyncError):
            read_evidence(FakeAPI({"data": {"repository": {"i632": data}}}), REPO, [632])


class WriteTests(unittest.TestCase):
    def setUp(self):
        self.updates = plan_updates({632: row()}, {632: issue()}, REPO, "develop")

    def test_preview_makes_no_mutations(self):
        api = FakeAPI()
        apply_updates(api, self.updates, True)
        self.assertEqual(api.calls, [])

    def test_status_only_patch_preserves_owner_relation_and_body(self):
        api = FakeAPI({}, row(status=DONE))
        apply_updates(api, self.updates, False)
        self.assertEqual(api.calls, [
            ("PATCH", "/pages/" + SOURCE, {"properties": {"상태": {"status": {"name": DONE}}}}),
            ("GET", "/pages/" + SOURCE)])

    def test_read_back_mismatch_fails(self):
        with self.assertRaises(SyncError):
            apply_updates(FakeAPI({}, row()), self.updates, False)

    def test_rate_limit_retry_and_credentials_are_not_logged(self):
        error = HTTPError("https://api.notion.com/v1/pages/test", 429, "limited",
                          {"Retry-After": "2"}, None)
        result = io.BytesIO(json.dumps({"ok": True}).encode())
        api = API("https://api.notion.com/v1", "secret-token", sleep=lambda _: None)
        with patch("notion_wbs_sync.urlopen", side_effect=[error, result]) as call:
            self.assertEqual(api.request("GET", "/pages/test"), {"ok": True})
            self.assertEqual(call.call_count, 2)
        forbidden = HTTPError("https://api.notion.com/v1/pages/test", 403, "denied", {}, None)
        with patch("notion_wbs_sync.urlopen", side_effect=forbidden):
            with self.assertRaises(SyncError) as context:
                api.request("GET", "/pages/test")
        self.assertNotIn("secret-token", str(context.exception))


if __name__ == "__main__":
    unittest.main()
