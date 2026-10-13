export const JOURNAL_TYPE = {
  EXPECTED_INSURER_INCOME: '예상 수입(원수사→GA)',
  ACTUAL_INSURER_STATEMENT: '실제 명세(원수사)',
  EXPECTED_FC_PAYOUT: '예상 지급(GA→설계사)',
  CONFIRMED_FC_PAYOUT: '확정 지급(GA→설계사)',
  ADJUSTMENT: '조정',
  CLAWBACK: '환수',
  RECOVERY: '회수',
  REVERSAL: '역분개',
} as const
export const JOURNAL_STATUS = { DRAFT: '작성중', POSTED: '기표됨', REVERSED: '역분개됨' } as const

// 레거시 JournalAccountCode의 조회 선택지. 활성 계정 API는 입력용이므로 과거 조회에서 빠진 코드를 보완한다.
export const JOURNAL_ACCOUNT = {
  EXPECTED_RECEIVABLE: '예상 수수료미수금',
  EXPECTED_INCOME: '예상 수수료수익 대응',
  ACTUAL_RECEIVABLE: '실제 수수료미수금',
  ACTUAL_INCOME: '실제 수수료수익 대응',
  EXPECTED_PAYOUT_EXPENSE: '예상 설계사 지급비용',
  EXPECTED_PAYOUT_PAYABLE: '예상 지급채무',
  CONFIRMED_PAYOUT_EXPENSE: '확정 설계사 지급비용',
  CONFIRMED_PAYOUT_PAYABLE: '확정 지급채무',
} as const
