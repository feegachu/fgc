import copy
from contextlib import contextmanager
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import demo as control
from load_filming_demo import FilmingOctoberDemo, MONTH_COUNTS, expected_october_cases, october_open_count, portfolio_offsets
from test_demo import options


class FilmingVariantsTest(unittest.TestCase):
    def test_compact_pays_the_missing_payment_while_full_keeps_it_missing(self):
        for variant in ('full', 'compact'):
            with self.subTest(variant=variant):
                demo = object.__new__(FilmingOctoberDemo)
                demo.variant = variant
                demo.db = Mock()
                demo.db.sql.return_value = '1'
                demo.loader = Mock()
                demo.loader.lines.return_value = [('GA_TO_FC', {
                    'dueDate': '2026-10-08', 'expectedAmount': 650000, 'commissionItemId': 1,
                    'scheduleLineId': 7, 'beneficiaryAgentId': 2,
                })]
                demo.manifest = {'contracts': {'CAP_PAYMENT': {'contractId': 3}}, 'payments': {}}
                demo.pay = Mock(return_value=9)
                demo.pay_month('2026-10')
                if variant == 'full':
                    demo.pay.assert_not_called()
                    self.assertEqual(demo.manifest['payments']['missingAgent']['expectedAmount'], 650000)
                else:
                    demo.pay.assert_called_once()
                    self.assertEqual(demo.pay.call_args.args[0]['amount'], 650000)
                    self.assertNotIn('missingAgent', demo.manifest['payments'])

    def test_normal_portfolio_is_halved_and_all_eight_status_samples_survive(self):
        full = {(mon, n) for mon in MONTH_COUNTS for n in portfolio_offsets(mon)}
        compact = {(mon, n) for mon in MONTH_COUNTS for n in portfolio_offsets(mon, 'compact')}
        protected = {(mon, n) for mon in (5, 6) for n in range(4)}
        self.assertEqual(len(full), 39)
        self.assertTrue(protected <= compact <= full)
        self.assertEqual(len(full - protected), 31)
        self.assertEqual(len(compact - protected), 16)  # 홀수는 1건 올림한다.
        for insurer_parity in (0, 1):
            self.assertEqual(sum(n % 2 == insurer_parity for mon, n in protected), 4)

    def test_only_missing_payment_is_removed_from_expected_exceptions(self):
        manifest = {'scenarioVersion': 'OCTOBER_PAYMENT_WORKFLOW_V4', 'capContractKey': 'MISSING_INSURER',
                    'contracts': {key: {'contractNo': key} for key in
                                  ('CAP_PAYMENT', 'MISSING_INSURER', 'RECOVERY', 'AMOUNT_DIFFERENCE')}}
        original = copy.deepcopy(manifest)
        full = expected_october_cases(manifest)
        self.assertEqual(manifest, original)
        self.assertEqual(sum(full.values()), 4)
        compact = expected_october_cases(manifest | {'includeMissingPayment': False})
        self.assertEqual(sum(compact.values()), 3)
        removed = full - compact
        self.assertEqual(list(removed), [('RECONCILIATION_MISMATCH', 'ACTUAL_MISSING', 'CAP_PAYMENT')])
        self.assertEqual(october_open_count(manifest), 4)
        self.assertEqual(october_open_count(manifest | {'includeMissingPayment': False}), 3)

    def test_control_forwards_variant_without_starting_a_real_server(self):
        class Stub(control.Demo):
            @contextmanager
            def server(self):
                yield

        with tempfile.TemporaryDirectory() as directory:
            for variant in ('full', 'compact'):
                args = options(Path(directory), action='load')
                args.variant = variant
                target = Stub(args)
                target.db = 'isolated-db'
                with patch.object(control, 'run') as run:
                    target.load()
                command = run.call_args.args[0]
                self.assertEqual(command[command.index('--variant') + 1], variant)


if __name__ == '__main__':
    unittest.main()
