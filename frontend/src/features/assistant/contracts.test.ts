import { describe, expect, it, vi } from 'vitest'
import type { AssistantContext, WorkflowId } from './contracts'
import { GUIDE_CATALOG, findGuide, findStep, selectGuideTransition } from './catalog'
import {
  checkCalculationRecommendation,
  checkGuideProposal,
  isAmountInput,
  isCurrentResponse,
  isEntityId,
  isWonAmount,
} from './guards'
import { PAYMENT_SCENARIOS, readPaymentFixture } from './fixtures'

const now = Date.parse('2026-10-28T00:00:00Z')
const context = (): AssistantContext => ({
  screenId: 'TRAN-W02',
  paymentId: '1027',
  contractId: '101',
  settlementMonth: '2026-10',
  paymentStage: 'GA_TO_FC',
  installmentNo: 1,
  commissionItemId: '1',
  beneficiaryAgentId: '7',
  formDirty: false,
  clientRevision: 3,
})

describe('assistant.v1 boundary and registered guides (#628)', () => {
  it('rejects selectors, unknown workflows/steps and version drift', () => {
    expect(findGuide('javascript:alert(1)', '1.0')).toBeNull()
    expect(findGuide('payment.confirm', 'old')).toBeNull()
    expect(findStep('payment.confirm', '1.0', '#btn-confirm')).toBeNull()
    expect(findStep('payment.confirm', '1.0', 'payment.confirm')?.completionRuleId).toBe('PAYMENT_CONFIRMED')
    for (const guide of GUIDE_CATALOG) {
      expect(new Set(guide.steps.map((step) => step.stepId)).size).toBe(guide.steps.length)
      expect(Object.isFrozen(guide)).toBe(true)
      expect(Object.isFrozen(guide.steps)).toBe(true)
    }
  })
  it('keeps amount-only review and draft saving separate from confirmation', () => {
    for (const id of ['payment.amount.review', 'payment.draft']) {
      expect(findGuide(id, '1.0')?.steps.some((step) => step.targetId === 'payment.confirm')).toBe(false)
    }
    expect(findGuide('payment.confirm', '1.0')?.steps.map((step) => step.completionRuleId)).toContain(
      'CURRENT_PRECHECK_PASSED',
    )
  })
  it('preserves Long identity without Number coercion and enforces canonical amount inputs', () => {
    expect(isEntityId('9223372036854775807')).toBe(true)
    for (const value of ['9223372036854775808', '0', '-1', '01', '1e3', 12]) expect(isEntityId(value)).toBe(false)
    for (const value of ['0', '100000', '9999999999999.99', '0.49']) expect(isAmountInput(value)).toBe(true)
    for (const value of ['-1', '1e3', '10,000', '01', '1.001', '10000000000000', 100])
      expect(isAmountInput(value)).toBe(false)
    expect(isWonAmount('-50000')).toBe(true)
    expect(isWonAmount('90071992547409930000')).toBe(true)
    for (const value of ['-0', '1.5', '01', 1]) expect(isWonAmount(value)).toBe(false)
  })
  it('blocks synthetic evidence by default and rejects stale revision, scope or expiry', () => {
    const c = context(),
      response = readPaymentFixture('PAY-02', c, now)
    expect(checkGuideProposal(response, c, { now })).toMatchObject({ ok: false, reason: 'SYNTHETIC_ONLY' })
    expect(checkGuideProposal(response, c, { now, mode: 'synthetic' }).ok).toBe(true)
    expect(checkGuideProposal(response, { ...c, clientRevision: 4 }, { now, mode: 'synthetic' })).toMatchObject({
      reason: 'STALE_REVISION',
    })
    expect(checkGuideProposal(response, { ...c, paymentId: '99' }, { now, mode: 'synthetic' })).toMatchObject({
      reason: 'CONTEXT_CHANGED',
    })
    expect(checkGuideProposal(response, c, { now: now + 300_000, mode: 'synthetic' })).toMatchObject({
      reason: 'EXPIRED',
    })
    response.workflowProposal!.expiresAt = 'not-a-date'
    expect(checkGuideProposal(response, c, { now, mode: 'synthetic' }).ok).toBe(false)
    expect(isCurrentResponse({ clientRevision: -1 }, { ...c, clientRevision: -1 })).toBe(false)
  })
  it('does not accept a forged workflow or guide version even in explicit fixture mode', () => {
    const c = context(),
      response = readPaymentFixture('PAY-02', c, now)
    response.workflowProposal!.workflowId = 'unregistered' as WorkflowId
    expect(checkGuideProposal(response, c, { now, mode: 'synthetic' })).toMatchObject({ reason: 'UNREGISTERED_GUIDE' })
  })
})

describe('PAY-01~12 explicit synthetic fixtures', () => {
  it('never invokes a network or financial endpoint and always labels fixtures', () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('No network allowed'))
    try {
      for (const scenario of PAYMENT_SCENARIOS) {
        const response = readPaymentFixture(scenario, context(), now)
        expect(response.synthetic).toBe(true)
        expect(response.text).toContain('합성 시연')
        for (const calc of response.calculations) {
          expect(calc.synthetic).toBe(true)
          expect(calc.validity.stateToken).toContain('not-a-proof')
          expect(calc.basis.rounding.aggregation).toBe('ROUND_EACH_LINE_THEN_SUM')
        }
      }
      expect(fetch).not.toHaveBeenCalled()
    } finally {
      fetch.mockRestore()
    }
  })
  it('asks for missing scope without silently choosing a contract or a payment', () => {
    const c = { ...context(), contractId: null, paymentStage: null }
    expect(readPaymentFixture('PAY-01', c, now)).toMatchObject({
      outcome: 'CLARIFICATION_REQUIRED',
      missingSlots: ['contractId', 'paymentStage'],
      calculations: [],
    })
    expect(readPaymentFixture('PAY-04', { ...context(), paymentId: null }, now).missingSlots).toEqual(['paymentId'])
    expect(readPaymentFixture('PAY-02', { ...context(), paymentId: null }, now).workflowProposal?.workflowId).toBe(
      'payment.draft',
    )
  })
  it('distinguishes a balance gap, correction amount, missing candidate, role denial and stale state', () => {
    expect(readPaymentFixture('PAY-03', context(), now).calculations[0]).toMatchObject({
      amounts: { differenceAmount: '10000' },
      recommendation: { allowed: false },
    })
    expect(readPaymentFixture('PAY-07', context(), now).calculations[0]).toMatchObject({
      amounts: { differenceAmount: '-50000', correctedAmount: null },
    })
    expect(readPaymentFixture('PAY-05', context(), now).calculations[0]).toMatchObject({
      mode: 'HYPOTHETICAL',
      status: 'INSUFFICIENT_DATA',
      confirmable: null,
    })
    expect(readPaymentFixture('PAY-09', context(), now).clarification?.options).toHaveLength(3)
    expect(readPaymentFixture('PAY-10', context(), now)).toMatchObject({
      outcome: 'PERMISSION_DENIED',
      workflowProposal: null,
      calculations: [],
    })
    expect(readPaymentFixture('PAY-11', context(), now).calculations[0]).toMatchObject({
      status: 'UNSUPPORTED',
      amounts: {},
    })
    expect(readPaymentFixture('PAY-12', context(), now).calculations[0]).toMatchObject({
      status: 'STALE',
      recommendation: { allowed: false },
    })
  })
  it('does not claim real precheck success, isolate copies and derives expiry from the supplied clock', () => {
    const c = context(),
      first = readPaymentFixture('PAY-02', c, now)
    first.workflowProposal!.resolvedContext.paymentId = '999'
    expect(c.paymentId).toBe('1027')
    expect(readPaymentFixture('PAY-02', c, now).workflowProposal!.resolvedContext.paymentId).toBe('1027')
    expect(readPaymentFixture('PAY-04', c, now)).toMatchObject({
      outcome: 'PRECONDITION_REQUIRED',
      workflowProposal: null,
    })
    expect(Date.parse(readPaymentFixture('PAY-01', c, now).calculations[0].validity.expiresAt)).toBe(now + 300_000)
    const edited = readPaymentFixture('PAY-08', c, now)
    expect(checkGuideProposal(edited, c, { now, mode: 'synthetic' })).toMatchObject({ reason: 'CONTEXT_CHANGED' })
    expect(() => readPaymentFixture('PAY-01', c, NaN)).toThrow()
  })
})

describe('calculation recommendation boundary', () => {
  it('requires explicit fixture mode, fresh context and a valid suggested amount', () => {
    const c = context(),
      calc = readPaymentFixture('PAY-01', c, now).calculations[0]
    expect(checkCalculationRecommendation(calc, c, { now })).toMatchObject({ reason: 'SYNTHETIC_ONLY' })
    expect(checkCalculationRecommendation(calc, c, { now, mode: 'synthetic' })).toMatchObject({
      ok: true,
      suggestedAmount: '100000',
    })
    expect(checkCalculationRecommendation(calc, { ...c, contractId: '99' }, { now, mode: 'synthetic' })).toMatchObject({
      reason: 'CONTEXT_CHANGED',
    })
    expect(checkCalculationRecommendation(calc, c, { now: now + 300_000, mode: 'synthetic' })).toMatchObject({
      reason: 'EXPIRED',
    })
    calc.recommendation.targetId = 'payment.save'
    expect(checkCalculationRecommendation(calc, c, { now, mode: 'synthetic' })).toMatchObject({
      reason: 'UNREGISTERED_TARGET',
    })
    calc.recommendation.targetId = 'payment.amount'
    calc.status = 'REVIEW_REQUIRED'
    expect(checkCalculationRecommendation(calc, c, { now, mode: 'synthetic' })).toMatchObject({
      reason: 'NOT_RECOMMENDED',
    })
    calc.status = 'CALCULATED'
    calc.recommendation.suggestedAmount = '-50000'
    expect(checkCalculationRecommendation(calc, c, { now, mode: 'synthetic' })).toMatchObject({
      reason: 'INVALID_AMOUNT',
    })
  })
})

it('registers role/next metadata and rejects missing proposal slots and unscoped recommendations', () => {
  for (const guide of GUIDE_CATALOG) {
    expect(guide.roles.length).toBeGreaterThan(0)
    for (const step of guide.steps) {
      for (const edge of step.next) expect(guide.steps.some((candidate) => candidate.stepId === edge.stepId)).toBe(true)
    }
  }
  const c = context()
  const response = readPaymentFixture('PAY-02', c, now)
  response.workflowProposal!.workflowId = 'payment.confirm'
  response.workflowProposal!.resolvedContext.paymentId = null
  expect(checkGuideProposal(response, { ...c, paymentId: null }, { now, mode: 'synthetic' })).toMatchObject({
    reason: 'MISSING_REQUIRED_CONTEXT',
  })
  const calc = readPaymentFixture('PAY-01', c, now).calculations[0]
  calc.resolvedScope = null
  expect(checkCalculationRecommendation(calc, c, { now, mode: 'synthetic' })).toMatchObject({
    reason: 'UNRESOLVED_SCOPE',
  })
})

it('routes a current saved draft to precheck and requires saving for missing or changed input', () => {
  const step = findStep('payment.confirm', '1.0', 'payment.scope')!
  expect(step.next).toEqual([
    { conditionId: 'CURRENT_DRAFT_ALREADY_SAVED', stepId: 'payment.precheck' },
    { conditionId: 'DRAFT_SAVE_REQUIRED', stepId: 'payment.save' },
  ])
  expect(selectGuideTransition(step, { paymentId: '77', saved: true, formValid: true })).toBe('payment.precheck')
  for (const context of [
    { paymentId: '77', saved: false, formValid: true },
    { paymentId: null, saved: true, formValid: true },
    { paymentId: '77', saved: true, formValid: false },
  ]) expect(selectGuideTransition(step, context)).toBe('payment.save')
  expect(() => selectGuideTransition({ ...step, next: [step.next[0], step.next[0]] }, { paymentId: '77', saved: true, formValid: true })).toThrow()
  expect(() => selectGuideTransition({ ...step, next: [step.next[0]] }, { paymentId: null, saved: false, formValid: true })).toThrow()
})
