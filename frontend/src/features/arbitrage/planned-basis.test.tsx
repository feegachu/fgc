import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import userEvent from '@testing-library/user-event'
import { columns, compactColumns } from './columns'
import { UPPER_BOUND_NOTE } from './planned-basis'
import type { ArbitrageRow } from './api'

afterEach(cleanup)

it.each([
  ['REMAINING_V1', '잔여 지급예정액'],
  ['UNRESOLVED_UPPER_BOUND_V1', '지급예정액 상한'],
  [null, '지급예정액 (기준 미기록)'],
  ['UNRECOGNIZED', '지급예정액 (기준 확인 필요)'],
])('목록은 같은 금액이라도 저장 기준 %s를 구분한다', (basis, label) => {
  const planned = columns.find(column => column.key === 'planned')!
  const row: ArbitrageRow = { plannedCommissionBasis: basis, plannedCommissionAmount: 50000 }
  render(<div>{planned.render!(row)}</div>)
  expect(screen.getByText('50,000원')).toBeVisible()
  expect(screen.getByText(label!)).toBeVisible()
  expect(screen.getByText(label!)).not.toHaveAttribute('title')
  // 설명은 행마다 반복하지 않고 표 머리의 도움말에서 한 번만 본다.
  expect(screen.queryByText(UPPER_BOUND_NOTE)).not.toBeInTheDocument()
})


it.each([
  ['REMAINING_V1', '잔여 지급예정액'],
  ['UNRESOLVED_UPPER_BOUND_V1', '지급예정액 상한'],
  [null, '지급예정액 (기준 미기록)'],
  ['UNRECOGNIZED', '지급예정액 (기준 확인 필요)'],
])('간결한 목록은 금액만 보이면서 저장 기준 %s를 키보드로 확인한다', async (basis, label) => {
  const planned = compactColumns.find(column => column.key === 'planned')!
  render(<div>{planned.render!({ plannedCommissionBasis: basis, plannedCommissionAmount: 50000 })}</div>)
  const amount = screen.getByText('50,000원')
  expect(amount).toBeVisible()
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  expect(document.querySelector('.arb-planned-basis')).toBeNull()
  const actions = userEvent.setup()
  await actions.tab()
  const trigger = screen.getByRole('button', { name: '50,000원' })
  expect(trigger).toHaveFocus()
  const tooltip = await screen.findByRole('tooltip')
  expect(tooltip).toHaveTextContent(label!)
  expect(trigger).toHaveAttribute('aria-describedby', tooltip.id)
  if (basis === 'UNRESOLVED_UPPER_BOUND_V1') expect(tooltip).toHaveTextContent('확정된 잔여액으로 해석하지 마세요')
  await actions.keyboard('{Escape}')
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
})
