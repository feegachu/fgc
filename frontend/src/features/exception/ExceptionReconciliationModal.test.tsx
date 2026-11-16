import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { ExceptionReconciliationModal } from './ExceptionReconciliationModal'

afterEach(() => vi.restoreAllMocks())
const detected = { reconciliationResultId: 1, reconciliationRunId: 10, contractId: 3, contractNo: 'C-3', commissionItemName: '기본수수료',
  expectedTotalAmount: 0, actualTotalAmount: 650000, differenceAmount: 650000, resultType: 'INVALID_CONTRACT_PAYMENT', resultTypeLabel: '비유효 계약 지급' }
const current = { ...detected, reconciliationResultId: 2, reconciliationRunId: 11, actualTotalAmount: 0, differenceAmount: 0,
  resultType: 'MATCHED', resultTypeLabel: '일치', detailSnapshot: { originalActualAmount: 650000, recoveryDeductionAmount: 650000 } }

function setup(removed = false) {
  vi.spyOn(apiClient, 'request').mockImplementation((async (path: string) => ({ data:
    path.endsWith('/basis') ? { detected, currentRunId: 11, currentResults: removed ? [] : [current] }
      : path.endsWith('/status-events') ? []
        : path === '/api/v1/contracts/3' ? { contractId: 3, contractStatus: 'CANCELLED', contractStatusLabel: '청약철회' }
          : path.includes('/results?') ? { items: { content: removed ? [] : path.includes('/11/') ? [current] : [detected], totalPages: 1 } }
            : {}, error: null, requestId: 'reco-basis-test' })) as typeof apiClient.request)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><ExceptionReconciliationModal resultId="1" onClose={() => {}} /></QueryClientProvider>)
  return userEvent.setup()
}

it('처리 후 재대사와 환수 차감을 기본 표시하고 검출 원본을 별도로 확인한다', async () => {
  const actions = setup()
  const modal = await screen.findByRole('dialog', { name: '계약 대사 결과' })
  expect(await within(modal).findByText(/원지급 650,000원에서 환수 차감 650,000원이 반영/)).toBeVisible()
  expect(within(modal).getByRole('button', { name: '처리 후 대사' })).toHaveAttribute('aria-pressed', 'true')
  expect(modal.querySelector('.exception-reco-amounts')).toHaveTextContent('실제 지급 자료0원')
  await actions.click(within(modal).getByRole('button', { name: '검출 당시 대사' }))
  expect(within(modal).queryByText(/검출 당시 계약 상태가 지급 조건에 맞지 않아/)).not.toBeInTheDocument()
  expect(modal.querySelector('.exception-reco-amounts')).toHaveTextContent('실제 지급 자료650,000원')
  await actions.click(within(modal).getByRole('button', { name: '처리 후 대사' }))
  expect(await within(modal).findByText(/차감 후 지급액 0원과 예상 지급액 0원이 일치/)).toBeVisible()
})

it('정정 후 사라진 항목은 일치나 예전 금액으로 꾸미지 않고 비교 항목 없음으로 표시한다', async () => {
  setup(true)
  expect(await screen.findByText(/처리 후 대사에는 이 지급 항목이 없습니다/)).toBeVisible()
  expect(document.querySelector('.exception-reco-amounts')).toHaveTextContent('실제 지급 자료비교 항목 없음')
  expect(document.querySelector('.exception-reco-heading')).not.toHaveTextContent('비유효 계약 지급')
})
