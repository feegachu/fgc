import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { donutSegments } from './donut'
import { RiskComposition } from './RiskComposition'
import type { CompositionItem } from './RiskComposition'

const items: CompositionItem[] = [
  { key: 'a', label: '1,200% 위반', value: 6, color: 'error', to: '/cap-checks?status=VIOLATION' },
  { key: 'b', label: '1,200% 주의', value: 3, color: 'warning', to: '/cap-checks?status=WARNING' },
  { key: 'c', label: '대사 불일치', value: 1, color: 'info', to: '/reconciliations' },
  { key: 'd', label: '원장 불균형', value: 0, color: 'success', to: '/journals' },
]

function renderChart(list = items) {
  return render(
    <MemoryRouter>
      <RiskComposition items={list} />
    </MemoryRouter>,
  )
}

describe('donutSegments', () => {
  it('0건 항목은 조각을 만들지 않고 조각 길이는 비중에 비례하며 시작 위치가 이어진다', () => {
    const segments = donutSegments(items)
    expect(segments.map((s) => s.key)).toEqual(['a', 'b', 'c'])
    expect(segments[0].share).toBeCloseTo(0.6)
    expect(segments[1].offset).toBeCloseTo(segments[0].length + 1.5)
    expect(segments[0].length).toBeGreaterThan(segments[1].length)
  })

  it('조각이 하나뿐이면 틈 없이 원 전체를 채운다', () => {
    const [only] = donutSegments([{ key: 'x', value: 5 }])
    expect(only.length).toBeCloseTo(2 * Math.PI * 42)
  })
})

describe('RiskComposition', () => {
  it('범례 줄이 이름·건수·비중을 접근 가능한 이름으로 갖고 목록 화면으로 연결한다', () => {
    renderChart()
    const violation = screen.getByRole('link', { name: '1,200% 위반 6건, 비중 60%' })
    expect(violation).toHaveAttribute('href', '/cap-checks?status=VIOLATION')
    expect(screen.getByRole('link', { name: '원장 불균형 0건, 비중 0%' })).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(4)
  })

  it('범례 줄에 올리면 해당 줄과 조각이 강조되고 나가면 해제된다', async () => {
    const actions = userEvent.setup()
    const { container } = renderChart()
    const warning = screen.getByRole('link', { name: /1,200% 주의/ })
    await actions.hover(warning)
    expect(warning).toHaveClass('is-active')
    expect(container.querySelectorAll('.risk-donut-segment.is-dimmed')).toHaveLength(2)
    expect(container.querySelectorAll('.risk-donut-segment.is-active')).toHaveLength(1)
    await actions.unhover(warning)
    expect(container.querySelectorAll('.risk-donut-segment.is-dimmed')).toHaveLength(0)
  })

  it('키보드 포커스로도 강조된다', async () => {
    const actions = userEvent.setup()
    renderChart()
    await actions.tab()
    expect(screen.getByRole('link', { name: /1,200% 위반/ })).toHaveClass('is-active')
  })

  it('중앙에는 합계 대신 안내 문구만 두어 단위가 다른 건수를 더하지 않는다', () => {
    const { container } = renderChart()
    expect(container.querySelector('.risk-donut-center')).toHaveTextContent('항목별 비중')
    expect(screen.queryByText('10')).not.toBeInTheDocument()
  })

  it('모든 건수가 0이면 빈 상태를 보여 준다', () => {
    renderChart(items.map((item) => ({ ...item, value: 0 })))
    expect(screen.getByText('이번 달 표시할 위험 건이 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
  })
})
