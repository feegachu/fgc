import unittest

from npm_audit_summary import classify


def report(high=0):
    return {"metadata": {"vulnerabilities": {
        "info": 0, "low": 0, "moderate": 0, "high": high, "critical": 0, "total": high}}}


class AuditSummaryTests(unittest.TestCase):
    def test_completed_clean_scan(self):
        self.assertEqual(classify(report(), 0)[0], "clean")

    def test_vulnerabilities_are_reported(self):
        self.assertEqual(classify(report(5), 1), ("findings", report(5)["metadata"]["vulnerabilities"]))

    def test_registry_error_is_not_clean_even_with_exit_one(self):
        self.assertEqual(classify({"error": {"code": "E503"}}, 1), ("unavailable", None))

    def test_timeout_or_missing_exit_code_is_not_clean(self):
        for code in (124, 137, -1, 2):
            self.assertEqual(classify(report(), code), ("unavailable", None))

    def test_missing_or_partial_report_is_not_clean(self):
        for data in (None, [], {}, {"metadata": None}, {"metadata": {"vulnerabilities": {"total": 0}}}):
            self.assertEqual(classify(data, 0), ("unavailable", None))

    def test_invalid_counts_are_not_clean(self):
        for value in (-1, "0", False, None):
            data = report()
            data["metadata"]["vulnerabilities"]["high"] = value
            self.assertEqual(classify(data, 0), ("unavailable", None))

    def test_inconsistent_total_or_exit_code_is_not_clean(self):
        data = report(5)
        data["metadata"]["vulnerabilities"]["total"] = 0
        for payload, code in ((data, 0), (report(5), 0), (report(), 1)):
            self.assertEqual(classify(payload, code), ("unavailable", None))


if __name__ == "__main__":
    unittest.main()
