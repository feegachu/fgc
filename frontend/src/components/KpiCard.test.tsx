import { act, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { KpiCard } from './KpiCard'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

it('공통 KPI 숫자가 올라오고 변경된 값과 소수점 표시 형식도 유지한다', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] })
  const view = render(<KpiCard label="처리 건수" value={1234} unit="건" />)
  const counter = screen.getByText('0')
  act(() => vi.advanceTimersByTime(250))
  const intermediate = Number(counter.textContent?.replaceAll(',', ''))
  expect(intermediate).toBeGreaterThan(0)
  expect(intermediate).toBeLessThan(1234)
  act(() => vi.advanceTimersByTime(800))
  expect(counter).toHaveTextContent('1,234')
  view.rerender(<KpiCard label="비중" value={12.3} unit="%" format={(value) => value.toFixed(1)} />)
  act(() => vi.advanceTimersByTime(800))
  expect(counter).toHaveTextContent('12.3')
})

it('동작 줄이기에서는 최종 숫자를 바로 표시하고 빈 값 표기도 보존한다', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  const view = render(<KpiCard label="처리 건수" value={1234} unit="건" />)
  expect(screen.getByText('1,234')).toBeInTheDocument()
  view.rerender(<KpiCard label="처리 건수" value="-" unit="건" />)
  expect(screen.getByText('-')).toBeInTheDocument()
})
