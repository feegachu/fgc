import { won } from '../../lib/format'

export interface AmountPair {
  debitAmount: string
  creditAmount: string
}

// 빈칸은 쓰지 않는 쪽의 0 으로 본다. 숫자가 아니거나 음수·소수는 null 로 돌려 오류로 처리한다.
function wonAmount(value: string): number | null {
  const text = value.trim()
  if (text === '') return 0
  const parsed = Number(text)
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : null
}

/**
 * 재기표 라인을 제출 전에 검사한다 — 서버(JournalCorrectionServiceImpl)와 같은 규칙이다.
 *   · 각 라인은 차변·대변 중 한쪽만 양수 (둘 다 0 이거나 둘 다 양수면 거부)
 *   · 차변 합계와 대변 합계가 같고 0 보다 커야 함
 * 문제가 없으면 null, 있으면 사용자에게 보여 줄 문구를 돌려준다.
 */
export function validateCorrectionLines(lines: AmountPair[]): string | null {
  let debitTotal = 0
  let creditTotal = 0
  for (const [index, line] of lines.entries()) {
    const debit = wonAmount(line.debitAmount)
    const credit = wonAmount(line.creditAmount)
    if (debit === null || credit === null) {
      return `${index + 1}번 라인의 금액은 0 이상의 정수(원 단위)로 입력하세요.`
    }
    if (debit > 0 === credit > 0) {
      return `${index + 1}번 라인은 차변 또는 대변 중 한쪽에만 금액을 입력하세요.`
    }
    debitTotal += debit
    creditTotal += credit
  }
  if (debitTotal <= 0 || debitTotal !== creditTotal) {
    return `차변 합계(${won(debitTotal)})와 대변 합계(${won(creditTotal)})가 같아야 합니다.`
  }
  return null
}
