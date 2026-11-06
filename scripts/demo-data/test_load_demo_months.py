import datetime
import unittest

from load_demo_months import COMMON_COST, CONTRACTS, EVENTS, SETTLEMENT_SUPPORT, anomaly, contract_number


NUMBERS = {contract_number(index, row[0], row[2]) for index, row in enumerate(CONTRACTS, start=1)}


def line(installment=1, item='FC 기본수수료', amount=650000, due='2026-11-02'):
    return {'installmentNo': installment, 'commissionItemName': item, 'expectedAmount': amount, 'dueDate': due}


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
