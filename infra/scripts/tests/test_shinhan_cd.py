import importlib.util
import io
import json
import tarfile
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


release = load("release", "shinhan_release.py")
SHA = "a" * 40
API = f"{release.REGISTRY}/api@sha256:" + "b" * 64
WEB = f"{release.REGISTRY}/web@sha256:" + "c" * 64


def manifest():
    return {"schema": 1, "commit": SHA, "api": API, "web": WEB,
            "ci_build_url": "http://jenkins/job/dev-fgc/62/"}


def promotion():
    return {"schema": 1, "source_job": "stage-fgc", "source_build": 12,
            "source_build_url": "http://jenkins/job/stage-fgc/12/",
            "bundle_sha256": "d" * 64, "release": manifest()}


class PromotionTests(unittest.TestCase):
    def bundle(self, *, bundled=None, duplicate=False, symlink=False):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = pathlib.Path(directory.name)
        archive = path / "bundle.tgz"
        (path / "release.json").write_text(json.dumps(manifest()), encoding="utf-8")
        with tarfile.open(archive, "w:gz") as tar:
            data = json.dumps(bundled or manifest()).encode()
            member = tarfile.TarInfo("release.json")
            member.size = len(data)
            if symlink:
                member.type = tarfile.SYMTYPE
                member.linkname = "/etc/passwd"
                member.size = 0
                tar.addfile(member)
            else:
                tar.addfile(member, io.BytesIO(data))
                if duplicate:
                    tar.addfile(member, io.BytesIO(data))
            # The production job must not extract or run any archived scripts.
            script = tarfile.TarInfo("../../unexpected-script")
            script.size = 4
            tar.addfile(script, io.BytesIO(b"evil"))
        return path

    def test_selected_staging_bundle_preserves_old_commit_and_exact_digests(self):
        path = self.bundle()
        value = release.stage_promotion(path / "bundle.tgz", path / "release.json", "12", "http://jenkins/")
        self.assertEqual(value["release"], manifest())
        self.assertEqual(value["source_job"], "stage-fgc")
        self.assertEqual(value["source_build"], 12)
        self.assertEqual(len(list(path.iterdir())), 2)

    def test_mismatched_or_ambiguous_bundle_rejected(self):
        variants = [{"bundled": manifest() | {"api": f"{release.REGISTRY}/api@sha256:" + "e" * 64}},
                    {"duplicate": True}, {"symlink": True}]
        for variant in variants:
            with self.subTest(variant=variant):
                path = self.bundle(**variant)
                with self.assertRaises(ValueError):
                    release.stage_promotion(path / "bundle.tgz", path / "release.json", "12", "http://jenkins")

    def test_tags_and_wrong_registry_cannot_replace_verified_digest(self):
        for image in (f"{release.REGISTRY}/api:latest", "registry.example/api@sha256:" + "b" * 64):
            with self.subTest(image=image), self.assertRaises(ValueError):
                release.validate_release(manifest() | {"api": image})

    def test_dev_source_or_missing_staging_build_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "promotion.json"
            for change in ({"source_job": "dev-fgc"}, {"source_build": 0}, {"source_build": "12"}):
                path.write_text(json.dumps(promotion() | change))
                with self.subTest(change=change), self.assertRaises(ValueError):
                    release.read_promotion(path)

    def test_invalid_source_build_rejected(self):
        path = self.bundle()
        for build in ("", "0", "latest", "12;echo x"):
            with self.subTest(build=build), self.assertRaises(ValueError):
                release.stage_promotion(path / "bundle.tgz", path / "release.json", build, "http://jenkins")


class ReleaseTests(unittest.TestCase):
    def info(self):
        return {"Config": {"Labels": {"org.opencontainers.image.revision": SHA}},
                "RepoDigests": [f"{release.REGISTRY}/api@sha256:" + "b" * 64]}

    def test_immutable_reference_for_matching_revision(self):
        self.assertIsNone(release.verify_image(self.info(), "api", manifest()))

    def test_revision_mismatch_and_unexpected_registry_rejected(self):
        with self.assertRaises(ValueError):
            release.verify_image(self.info(), "api", manifest() | {"commit": "c" * 40})
        info = self.info()
        info["RepoDigests"] = ["registry.example/api@sha256:" + "b" * 64]
        with self.assertRaises(ValueError):
            release.verify_image(info, "api", manifest())

    def test_https_headers_and_spa_paths_preserved(self):
        template = (ROOT.parents[1] / "nginx/default.conf.template").read_text(encoding="utf-8")
        actual = release.adapt_nginx(template)
        self.assertIn("X-Forwarded-Proto https;", actual)
        self.assertIn("X-Forwarded-Port 443;", actual)
        self.assertIn("location ^~ /app/assets/", actual)
        self.assertIn("proxy_set_header X-Forwarded-For", actual)

    def test_public_origin_normalization_and_invalid_values(self):
        self.assertEqual(release.public_origin("https://example.com:443/"), ("https://example.com", "example.com"))
        for value in ("http://example.com", "https://user:pass@example.com", "https://example.com/app", "https://example.com:8080", "https://xn--v42bn2m21nmmd.shinhanacademy.co.kr"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                release.public_origin(value)

    def test_changed_image_template_fails_instead_of_silent_misconfiguration(self):
        with self.assertRaises(ValueError):
            release.adapt_nginx("server { listen 80; }")


class DeploymentTests(unittest.TestCase):
    def deploy(self, *, bad_revision=False, bad_digest=False, backup_failure=False, api_failure=False, public_failure=False):
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
        promotion_path = base / "promotion.json"
        promotion_path.write_text(json.dumps(promotion()), encoding="utf-8")
        bin_dir = base / "bin"
        bin_dir.mkdir()
        fixtures = base / "fixtures"
        fixtures.mkdir()
        for name in ("api", "web"):
            info = {"Config": {"Labels": {"org.opencontainers.image.revision":
                    "b" * 40 if bad_revision else SHA}},
                    "RepoDigests": [f"{release.REGISTRY}/{name}@sha256:" + "e" * 64 if bad_digest else manifest()[name]]}
            (fixtures / f"{name}.json").write_text(json.dumps([info]))
        shutil.copyfile(ROOT.parents[1] / "nginx/default.conf.template", fixtures / "nginx")
        commands = {
            "docker": r'''#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$DEPLOY_LOG"
if [[ "$1" == pull ]]; then exit 0; fi
if [[ "$1" == image ]]; then
  if [[ "$*" == *'/api@'*  ]]; then cat "$FIXTURES/api.json"; else cat "$FIXTURES/web.json"; fi
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
if [[ "$*" == *'https://example.com/app/login'* && "$PUBLIC_FAILURE" == 1 ]]; then printf 503
elif [[ "$*" == *'/actuator/health'* ]]; then
  [[ "$API_FAILURE" == 0 ]] || exit 7
  printf '{"status":"UP"}'
elif [[ "$*" == *'/api/v1/auth/login'* ]]; then printf 401
else printf 200
fi
''',
            "sleep": "#!/usr/bin/env bash\nexit 0\n",
            "seq": "#!/usr/bin/env bash\nprintf '1\\n'\n",
            "flock": "#!/usr/bin/env bash\nexit 0\n",
            "python3": "#!/usr/bin/env bash\nset -o pipefail\n'" + pathlib.Path(sys.executable).as_posix() + "' \"$@\" | sed 's/\\r$//'\n",
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
                            "PUBLIC_FAILURE": str(int(public_failure)),
                            "PYTHONUTF8": "1", "FGC_PUBLIC_URL": "https://example.com", "TEST_BIN": bin_dir.as_posix(), "TEST_HOME": home.as_posix()}
        bootstrap = 'if command -v cygpath >/dev/null; then export PATH="$(cygpath -u "$TEST_BIN"):/usr/bin:/bin"; export HOME="$(cygpath -u "$TEST_HOME")"; else export PATH="$TEST_BIN:/usr/bin:/bin"; fi; exec bash "$@"'
        result = subprocess.run([bash, "-c", bootstrap, "test", str(ROOT / "deploy-shinhan.sh"), str(promotion_path)],
                                env=env, capture_output=True, text=True, encoding="utf-8", timeout=60)
        log = (base / "commands").read_text() if (base / "commands").exists() else ""
        self.assertEqual((state / ".env").read_text(), "DB_PASSWORD=preserve-me\nJWT_SECRET=preserve-this-key\n")
        return result, state, log

    def test_success_preserves_secrets_and_backs_up_before_upgrade(self):
        result, state, log = self.deploy()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads((state / "current.json").read_text()), manifest())
        self.assertEqual(json.loads((state / "current-promotion.json").read_text())["source_build"], 12)
        pulls = [line for line in log.splitlines() if line.startswith("pull ")]
        self.assertEqual(pulls, ["pull " + API, "pull " + WEB])
        self.assertIn("SPRING_PROFILES_ACTIVE: prod", (state / "compose.yaml").read_text())
        self.assertEqual(len(list((state / "backups").glob("*.dump"))), 1)
        self.assertLess(log.index("pg_dump"), log.index("up -d db api"))
        self.assertLess(log.index("nginx -t"), log.index("up -d --force-recreate"))

    def test_wrong_revision_never_mutates_deployment(self):
        result, state, log = self.deploy(bad_revision=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((state / "compose.yaml").read_text(), "old compose")
        self.assertNotIn("pg_dump", log)
        self.assertNotIn("up -d", log)

    def test_wrong_digest_never_mutates_deployment(self):
        result, state, log = self.deploy(bad_digest=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((state / "compose.yaml").read_text(), "old compose")
        self.assertNotIn("pg_dump", log)
        self.assertNotIn("up -d", log)

    def test_public_https_failure_is_not_recorded_as_success(self):
        result, state, log = self.deploy(public_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((state / "current.json").exists())

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
