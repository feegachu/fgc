import { screenMessage } from '../../lib/screenText'
import { REGIME_REASON_TEXT } from '../schedule/regimeReasonText'

export const present = (value: unknown) => screenMessage(value == null ? null : String(value))

// 서버는 이 원문을 예외 분류 키로도 쓰므로 원문은 두고 화면에서만 바꾼다. 앞뒤에 붙은 말과 모르는 문구는 그대로 둔다.
const DECISION_REASONS: [string, string][] = [
  ['기준일 이하 계약 금융 스냅샷이 없습니다', '기준일 이전 계약 금액 자료가 없습니다'],
  ['해약환급금 스냅샷 값이 없습니다', '해약환급금 자료가 없습니다'],
  ['예상 해약환급금의 환급률표 ID가 없습니다', '예상 해약환급금에 연결된 환급률표가 없습니다'],
  ['금융 스냅샷과 적용 환급률표가 일치하지 않습니다', '계약 금액 자료와 적용 환급률표가 맞지 않습니다'],
]
// 배치가 업무 오류 코드를 설명으로 저장한 예외도 있다. 코드는 화면에 쓰지 않는다(화면정의서 공통규칙 10).
const ERROR_CODE = /\bFGC-[A-Z]+-[A-Z0-9_]+\b(?::\s*)?/g
// 분급 정책 검토 예외는 판정 코드를 설명으로 저장한다(ScheduleService). 스케줄 화면과 같은 문장으로 보인다.
const REGIME_REVIEW = /\bREVIEW_REQUIRED: ([A-Z_]+)\b/g
export const decisionReasonText = (value: unknown) => {
  if (value == null) return present(null)
  const text = DECISION_REASONS.reduce((reason, [raw, shown]) => reason.replaceAll(raw, shown), String(value))
    .replace(REGIME_REVIEW, (_, code: string) => REGIME_REASON_TEXT[code] ?? '적용할 분급 정책을 자동으로 정하지 못했습니다.')
    .replace(ERROR_CODE, '')
    .replace(/^[\s/]+|[\s/]+$/g, '')
  return present(text || '자동 처리 중 오류가 기록되었습니다. 계약 자료를 확인하세요.')
}
