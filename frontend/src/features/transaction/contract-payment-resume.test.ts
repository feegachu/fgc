import { beforeEach, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import { readPayment, type Payment } from './form-api'
import { loadSchedulePayments } from './contract-payment-resume'

vi.mock('../../lib/api/client', () => ({ apiClient: { request: vi.fn() } }))
vi.mock('./form-api', () => ({ readPayment: vi.fn() }))

const payment: Payment = {
  commissionTransactionId: 12, contractId: 3, paymentStage: 'GA_TO_FC',
  settlementMonth: '2026-07-01', sourceType: 'GA_MANUAL_PAYMENT',
  sourceBusinessKey: 'SCHEDULE-LINE-GA_TO_FC-7', status: 'DRAFT',
  updatedAt: '2026-07-01T12:00:00', attributions: [{ contractId: 3, amount: 100 }],
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(apiClient.request).mockResolvedValue({ error: null, requestId: 'resume', data: { transactions: [payment], reconciliations: [] } })
  vi.mocked(readPayment).mockResolvedValue(payment)
})

it('restores the exact schedule draft and ignores unrelated payment scopes', async () => {
  vi.mocked(apiClient.request).mockResolvedValue({ error: null, requestId: 'resume', data: { transactions: [
    { ...payment, paymentStage: 'INSURER_TO_GA' },
    { ...payment, settlementMonth: '2026-08-01' },
    { ...payment, sourceType: 'COMMON_COST' }, payment,
  ] } })
  expect(await loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7])).toEqual({ 7: payment })
  expect(readPayment).toHaveBeenCalledTimes(1)
})

it('restores confirmed rows so they cannot be registered again', async () => {
  vi.mocked(readPayment).mockResolvedValue({ ...payment, status: 'CONFIRMED' })
  expect((await loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7]))[7].status).toBe('CONFIRMED')
})

it.each(['DRAFT', 'CONFIRMED'] as const)('restores an exact loader payment in status %s', async status => {
  const loaded = { ...payment, sourceBusinessKey: 'GA-202607-L7', status }
  vi.mocked(readPayment).mockResolvedValue(loaded)
  expect(await loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7])).toEqual({ 7: loaded })
})

it.each(['GA-202607-L7-2', 'GA-202608-L7', 'MANUAL-7'])('does not absorb unrelated manual key %s', async sourceBusinessKey => {
  vi.mocked(readPayment).mockResolvedValue({ ...payment, sourceBusinessKey })
  expect(await loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7])).toEqual({})
})

it('rejects two payment key formats for the same schedule line', async () => {
  vi.mocked(apiClient.request).mockResolvedValue({ error: null, requestId: 'resume', data: { transactions: [payment, payment] } })
  vi.mocked(readPayment).mockResolvedValueOnce(payment).mockResolvedValueOnce({ ...payment, sourceBusinessKey: 'GA-202607-L7' })
  await expect(loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7])).rejects.toThrow('중복')
})

it.each([
  { status: 'CANCELLED' }, { contractId: 4 }, { updatedAt: undefined },
  { attributions: [{ contractId: 4 }] },
  { attributions: [{ contractId: 3 }, { contractId: 4 }] },
] as Partial<Payment>[])('blocks unsafe restored payment %j', async patch => {
  vi.mocked(readPayment).mockResolvedValue({ ...payment, ...patch })
  await expect(loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7])).rejects.toThrow()
})

it('blocks duplicate schedule keys rather than choosing an arbitrary draft', async () => {
  vi.mocked(apiClient.request).mockResolvedValue({ error: null, requestId: 'resume', data: { transactions: [payment, payment] } })
  await expect(loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7])).rejects.toThrow('중복')
})

it('does not mistake a failed contract lookup for an empty payment list', async () => {
  vi.mocked(apiClient.request).mockResolvedValue({ error: null, requestId: 'resume', data: {} })
  await expect(loadSchedulePayments('3', 'GA_TO_FC', '2026-07', [7])).rejects.toThrow('확인하지 못했습니다')
})
