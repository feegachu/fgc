import { expect, it } from 'vitest'
import { reconciliationExplanation } from './explanation'
import { exceptionDescription } from '../exception/labels'

it('0원의 원인을 계약 상태로 단정하지 않고 서버가 분류한 사유를 구분한다', () => {
  expect(reconciliationExplanation({ expectedTotalAmount: 0, actualTotalAmount: 650000, resultType: 'INVALID_CONTRACT_PAYMENT' })).toBe('')
  expect(reconciliationExplanation({ expectedTotalAmount: 0, actualTotalAmount: 650000, resultType: 'EXPECTED_MISSING' })).toContain('지급 스케줄과 적용 정책도 확인')
  expect(reconciliationExplanation({ expectedTotalAmount: 0, actualTotalAmount: 0, resultType: 'MATCHED' })).toBe('예상 지급액과 실제 지급 자료가 일치합니다.')
  expect(reconciliationExplanation({ expectedTotalAmount: 0, resultType: 'ACTUAL_MISSING' })).toContain('실제 지급 자료가 없습니다')
  expect(reconciliationExplanation({ expectedTotalAmount: 0, resultType: 'DUPLICATE' })).toContain('중복으로 기록')
})

it('금액 차이도 별도 설명 없이 금액 카드로 표시한다', () => {
  expect(reconciliationExplanation({ resultType: 'AMOUNT_DIFFERENCE', differenceAmount: 300000 })).toBe('')
  expect(reconciliationExplanation({ resultType: 'AMOUNT_DIFFERENCE', differenceAmount: -300000 })).toBe('')
  expect(exceptionDescription('지급단계 GA→설계사 예상 0원 / 실제 650,000원 차액 650,000원')).toBe('지급단계 GA→설계사\n예상 0원\n실제 650,000원\n차액 650,000원')
})
