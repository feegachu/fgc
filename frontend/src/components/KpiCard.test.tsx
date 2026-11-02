import { render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { KpiCard } from './KpiCard'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

it('공통 KPI 숫자는 올라가는 효과 없이 처음부터 최종값과 소수점 표시 형식을 보인다', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  const view = render(<KpiCard label="처리 건수" value={1234} unit="건" />)
  expect(screen.getByText('1,234')).toBeInTheDocument()
  view.rerender(<KpiCard label="비중" value={12.3} unit="%" format={(value) => value.toFixed(1)} />)
  expect(screen.getByText('12.3')).toBeInTheDocument()
})

it('동작 줄이기에서는 최종 숫자를 바로 표시하고 빈 값 표기도 보존한다', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  const view = render(<KpiCard label="처리 건수" value={1234} unit="건" />)
  expect(screen.getByText('1,234')).toBeInTheDocument()
  view.rerender(<KpiCard label="처리 건수" value="-" unit="건" />)
  expect(screen.getByText('-')).toBeInTheDocument()
})
