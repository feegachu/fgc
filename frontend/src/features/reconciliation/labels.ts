export const PAYMENT_STAGE = { INSURER_TO_GA: '보험회사→GA', GA_TO_FC: 'GA→설계사' } as const
// 화면정의서 4-2 대사 결과 표기와 서버 라벨(ReconResultType)이 같다. 필터와 배지가 같은 이름을 쓴다.
export const RESULT_TYPE = {
  MATCHED: '일치',
  AMOUNT_DIFFERENCE: '금액 차이',
  EXPECTED_MISSING: '예상 금액 없음',
  ACTUAL_MISSING: '실제 없음(누락)',
  DUPLICATE: '중복 지급',
  AGENT_MISMATCH: '설계사 불일치',
  INSTALLMENT_MISMATCH: '회차 불일치',
  INVALID_CONTRACT_PAYMENT: '비유효 계약 지급',
  POLICY_VERSION_ERROR: '정책버전 오류',
  JOURNAL_IMBALANCE: '원장 불균형',
  REVIEW_REQUIRED: '검토필요',
} as const
export const RUN_STATUS = {
  CREATED: '생성됨',
  RUNNING: '실행중',
  COMPLETED: '계산완료',
  FAILED: '실패',
  FINALIZED: '확정(잠김)',
} as const
export const EMPTY = '-'
export const text = (value: unknown) => (value == null || value === '' ? EMPTY : String(value))
