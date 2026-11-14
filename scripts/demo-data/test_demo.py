"""시연 관리 안전장치. RUN_DEMO_DOCKER_TEST=1이면 격리 PostgreSQL로 백업/복원을 검증한다."""
from argparse import Namespace
import base64
from contextlib import contextmanager
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import uuid

import demo
import load_filming_demo as loader


def options(root, action='backup', environment='local'):
    return Namespace(environment=environment, action=action, state=root / 'state', backups=root / 'backups',
                     files=root / 'files', accounts=root / 'accounts.json', seed=20261112, snapshot='latest', variant='full')


class SnapshotSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def archive(self, name='notice.pdf', kind=tarfile.REGTYPE):
        path = self.root / 'files.tgz'
        with tarfile.open(path, 'w:gz') as archive:
            member = tarfile.TarInfo(name)
            member.type = kind
            if kind == tarfile.REGTYPE:
                member.size = 3
                archive.addfile(member, io.BytesIO(b'PDF'))
            else:
                member.linkname = '../../outside'
                archive.addfile(member)
        return path

    def test_reject_path_escape_and_links(self):
        for name, kind in [('../outside', tarfile.REGTYPE), ('/outside', tarfile.REGTYPE),
                           ('C:/outside', tarfile.REGTYPE), ('..\\outside', tarfile.REGTYPE),
                           ('link', tarfile.SYMTYPE), ('hardlink', tarfile.LNKTYPE)]:
            with self.subTest(name=name):
                with self.assertRaises(RuntimeError):
                    demo.check_archive(self.archive(name, kind))

    def test_corrupt_or_cross_environment_backup_rejected(self):
        (self.root / 'db.dump').write_bytes(b'PGDMP')
        self.archive()
        manifest = {'version': 1, 'environment': 'local',
                    'sha256': {n: demo.sha256(self.root / n) for n in ('db.dump', 'files.tgz')}}
        (self.root / 'snapshot.json').write_text(json.dumps(manifest))
        demo.validate_snapshot(self.root, 'local')
        with self.assertRaises(RuntimeError):
            demo.validate_snapshot(self.root, 'ec2')
        (self.root / 'db.dump').write_bytes(b'broken')
        with self.assertRaises(RuntimeError):
            demo.validate_snapshot(self.root, 'local')

    def test_restore_selects_source_before_automatic_backup_changes_latest(self):
        events = []

        class Stub(demo.Demo):
            def setup(self):
                pass

            def snapshot(self):
                events.append('resolve-original')
                return self.home / 'original'

            @contextmanager
            def maintenance(self):
                yield

            def backup(self):
                events.append('backup-current')

            def restore(self, path):
                events.append(path.name)

        Stub(options(self.root, 'restore')).execute()
        self.assertEqual(events, ['resolve-original', 'backup-current', 'original'])

    def test_backup_failure_never_reaches_truncate(self):
        commands = []

        class Stub(demo.Demo):
            def setup(self):
                pass

            @contextmanager
            def maintenance(self):
                yield

            def backup(self):
                raise RuntimeError('disk full')

            def sql(self, sql):
                commands.append(sql)

        with self.assertRaisesRegex(RuntimeError, 'disk full'):
            Stub(options(self.root, 'reset')).execute()
        self.assertEqual(commands, [])

    def test_active_database_connections_block_backup(self):
        target = demo.Demo(options(self.root))
        with patch.object(target, 'sql', return_value='1'):
            with self.assertRaisesRegex(RuntimeError, 'DB 연결'):
                with target.maintenance():
                    self.fail('must not run')

    def test_bom_credentials_are_accepted(self):
        args = options(self.root)
        args.accounts.write_text(json.dumps({'local': {'accounts': {'settle01': 'test', 'gaadmin': 'test'}}}), encoding='utf-8-sig')
        demo.Demo(args).credentials()

    def test_failed_ec2_operation_keeps_web_and_api_stopped(self):
        args = options(self.root, environment='ec2')
        args.state.mkdir()
        for name in ('.env', 'release.env', 'compose.yaml'):
            (args.state / name).touch()
        target = demo.Demo(args)
        with patch('demo.run') as command, patch.object(target, 'sql', return_value='0'):
            with self.assertRaisesRegex(RuntimeError, 'failed'):
                with target.maintenance():
                    raise RuntimeError('failed')
        self.assertEqual([c.args[0][-3:] for c in command.call_args_list], [['stop', 'web', 'api'], ['stop', 'web', 'api']])


    def test_failed_ec2_restart_stops_services_and_preserves_original_error(self):
        args = options(self.root, environment='ec2')
        args.state.mkdir()
        for name in ('.env', 'release.env', 'compose.yaml'):
            (args.state / name).touch()
        target = demo.Demo(args)
        for step in ('api', 'health', 'web'):
            for stop_fails in (False, True):
                with self.subTest(step=step, stop_fails=stop_fails):
                    failure = RuntimeError('restart failed: ' + step)
                    commands = []

                    def command(argv):
                        commands.append(argv)
                        if argv[-3:] == ['up', '-d', step]:
                            raise failure
                        if stop_fails and len(commands) > 1 and argv[-3:] == ['stop', 'web', 'api']:
                            raise RuntimeError('stop failed')

                    with patch('demo.run', side_effect=command), \
                         patch('demo.wait_health', side_effect=failure if step == 'health' else None), \
                         patch.object(target, 'sql', return_value='0'):
                        with self.assertRaises(RuntimeError) as raised:
                            with target.maintenance():
                                pass
                    self.assertIs(raised.exception, failure)
                    self.assertEqual(commands[-1][-3:], ['stop', 'web', 'api'])
                    if step != 'web':
                        self.assertNotIn(target.compose + ['up', '-d', 'web'], commands)


class LoaderGuardsTest(unittest.TestCase):
    def test_api_session_must_exist_in_target_database_before_business_changes(self):
        payload = base64.urlsafe_b64encode(json.dumps({'sid': str(uuid.uuid4())}).encode()).decode().rstrip('=')
        client = Namespace(token='header.' + payload + '.signature', login_id='settle01')
        db = Mock()
        db.sql.return_value = 'f'
        with self.assertRaisesRegex(RuntimeError, 'API와 지정한 Docker DB'):
            loader.verify_api_database(db, client)
        self.assertTrue(db.sql.call_args.args[0].startswith('SELECT EXISTS'))
        db.sql.return_value = 't'
        loader.verify_api_database(db, client)

    def test_clone_is_removed_after_restore_or_startup_or_shutdown_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'prepared.json').write_text('{}')
            (path / 'filming-start.dump').write_bytes(b'dump')
            (path / 'accounts.json').write_text(json.dumps({'local': {'accounts': {}}}))
            args = Namespace(verify_manifest=str(path / 'prepared.json'), accounts=str(path / 'accounts.json'),
                             env='local', port=18084, jar=None)
            for step in ('restore', 'startup', 'shutdown'):
                with self.subTest(step=step):
                    db = Mock(name='database')
                    db.name = 'fgc_october_filmverify_test'
                    process = Mock()
                    process.poll.return_value = None
                    process.wait.side_effect = [subprocess.TimeoutExpired('java', 30), None]
                    with patch.object(loader, 'Database', return_value=db), \
                         patch.object(loader, 'command', side_effect=RuntimeError('restore') if step == 'restore' else None), \
                         patch.object(loader, 'start_server', side_effect=RuntimeError('startup') if step == 'startup' else None, return_value=process), \
                         patch.object(loader, 'OctoberDemo', side_effect=RuntimeError('verification')):
                        with self.assertRaises(RuntimeError):
                            loader.verify_saved_demo(args)
                    db.sql.assert_called_once_with('DROP DATABASE fgc_october_filmverify_test WITH (FORCE)', 'postgres')
                    if step == 'shutdown':
                        process.kill.assert_called_once()

    def test_shutdown_error_does_not_skip_clone_database_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'prepared.json').write_text('{}')
            (path / 'filming-start.dump').write_bytes(b'dump')
            (path / 'accounts.json').write_text(json.dumps({'local': {'accounts': {}}}))
            args = Namespace(verify_manifest=str(path / 'prepared.json'), accounts=str(path / 'accounts.json'), env='local', port=18084, jar=None)
            db = Mock()
            db.name = 'fgc_october_filmverify_test'
            with patch.object(loader, 'Database', return_value=db), patch.object(loader, 'command'), \
                 patch.object(loader, 'start_server'), patch.object(loader, 'OctoberDemo', side_effect=RuntimeError('verify')), \
                 patch.object(loader, 'stop_server', side_effect=OSError('stop')):
                with self.assertRaises(OSError):
                    loader.verify_saved_demo(args)
            db.sql.assert_called_once_with('DROP DATABASE fgc_october_filmverify_test WITH (FORCE)', 'postgres')


@unittest.skipUnless(os.environ.get('RUN_DEMO_DOCKER_TEST') == '1', '격리 Docker 테스트는 명시적으로 실행')
class DockerRoundTripTest(unittest.TestCase):
    def test_database_pdf_reset_and_restore_preserve_masters(self):
        name = 'fgc-demo-ops-test-' + uuid.uuid4().hex[:10]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = options(root)
            args.files.mkdir()
            (args.files / 'notice.pdf').write_bytes(b'original PDF')
            run = demo.run
            run(['docker', 'run', '-d', '--name', name, '--tmpfs', '/var/lib/postgresql/data',
                 '-e', 'POSTGRES_USER=fgc', '-e', 'POSTGRES_DB=fgc', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:17'])
            try:
                target = demo.Demo(args)
                target.db = name
                for _ in range(60):
                    if subprocess.run(['docker', 'exec', name, 'pg_isready', '-U', 'fgc'],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                        break
                    time.sleep(1)
                target.sql('CREATE SCHEMA fgc; CREATE TABLE fgc.policy(id int); INSERT INTO fgc.policy VALUES(7);'
                           'CREATE TABLE fgc.insurance_contract(id int); INSERT INTO fgc.insurance_contract VALUES(1);'
                           'CREATE TABLE fgc.commission_transaction(id int); CREATE TABLE fgc.statement_batch(id int);'
                           'CREATE TABLE fgc.validation_run(id int); CREATE TABLE fgc.exception_case(id int);')
                session_id = str(uuid.uuid4())
                target.sql("CREATE TABLE fgc.app_user(user_id int,login_id text); INSERT INTO fgc.app_user VALUES(1,'settle01'); "
                           "CREATE TABLE fgc.auth_refresh_token(user_id int,session_id uuid,revoked_at timestamptz,expires_at timestamptz); "
                           f"INSERT INTO fgc.auth_refresh_token VALUES(1,'{session_id}',NULL,clock_timestamp()+interval '1 hour');")
                payload = base64.urlsafe_b64encode(json.dumps({'sid': session_id}).encode()).decode().rstrip('=')
                client = Namespace(login_id='settle01', token='header.' + payload + '.signature')
                loader.verify_api_database(target, client)
                target.sql('DELETE FROM fgc.auth_refresh_token')
                with self.assertRaisesRegex(RuntimeError, 'API와 지정한 Docker DB'):
                    loader.verify_api_database(target, client)
                snapshot = target.backup()
                demo.validate_snapshot(snapshot, 'local')
                target.sql(demo.TRUNCATE)
                self.assertEqual(target.sql('SELECT count(*) FROM fgc.insurance_contract'), '0')
                self.assertEqual(target.sql('SELECT id FROM fgc.policy'), '7')
                (args.files / 'notice.pdf').write_bytes(b'changed')
                (args.files / 'new.pdf').write_bytes(b'extra')
                target.restore(snapshot)
                self.assertEqual(target.sql('SELECT id FROM fgc.insurance_contract'), '1')
                self.assertEqual((args.files / 'notice.pdf').read_bytes(), b'original PDF')
                self.assertFalse((args.files / 'new.pdf').exists())
                # EC2와 같은 named volume 경로도 실제 Docker로 검증한다.
                volume = name + '-files'
                run(['docker', 'volume', 'create', volume])
                try:
                    target.ec2 = True
                    target.args.environment = 'ec2'
                    target.api = name
                    target.volume = volume
                    target.files_command(root, "printf 'EC2 PDF' > /data/notice.pdf")
                    ec2_snapshot = target.backup()
                    demo.validate_snapshot(ec2_snapshot, 'ec2')
                    target.files_command(root, "printf 'changed' > /data/notice.pdf; touch /data/extra.pdf")
                    target.restore(ec2_snapshot)
                    target.files_command(root, "test ! -f /data/extra.pdf; test \"$(cat /data/notice.pdf)\" = 'EC2 PDF'")
                finally:
                    run(['docker', 'volume', 'rm', volume])
                # 잘못된 dump는 pg_restore 단일 트랜잭션으로 기존 DB를 보존한다.
                (snapshot / 'db.dump').write_bytes(b'not a dump')
                with self.assertRaises(subprocess.CalledProcessError):
                    target.restore(snapshot)
                self.assertEqual(target.sql('SELECT id FROM fgc.insurance_contract'), '1')
            finally:
                run(['docker', 'rm', '-f', name])


if __name__ == '__main__':
    unittest.main()
