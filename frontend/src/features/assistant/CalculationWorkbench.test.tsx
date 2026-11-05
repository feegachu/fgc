import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { CalculationWorkbench } from './CalculationWorkbench'
const snapshot = {
  calculationId: 'c1',
  kind: 'EXPECTED_COMMISSION',
  mode: 'EXISTING',
  status: 'CALCULATED',
  clientRevision: 0,
  synthetic: false,
  snapshotOnly: true,
  evidenceKind: 'EXPECTED',
  currency: 'KRW',
  amounts: { expectedAmount: '100000' },
  lines: [],
  basis: { sourceRefs: [], policyVersionIds: ['9'], ruleIds: ['8'], rounding: { mode: 'HALF_UP' } },
  validity: { stateToken: 'calculation-proof', expiresAt: '2099-01-01T00:00:00Z' },
  recommendation: { allowed: true, suggestedAmount: '100000' },
}
const envelope = (data: unknown) => ({ data, error: null, requestId: 'test' })
afterEach(() => vi.restoreAllMocks())
it('does not call a calculator automatically and preserves string amounts in hypothetical input', async () => {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockResolvedValue(
      envelope({
        ...snapshot,
        kind: 'ATTRIBUTION_BALANCE',
        mode: 'HYPOTHETICAL',
        evidenceKind: 'HYPOTHETICAL',
        amounts: { differenceAmount: '-1' },
        recommendation: { allowed: false },
      }),
    )
  render(<CalculationWorkbench canProcess={false} />)
  expect(request).not.toHaveBeenCalled()
  await userEvent.click(screen.getByText('업무 계산 도우미'))
  await userEvent.selectOptions(screen.getByLabelText('계산 업무'), 'ATTRIBUTION_BALANCE')
  await userEvent.type(screen.getByLabelText('지급액'), '1.49')
  await userEvent.type(screen.getByLabelText('귀속 금액 (쉼표로 구분)'), '0.50,0.50')
  await userEvent.click(screen.getByRole('button', { name: '계산 조회' }))
  expect(await screen.findByText('-1원')).toBeInTheDocument()
  expect(request).toHaveBeenCalledWith(
    '/api/v1/assistant/calculations',
    expect.objectContaining({
      body: expect.objectContaining({
        candidate: { balance: { amount: '1.49', attributionAmounts: ['0.50', '0.50'] } },
      }),
    }),
  )
  expect(screen.queryByRole('option', { name: '현재 입력의 지급 후보 검토' })).not.toBeInTheDocument()
})
it('revalidates with the original input and blocks applying a stale recommendation', async () => {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockResolvedValueOnce(envelope(snapshot))
    .mockResolvedValueOnce(
      envelope({ ...snapshot, status: 'STALE', amounts: {}, validity: null, recommendation: { allowed: false } }),
    )
  const apply = vi.fn(() => true)
  render(<CalculationWorkbench canProcess onApply={apply} />)
  await userEvent.click(screen.getByText('업무 계산 도우미'))
  await userEvent.type(screen.getByLabelText('계약 번호'), '3')
  await userEvent.click(screen.getByRole('button', { name: '계산 조회' }))
  await screen.findByText('100,000원')
  await userEvent.click(screen.getByRole('button', { name: '재확인 후 지급액에 반영' }))
  await waitFor(() => expect(screen.queryByText('100,000원')).not.toBeInTheDocument())
  expect(apply).not.toHaveBeenCalled()
  expect(request.mock.calls[1][0]).toBe('/api/v1/assistant/calculations/revalidate')
  expect(request.mock.calls[1][1]?.body).toEqual({
    calculationId: 'c1',
    stateToken: 'calculation-proof',
    input: request.mock.calls[0][1]?.body,
  })
})
it('discards current calculation when selection inputs change', async () => {
  vi.spyOn(apiClient, 'request').mockResolvedValue(envelope(snapshot))
  render(<CalculationWorkbench canProcess />)
  await userEvent.click(screen.getByText('업무 계산 도우미'))
  await userEvent.click(screen.getByRole('button', { name: '계산 조회' }))
  await screen.findByText('100,000원')
  await userEvent.type(screen.getByLabelText('계약 번호'), '4')
  expect(screen.queryByText('100,000원')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '최신 근거 재확인' })).not.toBeInTheDocument()
})
it('previews a candidate without calling draft or confirmation writes', async () => {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockResolvedValue(
      envelope({
        ...snapshot,
        kind: 'PAYMENT_CANDIDATE',
        mode: 'HYPOTHETICAL',
        evidenceKind: 'HYPOTHETICAL',
        recommendation: { allowed: false },
      }),
    )
  render(<CalculationWorkbench canProcess candidate={() => ({ amount: '50000', contractId: '3' })} paymentId="77" />)
  await userEvent.click(screen.getByText('업무 계산 도우미'))
  await userEvent.selectOptions(screen.getByLabelText('계산 업무'), 'PAYMENT_CANDIDATE')
  await userEvent.click(screen.getByRole('button', { name: '계산 조회' }))
  await screen.findByText('계산 완료')
  expect(request).toHaveBeenCalledTimes(1)
  expect(request).toHaveBeenCalledWith(
    '/api/v1/assistant/calculations',
    expect.objectContaining({
      body: expect.objectContaining({
        scope: { paymentId: '77' },
        candidate: { operation: 'REPLACE_DRAFT', payment: { amount: '50000', contractId: '3' } },
      }),
    }),
  )
})
