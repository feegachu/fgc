import type { ExceptionSearchResult } from './api'

type ExceptionType = NonNullable<NonNullable<ExceptionSearchResult['summary']>[number]['type']>

const summaryDescriptions = {
  CAP_WARNING: '사용률 경고 기준 이상',
  CAP_VIOLATION: '확정 위반 판정',
  CAP_REVIEW_REQUIRED: '한도 판정 근거 확인 필요',
  RECONCILIATION_MISMATCH: '대사 금액 차이 확인 필요',
  JOURNAL_IMBALANCE: '차변·대변 합계 확인 필요',
  JOURNAL_CORRECTION_REQUIRED: '역분개·재기표 검토 필요',
  ARBITRAGE_CANDIDATE: '위반 확정 아님',
  REFUND_TABLE_MISSING: '적용할 환급률표 확인 필요',
  PRODUCT_CODE_MISMATCH: '상품코드 연결 확인 필요',
  POLICY_MISSING: '적용 가능한 정책 확인 필요',
  POLICY_DUPLICATE: '중복 적용 정책 확인 필요',
  ALLOCATION_EVIDENCE_MISSING: '배부 근거 보완 필요',
  DATA_QUALITY: '입력 데이터 확인 필요',
  OTHER: '상세 내용 확인 필요',
} satisfies Record<ExceptionType, string>

export function exceptionSummaryDescription(type?: string) {
  return type && Object.hasOwn(summaryDescriptions, type)
    ? summaryDescriptions[type as ExceptionType]
    : summaryDescriptions.OTHER
}
