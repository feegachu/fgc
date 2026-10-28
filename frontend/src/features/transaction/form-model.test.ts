import { describe, expect, it } from 'vitest'
import {
  blockedContractId,
  blockedContractNo,
  buildPayload,
  draftRows,
  inclusionOptions,
  filterAgentId,
  gateStates,
  hasRecordableBlocker,
  initialForm,
  isRetryableConfirmation,
  newRow,
  normalizeRow,
  selectContract,
} from './form-model'

const form = { ...initialForm('2026-07'), sourceBusinessKey: 'GA-MANUAL-test', agentId: '1', commissionItemId: '2' }
const row = { ...newRow(1, '2026-07-01', '250000'), contractId: '3' }

describe('transaction draft and attribution contract', () => {
  it('uses only in-scope exclusions and defaults an excluded draft to newcomer support (SRC-036)', () => {
    expect(inclusionOptions(false, false).map(([value]) => value)).toEqual([
      'INCLUDED',
      'REVIEW_REQUIRED',
      'EXCLUDED:NEW_AGENT_SUPPORT',
    ])
    expect(inclusionOptions(true, false).map(([value]) => value)).toEqual([
      'INCLUDED',
      'REVIEW_REQUIRED',
      'EXCLUDED:COMPLIANCE_3PCT',
    ])
    expect(draftRows({ attributions: [{ inclusionDecisionStatus: 'EXCLUDED' }] })[0].decision).toBe(
      'EXCLUDED:NEW_AGENT_SUPPORT',
    )
  })
  it('saves the month first day, legacy nulls and unmatched sums without client CAP calculation', () => {
    const payload = buildPayload(form, [{ ...row, amount: '1' }], false)
    expect(payload).toMatchObject({
      sourceType: 'GA_MANUAL_PAYMENT',
      sourceBusinessKey: 'GA-MANUAL-test',
      contractId: 3,
      agentId: 1,
      amount: 250000,
      settlementMonth: '2026-07-01',
      scheduledPaymentDate: '2026-07-01',
      allocationPolicyVersion: null,
      evidenceRef: null,
      note: null,
    })
    expect(payload.attributions[0]).toMatchObject({
      amount: 1,
      inclusionDecisionStatus: 'INCLUDED',
      inclusionDecisionReason: '화면에서 산입으로 지정',
      evidenceRef: null,
    })
  })
  it('rejects missing rows, negative amounts, exclusion evidence and approved allocation basis', () => {
    expect(() => buildPayload(form, [], false)).toThrow('귀속행을 하나 이상 추가하세요.')
    expect(() => buildPayload({ ...form, amount: '-1' }, [row], false)).toThrow('금액')
    expect(() => buildPayload(form, [{ ...row, decision: 'EXCLUDED:NEW_AGENT_SUPPORT' }], false)).toThrow('제외 귀속행')
    expect(() =>
      buildPayload(form, [{ ...row, attributionMethod: 'APPROVED_ALLOCATION', allocationBasis: ' ' }], false),
    ).toThrow('배부정책')
  })
  it('keeps newcomer support as an agent-scoped non-contract attribution with evidence', () => {
    const normalized = normalizeRow(row, false, true)
    expect(normalized).toMatchObject({
      contractId: '',
      attributionMethod: 'NEWCOMER_NON_CONTRACT',
      decision: 'EXCLUDED:NEW_AGENT_SUPPORT',
    })
    expect(() => buildPayload(form, [{ ...normalized, decision: 'REVIEW_REQUIRED' }], true)).toThrow('증빙')
    const payload = buildPayload(form, [{ ...normalized, evidenceRef: '증빙-1' }], true)
    expect(payload.contractId).toBeNull()
    expect(payload.attributions[0]).toMatchObject({
      contractId: null,
      attributionMethod: 'NEWCOMER_NON_CONTRACT',
      exclusionType: 'NEW_AGENT_SUPPORT',
      inclusionDecisionReason: '한도 제외: 신인활동지원비',
    })
  })
  it('limits insurer-stage methods and exclusion choices, without a recipient', () => {
    const normalized = normalizeRow(
      { ...row, attributionMethod: 'SETTLEMENT_SUPPORT_MONTHLY', decision: 'EXCLUDED:NEW_AGENT_SUPPORT' },
      true,
      false,
    )
    expect(normalized).toMatchObject({ attributionMethod: 'DIRECT', decision: 'INCLUDED' })
    expect(
      buildPayload({ ...form, paymentStage: 'INSURER_TO_GA', agentId: '' }, [normalized], false).agentId,
    ).toBeNull()
  })
  it('loads all contracts for managers and preserves dates after contract start', () => {
    const agents = [
      { agentId: 1, rankCode: 'FC' },
      { agentId: 2, rankCode: 'BM' },
    ]
    expect(filterAgentId('1', agents)).toBe('1')
    expect(filterAgentId('2', agents)).toBe('')
    const contracts = [{ contractId: 3, contractDate: '2026-07-18' }]
    expect(selectContract({ ...row, attributionDate: '' }, '3', contracts).attributionDate).toBe('')
    expect(selectContract(row, '3', contracts).attributionDate).toBe('2026-07-18')
    expect(selectContract({ ...row, attributionDate: '2026-08-02' }, '3', contracts).attributionDate).toBe('2026-08-02')
  })
})
describe('server confirmation gates', () => {
  it('keeps six gates unverified before precheck and after an earlier blocker', () => {
    expect(gateStates(null).map((gate) => gate.state)).toEqual(Array(6).fill('todo'))
    expect(gateStates({ confirmable: false, blockers: [{ code: 'FGC-TRAN-002' }] }).map((gate) => gate.state)).toEqual([
      'pass',
      'fail',
      'todo',
      'todo',
      'todo',
      'todo',
    ])
    expect(gateStates({ confirmable: true }).map((gate) => gate.state)).toEqual(Array(6).fill('pass'))
    expect(gateStates({ confirmable: false, blockers: [{ code: 'FGC-POLICY-001' }] })[5].state).toBe('fail')
  })
  it('only offers record-and-reject when the first blocker is recordable', () => {
    expect(hasRecordableBlocker({ blockers: [{ code: 'FGC-TRAN-002' }, { code: 'FGC-CAP-002' }] })).toBe(false)
    expect(hasRecordableBlocker({ blockers: [{ code: 'FGC-CAP-001' }] })).toBe(true)
    expect(hasRecordableBlocker({ blockers: [{ code: 'FGC-CAP-003' }] })).toBe(false)
  })
  it('links the rejected blocker contract even when it has no CAP preview', () => {
    const result = {
      blockers: [
        { code: 'FGC-CAP-002', contractId: 7 },
        { code: 'FGC-CAP-001', contractId: 3 },
      ],
      capPreview: [],
    }
    expect(blockedContractNo(result, 'FGC-CAP-002', [{ contractId: 7, contractNo: 'UNCLASSIFIED' }])).toBe(
      'UNCLASSIFIED',
    )
    expect(blockedContractNo(result, 'FGC-CAP-001', [{ contractId: 3, contractNo: 'VIOLATION' }])).toBeUndefined()
  })
})

describe('legacy defect model regressions (#410)', () => {
  it('resolves the actual blocker contract from preview before the first contracts page', () => {
    const result = {
      blockers: [
        { code: 'FGC-CAP-002', contractId: 103 },
        { code: 'FGC-CAP-001', contractId: 104 },
      ],
      capPreview: [
        { contractId: 104, contractNo: 'VIOLATION-104' },
        { contractId: 103, contractNo: 'REVIEW-103' },
      ],
    }
    expect(blockedContractId(result, 'FGC-CAP-002')).toBe(103)
    expect(blockedContractNo(result, 'FGC-CAP-002', [{ contractId: 104, contractNo: 'WRONG' }])).toBe('REVIEW-103')
    expect(blockedContractNo(result, 'FGC-CAP-001', [])).toBeUndefined()
    expect(
      blockedContractNo(
        { blockers: [{ code: 'FGC-CAP-002', contractId: 103 }], capPreview: result.capPreview.slice(0, 1) },
        'FGC-CAP-002',
        [],
      ),
    ).toBeUndefined()
  })
  it('preserves zero saved rows but retains the default row for a new form', () => {
    expect(draftRows({ attributions: [] })).toEqual([])
    expect(draftRows(undefined)).toHaveLength(1)
    expect(draftRows(undefined)[0].attributionDate).toBe('')
  })
})

it('keeps a first non-contract CAP blocker from linking a later contract (#410)', () => {
  const result = {
    blockers: [{ code: 'FGC-CAP-002' }, { code: 'FGC-CAP-002', contractId: 7 }],
    capPreview: [{ contractId: 7, contractNo: 'LATER-CONTRACT' }],
  }
  expect(blockedContractId(result, 'FGC-CAP-002')).toBeUndefined()
  expect(blockedContractNo(result, 'FGC-CAP-002', [{ contractId: 7, contractNo: 'LATER-CONTRACT' }])).toBeUndefined()
})

it('retries transport and malformed-success responses but requires a fresh precheck for business rejections (#410)', () => {
  expect(isRetryableConfirmation(null)).toBe(true)
  for (const status of [0, 200, 500, 503]) expect(isRetryableConfirmation({ status })).toBe(true)
  for (const status of [400, 401, 403, 409, 422]) expect(isRetryableConfirmation({ status })).toBe(false)
})
