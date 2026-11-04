import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { AgentHome } from './api'
import { PriorityContracts } from './PriorityContracts'

describe('FUN-086 확인 기록 날짜', () => {
  it.each(['2026-07-31T15:30:00Z', '2026-08-01T00:30:00+09:00'])(
    '응답 오프셋과 무관하게 서울 날짜로 표시한다: %s', (checkedAt) => {
      const home: AgentHome = {
        asOf: '2026-08-01', period: '12', range: { from: '2025-09-01', to: '2026-08-01' },
        filters: { insurerType: 'ALL' }, priorityContractTotal: 1,
        priorityContracts: [{
          contractId: 1, contractNo: 'C-1', insurerName: '보험사', productName: '상품',
          currentStatus: '정상', currentStatusCode: 'ACTIVE', reasonCodes: ['FOLLOW_UP'],
          contextVersion: 'event-1', expectedAmount: 0, heldAmount: 0, heldCount: 0,
          lastCheck: { result: 'FOLLOW_UP', checkedAt, nextCheckDate: '2026-08-02' },
        }],
        myCommission: { month: '2026-08', expected: { amount: 0, count: 0 },
          held: { amount: 0, count: 0 }, clawback: { amount: 0, count: 0 }, dataStatus: 'AVAILABLE' },
        portfolio: { contractCount: 1, insurers: [], monthly: [], categories: [], statuses: [] },
        basis: { asOf: '2026-08-01', warnings: [] }, dataStatus: 'AVAILABLE',
      }
      render(<MemoryRouter><PriorityContracts home={home} /></MemoryRouter>)
      expect(screen.getByText(/마지막 확인 2026-08-01 후속 확인/)).toBeVisible()
      expect(screen.queryByText(/마지막 확인 2026-07-31/)).not.toBeInTheDocument()
    },
  )
})
