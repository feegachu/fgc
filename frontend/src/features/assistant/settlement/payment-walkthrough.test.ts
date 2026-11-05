import { describe, expect, it } from 'vitest'
import { paymentWalkthrough } from './payment-walkthrough'
import { initialForm, newRow } from '../../transaction/form-model'
import type { PaymentGuideInput } from '../../transaction/payment-guide'

const input = (): PaymentGuideInput => ({
  role: 'SETTLEMENT', canProcess: true, paymentId: null, dirty: true, ready: true, valid: false,
  form: initialForm('2026-07'), rows: [newRow(1, '')],
})
describe('payment input walkthrough', () => {
  it('keeps missing recipient and contract as individual blocking steps', () => {
    const steps = paymentWalkthrough(input())
    expect(steps.find(s => s.id === 'recipient')?.ready).toBe(false)
    expect(steps.find(s => s.id === 'contract-1')?.ready).toBe(false)
    expect(steps.at(-1)?.id).toBe('save')
  })
  it('omits recipient for insurer payments and explains every added contract', () => {
    const value = input()
    value.form.paymentStage = 'INSURER_TO_GA'
    value.rows.push(newRow(2, '2026-07-31'))
    const steps = paymentWalkthrough(value)
    expect(steps.some(s => s.id === 'recipient')).toBe(false)
    expect(steps.filter(s => s.id.startsWith('contract-')).map(s => s.selector)).toEqual(['#attr-1-contractId', '#attr-2-contractId'])
  })
  it('requires evidence only for excluded rows and accepts shared evidence', () => {
    const value = input()
    expect(paymentWalkthrough(value).some(s => s.id === 'evidence-1')).toBe(false)
    value.rows[0].decision = 'EXCLUDED:NEW_AGENT_SUPPORT'
    expect(paymentWalkthrough(value).find(s => s.id === 'evidence-1')?.ready).toBe(false)
    value.form.evidenceRef = '문서-1'
    expect(paymentWalkthrough(value).find(s => s.id === 'evidence-1')?.ready).toBe(true)
  })
  it('does not accept negative, fractional or unsafe amounts', () => {
    for (const amount of ['-1', '1.5', '9007199254740992']) {
      const value = input()
      value.form.amount = amount
      expect(paymentWalkthrough(value).find(s => s.id === 'amount')?.ready).toBe(false)
    }
    const value = input()
    value.form.amount = '0'
    expect(paymentWalkthrough(value).find(s => s.id === 'amount')?.ready).toBe(true)
  })
  it('uses form validation for the final review and does not fabricate completion', () => {
    const value = input()
    value.validationMessage = '귀속합계를 확인하세요.'
    expect(paymentWalkthrough(value).find(s => s.id === 'review')).toMatchObject({ ready: false, instruction: value.validationMessage })
    expect(paymentWalkthrough(null)).toEqual([])
  })
})
