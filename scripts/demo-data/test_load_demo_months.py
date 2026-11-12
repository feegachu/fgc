import datetime
import io
import json
import unittest
import urllib.error
from unittest import mock

from load_demo_months import COMMON_COST, CONTRACTS, EVENTS, SETTLEMENT_SUPPORT, Client, Loader, anomaly, contract_number


NUMBERS = {contract_number(index, row[0], row[2]) for index, row in enumerate(CONTRACTS, start=1)}


def line(installment=1, item='FC 기본수수료', amount=650000, due='2026-11-02'):
    return {'installmentNo': installment, 'commissionItemName': item, 'expectedAmount': amount, 'dueDate': due}


class FakeServer:
    def __init__(self, runs):
        self.runs, self.calls = runs, []

    def ok(self, method, path, body=None):
        self.calls.append((method, path))
        if method == 'GET' and path.endswith('/progress'):
            return {'status': 'COMPLETED'}
        if method == 'GET':
            return {'content': self.runs}
        return {'validationRunId': 99}


def run(status):
    return {'validationRunId': 7, 'validationMonth': '2026-09-01', 'runType': 'MONTHLY', 'status': status}


class Requests(unittest.TestCase):
    def test_requests_name_the_tool_because_the_edge_blocks_the_python_default(self):
        response = mock.MagicMock(status=200)
        response.__enter__.return_value = response
        response.read.return_value = json.dumps({'data': {'accessToken': 'token'}}).encode()
        with mock.patch('urllib.request.urlopen', return_value=response) as opened:
            Client('https://example.test', 'id', 'pw')
        self.assertEqual(opened.call_args.args[0].get_header('User-agent'), 'fgc-demo-loader/1.0')


class Responses(unittest.TestCase):
    @staticmethod
    def reply(status, text):
        response = mock.MagicMock(status=status)
        response.__enter__.return_value = response
        response.read.return_value = text.encode()
        return response

    def test_expired_token_logs_in_again_even_when_the_401_body_is_not_json(self):
        login = self.reply(200, json.dumps({'data': {'accessToken': 'token'}}))
        replies = [login, urllib.error.HTTPError('u', 401, 'expired', {}, io.BytesIO(b'<html>')), login, self.reply(200, json.dumps({'data': {'ok': 1}}))]
        with mock.patch('urllib.request.urlopen', side_effect=replies):
            client = Client('https://example.test', 'id', 'pw')
            self.assertEqual(client.call('GET', '/api/v1/x'), (200, {'data': {'ok': 1}}))

    def test_a_second_401_after_logging_in_again_stops_instead_of_looping(self):
        login = self.reply(200, json.dumps({'data': {'accessToken': 'token'}}))
        expired = lambda: urllib.error.HTTPError('u', 401, 'expired', {}, io.BytesIO(b'{}'))
        with mock.patch('urllib.request.urlopen', side_effect=[login, expired(), login, expired()]):
            client = Client('https://example.test', 'id', 'pw')
            with self.assertRaises(SystemExit) as stopped:
                client.call('GET', '/api/v1/x')
        self.assertIn('401', str(stopped.exception))

    def test_a_reply_that_is_not_an_object_stops_with_the_path_and_status(self):
        replies = [self.reply(200, json.dumps({'data': {'accessToken': 'token'}})), self.reply(200, '[]')]
        with mock.patch('urllib.request.urlopen', side_effect=replies):
            client = Client('https://example.test', 'id', 'pw')
            with self.assertRaises(SystemExit) as stopped:
                client.call('GET', '/api/v1/x')
        self.assertIn('/api/v1/x: 200', str(stopped.exception))

    def test_a_page_reply_without_content_stops_with_the_path(self):
        replies = [self.reply(200, json.dumps({'data': {'accessToken': 'token'}})), self.reply(200, json.dumps({'data': None}))]
        with mock.patch('urllib.request.urlopen', side_effect=replies):
            client = Client('https://example.test', 'id', 'pw')
            with self.assertRaises(SystemExit) as stopped:
                client.ok('GET', '/api/v1/transactions?page=1', keys=('content', 'totalPages'))
        self.assertIn('/api/v1/transactions?page=1: 200', str(stopped.exception))

    def test_a_login_reply_with_a_number_token_stops(self):
        with mock.patch('urllib.request.urlopen', return_value=self.reply(200, json.dumps({'data': {'accessToken': 123}}))):
            with self.assertRaises(SystemExit) as stopped:
                Client('https://example.test', 'id', 'pw')
        self.assertIn('/api/v1/auth/login', str(stopped.exception))

    def test_a_page_reply_with_null_content_stops_with_the_path(self):
        replies = [self.reply(200, json.dumps({'data': {'accessToken': 'token'}})),
                   self.reply(200, json.dumps({'data': {'content': None, 'totalPages': 1}}))]
        with mock.patch('urllib.request.urlopen', side_effect=replies):
            loader = Loader(Client('https://example.test', 'id', 'pw'), None)
            with self.assertRaises(SystemExit) as stopped:
                loader.pages('/api/v1/transactions?status=DRAFT')
        self.assertIn('/api/v1/transactions?status=DRAFT&page=1', str(stopped.exception))

    def test_a_login_reply_with_a_list_stops(self):
        with mock.patch('urllib.request.urlopen', return_value=self.reply(200, json.dumps({'data': ['x']}))):
            with self.assertRaises(SystemExit) as stopped:
                Client('https://example.test', 'id', 'pw')
        self.assertIn('/api/v1/auth/login', str(stopped.exception))

    def test_a_login_reply_without_a_token_stops(self):
        with mock.patch('urllib.request.urlopen', return_value=self.reply(200, json.dumps({'data': None}))):
            with self.assertRaises(SystemExit) as stopped:
                Client('https://example.test', 'id', 'pw')
        self.assertIn('로그인 실패', str(stopped.exception))


class MonthRuns(unittest.TestCase):
    def test_finished_month_is_reused(self):
        server = FakeServer([run('COMPLETED')])
        self.assertEqual(Loader(server, server).run_month('2026-09'), 7)
        self.assertEqual([c for c in server.calls if c[0] == 'POST'], [])

    def test_waiting_run_is_executed_instead_of_creating_one(self):
        server = FakeServer([run('CREATED')])
        self.assertEqual(Loader(server, server).run_month('2026-09'), 7)
        self.assertEqual([c for c in server.calls if c[0] == 'POST'], [('POST', '/api/v1/validation-runs/7/execute')])

    def test_month_without_a_run_gets_a_new_one(self):
        server = FakeServer([])
        self.assertEqual(Loader(server, server).run_month('2026-09'), 99)
        self.assertEqual([c[1] for c in server.calls if c[0] == 'POST'], ['/api/v1/validation-runs', '/api/v1/validation-runs/99/execute'])


class DraftDeletion(unittest.TestCase):
    def test_preserves_a_blocked_draft_with_business_history(self):
        server = mock.Mock()
        server.call.side_effect = [(201, {'data': {'commissionTransactionId': 241}}),
                                   (409, {'error': {'code': 'FGC-TRAN-009'}})]
        server.ok.return_value = {'confirmable': False}
        loader = Loader(server, server)
        row = {**line(), 'scheduleLineId': 547, 'commissionItemId': 1}
        self.assertIsNone(loader.pay_line('GA_TO_FC', row, 1, '2026-11'))
        self.assertEqual(server.call.call_args.args, ('DELETE', '/api/v1/transactions/241'))
        server.ok.assert_called_once_with('POST', '/api/v1/transactions/241/precheck', {})
        self.assertEqual(loader.counters['payments'], 1)

    def test_unexpected_deletion_failure_still_stops_the_loader(self):
        server = mock.Mock()
        server.call.side_effect = [(201, {'data': {'commissionTransactionId': 241}}),
                                   (500, {'error': {'code': 'OTHER'}})]
        server.ok.return_value = {'confirmable': False}
        row = {**line(), 'scheduleLineId': 547, 'commissionItemId': 1}
        with self.assertRaises(SystemExit):
            Loader(server, server).pay_line('GA_TO_FC', row, 1, '2026-11')


class DemoTables(unittest.TestCase):
    def test_every_table_points_at_a_contract_the_loader_creates(self):
        named = {row[1] for row in EVENTS} | {row[2] for row in COMMON_COST + SETTLEMENT_SUPPORT}
        self.assertEqual(named - NUMBERS, set())

    def test_status_changes_are_in_order_per_contract(self):
        last = {}
        for _, number, _, when, _ in EVENTS:
            moment = datetime.datetime.fromisoformat(when)
            if number in last:
                self.assertLess(last[number], moment, number)
            last[number] = moment

    def test_anomalies_touch_only_their_contract(self):
        self.assertEqual(anomaly('FGC-FGL01-202610-0109', 'GA_TO_FC', line()), 'SHORT')
        self.assertEqual(anomaly('FGC-FGN01-202610-0108', 'GA_TO_FC', line(item='관리자수수료', amount=36000)), 'SKIP')
        self.assertEqual(anomaly('FGC-FGN01-202611-0113', 'GA_TO_FC', line(item='시책수수료')), 'TWICE')
        self.assertEqual(anomaly('FGC-FGL01-202611-0112', 'GA_TO_FC', line()), 'DRAFT')
        self.assertEqual(anomaly('FGC-FGL01-202609-0101', 'GA_TO_FC', line(due='2026-11-02')), 'DRAFT')
        self.assertIsNone(anomaly('FGC-FGL01-202609-0101', 'GA_TO_FC', line(due='2026-10-02')))
        self.assertEqual(anomaly('FGC-FGL02-202611-0114', 'INSURER_TO_GA', line()), 'KEEP_REJECTED')
        self.assertIsNone(anomaly('FGC-FGL01-202610-0106', 'GA_TO_FC', line()))


from load_filming_demo import Database, OctoberDemo, start_server


class Isolation(unittest.TestCase):
    def test_rejects_main_database_and_sql_identifiers(self):
        for name in ('fgc', 'fgc_demo', 'postgres', 'fgc_october_test; DROP DATABASE fgc', 'fgc_october_'):
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                Database(name)

    def test_existing_database_is_never_overwritten(self):
        db = Database('fgc_october_existing')
        with mock.patch.object(db, 'sql', return_value='1') as sql:
            with self.assertRaises(RuntimeError):
                db.create()
        sql.assert_called_once()
        self.assertNotIn('DROP', sql.call_args.args[0])

    def test_main_server_port_is_rejected_before_startup(self):
        with self.assertRaises(RuntimeError), mock.patch('subprocess.Popen') as process:
            start_server(Database('fgc_october_test'), 8081, None)
        process.assert_not_called()


class Payments(unittest.TestCase):
    def test_monthly_scenario_leaves_draft_without_attempting_confirmation(self):
        demo = object.__new__(OctoberDemo)
        demo.cap_detection = 'MONTHLY'
        demo.s = mock.Mock()
        demo.s.ok.return_value = {'commissionTransactionId': 7}
        self.assertEqual(demo.pay({}, expect_cap=True), 7)
        demo.s.call.assert_not_called()

    def test_cap_scenario_must_be_blocked_by_the_real_cap_gate(self):
        demo = object.__new__(OctoberDemo)
        demo.s = mock.Mock()
        demo.s.ok.return_value = {'commissionTransactionId': 7}
        for result in [(200, {'data': {}}), (422, {'error': {'code': 'FGC-CAP-004'}})]:
            demo.s.call.return_value = result
            with self.assertRaises(RuntimeError):
                demo.pay({}, expect_cap=True)
        demo.s.call.return_value = (422, {'error': {'code': 'FGC-CAP-001'}})
        self.assertEqual(demo.pay({}, expect_cap=True), 7)


if __name__ == '__main__':
    unittest.main()
