import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it } from 'vitest'
import type { ArbitrageRow } from './api'
import { ArbitrageAmountSummary } from './ArbitrageAmountSummary'

const row: ArbitrageRow = {
  paidCommissionAmount: 640000,
  includedSurrenderValueAmount: 0,
  cumulativePaidPremium: 100000,
  plannedCommissionAmount: 850000,
  netDifferenceAmount: 540000,
  projectedExcessAmount: 1390000,
}
afterEach(() => {
  localStorage.removeItem('fgc:arbitrage:amount-view')
  localStorage.removeItem('fgc:arbitrage:amount-view:v2')
})

it('기존 비교형 저장값이 있어도 목록형을 첫 번째 기본 보기로 제공한다', () => {
  localStorage.setItem('fgc:arbitrage:amount-view', 'compare')
  render(<ArbitrageAmountSummary row={row} />)
  const buttons = within(screen.getByRole('group', { name: '금액 표시 방식' })).getAllByRole('button')
  expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual(['목록형', '비교형', '카드형'])
  expect(buttons[0]).toHaveAttribute('aria-pressed', 'true')
  expect(document.querySelectorAll('.arb-amount-list > div')).toHaveLength(4)
})

it('비교형은 현재 금액을 초과액 위의 막대로 비교하고 전체 예정액은 참고 영역에 분리한다', async () => {
  const user = userEvent.setup()
  render(<ArbitrageAmountSummary row={row} />)
  await user.click(screen.getByRole('button', { name: '비교형' }))
  const summary = screen.getByRole('region', { name: '차익거래 금액 요약' })
  expect(screen.getByRole('button', { name: '비교형' })).toHaveAttribute('aria-pressed', 'true')
  expect(summary.querySelectorAll('.arb-amount-track')).toHaveLength(3)
  expect(
    summary.querySelector('.arb-amount-bars')!.compareDocumentPosition(summary.querySelector('.arb-amount-excess')!) &
      Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy()
  expect(summary.querySelector('.arb-amount-reference')).toHaveTextContent('전체 지급예정액850,000원')
  expect(summary).not.toHaveTextContent('1,390,000원')
  expect(within(summary).getByText('540,000원')).toBeVisible()
})

it('작은 아이콘으로 세 가지 보기를 전환하고 다음 계약과 새 화면에서도 선택을 유지한다', async () => {
  const user = userEvent.setup()
  const { rerender } = render(<ArbitrageAmountSummary row={row} />)
  await user.click(screen.getByRole('button', { name: '카드형' }))
  expect(document.querySelectorAll('.arb-amount-cards > div')).toHaveLength(3)
  await user.click(screen.getByRole('button', { name: '목록형' }))
  expect(document.querySelectorAll('.arb-amount-list > div')).toHaveLength(4)
  rerender(<ArbitrageAmountSummary row={{ ...row, netDifferenceAmount: 0 }} />)
  expect(screen.getByRole('button', { name: '목록형' })).toHaveAttribute('aria-pressed', 'true')
  cleanup()
  render(<ArbitrageAmountSummary row={row} />)
  expect(screen.getByRole('button', { name: '목록형' })).toHaveAttribute('aria-pressed', 'true')
  await user.click(screen.getByRole('button', { name: '비교형' }))
  expect(document.querySelector('.arb-amount-bars')).toBeInTheDocument()
})

it('누락된 금액을 0원으로 바꾸지 않고 음수의 부호와 막대 방향을 유지한다', async () => {
  const user = userEvent.setup()
  render(
    <ArbitrageAmountSummary
      row={{ ...row, paidCommissionAmount: -1234, plannedCommissionAmount: undefined, netDifferenceAmount: -987 }}
    />,
  )
  await user.click(screen.getByRole('button', { name: '비교형' }))
  expect(screen.getByText('-1,234원')).toHaveClass('is-negative-amount')
  expect(screen.getByText('-987원')).toHaveClass('is-negative-amount')
  expect(document.querySelector('.arb-amount-reference')).toHaveTextContent('전체 지급예정액-')
  expect(document.querySelector('.arb-amount-fill.is-negative')).toHaveStyle({ left: '0%' })
  expect(document.querySelectorAll('.arb-amount-zero')).toHaveLength(3)
})
