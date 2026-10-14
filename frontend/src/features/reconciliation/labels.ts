export const PAYMENT_STAGE = { INSURER_TO_GA: '원수사→GA', GA_TO_FC: 'GA→설계사' } as const
export const RESULT_TYPE = {
  MATCHED: '일치',
  AMOUNT_DIFFERENCE: '금액 차이',
  EXPECTED_MISSING: '예상 내역 없음',
  ACTUAL_MISSING: '실제 지급 없음',
  DUPLICATE: '중복 지급',
  AGENT_MISMATCH: '설계사 불일치',
  INSTALLMENT_MISMATCH: '회차 불일치',
  INVALID_CONTRACT_PAYMENT: '무효·취소 계약 지급',
  POLICY_VERSION_ERROR: '정책 버전 오류',
  JOURNAL_IMBALANCE: '분개 불균형',
  REVIEW_REQUIRED: '검토 필요',
} as const
export const RUN_STATUS = {
  CREATED: '생성됨',
  RUNNING: '실행중',
  COMPLETED: '계산완료',
  FAILED: '실패',
  FINALIZED: '확정됨',
} as const
export const EMPTY = '—'
export const text = (value: unknown) => (value == null || value === '' ? EMPTY : String(value))
