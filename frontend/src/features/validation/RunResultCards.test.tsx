import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { RunSummaryCards } from './RunResultCards'

const detail = {
  capSummary: { checkedCount: 1200, violationCount: 3, warningCount: 2, reviewRequiredCount: 1 },
  arbitrageSummary: { checkedCount: 10, candidateCount: 4, reviewRequiredCount: 0 },
  ledgerSummary: { journalCount: 30, imbalanceCount: 0 },
  reconciliationSummary: { resultCount: 22, mismatchCount: 5 },
  exceptionSummary: { openWorkItemCount: 8 },
}

it('결과 요약 카드는 무엇 중 몇 건인지 분모를 함께 보여 준다', () => {
  render(<RunSummaryCards detail={detail} completed={8} />)
  expect(screen.getByText('검사 1,200건 중, 확정 위반 판정')).toBeInTheDocument()
  expect(screen.getByText('검사 1,200건 중, 한도에 가까움')).toBeInTheDocument()
  expect(screen.getByText('계약 10건 중, 위반 확정 아님')).toBeInTheDocument()
  expect(screen.getByText('분개 30건 중, 0건이어야 확정')).toBeInTheDocument()
  expect(screen.getByText('대사 22건 중, 허용오차 0원')).toBeInTheDocument()
  expect(screen.getByText('이 검증월의 신규와 검토중')).toBeInTheDocument()
})

it('분모가 없는 응답이면 설명만 보여 준다', () => {
  render(<RunSummaryCards detail={{ ...detail, capSummary: { violationCount: 3, warningCount: 2 } }} completed={8} />)
  expect(screen.getByText('확정 위반 판정')).toBeInTheDocument()
  expect(screen.getByText('한도에 가까움')).toBeInTheDocument()
})
