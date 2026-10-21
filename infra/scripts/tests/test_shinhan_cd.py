import importlib.util
import pathlib
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


source = load("source", "resolve-shinhan-ci.py")
release = load("release", "shinhan-release.py")
SHA = "a" * 40


class SourceTests(unittest.TestCase):
    def run_info(self):
        return {"path": ".github/workflows/ci.yml", "event": "push",
                "head_branch": "develop", "status": "completed", "conclusion": "success",
                "head_repository": {"full_name": "feegachu/fgc"}, "head_sha": SHA}

    def test_successful_current_commit(self):
        self.assertTrue(source.validate_source(self.run_info(), "feegachu/fgc", SHA))

    def test_stale_completion_cannot_redeploy_old_commit(self):
        self.assertFalse(source.validate_source(self.run_info(), "feegachu/fgc", "b" * 40))

    def test_untrusted_or_unsuccessful_runs_rejected(self):
        changes = [{"event": "pull_request"}, {"head_branch": "main"},
                   {"head_repository": {"full_name": "fork/fgc"}},
                   {"conclusion": "failure"}, {"status": "in_progress"},
                   {"path": ".github/workflows/other.yml"}, {"head_sha": "bad"}]
        for change in changes:
            with self.subTest(change=change):
                info = self.run_info() | change
                with self.assertRaises(ValueError):
                    source.validate_source(info, "feegachu/fgc", SHA)


class ReleaseTests(unittest.TestCase):
    def info(self):
        return {"Config": {"Labels": {"org.opencontainers.image.revision": SHA}},
                "RepoDigests": [f"{release.REGISTRY}/api@sha256:" + "b" * 64]}

    def test_immutable_reference_for_matching_revision(self):
        self.assertEqual(release.image_reference(self.info(), "api", SHA), self.info()["RepoDigests"][0])

    def test_revision_mismatch_and_unexpected_registry_rejected(self):
        with self.assertRaises(ValueError):
            release.image_reference(self.info(), "api", "c" * 40)
        info = self.info()
        info["RepoDigests"] = ["registry.example/api@sha256:" + "b" * 64]
        with self.assertRaises(ValueError):
            release.image_reference(info, "api", SHA)

    def test_https_headers_and_spa_paths_preserved(self):
        template = (ROOT.parents[1] / "nginx/default.conf.template").read_text(encoding="utf-8")
        actual = release.adapt_nginx(template)
        self.assertIn("X-Forwarded-Proto https;", actual)
        self.assertIn("X-Forwarded-Port 443;", actual)
        self.assertIn("location ^~ /app/assets/", actual)
        self.assertIn("proxy_set_header X-Forwarded-For", actual)

    def test_public_origin_normalization_and_invalid_values(self):
        self.assertEqual(release.public_origin("https://example.com:443/"), ("https://example.com", "example.com"))
        for value in ("http://example.com", "https://user:pass@example.com", "https://example.com/app", "https://example.com:8080"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.public_origin(value)

    def test_changed_image_template_fails_instead_of_silent_misconfiguration(self):
        with self.assertRaises(ValueError):
            release.adapt_nginx("server { listen 80; }")


class DeploymentTests(unittest.TestCase):
    def deploy(self, *, bad_revision=False, backup_failure=False, api_failure=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        base = pathlib.Path(directory.name)
        home = base / "home"
        state = home / "fgc-stg"
        (state / "nginx").mkdir(parents=True)
        (state / ".env").write_text("DB_PASSWORD=preserve-me\nJWT_SECRET=preserve-this-key\n")
        (state / "compose.yaml").write_text("old compose")
        (state / "nginx/default.conf.template").write_text("old template")
        (state / "nginx/guard.conf").write_text("old guard")
        bin_dir = base / "bin"
        bin_dir.mkdir()
        fixtures = base / "fixtures"
        fixtures.mkdir()
        for name in ("api", "web"):
            import json
            info = {"Config": {"Labels": {"org.opencontainers.image.revision":
                    "b" * 40 if bad_revision else SHA}},
                    "RepoDigests": [f"{release.REGISTRY}/{name}@sha256:" + "c" * 64]}
            (fixtures / f"{name}.json").write_text(json.dumps([info]))
        shutil.copyfile(ROOT.parents[1] / "nginx/default.conf.template", fixtures / "nginx")
        commands = {
            "docker": r'''#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$DEPLOY_LOG"
if [[ "$1" == pull ]]; then exit 0; fi
if [[ "$1" == image ]]; then
  if [[ "$*" == *'/api:'* ]]; then cat "$FIXTURES/api.json"; else cat "$FIXTURES/web.json"; fi
  exit 0
fi
if [[ "$1" == run ]]; then cat "$FIXTURES/nginx"; exit 0; fi
if [[ "$*" == *'exec -T db pg_dump'* ]]; then
  [[ "$BACKUP_FAILURE" == 0 ]] || exit 9
  printf 'database backup'; exit 0
fi
exit 0
''',
            "curl": r'''#!/usr/bin/env bash
if [[ "$*" == *'/actuator/health'* ]]; then
  [[ "$API_FAILURE" == 0 ]] || exit 7
  printf '{"status":"UP"}'
elif [[ "$*" == *'/api/v1/auth/login'* ]]; then printf 401
else printf 200
fi
''',
            "sleep": "#!/usr/bin/env bash\nexit 0\n",
            "seq": "#!/usr/bin/env bash\nprintf '1\\n'\n",
            "flock": "#!/usr/bin/env bash\nexit 0\n",
            "python3": "#!/usr/bin/env bash\nexec '" + pathlib.Path(sys.executable).as_posix() + "' \"$@\"\n",
        }
        for name, content in commands.items():
            path = bin_dir / name
            path.write_text(content, newline="\n")
            path.chmod(0o755)
        bash = shutil.which("bash")
        if os.name == "nt":
            bash = r"C:/Program Files/Git/bin/bash.exe"
        env = os.environ | {"HOME": home.as_posix(), "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                            "FIXTURES": fixtures.as_posix(), "DEPLOY_LOG": (base / "commands").as_posix(),
                            "BACKUP_FAILURE": str(int(backup_failure)), "API_FAILURE": str(int(api_failure)),
                            "PYTHONUTF8": "1", "TEST_BIN": bin_dir.as_posix(), "TEST_HOME": home.as_posix()}
        bootstrap = 'if command -v cygpath >/dev/null; then export PATH="$(cygpath -u "$TEST_BIN"):/usr/bin:/bin"; export HOME="$(cygpath -u "$TEST_HOME")"; else export PATH="$TEST_BIN:/usr/bin:/bin"; fi; exec bash "$@"'
        result = subprocess.run([bash, "-c", bootstrap, "test", str(ROOT / "deploy-shinhan.sh"), SHA,
                                 "https://github.com/feegachu/fgc/actions/runs/123"],
                                env=env, capture_output=True, text=True, encoding="utf-8", timeout=60)
        log = (base / "commands").read_text() if (base / "commands").exists() else ""
        self.assertEqual((state / ".env").read_text(), "DB_PASSWORD=preserve-me\nJWT_SECRET=preserve-this-key\n")
        return result, state, log

    def test_success_preserves_secrets_and_backs_up_before_upgrade(self):
        result, state, log = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((state / "current.json").exists())
        self.assertEqual(len(list((state / "backups").glob("*.dump"))), 1)
        self.assertLess(log.index("pg_dump"), log.index("up -d db api"))
        self.assertLess(log.index("nginx -t"), log.index("up -d --force-recreate"))

    def test_wrong_revision_never_mutates_deployment(self):
        result, state, log = self.deploy(bad_revision=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((state / "compose.yaml").read_text(), "old compose")
        self.assertNotIn("pg_dump", log)
        self.assertNotIn("up -d", log)

    def test_backup_failure_aborts_before_config_or_container_changes(self):
        result, state, log = self.deploy(backup_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((state / "compose.yaml").read_text(), "old compose")
        self.assertNotIn("up -d", log)

    def test_api_failure_does_not_upgrade_web_or_mark_success(self):
        result, state, log = self.deploy(api_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("up -d --force-recreate", log)
        self.assertFalse((state / "current.json").exists())


if __name__ == "__main__":
    unittest.main()
