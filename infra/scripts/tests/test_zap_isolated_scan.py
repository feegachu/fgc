import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "zap-isolated-scan.sh"
# Windows 의 subprocess 는 PATH 보다 System32 를 먼저 찾아 WSL bash 에 걸린다. test_shinhan_cd.py 와 같이 Git Bash 를 지정한다.
BASH = r"C:/Program Files/Git/bin/bash.exe" if os.name == "nt" else shutil.which("bash")


class ZapTargetGuard(unittest.TestCase):
    """공유 서버에 스캔이 나가면 안 된다. 거부는 docker 를 부르기 전에 일어나야 한다."""

    def run_script(self, target, command="baseline"):
        with tempfile.TemporaryDirectory() as stub_dir:
            marker = pathlib.Path(stub_dir) / "docker-called"
            stub = pathlib.Path(stub_dir) / "docker"
            stub.write_text(f"#!/bin/sh\ntouch '{marker.as_posix()}'\n", encoding="utf-8")
            stub.chmod(0o755)
            env = {**os.environ, "ZAP_TARGET": target, "PATH": f"{stub_dir}{os.pathsep}{os.environ['PATH']}"}
            result = subprocess.run([BASH, str(SCRIPT), command], env=env, capture_output=True,
                                    text=True, encoding="utf-8", stdin=subprocess.DEVNULL, timeout=30)
            return result, marker.exists()

    def test_refuses_anything_but_loopback(self):
        for target in ("http://example.com:8080", "https://127.0.0.1:18770", "http://10.0.0.5:80",
                       "http://localhost@evil.example:80", "http://127.0.0.1.evil.example:80",
                       "http://127.0.0.1:80/@evil.example", "http://evil.example/#127.0.0.1:80",
                       "http://127.0.0.1:80\nhttp://evil.example:80"):
            with self.subTest(target=target):
                result, docker_called = self.run_script(target)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertFalse(docker_called)

    def test_loopback_passes_the_guard(self):
        for target in ("http://127.0.0.1:18770", "http://localhost:18770"):
            with self.subTest(target=target):
                # 없는 명령은 대상 검사를 지난 뒤 사용법으로 끝난다.
                result, _ = self.run_script(target, command="unknown")
                self.assertEqual(result.returncode, 64, result.stderr)

    def test_api_stops_before_scanning_when_login_fails(self):
        # 닫힌 포트라 로그인이 실패한다. 빈 토큰으로 스캔하면 인증 API 를 못 본 채 통과로 보인다.
        with mock.patch.dict(os.environ, {"ZAP_LOGIN_PASSWORD": "not-a-real-password"}):
            result, docker_called = self.run_script("http://127.0.0.1:9", command="api")
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertFalse(docker_called)


if __name__ == "__main__":
    unittest.main()
