import { readFileSync } from 'node:fs'
import { afterEach, expect, it, vi } from 'vitest'
import { waitFor } from '@testing-library/react'
import * as format from '../../lib/format'

const html = readFileSync('../src/main/resources/templates/cap/list.html', 'utf8')
const source = readFileSync('../src/main/resources/static/js/features/cap/cap-list.js', 'utf8')

afterEach(() => { document.body.innerHTML = ''; vi.unstubAllGlobals() })

it('legacy CAP list distinguishes unavailable amounts from a real zero amount', async () => {
  document.body.innerHTML = html
  window.history.replaceState({}, '', '/cap-checks?month=2026-07')
  const request = vi.fn(async (path: string) => ({ data: path.startsWith('/api/v1/base/') ? { content: [] } : {
    summary: {}, stageSummary: [], agentSummary: [], page: 1, totalPages: 1, totalElements: 2,
    content: [
      { capCheckId: 77, contractNo: 'BEFORE-2021', paymentStage: 'INSURER_TO_GA', resultStatus: 'NOT_APPLICABLE',
        resultStatusLabel: '적용대상 아님', basePremiumAmount: 100000, refund12mAmount: null,
        complianceDeductionAmount: null, limitAmount: null, includedAmount: null, remainingAmount: null, usagePct: null },
      { capCheckId: 78, contractNo: 'ZERO-2026', paymentStage: 'INSURER_TO_GA', resultStatus: 'NORMAL',
        resultStatusLabel: '정상', basePremiumAmount: 0, refund12mAmount: 0,
        complianceDeductionAmount: 0, limitAmount: 0, includedAmount: 0, remainingAmount: 0, usagePct: '0' },
    ],
  } }))
  vi.stubGlobal('FgcUi', { apiClient: { request }, modal: { open: vi.fn() }, format })
  window.eval(source)
  await waitFor(() => expect(document.querySelectorAll('#cap-contract-body tr')).toHaveLength(2))
  const rows = document.querySelectorAll('#cap-contract-body tr')
  for (const index of [4, 5, 6, 7, 8]) {
    expect(rows[0].querySelectorAll('td')[index].textContent).toBe('—')
  }
  expect(rows[1].textContent).toContain('0원')
})
