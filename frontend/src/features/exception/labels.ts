import type { ExceptionSearchResult } from './api'

type ExceptionType = NonNullable<NonNullable<ExceptionSearchResult['summary']>[number]['type']>

const summaryDescriptions = {
  CAP_WARNING: '사용률 경고 기준 이상',
  CAP_VIOLATION: '확정 위반 판정',
  CAP_REVIEW_REQUIRED: '한도 판정 근거 확인 필요',
  RECONCILIATION_MISMATCH: '대사 금액 차이 확인 필요',
  JOURNAL_IMBALANCE: '차변/대변 합계 확인 필요',
  JOURNAL_CORRECTION_REQUIRED: '역분개/재기표 검토 필요',
  ARBITRAGE_CANDIDATE: '위반 확정 아님',
  REFUND_TABLE_MISSING: '적용할 환급률표 확인 필요',
  PRODUCT_CODE_MISMATCH: '상품코드 연결 확인 필요',
  POLICY_MISSING: '적용 가능한 정책 확인 필요',
  POLICY_DUPLICATE: '중복 적용 정책 확인 필요',
  ALLOCATION_EVIDENCE_MISSING: '배부 근거 보완 필요',
  DATA_QUALITY: '입력 데이터 확인 필요',
  OTHER: '상세 내용 확인 필요',
} satisfies Record<ExceptionType, string>

const sourceLabels: Record<string, string> = {
  COMMISSION_TRANSACTION: '지급 건',
  INSURANCE_CONTRACT: '계약',
  SCHEDULE_HEADER: '스케줄',
  JOURNAL_HEADER: '분개',
  RECONCILIATION_RESULT: '대사 결과',
  ARBITRAGE_CHECK: '차익거래 검증',
  CAP_CHECK: '1,200% 판정',
}

/** 원천 참조를 화면 이름으로 보인다. 계약은 내부 번호 대신 계약번호를 쓰고, 모르는 유형만 'TYPE:ID' 원문으로 남긴다. */
export function sourceReference(type?: string, contractNo?: string) {
  if (!type || !Object.hasOwn(sourceLabels, type)) return '원본 보기'
  return type === 'INSURANCE_CONTRACT' && contractNo ? `${sourceLabels[type]} ${contractNo}` : sourceLabels[type]
}

export function exceptionSummaryDescription(type?: string) {
  return type && Object.hasOwn(summaryDescriptions, type)
    ? summaryDescriptions[type as ExceptionType]
    : summaryDescriptions.OTHER
}
