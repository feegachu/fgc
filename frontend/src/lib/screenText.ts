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
  return value
    .replace(/^실제 지급 없음\s*—\s*예상\s*([\d,]+)원 지급 건 미발견$/, '실제 지급 누락 (예상 $1원)')
    .replace(/^(.+?)(?: — |, )/, (_, lead: string) =>
      blockedActions[lead] ? `${blockedActions[lead]}. ` : `${lead}, `,
    )
    .replace(/\bINSURER_TO_GA\b/g, '원수사→GA')
    .replace(/\bGA_TO_FC\b/g, 'GA→설계사')
    .replace(/(?<=\S)·(?=\S)/g, '/')
    .replace(/\s*·\s*/g, ', ')
    .replace(/\s*—\s*/g, ', ')
}

/**
 * 서버가 저장한 실패 사유에 섞인 예외 원문, 오류 코드, 변수 이름을 가려낸다(화면정의서 공통규칙 10).
 * 영문 3자 이상, 키=값, 괄호 안 5자리 오류 코드(SQLSTATE)를 본다. 서버 ValidationFailureText와 같은 기준이다.
 * 원문은 서버 로그에 있다. 요청번호는 문의에 필요한 값이라 보지 않는다.
 */
export const isDeveloperText = (value: string) =>
  /[A-Za-z_]{3,}|[A-Za-z_]+=|\(\d{5}\)/.test(value.replace(/요청번호 [\w.-]+/g, ''))

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
