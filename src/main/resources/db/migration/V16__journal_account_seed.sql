-- 운영정책서 제39조 "최소 계정과목" 8개 시드.
-- journal_account는 baseline(V1)에서 CREATE TABLE만 되고 시드가 없었다 — #93
-- JournalPersistenceService.saveDraft()가 활성 계정과목을 조회해 검증하므로, 이 시드가
-- 없으면 저장이 항상 실패한다. 코드 값은
-- com.susukkang.fgc.journal.domain.JournalAccountCode enum과 문자열이 같아야 한다.
INSERT INTO fgc.journal_account (account_code, account_name, normal_balance) VALUES
    ('EXPECTED_RECEIVABLE',        '예상 수수료미수금',       'DEBIT'),
    ('EXPECTED_INCOME',            '예상 수수료수익 대응',     'CREDIT'),
    ('ACTUAL_RECEIVABLE',          '실제 수수료미수금',       'DEBIT'),
    ('ACTUAL_INCOME',              '실제 수수료수익 대응',     'CREDIT'),
    ('EXPECTED_PAYOUT_EXPENSE',    '예상 설계사 지급비용',     'DEBIT'),
    ('EXPECTED_PAYOUT_PAYABLE',    '예상 지급채무',           'CREDIT'),
    ('CONFIRMED_PAYOUT_EXPENSE',   '확정 설계사 지급비용',     'DEBIT'),
    ('CONFIRMED_PAYOUT_PAYABLE',   '확정 지급채무',           'CREDIT');
