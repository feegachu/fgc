import { errorScreenMessage, screenMessage } from './screenText'
// 화면정의서 §4 규칙 3: 소수 표시는 한 자리로 절사하고 원본 계산·판정값은 유지한다.
const EMPTY = '-'
const SEOUL = 'Asia/Seoul'

function isBlank(value: unknown) {
  return value == null || value === '' || (typeof value === 'string' && value.trim() === '')
}

/* 천 단위 콤마. 건수·회차처럼 금액이 아닌 수치에 쓴다. */
export function int(value: unknown) {
  if (isBlank(value)) return EMPTY
  const parsed = Number(value)
  if (!isFinite(parsed)) return EMPTY
  return parsed.toLocaleString('ko-KR', { maximumFractionDigits: 0 })
}

/*
 * 금액. 음수는 마이너스 기호를 붙인다 (화면정의서 4장 규칙 1).
 * 예: -1,517,944원. 호출부가 isNegative() 로 판별해
 * .is-negative-amount 클래스로 빨간색을 함께 적용한다.
 */
export function won(value: unknown) {
  if (isBlank(value)) return EMPTY
  const parsed = Number(value)
  if (!isFinite(parsed)) return EMPTY
  const digits = Math.abs(parsed).toLocaleString('ko-KR', { maximumFractionDigits: 0 })
  return parsed < 0 ? '-' + digits + '원' : digits + '원'
}

export function isNegative(value: unknown) {
  if (isBlank(value)) return false
  const parsed = Number(value)
  return isFinite(parsed) && parsed < 0
}

/*
 * 소수 자리를 "자르기만" 한다 — 반올림하지 않는다 (화면정의서 4장 규칙 2·3).
 * 서버가 문자열("104.166667")로 주는 이유가 부동소수점 오차를 없애기 위해서이므로
 * Number 로 바꾸지 않고 문자열을 다룬다.
 */
function truncateDecimal(value: unknown, digits: number) {
  if (isBlank(value)) return EMPTY

  let normalized = String(value).trim()
  if (!/^[+-]?\d+(?:\.\d+)?$/.test(normalized)) return EMPTY

  let sign = ''
  if (normalized.charAt(0) === '-' || normalized.charAt(0) === '+') {
    sign = normalized.charAt(0) === '-' ? '-' : ''
    normalized = normalized.slice(1)
  }

  const parts = normalized.split('.')
  const integerPart = parts[0].replace(/^0+(?=\d)/, '')
  const fractionPart = ((parts[1] || '') + new Array(digits + 1).join('0')).slice(0, digits)
  return sign + integerPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + fractionPart
}

/*
 * 요율·배수 — 소수 첫째 자리. 예: 650.0%, 12.0배.
 */
export function rate(value: unknown) {
  return truncateDecimal(value, 1)
}

/*
 * 사용률 — 소수 첫째 자리. 예: 104.1%.
 * 저장·API의 소수 6자리와 서버 판정은 보존하고 화면 문자열만 절사한다.
 */
export function usageRate(value: unknown) {
  return rate(value)
}

/* 서버 안내 문구에 포함된 소수 백분율만 표시 규칙을 적용한다. 1,200% 같은 명칭은 유지한다. */
export function rateText(value: unknown) {
  return screenMessage(
    String(value == null ? '' : value).replace(
      /(^|[^\w.,+-])([+-]?\d[\d,]*\.\d+)(\s*%)/g,
      (_, prefix: string, decimal: string, suffix: string) => prefix + rate(decimal.replace(/,/g, '')) + suffix,
    ),
  )
}

function seoulParts(value: string | Date) {
  const date = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(date.getTime())) return null

  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: SEOUL,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(date)

  const found: Record<string, string> = {}
  parts.forEach(function (part) {
    found[part.type] = part.value
  })
  /* Intl 은 자정을 24 로 주는 구현이 있다 (hour12:false + en-CA). */
  if (found.hour === '24') found.hour = '00'
  return found
}

/*
 * YYYY-MM-DD.
 * 날짜만 있는 값(LocalDate)은 시간대가 없으므로 그대로 통과시킨다. 시각이 붙어 있으면
 * 앞 10자를 자르지 말고 Asia/Seoul 로 환산한다 — UTC 로 온 값은 자정 근처에서 날짜가 하루 어긋난다.
 */
export function date(value: unknown) {
  if (isBlank(value)) return EMPTY

  const text = String(value).trim()
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text

  const parts = seoulParts(text)
  return parts ? parts.year + '-' + parts.month + '-' + parts.day : EMPTY
}

/* YYYY-MM-DD HH:mm, Asia/Seoul 고정. */
export function dateTime(value: unknown) {
  if (isBlank(value)) return EMPTY

  const parts = seoulParts(String(value).trim())
  if (!parts) return EMPTY
  return parts.year + '-' + parts.month + '-' + parts.day + ' ' + parts.hour + ':' + parts.minute
}

/* YYYY-MM-DD HH:mm:ss, Asia/Seoul 고정. 감사로그 발생시각처럼 초까지 구분해야 하는 값에 쓴다. */
export function dateTimeSeconds(value: unknown) {
  if (isBlank(value)) return EMPTY

  const parts = seoulParts(String(value).trim())
  return parts ? dateTime(value) + ':' + parts.second : EMPTY
}

/* YYYY-MM. 정산월·귀속월은 서버가 항상 그 달 1일로 준다. */
export function month(value: unknown) {
  const normalized = date(value)
  return normalized === EMPTY ? EMPTY : normalized.slice(0, 7)
}

/* Asia/Seoul 기준 오늘. 계약일 max 같은 입력 제한에 쓴다. */
export function today() {
  const parts = seoulParts(new Date())!
  return parts.year + '-' + parts.month + '-' + parts.day
}

/** 서버 내부 오류(500)는 상세를 숨기고 오류 화면과 같은 문구로 요청번호를 알린다. */
export function internalErrorText(requestId?: string | null) {
  return requestId
    ? `처리 중 오류가 발생했습니다. 요청번호 ${requestId}를 담당자에게 알려주세요.`
    : '처리 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.'
}

/** 내부 오류 코드는 숨기고, 문의에 필요한 요청번호는 한 번만 표시한다. */
export function errorText(
  error?: { message?: string; code?: string; requestId?: string | null } | null,
  fallbackMessage?: string,
) {
  return errorScreenMessage(
    rateText((error && error.message) || fallbackMessage || '요청을 처리하지 못했습니다.'),
    error?.requestId,
  )
}
