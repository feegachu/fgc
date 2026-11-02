import { render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import type { ArbitrageRow } from './api'
import { ArbitrageTimeline } from './ArbitrageTimeline'

const row = (id: number, asOfDate: string, amount?: number): ArbitrageRow => ({ arbitrageCheckId: id, asOfDate, netDifferenceAmount: amount })

it('날짜와 원 단위 축, 설명 및 저장된 초과액의 실제 값이 표시된다', () => {
  render(<ArbitrageTimeline rows={[row(1, '2026-07-31', 0), row(2, '2026-08-20', 410000)]} />)
  const chart = screen.getByRole('img', { name: '기준일별 초과액 추이' })
  expect(chart).toHaveAccessibleDescription('선택한 계약과 지급단계의 기준일별 지급분 초과액을 비교합니다.')
  expect(chart).toHaveTextContent('지급분 기준 초과액 (원)')
  expect(chart).toHaveTextContent('2026-07-31')
  expect(chart).toHaveTextContent('2026-08-20')
  expect(chart.querySelectorAll('.arb-timeline-tick').length).toBeGreaterThan(2)
  expect(chart.querySelectorAll('circle title')[1]).toHaveTextContent('410,000원')
})

it('날짜 사이 간격을 실제 경과일에 비례해서 표시한다', () => {
  render(<ArbitrageTimeline rows={[row(1, '2026-07-01', 0), row(2, '2026-07-02', 100), row(3, '2026-07-31', 200)]} />)
  const points = [...screen.getByRole('img').querySelectorAll('circle')].map((point) => Number(point.getAttribute('cx')))
  expect((points[1] - points[0]) / (points[2] - points[0])).toBeCloseTo(1 / 30, 2)
})

it('단일 0원 이력도 유효한 좌표로 표시한다', () => {
  render(<ArbitrageTimeline rows={[row(1, '2026-07-31', 0)]} />)
  const point = screen.getByRole('img').querySelector('circle')!
  expect(Number.isFinite(Number(point.getAttribute('cx')))).toBe(true)
  expect(Number.isFinite(Number(point.getAttribute('cy')))).toBe(true)
  expect(screen.getByRole('img').querySelectorAll('.arb-timeline-tick')).toHaveLength(2)
})

it('금액 누락을 0원으로 그리지 않고 선을 끊어 표시한다', () => {
  render(<ArbitrageTimeline rows={[row(1, '2026-07-01', 100), row(2, '2026-07-02'), row(3, '2026-07-31', -200)]} />)
  const chart = screen.getByRole('img')
  expect(chart.querySelectorAll('circle')).toHaveLength(2)
  expect(chart.querySelectorAll('polyline')).toHaveLength(2)
  expect(chart.querySelectorAll('circle title')[1]).toHaveTextContent('-200원')
})
