import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '../../lib/api/client'
import type { Payment } from './form-api'
import { draftRows, initialForm } from './form-model'
import { PaymentGuideObserver, paymentGuideConditions } from './payment-guide'
import type { PaymentGuideInput } from './payment-guide'

const draft: Payment = {
  commissionTransactionId: 77,
  status: 'DRAFT',
  updatedAt: '2026-10-28T10:00:00',
  paymentStage: 'GA_TO_FC',
  settlementMonth: '2026-07-01',
  sourceBusinessKey: 'D77',
  agentId: 1,
  commissionItemId: 1,
  amount: 200,
  cashflowType: 'PAYMENT',
  attributions: [
    {
      contractId: 3,
      amount: 200,
      attributionDate: '2026-07-01',
      inclusionDecisionStatus: 'INCLUDED',
      attributionMethod: 'DIRECT',
    },
  ],
}
let observer: PaymentGuideObserver
let input: PaymentGuideInput
let response: Payment
const read = () => observer.readBusinessState(observer.readContext(), new AbortController().signal)
const passed = { paymentId: 77, confirmable: true, blockers: [] }
beforeEach(() => {
  observer = new PaymentGuideObserver()
  input = {
    role: 'SETTLEMENT',
    canProcess: true,
    paymentId: '77',
    form: initialForm('2026-07', draft),
    rows: draftRows(draft),
    dirty: false,
    ready: true,
    valid: true,
  }
  observer.update(input)
  response = structuredClone(draft)
  vi.spyOn(apiClient, 'request').mockImplementation(
    async () => ({ data: response, error: null, requestId: 'G' }) as never,
  )
})
afterEach(() => {
  observer.dispose()
  vi.restoreAllMocks()
})
const precheck = () => observer.acceptPrecheck(observer.begin('precheck'), draft, passed, draft)

describe('payment guide server evidence', () => {
  it('requires current server data, not input validity or a next-button acknowledgement', async () => {
    observer.update({ ...input, paymentId: null, dirty: true })
    observer.acknowledge('SCOPE_ACKNOWLEDGED')
    const state = await read()
    expect(state.valid).toBe(true)
    expect(state.saved).toBe(false)
    expect(state.prechecked).toBe(false)
    expect(state.confirmed).toBe(false)
    expect(apiClient.request).not.toHaveBeenCalled()
  })
  it('accepts matching current precheck, not a blocked or missing decision', async () => {
    expect(precheck()).toBe(true)
    expect((await read()).prechecked).toBe(true)
    expect(
      observer.acceptPrecheck(
        observer.begin('precheck'),
        draft,
        { ...passed, confirmable: false, blockers: [{ code: 'FGC-CAP-001' }] },
        draft,
      ),
    ).toBe(true)
    expect((await read()).prechecked).toBe(false)
    observer.acceptPrecheck(observer.begin('precheck'), draft, { paymentId: 77 }, draft)
    expect((await read()).prechecked).toBe(false)
  })
  it('rejects a response without identity or a changed saved version during precheck', () => {
    expect(observer.acceptPrecheck(observer.begin('precheck'), draft, { confirmable: true }, draft)).toBe(false)
    expect(observer.acceptPrecheck(observer.begin('precheck'), draft, passed, { ...draft, updatedAt: 'later' })).toBe(
      false,
    )
  })
  it.each(['amount', 'updatedAt', 'status'] as const)(
    'invalidates earlier results after remote %s changes',
    async (field) => {
      precheck()
      response = { ...draft, [field]: field === 'amount' ? 300 : field === 'status' ? 'CANCELLED' : 'later' }
      await expect(read()).rejects.toThrow('SERVER_STATE_CHANGED')
      expect((await read()).prechecked).toBe(false)
    },
  )
  it('ignores stale operation results after changing inputs, role, or selected payment', () => {
    for (const patch of [{ form: { ...input.form, amount: '300' } }, { role: 'COMPLIANCE' }, { paymentId: '88' }]) {
      const operation = observer.begin('precheck')
      observer.update({ ...input, ...patch })
      expect(observer.acceptPrecheck(operation, draft, passed, draft)).toBe(false)
      observer.update(input)
    }
  })
  it('rejects stale reads even when the transport ignores cancellation', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(apiClient.request).mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }) as never,
    )
    const pending = read()
    observer.invalidateInput()
    resolve({ data: draft })
    await expect(pending).rejects.toThrow('CONTEXT_CHANGED')
  })
  it('requires a matching CONFIRMED response and fresh CONFIRMED detail', async () => {
    const operation = observer.begin('confirm')
    const confirmed: Payment = { ...draft, status: 'CONFIRMED', updatedAt: 'later' }
    expect(observer.acceptConfirmed(operation, draft, confirmed)).toBe(false)
    expect(observer.acceptConfirmed(operation, confirmed, draft)).toBe(false)
    expect(observer.acceptConfirmed(operation, { ...confirmed, commissionTransactionId: 88 }, confirmed)).toBe(false)
    expect(observer.acceptConfirmed(operation, confirmed, confirmed)).toBe(true)
    response = confirmed
    const state = await read()
    expect(paymentGuideConditions.PAYMENT_CONFIRMED(observer.readContext(), state)).toBe('SATISFIED')
    expect(state.saved).toBe(false)
  })
  it('does not reuse a confirmation merely because GET shows CONFIRMED', async () => {
    response = { ...draft, status: 'CONFIRMED' }
    expect((await read()).confirmed).toBe(false)
  })
  it('rejects ambiguous contracts and unsafe IDs without picking a first row', () => {
    observer.update({ ...input, rows: [...input.rows, { ...input.rows[0], key: 2, contractId: '4' }] })
    expect(() => observer.readContext()).toThrow('MULTIPLE_CONTRACTS_UNSUPPORTED')
    observer.update({ ...input, paymentId: '9007199254740993' })
    expect(() => observer.readContext()).toThrow('INVALID_ENTITY_REFS')
  })
  it('clears acknowledgements on edits and denies missing processing permission', async () => {
    observer.acknowledge('AMOUNT_REVIEW_ACKNOWLEDGED')
    expect((await read()).acknowledged).toContain('AMOUNT_REVIEW_ACKNOWLEDGED')
    observer.update({ ...input, canProcess: false })
    expect(observer.readContext().role).toBe('UNAUTHORIZED')
    expect((await read()).acknowledged).toEqual([])
  })
  it('only performs detail GETs while evaluating conditions', async () => {
    await read()
    const [url, options] = vi.mocked(apiClient.request).mock.calls[0]
    expect(url).toBe('/api/v1/transactions/77')
    expect(options?.method).toBeUndefined()
    expect(options?.body).toBeUndefined()
  })
})

describe('step-specific input guidance', () => {
  it('allows scope review before amount and attribution inputs are completed', async () => {
    observer.update({
      ...input,
      paymentId: null,
      valid: false,
      form: { ...input.form, commissionItemId: '', amount: '' },
      rows: [],
    })
    observer.acknowledge('SCOPE_ACKNOWLEDGED')
    const state = await read()
    expect(paymentGuideConditions.SCOPE_ACKNOWLEDGED(observer.readContext(), state)).toBe('SATISFIED')
    expect(paymentGuideConditions.EVIDENCE_FIELDS_VALID(observer.readContext(), state)).toBe('WAITING_INPUT')
    expect(state.amountReady).toBe(false)
    expect(state.attributionsReady).toBe(false)
    expect(state.saved).toBe(false)
  })
  it('requires the recipient for GA payments and matching attribution totals', async () => {
    observer.update({
      ...input,
      paymentId: null,
      form: { ...input.form, agentId: '' },
      rows: [{ ...input.rows[0], amount: '100' }],
    })
    expect((await read()).scopeReady).toBe(false)
    expect((await read()).attributionsReady).toBe(false)
    observer.update({ ...input, paymentId: null, form: { ...input.form, agentId: '', paymentStage: 'INSURER_TO_GA' } })
    expect((await read()).scopeReady).toBe(true)
    expect((await read()).attributionsReady).toBe(true)
  })
})
