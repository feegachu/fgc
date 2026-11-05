import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { columns } from './columns'
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
  expect(screen.getByText(label!)).toHaveAttribute('title')
})
