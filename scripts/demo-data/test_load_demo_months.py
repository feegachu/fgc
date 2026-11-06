import datetime
import json
import unittest
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


if __name__ == '__main__':
    unittest.main()
