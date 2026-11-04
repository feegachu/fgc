import { act, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { KpiCard } from './KpiCard'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

it('공통 KPI 숫자는 0부터 채워지고 변경된 값의 소수점 형식을 유지한다', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] })
  const view = render(<KpiCard guideId={undefined} label="처리 건수" value={1234} unit="건" />)
  expect(screen.getByText('0')).toBeVisible()
  act(() => vi.advanceTimersByTime(350))
  expect(Number(screen.getByText(/^[\d,]+$/, { selector: '[aria-hidden="true"]' }).textContent?.replaceAll(',', '')))
    .toBeGreaterThan(0)
  act(() => vi.advanceTimersByTime(400))
  expect(screen.getByText('1,234')).toBeInTheDocument()
  view.rerender(<KpiCard guideId={undefined} label="비중" value={12.3} unit="%" format={(value) => value.toFixed(1)} />)
  act(() => vi.advanceTimersByTime(750))
  expect(screen.getByText('12.3')).toBeInTheDocument()
})

it('동작 줄이기에서는 최종 숫자를 바로 표시하고 빈 값 표기도 보존한다', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true }))
  const view = render(<KpiCard guideId={undefined} label="처리 건수" value={1234} unit="건" />)
  expect(screen.getByText('1,234')).toBeInTheDocument()
  view.rerender(<KpiCard guideId={undefined} label="처리 건수" value="-" unit="건" />)
  expect(screen.getByText('-')).toBeInTheDocument()
})
