import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { columns, compactColumns } from './columns'
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
  if (basis === 'UNRESOLVED_UPPER_BOUND_V1')
    expect(screen.getByText('연결이 불명확한 지급은 차감하지 않은 상한입니다. 자료 확인 전에는 확정된 잔여액으로 해석하지 마세요.')).toBeVisible()
})


it.each([
  ['REMAINING_V1', '잔여 지급예정액'],
  ['UNRESOLVED_UPPER_BOUND_V1', '지급예정액 상한'],
  [null, '지급예정액 (기준 미기록)'],
  ['UNRECOGNIZED', '지급예정액 (기준 확인 필요)'],
])('간결한 목록은 금액만 보이면서 저장 기준 %s를 hover·보조기술에 제공한다', (basis, label) => {
  const planned = compactColumns.find(column => column.key === 'planned')!
  render(<div>{planned.render!({ plannedCommissionBasis: basis, plannedCommissionAmount: 50000 })}</div>)
  const amount = screen.getByText('50,000원')
  expect(amount).toBeVisible()
  expect(amount.parentElement?.getAttribute('title')).toContain(label)
  expect(amount.parentElement?.querySelector('.visually-hidden')).toHaveTextContent(label!)
  expect(document.querySelector('.arb-planned-basis')).toBeNull()
})
