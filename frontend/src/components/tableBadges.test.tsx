import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'
import { DataTable } from './DataTable'
import { CategoryBadge, StatusBadge } from './StatusBadge'
import { CapStatus } from '../features/cap/badges'
import { ContractStatusBadge, DataOriginBadge } from '../features/contract/badges'
import { CalculationBadge } from '../features/clawback/badges'

it('표의 전체 선택지가 4종 이상이면 배지로, 3종 이하면 텍스트로 표시하며 필터 결과 수에 영향받지 않는다', () => {
  const rows = [{ id: 1 }, { id: 2 }]
  const columns = [
    { key: 'four', label: '4종', render: () => <CategoryBadge optionCount={4}>네 가지 유형</CategoryBadge> },
    { key: 'three', label: '3종', render: () => <CategoryBadge optionCount={3}>세 가지 유형</CategoryBadge> },
    { key: 'status', label: '계약 상태', render: () => <ContractStatusBadge code="ACTIVE" label="서버·계약 상태" /> },
    { key: 'origin', label: '출처', render: () => <DataOriginBadge code="SEED" /> },
    {
      key: 'source',
      label: '원천유형',
      render: () => (
        <CategoryBadge value="INSURER_STATEMENT" optionCount={7} tablePresentation="text">
          원수사 명세
        </CategoryBadge>
      ),
    },
    {
      key: 'clawback',
      label: '환수 상태',
      render: () => <CalculationBadge row={{ calculationStatus: 'UNCALCULATED' }} />,
    },
    {
      key: 'severity',
      label: '심각도',
      render: () => (
        <StatusBadge kind="severity" optionCount={4} tablePresentation="text" tone="error">
          긴급
        </StatusBadge>
      ),
    },
    { key: 'cap', label: '1,200% 판정', render: () => <CapStatus code="VIOLATION" /> },
  ]
  const table = (filteredRows: typeof rows) => (
    <DataTable caption="표 배지 기준" rows={filteredRows} rowKey={(row) => String(row.id)} columns={columns} />
  )
  const { rerender } = render(table(rows))
  expect(screen.getAllByText('네 가지 유형')).toHaveLength(2)
  rerender(table(rows.slice(0, 1)))
  const scope = within(screen.getByRole('table', { name: '표 배지 기준' }))
  for (const label of ['네 가지 유형', '위반']) {
    expect(scope.getByText(label)).toHaveAttribute('data-table-presentation', 'badge')
  }
  expect(scope.getByText('서버/계약 상태')).toHaveAttribute('data-table-presentation', 'dot')
  expect(scope.getByText('미계산')).toHaveAttribute('data-table-presentation', 'dot')
  for (const label of ['세 가지 유형', '시드', '긴급', '원수사 명세']) {
    expect(scope.getByText(label)).toHaveAttribute('data-table-presentation', 'text')
  }
})

it('1,200% 판정의 모든 상태는 배지를 유지하고 빈 분류는 배지를 만들지 않는다', () => {
  const codes = ['NORMAL', 'WARNING', 'VIOLATION', 'REVIEW_REQUIRED', 'NOT_APPLICABLE']
  const { container } = render(
    <>
      {codes.map((code) => (
        <CapStatus key={code} code={code} label={code} />
      ))}
      <CategoryBadge value="-" optionCount={4} />
    </>,
  )
  for (const code of codes) expect(screen.getByText(code)).toHaveAttribute('data-table-presentation', 'badge')
  expect(container.querySelectorAll('.status-badge')).toHaveLength(5)
  expect(screen.getByText('-')).not.toHaveClass('status-badge')
})
