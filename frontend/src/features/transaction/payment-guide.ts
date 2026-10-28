import type { GuideCondition, GuideContext, GuideEnvironment } from '../assistant/guide/types'
import { GuideFailure } from '../assistant/guide/targets'
import { readPayment } from './form-api'
import type { Payment, Precheck } from './form-api'
import { draftRows, initialForm } from './form-model'
import type { AttributionRow, FormValues } from './form-model'

export interface PaymentGuideInput {
  role: string
  canProcess: boolean
  paymentId: string | null
  form: FormValues
  rows: AttributionRow[]
  dirty: boolean
  ready: boolean
  valid: boolean
}
export interface PaymentGuideState {
  available: boolean
  valid: boolean
  saved: boolean
  prechecked: boolean
  confirmed: boolean
  acknowledged: readonly string[]
}
const readers = ['SYSTEM_ADMIN', 'GA_ADMIN', 'SETTLEMENT', 'COMPLIANCE']
const processors = ['SYSTEM_ADMIN', 'GA_ADMIN', 'SETTLEMENT']
const positiveId = (id: string | number) => {
  const value = Number(id)
  if (!/^\d+$/.test(String(id)) || !Number.isSafeInteger(value) || value <= 0)
    throw new GuideFailure('INVALID_ENTITY_REFS')
  return value
}
// Comparison keys stay in memory and are never emitted as guide events or server versions.
const inputKey = (form: FormValues, rows: AttributionRow[]) =>
  JSON.stringify([form, rows.map((row) => ({ ...row, key: undefined }))])
const serverKey = (payment: Payment) => JSON.stringify(payment)
const sameInput = (input: PaymentGuideInput, payment: Payment) =>
  inputKey(input.form, input.rows) ===
  inputKey(initialForm(input.form.settlementMonth, payment), draftRows(payment, input.form.settlementMonth))

export interface PaymentOperation {
  readonly revision: number
  readonly paymentId: string | null
  readonly kind: 'save' | 'precheck' | 'confirm'
  readonly sequence: number
}

/** One mounted TRAN-W02 form. All evidence comes from its existing API handlers or fresh GETs. */
export class PaymentGuideObserver {
  private input: PaymentGuideInput | null = null
  private revision = 0
  private sequence = 0
  private server: Payment | null = null
  private checked: { key: string; result: Precheck } | null = null
  private confirmation: Payment | null = null
  private acknowledgements = new Set<string>()
  private viewRevision = 0
  getViewRevision = () => this.viewRevision
  private listeners = new Set<() => void>()
  private disposed = false
  private lifetime = new AbortController()

  constructor(initialPayment?: Payment) {
    this.server = initialPayment ? structuredClone(initialPayment) : null
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }
  private emit() {
    this.viewRevision++
    this.listeners.forEach((listener) => listener())
  }
  update(input: PaymentGuideInput) {
    if (this.disposed) return
    const previous = this.input
    const changed =
      !previous ||
      inputKey(previous.form, previous.rows) !== inputKey(input.form, input.rows) ||
      previous.paymentId !== input.paymentId ||
      previous.role !== input.role ||
      previous.canProcess !== input.canProcess
    this.input = structuredClone(input)
    if (changed) {
      this.revision++
      this.checked = null
      this.confirmation = null
      this.acknowledgements.clear()
    }
    if (
      changed ||
      previous?.dirty !== input.dirty ||
      previous?.ready !== input.ready ||
      previous?.valid !== input.valid
    )
      this.emit()
  }
  invalidateInput() {
    this.revision++
    this.checked = null
    this.confirmation = null
    this.acknowledgements.clear()
    this.emit()
  }
  readContext = (): GuideContext => {
    const input = this.requireInput()
    const contracts = [...new Set(input.rows.map((row) => row.contractId).filter(Boolean))]
    // Never silently choose the first contract of a multi-contract payment.
    if (contracts.length > 1) throw new GuideFailure('MULTIPLE_CONTRACTS_UNSUPPORTED')
    return {
      screenId: 'TRAN-W02',
      role:
        readers.includes(input.role) && (input.canProcess || !processors.includes(input.role))
          ? input.role
          : 'UNAUTHORIZED',
      entityRefs: [
        ...(input.paymentId ? [{ type: 'PAYMENT' as const, id: positiveId(input.paymentId) }] : []),
        ...contracts.map((id) => ({ type: 'CONTRACT' as const, id: positiveId(id) })),
      ],
      clientContextRevision: String(this.revision),
      serverStateVersion: this.server?.updatedAt ?? null,
      dirty: input.dirty,
    }
  }
  private requireInput() {
    if (this.disposed || !this.input) throw new GuideFailure('SCREEN_UNAVAILABLE')
    return this.input
  }
  begin(kind: PaymentOperation['kind']): PaymentOperation {
    const input = this.requireInput()
    this.checked = kind === 'save' || kind === 'precheck' ? null : this.checked
    this.confirmation = null
    return { revision: this.revision, paymentId: input.paymentId, kind, sequence: ++this.sequence }
  }
  isCurrent(operation: PaymentOperation) {
    return (
      !this.disposed &&
      operation.revision === this.revision &&
      operation.sequence === this.sequence &&
      operation.paymentId === this.input?.paymentId
    )
  }
  fail(operation: PaymentOperation) {
    if (!this.isCurrent(operation)) return
    this.checked = null
    this.confirmation = null
    this.emit()
  }
  async readOperationPayment(operation: PaymentOperation) {
    if (!this.isCurrent(operation) || !operation.paymentId) throw new GuideFailure('CONTEXT_CHANGED')
    return readPayment(operation.paymentId, this.lifetime.signal)
  }
  acceptSaved(operation: PaymentOperation, payment: Payment) {
    if (
      !this.isCurrent(operation) ||
      operation.kind !== 'save' ||
      payment.status !== 'DRAFT' ||
      !payment.commissionTransactionId ||
      (operation.paymentId && positiveId(operation.paymentId) !== payment.commissionTransactionId)
    )
      return false
    positiveId(payment.commissionTransactionId)
    this.server = structuredClone(payment)
    this.emit()
    return true
  }
  acceptPrecheck(operation: PaymentOperation, before: Payment, result: Precheck, after: Payment) {
    if (
      !this.isCurrent(operation) ||
      operation.kind !== 'precheck' ||
      !operation.paymentId ||
      before.commissionTransactionId !== positiveId(operation.paymentId) ||
      result.paymentId !== before.commissionTransactionId ||
      after.status !== 'DRAFT' ||
      serverKey(before) !== serverKey(after) ||
      !sameInput(this.requireInput(), after)
    )
      return false
    this.server = structuredClone(after)
    this.checked = { key: serverKey(after), result: structuredClone(result) }
    this.emit()
    return true
  }
  acceptConfirmed(operation: PaymentOperation, response: Payment, refreshed: Payment) {
    if (
      !this.isCurrent(operation) ||
      operation.kind !== 'confirm' ||
      !operation.paymentId ||
      response.commissionTransactionId !== positiveId(operation.paymentId) ||
      response.status !== 'CONFIRMED' ||
      refreshed.commissionTransactionId !== response.commissionTransactionId ||
      refreshed.status !== 'CONFIRMED' ||
      !sameInput(this.requireInput(), refreshed)
    )
      return false
    this.server = structuredClone(refreshed)
    this.confirmation = structuredClone(refreshed)
    this.emit()
    return true
  }
  acknowledge(
    rule:
      'SCOPE_ACKNOWLEDGED' | 'AMOUNT_REVIEW_ACKNOWLEDGED' | 'ATTRIBUTIONS_REVIEWED' | 'PRECHECK_RESULT_ACKNOWLEDGED',
  ) {
    this.requireInput()
    this.acknowledgements.add(rule)
    this.emit()
  }
  readBusinessState = async (context: GuideContext, signal: AbortSignal): Promise<PaymentGuideState> => {
    const input = this.requireInput()
    const initialContext = JSON.stringify(this.readContext())
    if (JSON.stringify(context) !== initialContext) throw new GuideFailure('CONTEXT_CHANGED')
    const payment = input.paymentId
      ? await readPayment(input.paymentId, AbortSignal.any([signal, this.lifetime.signal]))
      : null
    signal.throwIfAborted()
    if (this.disposed || JSON.stringify(this.readContext()) !== initialContext)
      throw new GuideFailure('CONTEXT_CHANGED')
    const matches =
      payment != null && payment.commissionTransactionId === positiveId(input.paymentId!) && sameInput(input, payment)
    const unchanged = this.server == null || (payment != null && serverKey(this.server) === serverKey(payment))
    if (payment && !unchanged) {
      this.server = structuredClone(payment)
      this.checked = null
      this.confirmation = null
      this.acknowledgements.clear()
      this.revision++
      this.emit()
      throw new GuideFailure('SERVER_STATE_CHANGED')
    }
    return {
      available: input.ready && readers.includes(input.role),
      valid: input.valid,
      saved: matches && !input.dirty && payment?.status === 'DRAFT',
      prechecked:
        matches &&
        !input.dirty &&
        this.checked?.key === serverKey(payment!) &&
        this.checked.result.confirmable === true &&
        this.checked.result.blockers?.length === 0,
      confirmed:
        matches &&
        !input.dirty &&
        payment?.status === 'CONFIRMED' &&
        this.confirmation != null &&
        serverKey(this.confirmation) === serverKey(payment),
      acknowledged: [...this.acknowledgements],
    }
  }
  environment(): GuideEnvironment<PaymentGuideState> {
    return {
      readContext: this.readContext,
      readBusinessState: this.readBusinessState,
      conditions: paymentGuideConditions,
      openScreen: async (screen, signal) => {
        signal.throwIfAborted()
        if (screen !== 'TRAN-W02') throw new GuideFailure('SCREEN_UNAVAILABLE')
      },
      confirmLeave: async () => false,
    }
  }
  dispose() {
    this.disposed = true
    this.lifetime.abort()
    this.input = null
    this.server = null
    this.checked = null
    this.confirmation = null
    this.acknowledgements.clear()
    this.emit()
    this.listeners.clear()
  }
}
const inputCondition =
  (rule: string): GuideCondition<PaymentGuideState> =>
  (_context, state) =>
    state.available && state.valid && state.acknowledged.includes(rule) ? 'SATISFIED' : 'WAITING_INPUT'
export const paymentGuideConditions: Readonly<Record<string, GuideCondition<PaymentGuideState>>> = {
  ALWAYS: () => 'SATISFIED',
  CONTEXT_AVAILABLE: (_context, state) => (state.available ? 'SATISFIED' : 'WAITING_INPUT'),
  SCOPE_ACKNOWLEDGED: inputCondition('SCOPE_ACKNOWLEDGED'),
  AMOUNT_REVIEW_ACKNOWLEDGED: inputCondition('AMOUNT_REVIEW_ACKNOWLEDGED'),
  ATTRIBUTIONS_REVIEWED: inputCondition('ATTRIBUTIONS_REVIEWED'),
  EVIDENCE_FIELDS_VALID: (_context, state) => (state.valid ? 'SATISFIED' : 'WAITING_INPUT'),
  CURRENT_DRAFT_SAVED: (_context, state) => (state.saved ? 'SATISFIED' : 'WAITING_RESULT'),
  CURRENT_PRECHECK_PASSED: (_context, state) => (state.prechecked ? 'SATISFIED' : 'WAITING_RESULT'),
  PRECHECK_RESULT_ACKNOWLEDGED: (context, state) =>
    state.prechecked ? inputCondition('PRECHECK_RESULT_ACKNOWLEDGED')(context, state) : 'WAITING_RESULT',
  PAYMENT_CONFIRMED: (_context, state) => (state.confirmed ? 'SATISFIED' : 'WAITING_RESULT'),
}

// The shell overlay can subscribe to the current mounted form without retaining payment data after navigation.
let active: PaymentGuideObserver | null = null
const connections = new Set<() => void>()
export const getPaymentGuideObserver = () => active
export const subscribePaymentGuide = (listener: () => void) => {
  connections.add(listener)
  return () => {
    connections.delete(listener)
  }
}
export function connectPaymentGuide(observer: PaymentGuideObserver) {
  active = observer
  connections.forEach((listener) => listener())
  return () => {
    if (active === observer) {
      active = null
      connections.forEach((listener) => listener())
    }
    observer.dispose()
  }
}
