/** 화면 문구의 가운데점을 나열 표현이나 공백으로 정리한다. */
export function withoutMiddleDots(value: string) {
  return value
    .replace(/[\p{L}\p{N}%]+(?:·[\p{L}\p{N}%]+)+/gu, (list) => {
      const items = list.split('·')
      return `${items.slice(0, -1).join(', ')} 및 ${items.at(-1)}`
    })
    .replace(/[ \t]*·[ \t]*/g, ' ')
}

const blockedActions: Record<string, string> = {
  '지급 확정 불가': '지급을 확정할 수 없습니다',
  '스케줄 확정 불가': '스케줄을 확정할 수 없습니다',
  '저장 불가': '저장할 수 없습니다',
  '처리 불가': '처리할 수 없습니다',
  '수정 불가': '수정할 수 없습니다',
  '삭제 불가': '삭제할 수 없습니다',
  '취소 불가': '취소할 수 없습니다',
  '실행 생성 불가': '실행을 만들 수 없습니다',
  '상태 변경 불가': '상태를 변경할 수 없습니다',
  '확정 불가': '확정할 수 없습니다',
  '기표 불가': '기표할 수 없습니다',
  '정정 불가': '정정할 수 없습니다',
  '승인 불가': '승인할 수 없습니다',
  '제출 불가': '제출할 수 없습니다',
  '통지서 생성 불가': '통지서를 생성할 수 없습니다',
  '되돌릴 수 없습니다': '되돌릴 수 없습니다',
  '한도 검증 보류': '한도 검증을 보류합니다',
}

/** Presentation only. Stored messages and API values remain unchanged. */
export function screenMessage(value?: string | null) {
  if (!value || /^\s*[—-]\s*$/.test(value)) return '-'
  return withoutMiddleDots(value)
    .replace(/^실제 지급 없음\s*—\s*예상\s*([\d,]+)원 지급 건 미발견$/, '실제 지급 누락 (예상 $1원)')
    .replace(/^(.+?)(?: — |, )/, (_, lead: string) =>
      blockedActions[lead] ? `${blockedActions[lead]}. ` : `${lead}, `,
    )
    .replace(/\bINSURER_TO_GA\b/g, '보험회사→GA')
    .replace(/\bGA_TO_FC\b/g, 'GA→설계사')
    .replace(/\s*—\s*/g, ', ')
}

/**
 * 서버가 저장한 실패 사유에 섞인 예외 원문, 오류 코드, 변수 이름을 가려낸다(화면정의서 공통규칙 10).
 * 영문 3자 이상, 키=값, 괄호 안 5자리 오류 코드(SQLSTATE)를 본다. 서버 ValidationFailureText와 같은 기준이다.
 * 원문은 서버 로그에 있다. 요청번호와 계약번호(화면정의서 EXCP-W01의 정식 형식)는 업무에 필요한 값이라 보지 않는다.
 */
export const isDeveloperText = (value: string) =>
  /[A-Za-z_]{3,}|[A-Za-z_]+=|\(\d{5}\)/.test(value.replace(/요청번호 [\w.-]+/g, '').replace(/\bFGC-[A-Z0-9]+-\d{6}-\d+\b/g, ''))

export function errorScreenMessage(value?: string | null, requestId?: string | null) {
  const embeddedRequestId = value?.match(/\([^)]*요청 ID:\s*([^,)]+)[^)]*\)/)?.[1]?.trim()
  const message = screenMessage(value).replace(/\s*\((?:FGC-[A-Z0-9-]+[^)]*|요청 ID:[^)]*)\)/g, '')
  const traceId = requestId?.trim() || embeddedRequestId
  return traceId && !message.includes(traceId) ? `${message} (요청번호: ${traceId})` : message
}

export function nameWithCode(name?: string | null, code?: string | null) {
  return name && code ? `${name} (${code})` : name || code || '-'
}

export function monthText(value?: string | null) {
  const match = value?.match(/^(\d{4})-(\d{2})(?:-|$)/)
  if (!match || Number(match[2]) < 1 || Number(match[2]) > 12) return '-'
  return `${match[1]}년 ${Number(match[2])}월`
}


const demoEvidenceLabels: Record<string, string> = {
  'SYNTHETIC:FILMING:SETTLEMENT_SUPPORT': '시연용 정착지원금 지급 근거',
  'SYNTHETIC:FILMING:APPROVED_COMMON_COST': '시연용 공통비 지급 승인 근거',
  'SYNTHETIC:FILMING:OCTOBER_WARNING:INDIVIDUAL_AGREEMENT': '시연용 개별 약정 지급 근거',
  'SYNTHETIC:OCTOBER_DEMO': '10월 시연용 지급 근거',
}

/** 알려진 시연 증빙만 화면에서 풀어 쓴다. 입력 및 저장 값은 그대로 유지한다. */
export function paymentEvidenceText(value: string) {
  if (Object.hasOwn(demoEvidenceLabels, value)) return demoEvidenceLabels[value]
  if (/^SYNTHETIC:FILMING:EXTERNAL_PAYMENT_RECORD_ERROR:\d+$/.test(value)) return '시연용 실제 송금액 확인 및 지급 기록 정정 근거'
  const paid = /^SYNTHETIC:FILMING:EXTERNAL_ALREADY_PAID:(\d{4}-\d{2})$/.exec(value)
  return paid ? `${monthText(paid[1])} 시연용 지급 완료 확인 근거` : value
}
