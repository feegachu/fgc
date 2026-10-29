import { render, screen, within } from '@testing-library/react'
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
    ['650.000000', '650.0'],
    ['34.800000', '34.8'],
    ['1.234567', '1.2'],
    ['1200', '1,200.0'],
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
  expect(within(rows[0]).getByText('1.2')).toBeInTheDocument()
  expect(within(rows[0]).getByText('1,200,000')).toBeInTheDocument()
  expect(rows[0].querySelectorAll('td')[5]).toHaveTextContent('-')
  expect(within(rows[1]).getByText('- ~ 12회차')).toBeInTheDocument()
  expect(within(rows[2]).getByText('1 ~ -회차')).toBeInTheDocument()
})

it('긴 정책 값은 HTML을 escape하고 전체 보기 없이 원문을 표시한다 (#597)', () => {
  const { container } = render(
    <>
      <ReferenceText value="짧은 정책명" limit={20} />
      <ReferenceText value="매우 긴 <정책> 이름과 판단 사유" limit={10} />
    </>,
  )
  expect(screen.getByText('매우 긴 <정책> 이름과 판단 사유')).toBeInTheDocument()
  expect(container.querySelectorAll('details, summary')).toHaveLength(0)
  expect(container.querySelector('정책')).toBeNull()
  expect(container.innerHTML).toContain('&lt;정책&gt;')
})
