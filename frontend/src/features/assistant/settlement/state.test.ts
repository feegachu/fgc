import { destinations } from './catalog'
import { describe, expect, it } from 'vitest'
import { decide, STEPS } from './state'
import type { Evidence, Selection } from './state'
const selection: Selection = {
  month: '2026-07',
  stage: 'GA_TO_FC',
  contractId: 1,
  scheduleId: 2,
  paymentId: 3,
  validationRunId: 4,
  reconciliationRunId: 5,
}
function evidence(): Evidence {
  return {
    contract: { contractId: 1, contractNo: 'C1', insurerId: 1 },
    schedule: {
      header: {
        scheduleHeaderId: 2,
        activeYn: true,
        contractNo: 'C1',
        paymentStage: 'GA_TO_FC',
        scheduleRegime: 'CURRENT',
        schedulePurpose: 'OPERATIONAL',
      },
      lines: [{ lineNo: 1 }],
    },
    scheduleAcknowledged: true,
    payment: {
      commissionTransactionId: 3,
      settlementMonth: '2026-07-01',
      paymentStage: 'GA_TO_FC',
      status: 'CONFIRMED',
      attributions: [{ contractId: 1 }],
    },
    validation: {
      targets: [{ contractNo: 'C1', selectionStatus: 'SELECTED' }],
      header: { validationRunId: 4, validationMonth: '2026-07-01', runType: 'MONTHLY', status: 'COMPLETED' },
    },
    reconciliation: {
      reconciliationRunId: 5,
      insurerId: 1,
      validationRunId: 4,
      settlementMonth: '2026-07-01',
      paymentStage: 'GA_TO_FC',
      status: 'COMPLETED',
    },
    results: { summary: { resultCount: 1, matchedCount: 1, exceptionCount: 0 } },
    openExceptions: 0,
    checklist: { validationRunId: 4, passed: true, conditions: Array.from({ length: 8 }, () => ({ passed: true })) },
  }
}
describe('FUN-088 server evidence decisions', () => {
  it('registers exactly nine logical stages', () => expect(STEPS).toHaveLength(9))
  it('does not call a completed calculation finalized', () =>
    expect(decide(selection, evidence(), 'GA_ADMIN')).toMatchObject({ step: 'validation.finalize' }))
  it('only finishes on the actual FINALIZED state', () => {
    const state = evidence()
    state.validation!.header!.status = 'FINALIZED'
    expect(decide(selection, state, 'SETTLEMENT').complete).toBe(true)
  })
  it('routes a cancelled original payment to explicit correction selection without completing', () => {
    const state = evidence()
    state.payment!.status = 'CANCELLED'
    expect(decide(selection, state, 'SETTLEMENT')).toMatchObject({ step: 'exception.resolve' })
    expect(decide(selection, state, 'SETTLEMENT').complete).toBeUndefined()
  })
  it('rechecks a selected replacement payment against the existing contract and month', () => {
    const state = evidence()
    state.payment!.commissionTransactionId = 30
    expect(decide({ ...selection, paymentId: 30 }, state, 'GA_ADMIN').step).toBe('validation.finalize')
    state.payment!.attributions = [{ contractId: 999 }]
    expect(() => decide({ ...selection, paymentId: 30 }, state, 'GA_ADMIN')).toThrow('현재 안내 대상')
  })
  it('starts a new contract without synthetic IDs', () =>
    expect(decide({ month: '2026-07', stage: 'GA_TO_FC' }, { scheduleAcknowledged: false }, 'SETTLEMENT').step).toBe(
      'contract.create',
    ))
  it.each(['FOUR_YEAR', 'SEVEN_YEAR', undefined])('never sends %s schedules into payment', (regime) => {
    const state = evidence()
    state.schedule!.header!.scheduleRegime = regime
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('schedule.review')
  })
  it('requires explicit schedule review', () => {
    const state = evidence()
    state.scheduleAcknowledged = false
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('schedule.review')
  })
  it('waits for a real draft save', () => {
    const state = evidence()
    state.payment = undefined
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('payment.draft')
  })
  it('does not restore old precheck success from a draft', () => {
    const state = evidence()
    state.payment!.status = 'DRAFT'
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('payment.precheck')
  })
  it('invalidates edited input before precheck', () => {
    const state = evidence()
    state.payment!.status = 'DRAFT'
    state.paymentGuide = {
      available: true,
      valid: true,
      saved: false,
      prechecked: false,
      confirmed: false,
      scopeReady: true,
      amountReady: true,
      attributionsReady: true,
      acknowledged: [],
    }
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('payment.draft')
  })
  it('uses current observer precheck evidence', () => {
    const state = evidence()
    state.payment!.status = 'DRAFT'
    state.paymentGuide = {
      available: true,
      valid: true,
      saved: true,
      prechecked: true,
      confirmed: false,
      scopeReady: true,
      amountReady: true,
      attributionsReady: true,
      acknowledged: [],
    }
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('payment.confirm')
  })
  it.each(['CREATED', 'RUNNING', 'FAILED'] as const)('does not advance validation %s', (status) => {
    const state = evidence()
    state.validation!.header!.status = status
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('validation.execute')
  })
  it.each(['RUNNING', 'FAILED'])('does not accept reconciliation %s', (status) => {
    const state = evidence()
    state.reconciliation!.status = status
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('reconciliation.review')
  })
  it('branches mismatch to exception and only returns after fresh matched results', () => {
    const state = evidence()
    state.results!.summary!.exceptionCount = 1
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('exception.resolve')
    state.results!.summary!.exceptionCount = 0
    state.openExceptions = 1
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('exception.resolve')
    state.openExceptions = 0
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('validation.finalize')
  })
  it('does not accept an empty reconciliation as a verified match', () => {
    const state = evidence()
    state.results!.summary = { resultCount: 0, matchedCount: 0, exceptionCount: 0 }
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('reconciliation.review')
  })
  it('rejects reconciliation without the contract insurer', () => {
    const state = evidence()
    state.contract!.insurerId = undefined
    expect(() => decide(selection, state, 'GA_ADMIN')).toThrow()
  })
  it('does not coerce missing counts to zero', () => {
    const state = evidence()
    state.openExceptions = undefined
    expect(decide(selection, state, 'GA_ADMIN').step).toBe('reconciliation.review')
  })
  it('hands finalization to authorized staff', () =>
    expect(decide(selection, evidence(), 'SETTLEMENT').reason).toContain('인계'))
  it('requires all eight checklist items', () => {
    const state = evidence()
    state.checklist!.conditions!.pop()
    expect(decide(selection, state, 'GA_ADMIN').reason).toContain('8개 서버 조건')
  })
  it.each(['month', 'contract', 'stage', 'run', 'reconciliation'] as const)('rejects mismatched %s', (field) => {
    const state = evidence()
    if (field === 'month') state.payment!.settlementMonth = '2026-08-01'
    if (field === 'contract') state.payment!.attributions = [{ contractId: 99 }]
    if (field === 'stage') state.schedule!.header!.paymentStage = 'INSURER_TO_GA'
    if (field === 'run') state.validation!.header!.runType = 'MANUAL_CONTRACT'
    if (field === 'reconciliation') state.reconciliation!.validationRunId = 99
    expect(() => decide(selection, state, 'GA_ADMIN')).toThrow()
  })
})

describe('reconciliation selection spotlight', () => {
  it('points to the history until a run is selected, then to its results', () => {
    const base = { month: '2026-07', stage: 'GA_TO_FC' as const }
    expect(destinations(base)['reconciliation.review'].target).toBe('reconciliation.history')
    expect(destinations({ ...base, reconciliationRunId: 5 })['reconciliation.review'].target).toBe(
      'reconciliation.results',
    )
  })
})
