import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from e2e_policy import ci_plan, classify, validate_ci_plan


class CISelectionTests(unittest.TestCase):
    def test_documentation_formats_skip_ci_and_e2e(self):
        paths = ["README.md", "docs/요구사항.CSV", "docs/spec.xlsx", "docs/design.docx",
                 "docs/slides.pptx", "docs/architecture.drawio", "docs/flow.mmd", "docs/model.puml"]
        plan = classify(paths)
        self.assertFalse(plan["ui"])
        self.assertFalse(plan["server"])
        self.assertEqual(ci_plan(plan), {"run_checks": False})

    def test_code_and_configuration_mixed_with_docs_run_ci(self):
        for path in ["src/main/java/Api.java", "frontend/src/Page.tsx", "build.gradle",
                     ".github/workflows/ci.yml", "Containerfile.web", "docs/setup.sh",
                     "docs/config.json", "docs/seed.sql", "requirements.txt", "unknown/file"]:
            with self.subTest(path=path):
                self.assertEqual(ci_plan(classify(["docs/README.md", path])), {"run_checks": True})

    def test_runtime_documentation_is_not_exempt(self):
        for path in ["frontend/public/help.md", "src/main/resources/static/help.pdf",
                     "src/main/resources/templates/guide.md", "src/test/resources/fixtures.csv"]:
            with self.subTest(path=path):
                self.assertTrue(ci_plan(classify([path]))["run_checks"])


class CIRequiredCheckTests(unittest.TestCase):
    def needs(self, result="success", flag="false"):
        return {"changes": {"result": result, "outputs": {"run_checks": flag}}}

    def test_valid_documentation_and_code_plans(self):
        self.assertFalse(validate_ci_plan(self.needs(flag="false")))
        self.assertTrue(validate_ci_plan(self.needs(flag="true")))

    def test_failed_cancelled_skipped_detection_blocks_required_jobs(self):
        for result in ["failure", "cancelled", "skipped", None]:
            for flag in ["true", "false"]:
                with self.subTest(result=result, flag=flag), self.assertRaises(ValueError):
                    validate_ci_plan(self.needs(result=result, flag=flag))

    def test_missing_or_malformed_outputs_block_required_jobs(self):
        for flag in [None, "", "yes", True, False]:
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                validate_ci_plan(self.needs(flag=flag))
        with self.assertRaises(ValueError):
            validate_ci_plan({})


class CICommandIntegrationTests(unittest.TestCase):
    def test_real_git_pr_and_push_select_docs_and_then_mixed_changes(self):
        policy_script = Path(__file__).with_name("e2e_policy.py").resolve()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args):
                return subprocess.check_output(["git", "-C", directory, *args]).decode().strip()

            git("init", "-q")
            git("config", "user.name", "CI policy test")
            git("config", "user.email", "test@example.invalid")
            (root / "README.md").write_text("base", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "base")
            base = git("rev-parse", "HEAD")
            for relative_path, expected in [("docs/requirements.CSV", "false"), ("frontend/src/app.ts", "true")]:
                target = root / relative_path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("change", encoding="utf-8")
                git("add", relative_path)
                git("commit", "-qm", "change")
                head = git("rev-parse", "HEAD")
                events = {
                    "push": {"before": base, "after": head},
                    "pull_request": {"pull_request": {"base": {"sha": base}, "head": {"sha": head}}},
                }
                for event_name, event in events.items():
                    event_file, output_file, summary_file = [root / name for name in ("event.json", "output", "summary")]
                    event_file.write_text(json.dumps(event), encoding="utf-8")
                    output_file.write_text("", encoding="utf-8")
                    env = dict(os.environ, GITHUB_EVENT_PATH=str(event_file), GITHUB_OUTPUT=str(output_file),
                               GITHUB_STEP_SUMMARY=str(summary_file), GITHUB_EVENT_NAME=event_name)
                    subprocess.run([sys.executable, "-B", str(policy_script), "ci-plan"], cwd=root, env=env,
                                   check=True, capture_output=True)
                    with self.subTest(path=relative_path, event=event_name):
                        self.assertEqual(output_file.read_text().strip(), f"run_checks={expected}")


if __name__ == "__main__":
    unittest.main()
