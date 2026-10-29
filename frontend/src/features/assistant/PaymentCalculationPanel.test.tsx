import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { PaymentCalculationPanel } from './PaymentCalculationPanel'
import type { PaymentObservation } from './guide-runtime'
const observation: PaymentObservation = {
  owner: 'form-1',
  paymentId: '77',
  scopeKey: 'scope-1',
  revision: 2,
  canProcess: true,
  ready: true,
  amountValid: true,
  attributionsValid: true,
  formValid: true,
  saved: true,
  busy: false,
  precheck: 'NONE',
  modalOpen: false,
}
const snapshot = {
  calculationId: 'server-result',
  kind: 'ATTRIBUTION_BALANCE',
  mode: 'EXISTING',
  status: 'REVIEW_REQUIRED',
  clientRevision: 2,
  synthetic: false,
  snapshotOnly: true,
  currency: 'KRW',
  paymentStatus: 'DRAFT',
  resolvedScope: { paymentId: '77' },
  amounts: { amount: '100000', attributedTotal: '90000', differenceAmount: '10000' },
  capPreview: [],
  blockers: [],
  basis: { sourceRefs: [{ sourceId: 'payment:77', title: '저장된 지급 및 귀속 자료', entityId: '77' }] },
  recommendation: { allowed: false, reason: '차액은 추가 지급 추천이 아닙니다.' },
  validity: null,
}
const envelope = (data: unknown) => ({ data, error: null, requestId: 'QA' })
afterEach(() => vi.restoreAllMocks())
it('does not auto-fetch or query an unsaved form and hides candidate action for read roles', async () => {
  const request = vi.spyOn(apiClient, 'request')
  render(<PaymentCalculationPanel observation={{ ...observation, saved: false }} canProcess={false} />)
  expect(screen.getByRole('button', { name: '귀속 차액 조회' })).toBeDisabled()
  expect(screen.queryByRole('button', { name: '지급 후보 검토 조회' })).not.toBeInTheDocument()
  expect(request).not.toHaveBeenCalled()
})
it('requests only the selected stored payment and displays exact server strings with DRAFT label', async () => {
  const request = vi.spyOn(apiClient, 'request').mockResolvedValue(envelope(snapshot))
  render(<PaymentCalculationPanel observation={observation} canProcess />)
  await userEvent.click(screen.getByRole('button', { name: '귀속 차액 조회' }))
  expect(await screen.findByText('10,000원')).toBeInTheDocument()
  expect(screen.getByText(/저장 상태: DRAFT/)).toBeInTheDocument()
  expect(screen.getByText(/추천값 반영·가이드 통과에 사용할 수 없으며/)).toBeInTheDocument()
  expect(request).toHaveBeenCalledWith(
    '/api/v1/assistant/calculations',
    expect.objectContaining({
      method: 'POST',
      body: {
        contractVersion: 'assistant.v1',
        kind: 'ATTRIBUTION_BALANCE',
        mode: 'EXISTING',
        scope: { paymentId: '77' },
        candidate: null,
        clientRevision: 2,
      },
    }),
  )
  expect(request).toHaveBeenCalledTimes(1)
})
it('preserves null amounts and candidate blockers and never treats confirmable as completed precheck', async () => {
  vi.spyOn(apiClient, 'request').mockResolvedValue(
    envelope({
      ...snapshot,
      kind: 'PAYMENT_CANDIDATE',
      amounts: {},
      confirmable: false,
      capPreview: [
        {
          contractId: '3',
          limitAmount: null,
          includedAmount: '0',
          remainingAmount: null,
          candidateAmount: '0',
          resultStatus: 'NOT_APPLICABLE',
        },
      ],
      blockers: [{ code: 'FGC-CAP-002', message: '근거 확인' }],
    }),
  )
  render(<PaymentCalculationPanel observation={observation} canProcess />)
  await userEvent.click(screen.getByRole('button', { name: '지급 후보 검토 조회' }))
  expect(await screen.findByText(/FGC-CAP-002 · 근거 확인/)).toBeInTheDocument()
  expect(screen.getAllByText('확인 전')).toHaveLength(2)
  expect(screen.getByText(/실제 사전검증 완료 증표가 아닙니다/)).toBeInTheDocument()
})
it('rejects other payment/revision/synthetic responses and invalid money instead of showing them', async () => {
  const request = vi
    .spyOn(apiClient, 'request')
    .mockResolvedValue(envelope({ ...snapshot, resolvedScope: { paymentId: '88' } }))
  render(<PaymentCalculationPanel observation={observation} canProcess />)
  await userEvent.click(screen.getByRole('button', { name: '귀속 차액 조회' }))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  request.mockResolvedValue(envelope({ ...snapshot, amounts: { amount: 100000 } }))
  await userEvent.click(screen.getByRole('button', { name: '귀속 차액 조회' }))
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '서버 계산 조회 결과' })).not.toBeInTheDocument()
})
it('aborts on form identity/revision remount and discards late results', async () => {
  let resolve!: (value: ReturnType<typeof envelope>) => void
  const request = vi.spyOn(apiClient, 'request').mockReturnValue(
    new Promise((done) => {
      resolve = done
    }),
  )
  const view = render(<PaymentCalculationPanel key="revision-2" observation={observation} canProcess />)
  await userEvent.click(screen.getByRole('button', { name: '귀속 차액 조회' }))
  const signal = request.mock.calls[0][1]?.signal
  view.rerender(
    <PaymentCalculationPanel key="revision-3" observation={{ ...observation, revision: 3, saved: false }} canProcess />,
  )
  expect(signal?.aborted).toBe(true)
  await act(async () => {
    resolve(envelope(snapshot))
  })
  await waitFor(() => expect(screen.queryByText('10,000원')).not.toBeInTheDocument())
})

it('shows server-provided row rounding, dates and policy evidence without recalculating', async () => {
  vi.spyOn(apiClient, 'request').mockResolvedValue(
    envelope({
      ...snapshot,
      basis: {
        ...snapshot.basis,
        asOfDate: '2026-10-01',
        policyVersionIds: ['9'],
        ruleIds: ['5'],
        lines: [
          {
            attributionSequence: '1',
            contractId: '3',
            attributionDate: '2026-10-01',
            originalAmount: '0.50',
            roundedAmount: '1',
          },
        ],
      },
    }),
  )
  render(<PaymentCalculationPanel observation={observation} canProcess />)
  await userEvent.click(screen.getByRole('button', { name: '귀속 차액 조회' }))
  await screen.findByText('귀속 행별 반올림 근거')
  await userEvent.click(screen.getByText('귀속 행별 반올림 근거'))
  expect(screen.getByText(/원천 0.50원 → 반올림 1원/)).toBeInTheDocument()
  expect(screen.getByText(/배부 정책 버전: 9/)).toBeInTheDocument()
})
