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
