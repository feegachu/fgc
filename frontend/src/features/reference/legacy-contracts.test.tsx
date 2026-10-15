import { render, screen, within, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { CommissionRules } from '../policies/PolicyDetailTables'
import { ReferenceText } from './ReferenceUI'
import { int, rate } from '../../lib/format'

afterEach(() => vi.unstubAllGlobals())

it('정책 숫자·회차 표시는 1차 node 정책 테스트의 입력 계약을 유지한다 (#418)', () => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
  for (const [input, output] of [
    ['650.000000', '650.0000'],
    ['34.800000', '34.8000'],
    ['1.234567', '1.2345'],
    ['1200', '1,200.0000'],
  ])
    expect(rate(input)).toBe(output)
  expect(int(1200000)).toBe('1,200,000')
  for (const value of [null, '', '   ', 'not-a-number']) {
    expect(int(value)).toBe('-')
    expect(rate(value)).toBe('-')
  }
  render(
    <CommissionRules
      rows={[
        { commissionRuleId: 1, ratePct: '1.234567', fixedAmount: 1200000 },
        { commissionRuleId: 2, installmentTo: 12 },
        { commissionRuleId: 3, installmentFrom: 1 },
      ]}
    />,
  )
  const rows = screen.getAllByRole('row').slice(1)
  expect(within(rows[0]).getByText('1.2345')).toBeInTheDocument()
  expect(within(rows[0]).getByText('1,200,000')).toBeInTheDocument()
  expect(rows[0].querySelectorAll('td')[5]).toHaveTextContent('-')
  expect(within(rows[1]).getByText('- ~ 12회차')).toBeInTheDocument()
  expect(within(rows[2]).getByText('1 ~ -회차')).toBeInTheDocument()
})

it('긴 정책 값은 HTML을 escape하고 실제 잘림일 때만 전체 보기·접기를 제공한다 (#418)', async () => {
  let resized = () => {}
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: () => void) {
        resized = callback
      }
      observe() {}
      disconnect() {}
    },
  )
  const { container } = render(
    <>
      <ReferenceText value="짧은 정책명" limit={20} />
      <ReferenceText value="매우 긴 <정책> 이름과 판단 사유" limit={10} />
    </>,
  )
  expect(container.querySelectorAll('details')).toHaveLength(1)
  expect(container.querySelector('정책')).toBeNull()
  expect(container.innerHTML).toContain('&lt;정책&gt;')
  const preview = container.querySelector<HTMLElement>('.table-cell-preview')!
  const details = container.querySelector('details')!
  Object.defineProperty(preview, 'clientWidth', { configurable: true, value: 100 })
  Object.defineProperty(preview, 'scrollWidth', { configurable: true, value: 200 })
  act(() => resized())
  expect(details.hidden).toBe(false)
  await userEvent.click(container.querySelector('summary')!)
  expect(details.open).toBe(true)
  Object.defineProperty(preview, 'scrollWidth', { configurable: true, value: 100 })
  act(() => resized())
  expect(details.hidden).toBe(true)
  expect(details.open).toBe(false)
})
