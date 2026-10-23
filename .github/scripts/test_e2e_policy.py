from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from e2e_policy import changed_paths, classify, documentation_only_since, plan_for_event, validate_gate


class SelectionTests(unittest.TestCase):
    def test_documentation_only_skips_suites(self):
        self.assertEqual(classify(["README.md", "docs/설계/설명.md", "docs/diagram.png"]),
                         {"ui": False, "server": False, "stability": False})

    def test_backend_only_runs_real_server(self):
        for path in ["src/main/java/Api.java", "src/test/java/ApiTest.java",
                     "src/main/resources/db/migration/V100__change.sql", "src/main/resources/application.yml",
                     "gradle/libs.versions.toml", "build.gradle", "gradlew.bat"]:
            with self.subTest(path=path):
                self.assertEqual(classify(["README.md", path]),
                                 {"ui": False, "server": True, "stability": False})

    def test_frontend_and_unknown_paths_run_both(self):
        for path in ["frontend/src/page.tsx", "frontend/package-lock.json", "frontend/public/help.md",
                     "src/main/resources/templates/reco/list.html", "src/main/resources/static/css/reco.css",
                     ".github/workflows/frontend-e2e.yml", ".github/scripts/e2e_policy.py",
                     "Containerfile.api", "nginx/default.conf.template", "requirements.txt", "docs/setup.sh", "unknown/file"]:
            with self.subTest(path=path):
                self.assertEqual(classify([path]), {"ui": True, "server": True, "stability": False})

    def test_mixed_changes_keep_all_affected_suites(self):
        self.assertTrue(classify(["src/main/java/Api.java", "frontend/src/page.tsx"])["ui"])

    def test_only_schedule_repeats_three_times(self):
        self.assertEqual(plan_for_event("schedule", {}), {"ui": True, "server": True, "stability": True})
        self.assertEqual(plan_for_event("workflow_dispatch", {}), {"ui": True, "server": True, "stability": False})

    def test_pr_uses_whole_pr_diff(self):
        with patch("e2e_policy.changed_paths", return_value=["src/main/java/Api.java"]) as diff:
            result = plan_for_event("pull_request", {"pull_request": {
                "base": {"sha": "a" * 40}, "head": {"sha": "b" * 40}}})
        diff.assert_called_once_with("a" * 40, "b" * 40, merge_base=True)
        self.assertEqual(result, {"ui": False, "server": True, "stability": False})

    def test_push_uses_before_and_after(self):
        with patch("e2e_policy.commit_available", return_value=True), \
                patch("e2e_policy.changed_paths", return_value=["README.md"]) as diff:
            self.assertFalse(plan_for_event("push", {"before": "a" * 40, "after": "b" * 40})["server"])
        diff.assert_called_once_with("a" * 40, "b" * 40)

    def test_new_branch_runs_all(self):
        self.assertEqual(plan_for_event("push", {"before": "0" * 40, "after": "b" * 40}),
                         {"ui": True, "server": True, "stability": False})

    def test_unavailable_push_base_runs_all(self):
        with patch("e2e_policy.commit_available", return_value=False), \
                patch("e2e_policy.changed_paths") as diff:
            self.assertEqual(plan_for_event("push", {"before": "a" * 40, "after": "b" * 40}),
                             {"ui": True, "server": True, "stability": False})
            diff.assert_not_called()

    def test_unknown_event_and_failed_diff_do_not_skip(self):
        with self.assertRaises(ValueError):
            plan_for_event("unexpected", {})
        with patch("e2e_policy.commit_available", return_value=True), \
                patch("e2e_policy.changed_paths", side_effect=RuntimeError("git failed")):
            with self.assertRaises(RuntimeError):
                plan_for_event("push", {"before": "a" * 40, "after": "b" * 40})


class GateTests(unittest.TestCase):
    def needs(self, ui=True, server=True):
        return {
            "changes": {"result": "success", "outputs": {
                "ui": str(ui).lower(), "server": str(server).lower(), "stability": "false"}},
            "ui": {"result": "success" if ui else "skipped"},
            "negative": {"result": "success" if ui else "skipped"},
            "real-server": {"result": "success" if server else "skipped"},
        }

    def test_all_backend_only_and_docs_only_pass(self):
        for ui, server in [(True, True), (False, True), (False, False)]:
            validate_gate(self.needs(ui, server))

    def test_failed_cancelled_or_skipped_required_job_blocks(self):
        for job in ["ui", "negative", "real-server", "changes"]:
            for result in ["failure", "cancelled", "skipped", None]:
                needs = self.needs()
                needs[job]["result"] = result
                with self.subTest(job=job, result=result), self.assertRaises(ValueError):
                    validate_gate(needs)

    def test_docs_only_does_not_hide_failed_detection_or_jobs(self):
        for job in ["changes", "ui", "negative", "real-server"]:
            needs = self.needs(False, False)
            needs[job]["result"] = "failure"
            with self.subTest(job=job), self.assertRaises(ValueError):
                validate_gate(needs)

    def test_missing_or_malformed_output_blocks(self):
        for flag in ["ui", "server", "stability"]:
            for value in [None, "", "yes", True]:
                needs = self.needs(False, False)
                needs["changes"]["outputs"][flag] = value
                with self.subTest(flag=flag, value=value), self.assertRaises(ValueError):
                    validate_gate(needs)

    def test_missing_job_blocks(self):
        needs = self.needs()
        del needs["real-server"]
        with self.assertRaises(ValueError):
            validate_gate(needs)

    def test_scheduled_plan_requires_server_and_accepts_success(self):
        needs = self.needs()
        needs["changes"]["outputs"]["stability"] = "true"
        validate_gate(needs)
        needs["changes"]["outputs"]["server"] = "false"
        with self.assertRaises(ValueError):
            validate_gate(needs)


class GitDiffTests(unittest.TestCase):
    def test_renamed_and_deleted_paths_are_not_hidden_as_docs(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args]).decode().strip()
            git("init", "-q")
            git("config", "user.name", "E2E test")
            git("config", "user.email", "test@example.invalid")
            root = Path(directory)
            (root / "frontend").mkdir()
            (root / "frontend/old.ts").write_text("same content", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            (root / "frontend/old.ts").rename(root / "README.md")
            git("add", "-A")
            git("commit", "-qm", "rename source to documentation")
            head = git("rev-parse", "HEAD")
            from e2e_policy import git as real_git
            with patch("e2e_policy.git", side_effect=lambda *args: real_git("-C", directory, *args)):
                paths = changed_paths(base, head, merge_base=True)
            self.assertCountEqual(paths, ["frontend/old.ts", "README.md"])
            self.assertTrue(classify(paths)["ui"])


class DocumentationOnlySinceTests(unittest.TestCase):
    """Jenkins dev-fgc: 문서 전용 push 는 직전 이미지를 재사용하므로 라벨 커밋이 HEAD 의 문서 전용 조상인지 확인한다."""

    def test_accepts_only_documentation_descendants(self):
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args]).decode().strip()
            git("init", "-q", "-b", "main")
            git("config", "user.name", "E2E test")
            git("config", "user.email", "test@example.invalid")
            root = Path(directory)
            (root / "src").mkdir()
            (root / "docs").mkdir()
            (root / "src/App.java").write_text("v1", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "image build")
            built = git("rev-parse", "HEAD")
            (root / "docs/policy.md").write_text("doc", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "docs only")
            docs = git("rev-parse", "HEAD")
            (root / "src/App.java").write_text("v2", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "code")
            code = git("rev-parse", "HEAD")
            git("switch", "-q", "-c", "other", built)
            (root / "docs").mkdir(exist_ok=True)
            (root / "docs/other.md").write_text("doc", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "sibling docs")
            sibling = git("rev-parse", "HEAD")
            from e2e_policy import git as real_git
            with patch("e2e_policy.git", side_effect=lambda *args: real_git("-C", directory, *args)):
                self.assertTrue(documentation_only_since(built, built))
                self.assertTrue(documentation_only_since(built, docs))
                self.assertFalse(documentation_only_since(built, code))
                self.assertFalse(documentation_only_since(docs, built))
                self.assertFalse(documentation_only_since(sibling, docs))
                with self.assertRaises(ValueError):
                    documentation_only_since(None, docs)


if __name__ == "__main__":
    unittest.main()
