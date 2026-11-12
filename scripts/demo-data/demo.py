#!/usr/bin/env python3
"""시연 관리: python -X utf8 scripts/demo-data/demo.py local|ec2 reset|load|rebuild|backup|restore

reset: 자동 백업 후 업무 데이터 초기화 (계정/정책/기준정보 유지)
load: 기존 load_filming_demo.py 실행, rebuild: reset + load
backup: DB + 환수 PDF 백업, restore: --snapshot 경로 또는 latest 복원
로컬은 IntelliJ 서버를 중지하고 실행한다. EC2는 web/api를 자동 중지/재시작한다.
EC2 배포 이미지와 소스는 동일 버전이어야 한다. 이 도구는 배포하지 않는다.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime
import getpass
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
TRUNCATE = ('BEGIN; TRUNCATE fgc.insurance_contract, fgc.commission_transaction, '
            'fgc.statement_batch, fgc.validation_run, fgc.exception_case CASCADE; COMMIT;')


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def capture(args):
    return run(args, stdout=subprocess.PIPE, encoding='utf-8').stdout.strip()


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() if hasattr(hashlib, 'file_digest') else _hash(stream)


def _hash(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def check_archive(path):
    # 백업을 다른 PC에서 받아도 경로 탈출/심볼릭 링크로 임의 파일을 덮어쓰지 않는다.
    with tarfile.open(path, 'r:gz') as archive:
        for member in archive.getmembers():
            parts = member.name.replace('\\', '/').split('/')
            if member.name.startswith(('/', '\\')) or '..' in parts or ':' in member.name:
                raise RuntimeError('파일 백업에 안전하지 않은 경로가 있습니다.')
            if not (member.isfile() or member.isdir()):
                raise RuntimeError('파일 백업에 링크/특수 파일이 있습니다.')


def validate_snapshot(path, environment):
    manifest = json.loads((path / 'snapshot.json').read_text(encoding='utf-8'))
    if manifest.get('version') != 1 or manifest.get('environment') != environment:
        raise RuntimeError('같은 환경(local/ec2)의 이 도구로 만든 백업만 복원할 수 있습니다.')
    for name in ('db.dump', 'files.tgz'):
        if (path / name).is_symlink() or sha256(path / name) != manifest['sha256'][name]:
            raise RuntimeError(f'백업 무결성 확인 실패: {name}')
    check_archive(path / 'files.tgz')
    return manifest


def wait_health(base, process=None):
    for _ in range(180):
        if process and process.poll() is not None:
            raise RuntimeError('임시 API가 종료됐습니다. server.log를 확인하세요.')
        try:
            with urllib.request.urlopen(base + '/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except (OSError, ValueError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError('API 준비 시간 초과. 서버 로그를 확인하세요.')


class Demo:
    def __init__(self, args):
        self.args = args
        self.ec2 = args.environment == 'ec2'
        self.state = args.state.expanduser().resolve()
        self.home = args.backups.expanduser().resolve()
        self.files = args.files.expanduser().resolve()
        self.base = 'http://127.0.0.1:18081' if self.ec2 else 'http://127.0.0.1:18084'
        self.environment = 'shinhan' if self.ec2 else 'local'
        self.accounts = (args.accounts or Path.home() / f'.fgc-{self.environment}-accounts.json').expanduser().resolve()
        self.compose = ['docker', 'compose']
        if self.ec2:
            for name in ('.env', 'release.env', 'compose.yaml'):
                if not (self.state / name).is_file():
                    raise RuntimeError(f'신한 배포 파일이 없습니다: {self.state / name}')
            self.compose += ['--project-directory', self.state, '--env-file', self.state / '.env',
                             '--env-file', self.state / 'release.env', '-f', self.state / 'compose.yaml']
            override = self.state / 'filming.override.yaml'
            if override.exists():
                self.compose += ['-f', override]
        else:
            self.compose += ['--project-directory', ROOT, '-f', ROOT / 'docker-compose.yml']
        self.db = None
        self.api = None
        self.volume = None

    def sql(self, statement):
        return run(['docker', 'exec', '-i', self.db, 'psql', '-X', '-qAt', '-U', 'fgc', '-d', 'fgc',
                    '-v', 'ON_ERROR_STOP=1'], input=statement, encoding='utf-8', stdout=subprocess.PIPE).stdout.strip()

    def setup(self):
        if not self.ec2:
            for host, port in (('127.0.0.1', 8081), ('::1', 8081), ('127.0.0.1', 18084)):
                try:
                    with socket.create_connection((host, port), timeout=1):
                        raise RuntimeError(f'{port} 서버를 먼저 중지하세요. IntelliJ 중지 후 다시 실행하세요.')
                except OSError:
                    pass
        run(self.compose + ['up', '-d', 'db'])
        self.db = capture(self.compose + ['ps', '-q', 'db'])
        if not self.db:
            raise RuntimeError('DB 컨테이너를 찾지 못했습니다.')
        for _ in range(60):
            result = subprocess.run(['docker', 'exec', self.db, 'pg_isready', '-U', 'fgc', '-d', 'fgc'],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if result.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('DB 시작 시간 초과')
        if self.ec2:
            self.api = capture(self.compose + ['ps', '-aq', 'api'])
            if not self.api:
                raise RuntimeError('먼저 현재 버전 API/web을 신한 배포 절차로 배포하세요.')
            mounts = json.loads(capture(['docker', 'inspect', '--format', '{{json .Mounts}}', self.api]))
            selected = [m for m in mounts if m['Destination'] == '/var/lib/fgc/files' and m['Type'] == 'volume']
            if len(selected) != 1:
                raise RuntimeError('API의 환수 PDF named volume을 확인할 수 없습니다.')
            self.volume = selected[0]['Name']
            if self.args.action in ('load', 'rebuild'):
                labels = json.loads(capture(['docker', 'inspect', '--format', '{{json .Config.Labels}}', self.api])) or {}
                commit = capture(['git', '-C', ROOT, 'rev-parse', 'HEAD'])
                if labels.get('org.opencontainers.image.revision') != commit:
                    raise RuntimeError('API 이미지와 소스 커밋이 다릅니다. 같은 버전을 배포/checkout한 뒤 적재하세요.')
            # 작업 중 네트워크가 막혀 복원에 실패하지 않도록 helper 이미지를 먼저 확인한다.
            run(['docker', 'image', 'inspect', 'postgres:17-alpine'], stdout=subprocess.DEVNULL)

    @contextmanager
    def maintenance(self):
        if self.ec2:
            run(self.compose + ['stop', 'web', 'api'])
        try:
            sessions = self.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                                "AND pid<>pg_backend_pid() AND backend_type='client backend'")
            if sessions != '0':
                raise RuntimeError('다른 DB 연결이 남아 있습니다. IntelliJ DB 콘솔/다른 API를 종료하세요.')
            yield
            if self.ec2:
                run(self.compose + ['up', '-d', 'api'])
                wait_health(self.base)
                run(self.compose + ['up', '-d', 'web'])
        except BaseException:
            if self.ec2:
                try:
                    run(self.compose + ['stop', 'web', 'api'])
                except Exception as stop_error:
                    print(f'web/api 중지 실패: 직접 중지 후 복구하세요. {stop_error}', flush=True)
                else:
                    print('작업 실패: web/api는 중지 상태입니다. 로그 확인 후 복원 명령을 실행하세요.', flush=True)
            raise

    def files_command(self, path, script, readonly=False):
        run(['docker', 'run', '--rm', '--network', 'none',
             '--mount', f'type=volume,src={self.volume},dst=/data' + (',readonly' if readonly else ''),
             '--mount', f'type=bind,src={path},dst=/backup', 'postgres:17-alpine', 'sh', '-ec', script])

    def backup(self):
        path = self.home / (datetime.now().strftime('%Y%m%d_%H%M%S') + '_' + uuid.uuid4().hex[:6])
        path.mkdir(parents=True, mode=0o700)
        remote = f'/tmp/fgc-demo-{uuid.uuid4().hex}.dump'
        try:
            run(['docker', 'exec', self.db, 'pg_dump', '-U', 'fgc', '-d', 'fgc', '-Fc', '-f', remote])
            run(['docker', 'cp', f'{self.db}:{remote}', path / 'db.dump'])
        finally:
            run(['docker', 'exec', self.db, 'rm', '-f', remote])
        if self.ec2:
            self.files_command(path, 'tar -czf /backup/files.tgz -C /data .', readonly=True)
        else:
            with tarfile.open(path / 'files.tgz', 'w:gz', dereference=False) as archive:
                if self.files.exists():
                    archive.add(self.files, arcname='.')
        check_archive(path / 'files.tgz')
        commit = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], capture_output=True, text=True)
        manifest = {'version': 1, 'environment': self.args.environment, 'commit': commit.stdout.strip(),
                    'sha256': {name: sha256(path / name) for name in ('db.dump', 'files.tgz')}}
        if self.ec2:
            manifest['apiImage'] = capture(['docker', 'inspect', '--format', '{{.Image}}', self.api])
        (path / 'snapshot.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        # 완료된 스냅샷만 latest로 공개한다. 실패한 디렉터리는 복원 대상에 포함하지 않는다.
        latest = self.home / 'latest.tmp'
        latest.write_text(path.name, encoding='utf-8')
        latest.replace(self.home / 'latest')
        print(f'백업 완료: {path}', flush=True)
        return path

    def snapshot(self):
        value = self.args.snapshot
        if value == 'latest':
            value = str(self.home / (self.home / 'latest').read_text(encoding='utf-8').strip())
        path = Path(value).expanduser().resolve()
        manifest = validate_snapshot(path, self.args.environment)
        if self.ec2 and manifest.get('apiImage') != capture(['docker', 'inspect', '--format', '{{.Image}}', self.api]):
            raise RuntimeError('백업 당시 API 이미지와 다릅니다. 같은 버전을 먼저 배포한 뒤 복원하세요.')
        return path

    def restore(self, path):
        # 자동 백업 전에 원본 경로를 확정했으므로 latest가 바뀌어도 올바른 원본을 읽는다.
        with (path / 'db.dump').open('rb') as stream:
            run(['docker', 'exec', '-i', self.db, 'pg_restore', '-U', 'fgc', '-d', 'fgc',
                 '--clean', '--if-exists', '--single-transaction', '--exit-on-error'], stdin=stream)
        if self.ec2:
            # /data는 위에서 확인한 PDF 전용 볼륨이다. 다른 Docker 볼륨은 접근하지 않는다.
            self.files_command(path, 'find /data -mindepth 1 -delete; tar -xzf /backup/files.tgz -C /data')
        else:
            # 기존 폴더는 남겨 둔다. DB 복원 후 파일 복원이 실패해도 원본을 복구할 수 있다.
            staging = self.files.with_name(self.files.name + '.restore-' + uuid.uuid4().hex[:8])
            staging.mkdir(parents=True)
            with tarfile.open(path / 'files.tgz', 'r:gz') as archive:
                archive.extractall(staging)  # validate_snapshot에서 링크 및 경로를 먼저 검사한다.
            if self.files.exists():
                self.files.rename(self.files.with_name(self.files.name + '.before-' + uuid.uuid4().hex[:8]))
            staging.rename(self.files)
        print(f'복원 완료: {path}', flush=True)

    def credentials(self):
        if not self.accounts.exists():
            values = {name: getpass.getpass(f'{name} 비밀번호: ') for name in ('settle01', 'gaadmin')}
            if not all(values.values()):
                raise RuntimeError('비밀번호가 비어 있습니다.')
            self.accounts.parent.mkdir(parents=True, exist_ok=True)
            with self.accounts.open('x', encoding='utf-8') as stream:
                os.chmod(self.accounts, 0o600)
                json.dump({self.environment: {'accounts': values}}, stream)
        values = json.loads(self.accounts.read_text(encoding='utf-8-sig'))[self.environment]['accounts']
        if not all(values.get(name) for name in ('settle01', 'gaadmin')):
            raise RuntimeError('계정 파일에 settle01과 gaadmin 비밀번호가 필요합니다.')

    @contextmanager
    def server(self):
        if self.ec2:
            override = self.state / 'filming.override.yaml'
            contents = ('services:\n  api:\n    environment:\n      FGC_DEMO_MONTH: "2026-10"\n'
                        '      FGC_BATCH_DAILY_CHANGED_CONTRACT_ENABLED: "false"\n')
            if override.exists() and override.read_text(encoding='utf-8') != contents:
                raise RuntimeError('filming.override.yaml에 다른 설정이 있습니다. 덮어쓰지 않고 중단합니다.')
            override.write_text(contents, encoding='utf-8')
            if override not in self.compose:
                self.compose += ['-f', override]
            run(self.compose + ['up', '-d', 'api'])
            self.api = capture(self.compose + ['ps', '-q', 'api'])
            wait_health(self.base)
            try:
                yield
            finally:
                run(self.compose + ['stop', 'api'])
            return
        gradle = ['cmd', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else ['sh', './gradlew']
        run(gradle + ['--console=plain', 'bootJar'], cwd=ROOT)
        jars = [p for p in (ROOT / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
        if len(jars) != 1:
            raise RuntimeError('실행 가능한 bootJar가 하나 있어야 합니다.')
        work = ROOT / 'output/demo-control'
        work.mkdir(parents=True, exist_ok=True)
        shutil.copy2(jars[0], work / 'server.jar')
        with (work / 'server.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(['java', '-jar', str(work / 'server.jar'), '--server.port=18084',
                '--spring.profiles.active=local',
                '--spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/fgc?currentSchema=fgc',
                '--spring.datasource.username=fgc', '--spring.datasource.password=fgc_local_only',
                '--spring.flyway.locations=classpath:db/migration,classpath:db/demo,classpath:db/demo-agent',
                '--fgc.batch.daily-changed-contract.enabled=false', '--fgc.demo-month=2026-10',
                '--fgc.llm.api-key=', f'--fgc.storage.root={self.files}'], cwd=ROOT, stdout=log, stderr=log)
            try:
                wait_health(self.base, process)
                yield
            finally:
                process.terminate()
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

    def load(self):
        with self.server():
            run([sys.executable, '-X', 'utf8', ROOT / 'scripts/demo-data/load_filming_demo.py',
                 '--base', self.base, '--container', self.db, '--database', 'fgc',
                 '--accounts', self.accounts, '--env', self.environment, '--seed', str(self.args.seed)], cwd=ROOT)

    def execute(self):
        self.setup()
        source = self.snapshot() if self.args.action == 'restore' else None
        if self.args.action in ('load', 'rebuild'):
            self.credentials()  # 계정 파일 오류는 초기화 전에 확인한다.
        with self.maintenance():
            self.backup()
            if self.args.action in ('reset', 'rebuild'):
                if self.sql("SELECT to_regclass('fgc.validation_run') IS NOT NULL") == 't':
                    self.sql(TRUNCATE)
                print('업무 데이터 초기화 완료. 계정/정책/기준정보는 보존했습니다.', flush=True)
            if self.args.action in ('load', 'rebuild'):
                self.load()
                self.backup()  # DB와 생성 PDF를 함께 저장한 시연 시작 상태
            if source:
                self.restore(source)
        if not self.ec2:
            print('완료. IntelliJ에서 FgcApplication을 다시 실행하세요.', flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('environment', choices=('local', 'ec2'))
    parser.add_argument('action', choices=('reset', 'load', 'rebuild', 'backup', 'restore'))
    parser.add_argument('--snapshot', default='latest', help='복원할 백업 폴더 (기본: latest)')
    parser.add_argument('--accounts', type=Path)
    parser.add_argument('--state', type=Path, default=Path.home() / 'fgc-stg')
    parser.add_argument('--backups', type=Path, help='백업 저장 위치')
    parser.add_argument('--files', type=Path, default=ROOT / 'files', help='로컬 FGC_STORAGE_ROOT와 동일한 경로')
    parser.add_argument('--seed', type=int, default=20261112)
    args = parser.parse_args(argv)
    args.backups = args.backups or Path.home() / 'fgc-demo-backups' / args.environment
    args.backups.expanduser().mkdir(parents=True, exist_ok=True, mode=0o700)
    # 백업 디렉터리 변경과 무관하게 같은 대상에 두 작업이 실행되지 않는다.
    lock = (args.state.expanduser() if args.environment == 'ec2' else ROOT) / '.fgc-demo.lock'
    if not lock.parent.is_dir():
        parser.error(f'배포 디렉터리가 없습니다: {lock.parent}')
    try:
        with lock.open('x', encoding='utf-8') as stream:
            stream.write(str(os.getpid()))
    except FileExistsError:
        parser.exit(1, f'다른 작업이 실행 중입니다: {lock}\n강제 종료 후라면 실행 프로세스가 없는지 확인하고 잠금 파일을 지우세요.\n')
    deployment_lock = None
    try:
        if args.environment == 'ec2':
            import fcntl
            deployment_lock = (args.state.expanduser() / 'deploy.lock').open('a')
            fcntl.flock(deployment_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        Demo(args).execute()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'중단: {error}\n')
    finally:
        if deployment_lock:
            deployment_lock.close()
        lock.unlink()


if __name__ == '__main__':
    main()
