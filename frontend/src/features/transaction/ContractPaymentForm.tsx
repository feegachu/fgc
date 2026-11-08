import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { SearchSelect } from '../../components/SearchSelect'
import { Modal } from '../../components/Modal'
import { ResizableSplit } from '../../components/ResizableSplit'
import { apiClient } from '../../lib/api/client'
import { int } from '../../lib/format'
import { useUnsavedWork } from '../../stores/unsavedWork'
import { useEntityChoices } from '../base/selection'
import { contractSchedulesQuery, validDate } from '../contract/api'
import { scheduleDetailQuery, type ScheduleLine } from '../schedule/api'
import {
  confirmPayment,
  precheckPayment,
  readPayment,
  saveDraft,
  formError,
  type Payment,
  type PaymentPayload,
  type Precheck,
} from './form-api'
import { loadSchedulePayments } from './contract-payment-resume'
import { ContractPaymentCapPanel } from './ContractPaymentCapPanel'
import { ContractPaymentGates } from './ContractPaymentGates'
import { ContractPaymentComparison } from './ContractPaymentComparison'
import { ScreenWalkthrough } from '../assistant/guide/ScreenWalkthrough'
import { contractPaymentHelp } from './contract-payment-help'
import { connectBatchPaymentGuide } from './contract-payment-guide'


type Edit = { amount: string; item: string }
type Saved = { payment: Payment; fingerprint: string }

const scheduleAmount = (line: ScheduleLine) =>
  line.expectedAmount == null
    ? undefined
    : line.cashflowType === 'DEDUCTION'
      ? Math.abs(line.expectedAmount)
      : line.expectedAmount

function payload(
  line: ScheduleLine,
  contractId: number,
  stage: 'GA_TO_FC' | 'INSURER_TO_GA',
  month: string,
  evidence: string,
): PaymentPayload {
  if (
    !line.scheduleLineId ||
    line.contractId !== contractId ||
    !line.commissionItemId ||
    !line.attributionDate ||
    !line.dueDate ||
    !Number.isSafeInteger(line.expectedAmount) ||
    line.expectedAmount! < 0 ||
    !['PAYMENT', 'DEDUCTION'].includes(line.cashflowType ?? '') ||
    (stage === 'GA_TO_FC' && !line.beneficiaryAgentId)
  ) {
    throw new Error('스케줄의 설계사·항목·금액·귀속일·예정일을 확인해 주세요.')
  }
  return {
    sourceType: 'GA_MANUAL_PAYMENT',
    sourceBusinessKey: `SCHEDULE-LINE-${stage}-${line.scheduleLineId}`,
    contractId,
    agentId: stage === 'GA_TO_FC' ? line.beneficiaryAgentId : null,
    commissionItemId: line.commissionItemId,
    amount: line.expectedAmount!,
    settlementMonth: `${month}-01`,
    scheduledPaymentDate: line.dueDate,
    paymentStage: stage,
    cashflowType: line.cashflowType!,
    evidenceRef: evidence || null,
    note: `예상 스케줄 ${line.scheduleLineId} / ${line.installmentNo}회차`,
    attributions: [
      {
        contractId,
        attributionDate: line.attributionDate,
        amount: line.expectedAmount!,
        inclusionDecisionStatus: 'INCLUDED',
        inclusionDecisionReason: '계약 예상 스케줄 기준 직접 귀속',
        allocationBasis: `스케줄 행 ${line.scheduleLineId}`,
        evidenceRef: evidence || null,
        attributionMethod: 'DIRECT',
      },
    ],
  }
}

export function ContractPaymentForm({
  month,
  canProcess,
  returnTo,
  initialContractId = '',
}: {
  month: string
  canProcess: boolean
  returnTo: string
  initialContractId?: string
}) {
  const editor = useRef<HTMLDivElement>(null)
  const [comparing, setComparing] = useState(false)
  useEffect(() => {
    const root = editor.current
    const start = () => setComparing(true)
    const stop = () => setComparing(false)
    root?.addEventListener('fgc-payment-compare-start', start)
    root?.addEventListener('fgc-payment-compare-stop', stop)
    return () => {
      root?.removeEventListener('fgc-payment-compare-start', start)
      root?.removeEventListener('fgc-payment-compare-stop', stop)
    }
  }, [])
  const client = useQueryClient()
  const [contractId, setContractId] = useState(initialContractId)
  const [stage, setStage] = useState<'GA_TO_FC' | 'INSURER_TO_GA'>('GA_TO_FC')
  const settlementMonth = month
  const monthStart = `${month}-01`
  const monthEnd = `${month}-${new Date(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0).getDate()}`
  const [attributionDateInput, setAttributionDate] = useState<string | null>(null)
  const [paymentDate, setPaymentDate] = useState(monthStart)
  const [installment, setInstallment] = useState('')
  const [excluded, setExcluded] = useState<number[]>([])
  const [edits, setEdits] = useState<Record<number, Partial<Edit>>>({})
  const [evidence, setEvidence] = useState('')
  const [busy, setBusy] = useState('')
  const lock = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const ensureMounted = () => {
    if (!mounted.current) throw new Error('화면을 이동하여 처리를 중단했습니다.')
  }
  const [error, setError] = useState('')
  const [savedLines, setSavedLines] = useState<ScheduleLine[] | null>(null)
  const [saved, setSaved] = useState<Record<number, Saved>>({})
  const [checks, setChecks] = useState<Record<number, Precheck>>({})
  const [selectedResult, setSelectedResult] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const contracts = useEntityChoices('contracts', { contractDateTo: monthEnd }, [contractId], true,
    row => !!row.contractDate && row.contractDate <= monthEnd)
  const selectedContract = contracts.data.find(row => String(row.contractId) === contractId)
  const contractDate = selectedContract?.contractDate
  const attributionMinimum = contractDate && contractDate > monthStart ? contractDate : monthStart
  const attributionDate = attributionDateInput ?? attributionMinimum
  const contractAllowed = !!selectedContract?.contractDate && selectedContract.contractDate <= monthEnd
  const schedules = useQuery({
    ...contractSchedulesQuery(contractId, stage),
    enabled: !!contractId && contractAllowed,
    placeholderData: undefined,
  })
  const active =
    schedules.data?.headers?.filter(
      (header) =>
        header.activeYn &&
        header.paymentStage === stage &&
        header.scheduleRegime === 'CURRENT' &&
        header.schedulePurpose === 'OPERATIONAL' &&
        !['HOLD', 'CANCELLED'].includes(header.status ?? ''),
    ) ?? []
  const header = active.length === 1 ? active[0] : undefined
  const detail = useQuery({
    ...scheduleDetailQuery(String(header?.scheduleHeaderId ?? '')),
    enabled: contractAllowed && !!header?.scheduleHeaderId,
  })
  const lines = savedLines ?? detail.data?.lines ?? []
  const installments = [...new Set(lines.map((line) => line.installmentNo).filter((n): n is number => n != null))].sort(
    (a, b) => a - b,
  )
  const chosen =
    installment ||
    String(lines.find((line) => line.dueDate?.startsWith(settlementMonth))?.installmentNo ?? installments[0] ?? '')
  const visible = lines.filter((line) => String(line.installmentNo) === chosen)
  const resumeKey = `${contractId}:${stage}:${month}:${header?.scheduleHeaderId}:${chosen}`
  const existing = useQuery({
    queryKey: ['transactions', 'schedule-resume', resumeKey],
    queryFn: ({ signal }) => loadSchedulePayments(contractId, stage, month,
      visible.flatMap(line => line.scheduleLineId ? [line.scheduleLineId] : []), signal),
    enabled: contractAllowed && !!header && detail.isSuccess,
    staleTime: 0,
    meta: { errorToast: false },
  })
  const [resumeError, setResumeError] = useState('')
  const [restoredKey, setRestoredKey] = useState('')
  const restoring = !!contractId && (existing.isPending || restoredKey !== resumeKey)
  function restoreDrafts() {
    if (!existing.data || !detail.data?.lines) return
    setRestoredKey(resumeKey)
    const entries = Object.entries(existing.data)
    const first = entries[0]?.[1]
    if (first && entries.some(([,payment]) => payment.scheduledPaymentDate !== first.scheduledPaymentDate ||
      payment.attributions?.[0]?.attributionDate !== first.attributions?.[0]?.attributionDate ||
      payment.evidenceRef !== first.evidenceRef)) {
      setResumeError('저장된 행의 귀속일·예정일·증빙이 서로 다릅니다. 지급 목록에서 개별 건을 이어서 처리하세요.')
      return
    }
    const restored: Record<number, Saved> = {}
    const restoredEdits: Record<number, Partial<Edit>> = {}
    for (const [key, payment] of entries) {
      const line = detail.data.lines.find(row => row.scheduleLineId === Number(key))
      if (!line || payment.amount == null || !payment.commissionItemId || !payment.scheduledPaymentDate || !payment.attributions?.[0]?.attributionDate) {
        setResumeError('저장된 지급 건의 필수 정보를 목록에서 확인하세요.')
        return
      }
      let body: PaymentPayload
      try {
        body = payload({ ...line, expectedAmount: payment.amount, commissionItemId: payment.commissionItemId,
        attributionDate: payment.attributions[0].attributionDate, dueDate: payment.scheduledPaymentDate,
        cashflowType: payment.cashflowType }, Number(contractId), stage, month, payment.evidenceRef ?? '')
      } catch {
        setResumeError('저장된 지급 건의 스케줄 정보를 목록에서 확인하세요.')
        return
      }
      restored[Number(key)] = { payment, fingerprint: JSON.stringify(body) }
      restoredEdits[Number(key)] = { amount: String(payment.amount), item: String(payment.commissionItemId) }
    }
    if (first) {
      setAttributionDate(first.attributions![0].attributionDate!)
      setPaymentDate(first.scheduledPaymentDate!)
      setEvidence(first.evidenceRef ?? '')
      setSavedLines(detail.data.lines)
      setSaved(restored)
      setEdits(restoredEdits)
    }
    setResumeError('')
  }
  // Hydrate a newly loaded scope before rendering its editor. Subsequent refetches
  // cannot overwrite edits made in this scope.
  if (existing.data && detail.data?.lines && restoredKey !== resumeKey) restoreDrafts()
  const eligible = (line: ScheduleLine) =>
    ['PLANNED', 'CONFIRMED', 'RESTARTED'].includes(line.lineStatus ?? '') && !!line.scheduleLineId
  const selected = visible.filter((line) => eligible(line) && !excluded.includes(line.scheduleLineId!))
  const values = (line: ScheduleLine): Edit => ({
    amount: String(scheduleAmount(line) ?? ''),
    item: String(line.commissionItemId ?? ''),
    ...edits[line.scheduleLineId!],
  })
  const items = useEntityChoices(
    'items',
    { asOf: `${settlementMonth}-01` },
    visible.map((line) => values(line).item),
    !!settlementMonth,
    (item) => stage !== 'INSURER_TO_GA' || item.itemCode !== 'NEWCOMER_SUPPORT',
    stage,
  )
  const itemSource = { ...items.source, label: (item: (typeof items.data)[number]) => item.itemName ?? '수수료 항목' }
  const datesReady = attributionDate >= attributionMinimum && [attributionDate, paymentDate].every((date) => validDate(date) && date.startsWith(`${month}-`))
  const rowPayload = (line: ScheduleLine) => {
    if (!contractAllowed) throw new Error('기준 정산월까지 체결된 계약을 선택하세요.')
    if (!datesReady) throw new Error('귀속일과 지급 예정일을 기준 정산월 안에서 선택하세요.')
    const edit = values(line)
    if (!/^\d+$/.test(edit.amount)) throw new Error('귀속금액을 0원 이상 원 단위로 입력하세요.')
    const item = items.data.find((item) => String(item.commissionItemId) === edit.item)
    if (!item) throw new Error('검색 결과에서 수수료 항목을 선택하세요.')
    const cashflowType = item.cashflowType || line.cashflowType
    return payload(
      {
        ...line,
        expectedAmount: Number(edit.amount),
        commissionItemId: Number(edit.item),
        attributionDate,
        dueDate: paymentDate,
        cashflowType,
      },
      Number(contractId),
      stage,
      settlementMonth,
      evidence,
    )
  }
  const total = selected.reduce((sum, line) => sum + Number(values(line).amount || 0), 0)
  const balanced =
    selected.length > 0 &&
    Number.isSafeInteger(total) &&
    selected.every((line) => /^\d+$/.test(values(line).amount) && Number.isSafeInteger(Number(values(line).amount)))
  const fingerprint = (line: ScheduleLine) => {
    try {
      return JSON.stringify(rowPayload(line))
    } catch {
      return ''
    }
  }
  const clean =
    selected.length > 0 &&
    selected.every((line) => saved[line.scheduleLineId!]?.fingerprint === fingerprint(line) && !!fingerprint(line))
  const done = (id: number) => saved[id]?.payment.status === 'CONFIRMED'
  const remaining = selected.filter((line) => !done(line.scheduleLineId!))
  const allChecked =
    clean &&
    balanced &&
    remaining.length > 0 &&
    remaining.every((line) => checks[line.scheduleLineId!]?.confirmable === true)
  const scopeLocked = !!busy || Object.keys(saved).length > 0 || uncertain
  const editingLocked = !!busy || uncertain || restoring || existing.isError || !!resumeError
  const currentResult = selected.some((line) => String(line.scheduleLineId) === selectedResult)
    ? Number(selectedResult)
    : remaining[0]?.scheduleLineId || selected[0]?.scheduleLineId
  useUnsavedWork(!!busy || (!!contractId && (!clean || uncertain)))
  const guideState = useRef({ contractId: 0, stage, month, paymentIds: [] as number[], ready: false })
  useLayoutEffect(() => {
    guideState.current = { contractId: Number(contractId), stage, month,
      paymentIds: selected.flatMap(line => {
        const id = saved[line.scheduleLineId!]?.payment.commissionTransactionId
        return id ? [id] : []
      }), ready: clean && !busy && !uncertain && remaining.length === 0 }
  })
  useEffect(() => connectBatchPaymentGuide(() => guideState.current), [])
  const reset = () => {
    setInstallment('')
    setExcluded([])
    setEdits({})
    setAttributionDate(null)
    setPaymentDate(monthStart)
    setChecks({})
    setError('')
    setSelectedResult('')
    setResumeError('')
    setRestoredKey('')
  }
  const editRow = (id: number, patch: Partial<Edit>) => {
    setEdits((previous) => ({ ...previous, [id]: { ...previous[id], ...patch } }))
    setChecks({})
  }
  async function run(action: string, task: () => Promise<void>) {
    if (lock.current || !canProcess || restoring || existing.isError || resumeError) return
    lock.current = true
    setBusy(action)
    setError('')
    try {
      await task()
      void client.invalidateQueries({ queryKey: ['transactions'], refetchType: 'none' })
      if (action === 'save' || action === 'precheck' || action === 'confirm') {
        editor.current?.querySelector('[data-guide-target="payment.batch.' + action + '"]')
          ?.dispatchEvent(new Event('fgc-payment-' + action + '-complete'))
      }
    } catch (failure) {
      setError(formError(failure, '처리 결과를 확인하지 못했습니다. 행별 상태를 확인해 주세요.'))
    } finally {
      lock.current = false
      setBusy('')
    }
  }
  async function save() {
    if (!header || !selected.length || !balanced || editingLocked) return
    await run('save', async () => {
      setChecks({})
      const current = await client.fetchQuery({ ...contractSchedulesQuery(contractId, stage), staleTime: 0 })
      const latest = current.headers?.filter(
        (row) =>
          row.activeYn &&
          row.paymentStage === stage &&
          row.schedulePurpose === 'OPERATIONAL' &&
          row.scheduleRegime === 'CURRENT',
      )
      if (
        latest?.length !== 1 ||
        latest[0].scheduleHeaderId !== header.scheduleHeaderId ||
        ['HOLD', 'CANCELLED'].includes(latest[0].status ?? '')
      )
        throw new Error('사용 중인 스케줄이 바뀌었습니다. 새 내용을 확인해 주세요.')
      const fresh = await client.fetchQuery({ ...scheduleDetailQuery(String(header.scheduleHeaderId)), staleTime: 0 })
      for (const line of selected) {
        const now = fresh.lines?.find((row) => row.scheduleLineId === line.scheduleLineId)
        if (!now || JSON.stringify(now) !== JSON.stringify(line))
          throw new Error('스케줄 내용이 바뀌었습니다. 새 내용을 확인한 뒤 다시 저장하세요.')
      }
      ensureMounted()
      const bodies = selected.map(rowPayload)
      const missing = selected.filter(line => !saved[line.scheduleLineId!])
      if (missing.length) {
        const response = await apiClient.request<Payment[]>('/api/v1/transactions/batch', {
          method: 'POST', body: { payments: missing.map(rowPayload) },
        })
        if (response.data?.length !== missing.length) {
          setUncertain(true)
          throw new Error('저장 결과를 확인하지 못했습니다. 지급 목록에서 등록 여부를 확인하세요.')
        }
        const next: Record<number, Saved> = {}
        missing.forEach((line, index) => {
          next[line.scheduleLineId!] = { payment: response.data![index], fingerprint: JSON.stringify(rowPayload(line)) }
        })
        setSavedLines(lines)
        setSaved(previous => ({ ...previous, ...next }))
      }
      for (const [index, line] of selected.entries()) {
        const old = saved[line.scheduleLineId!]
        if (!old || old.payment.status === 'CONFIRMED' || old.fingerprint === JSON.stringify(bodies[index])) continue
        ensureMounted()
        if (!old.payment.commissionTransactionId || !old.payment.updatedAt)
          throw new Error('저장본을 지급 목록에서 다시 확인하세요.')
        const payment = await saveDraft(String(old.payment.commissionTransactionId), bodies[index], old.payment.updatedAt)
        setSaved(previous => ({ ...previous, [line.scheduleLineId!]: { payment, fingerprint: JSON.stringify(bodies[index]) } }))
      }
    })
  }
  async function verifiedPrecheck(line: ScheduleLine) {
    ensureMounted()
    const entry = saved[line.scheduleLineId!],
      id = String(entry.payment.commissionTransactionId)
    const before = await readPayment(id)
    if (!before.updatedAt || before.updatedAt !== entry.payment.updatedAt || before.status !== 'DRAFT')
      throw new Error('다른 화면에서 지급 건이 변경되었습니다. 목록에서 다시 확인하세요.')
    ensureMounted()
    const result = await precheckPayment(id)
    const after = await readPayment(id)
    if (
      after.updatedAt !== before.updatedAt ||
      after.status !== 'DRAFT' ||
      result.paymentId !== before.commissionTransactionId
    )
      throw new Error('검증 중 저장본이 바뀌었습니다. 다시 확인하세요.')
    setChecks((previous) => ({ ...previous, [line.scheduleLineId!]: result }))
    return result
  }
  async function precheck() {
    if (!clean || !balanced || uncertain) return
    await run('precheck', async () => {
      setChecks({})
      for (const line of remaining) await verifiedPrecheck(line)
    })
  }
  async function confirm() {
    if (!allChecked || uncertain) return
    setConfirmOpen(false)
    await run('confirm', async () => {
      for (const line of remaining) {
        const key = line.scheduleLineId!,
          entry = saved[key]
        setSelectedResult(String(key))
        // Recheck each row after preceding confirmations so cumulative limits cannot be bypassed.
        const check = await verifiedPrecheck(line)
        if (!check.confirmable)
          throw new Error('이전 행의 확정 결과를 반영한 검증에서 차단됐습니다. 나머지 행은 확정하지 않았습니다.')
        try {
          ensureMounted()
          const payment = await confirmPayment(String(entry.payment.commissionTransactionId))
          if (
            payment.commissionTransactionId !== entry.payment.commissionTransactionId ||
            payment.status !== 'CONFIRMED'
          )
            throw new Error('확정 결과를 확인하지 못했습니다.')
          setSaved((previous) => ({ ...previous, [key]: { ...entry, payment } }))
        } catch (failure) {
          // A lost response is not a failed confirmation. Stop further writes until reconciled.
          setUncertain(true)
          setChecks({})
          throw failure
        }
      }
    })
  }
  async function reconcile() {
    await run('reload', async () => {
      for (const [key, entry] of Object.entries(saved)) {
        const payment = await readPayment(String(entry.payment.commissionTransactionId))
        if (
          !['DRAFT', 'CONFIRMED'].includes(payment.status ?? '') ||
          (payment.status !== 'CONFIRMED' && payment.updatedAt !== entry.payment.updatedAt)
        )
          throw new Error('지급 건이 변경되었습니다. 목록에서 최신 내용을 확인하세요.')
        setSaved((previous) => ({ ...previous, [Number(key)]: { ...entry, payment } }))
      }
      setUncertain(false)
      setChecks({})
    })
  }
  return (
    <div ref={editor} className={`publishing-page transaction-page transaction-form-page contract-payment-editor${comparing ? ' is-comparing' : ''}`}>
      {comparing && <ContractPaymentComparison root={editor} />}
      <header className="page-header transaction-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">지급 계약 건 등록, 귀속, 확정</h1>
        </div>
        <div className="page-header-actions">
          <ScreenWalkthrough title="계약별 지급 등록" steps={contractPaymentHelp} />
          <Link data-guide-feature="features.contract-payment-form.link-1" className="button button-secondary" to={returnTo}>
            목록으로
          </Link>
        </div>
      </header>
      {(existing.isError || resumeError) && <p role="alert" className="guidance guidance-warning">
        {resumeError || formError(existing.error, '기존 지급 건을 확인하지 못했습니다. 화면을 다시 열어주세요.')}
      </p>}
      {restoring && !existing.isError && !resumeError && <p role="status">기존 지급 건을 확인하고 있습니다.</p>}
      <ResizableSplit
        className="transaction-form-layout"
        storageKey="contract-payment-workspace"
        label="지급 계약 건과 검증 결과 너비"
        defaultRatio={65}
      >
        <div className="transaction-form-column">
          <section data-guide-feature="features.contract-payment-form.section-2" className="surface transaction-form-panel" data-guide-target="payment.batch.scope">
            <header className="surface-header transaction-surface-header">
              <h2 className="surface-title">지급 계약 건</h2>
              <span className="transaction-panel-meta">{clean ? '저장됨' : '미저장'}</span>
            </header>
            <fieldset
              className="surface-body transaction-form-grid contract-payment-fields"
            >
              <Field label="계약" required error={selectedContract && !contractAllowed ? '기준 정산월 이후의 계약입니다. 다른 계약을 선택하세요.' : undefined}>
                <SearchSelect data-guide-feature="features.contract-payment-form.searchselect-3"
                  data-guide-target="payment.batch.contract"
                  disabled={scopeLocked}
                  value={contractId}
                  source={contracts.source}
                  selected={selectedContract}
                  placeholder="계약번호 검색"
                  onSelect={(value, row) => {
                    contracts.remember(row)
                    setContractId(value)
                    setAttributionDate(null)
                    reset()
                  }}
                />
              </Field>
              <Field label="지급단계" required>
                <select data-guide-feature="features.contract-payment-form.select-4"
                  disabled={scopeLocked}
                  value={stage}
                  onChange={(event) => {
                    setStage(event.target.value as typeof stage)
                    reset()
                  }}
                >
                  <option value="GA_TO_FC">GA → 설계사</option>
                  <option value="INSURER_TO_GA">보험사 → GA</option>
                </select>
              </Field>
              <Field label="자료 구분">
                <input data-guide-feature="features.contract-payment-form.input-5" value="GA 수기지급" readOnly />
              </Field>
              <Field label="정산월" required>
                <input data-guide-feature="features.contract-payment-form.input-6" type="month" value={settlementMonth} readOnly />
              </Field>
              <Field label="회차" required>
                <select data-guide-feature="features.contract-payment-form.select-7"
                  disabled={scopeLocked || !installments.length}
                  value={chosen}
                  onChange={(event) => {
                    setInstallment(event.target.value)
                    setEdits({})
                    setExcluded([])
                    setChecks({})
                  }}
                >
                  {installments.length ? (
                    installments.map((value) => (
                      <option key={value} value={value}>
                        {value}회차
                      </option>
                    ))
                  ) : (
                    <option value="">계약을 선택하세요</option>
                  )}
                </select>
              </Field>
              <Field label="증빙">
                <input data-guide-feature="features.contract-payment-form.input-8"
                  disabled={editingLocked || selected.some(line => done(line.scheduleLineId!))}
                  value={evidence}
                  maxLength={500}
                  placeholder="문서번호, 링크"
                  onChange={(event) => {
                    setEvidence(event.target.value)
                    setChecks({})
                  }}
                />
              </Field>
              <Field
                label="귀속일"
                required
                error={
                  attributionDate && (!validDate(attributionDate) || !attributionDate.startsWith(`${month}-`) || attributionDate < attributionMinimum)
                    ? '계약일 이후, 기준 정산월 안에서 선택하세요.'
                    : undefined
                }
              >
                <input data-guide-feature="features.contract-payment-form.input-9"
                  type="date"
                  min={attributionMinimum}
                  max={monthEnd}
                  disabled={editingLocked || selected.some(line => done(line.scheduleLineId!))}
                  value={attributionDate}
                  onChange={(event) => {
                    setAttributionDate(event.target.value)
                    setChecks({})
                  }}
                />
              </Field>
              <Field
                label="지급 예정일"
                required
                error={
                  paymentDate && (!validDate(paymentDate) || !paymentDate.startsWith(`${month}-`))
                    ? '기준 정산월 안에서 선택하세요.'
                    : undefined
                }
              >
                <input data-guide-feature="features.contract-payment-form.input-10"
                  type="date"
                  min={monthStart}
                  max={monthEnd}
                  disabled={editingLocked || selected.some(line => done(line.scheduleLineId!))}
                  value={paymentDate}
                  onChange={(event) => {
                    setPaymentDate(event.target.value)
                    setChecks({})
                  }}
                />
              </Field>
              <div className="contract-payment-actions" data-guide-target="payment.batch.actions">
                <div className="transaction-action-list">
                  <Button data-guide-feature="features.contract-payment-form.button-11"
                    data-guide-target="payment.batch.save"
                    variant="secondary"
                    loading={busy === 'save'}
                    disabled={
                      !!busy ||
                      !canProcess ||
                      !contractAllowed ||
                      !header ||
                      !selected.length ||
                      selected.length > 200 ||
                      !balanced ||
                      !datesReady ||
                      clean ||
                      editingLocked ||
                      schedules.isFetching ||
                      detail.isFetching ||
                      items.isFetching
                    }
                    onClick={() => void save()}
                  >
                    임시저장
                  </Button>
                  <Button data-guide-feature="features.contract-payment-form.button-12"
                    data-guide-target="payment.batch.precheck"
                    variant="secondary"
                    loading={busy === 'precheck'}
                    disabled={!!busy || !canProcess || !clean || !balanced || !remaining.length || uncertain}
                    onClick={() => void precheck()}
                  >
                    사전검증
                  </Button>
                  <Button data-guide-feature="features.contract-payment-form.button-13"
                    data-guide-target="payment.batch.confirm"
                    loading={busy === 'confirm'}
                    disabled={!!busy || !canProcess || !allChecked || uncertain}
                    onClick={() => setConfirmOpen(true)}
                  >
                    확정
                  </Button>
                  {uncertain && Object.keys(saved).length > 0 && (
                    <Button data-guide-feature="features.contract-payment-form.button-14" variant="secondary" loading={busy === 'reload'} onClick={() => void reconcile()}>
                      확정 결과 다시 확인
                    </Button>
                  )}
                </div>
                {selected.length > 200 && (
                  <p className="field-error">한 번에 200행까지 등록할 수 있습니다. 등록할 행을 나눠 선택하세요.</p>
                )}
                {error && (
                  <p role="alert" className="field-error">
                    {error}
                  </p>
                )}
                {uncertain && <Link data-guide-feature="features.contract-payment-form.link-15" to={returnTo}>지급 목록에서 저장·확정 결과 확인</Link>}
              </div>
            </fieldset>
            {(schedules.isFetching || detail.isFetching) && (
              <p className="surface-body" role="status">
                예상 스케줄을 불러오고 있습니다.
              </p>
            )}
            {(schedules.error ||
              detail.error ||
              contracts.error ||
              contracts.selectedError ||
              items.error ||
              items.selectedError) && (
              <p className="surface-body" role="alert">
                {formError(
                  schedules.error ??
                    detail.error ??
                    contracts.error ??
                    contracts.selectedError ??
                    items.error ??
                    items.selectedError,
                  '자료를 불러오지 못했습니다.',
                )}
              </p>
            )}
            {contractId && !schedules.isFetching && !header && (
              <p className="surface-body">사용 중인 운영 스케줄을 확인해 주세요.</p>
            )}
          </section>
          <section data-guide-feature="features.contract-payment-form.section-16" className="surface transaction-attribution-panel" data-guide-target="payment.batch.lines">
            <header className="surface-header transaction-surface-header">
              <h2 className="surface-title">설계사별 지급 내역</h2>
              <span className="transaction-panel-meta">
                {selected.length}행 · 스케줄 {header?.scheduleVersionNo ?? '-'} 버전
              </span>
            </header>
            <div className="data-table-viewport">
              <table data-guide-feature="features.contract-payment-form.table-17" className="data-table contract-payment-table">
                <caption className="visually-hidden">계약의 지급 지급 내역</caption>
                <colgroup>
                  <col className="batch-col-select" />
                  <col className="batch-col-number" />
                  <col className="batch-col-agent" />
                  <col />
                  <col className="batch-col-amount" />
                  <col className="batch-col-status" />
                </colgroup>
                <thead>
                  <tr>
                    <th>선택</th>
                    <th>#</th>
                    <th>설계사</th>
                    <th>수수료 항목</th>
                    <th>귀속금액</th>
                    <th>처리 상태</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((line, index) => {
                    const key = line.scheduleLineId!,
                      edit = values(line),
                      selectedItem = items.data.find((item) => String(item.commissionItemId) === edit.item)
                    return (
                      <tr key={key} className={String(key) === String(currentResult) ? 'is-selected' : ''}>
                        <td>
                          <input data-guide-feature="features.contract-payment-form.input-18"
                            type="checkbox"
                            aria-label={`${line.recipientName} ${line.commissionItemName} 선택`}
                            disabled={scopeLocked || !eligible(line)}
                            checked={eligible(line) && !excluded.includes(key)}
                            onChange={(event) => {
                              setExcluded(
                                event.target.checked ? excluded.filter((id) => id !== key) : [...excluded, key],
                              )
                              setChecks({})
                            }}
                          />
                        </td>
                        <td>{index + 1}</td>
                        <td>
                          <span className="contract-payment-agent" title={line.recipientName ?? ''}>
                            {line.recipientName ?? (stage === 'INSURER_TO_GA' ? 'GA' : '대상 확인 필요')}
                          </span>
                        </td>
                        <td>
                          <SearchSelect data-guide-feature="features.contract-payment-form.searchselect-19"
                            disabled={editingLocked || done(line.scheduleLineId!) || !eligible(line)}
                            aria-label={`${line.recipientName} 수수료 항목 ${key}`}
                            value={edit.item}
                            source={itemSource}
                            selected={selectedItem}
                            placeholder="수수료 항목 검색"
                            onSelect={(value, row) => {
                              items.remember(row)
                              editRow(key, { item: value })
                            }}
                          />
                        </td>
                        <td>
                          <input data-guide-feature="features.contract-payment-form.input-20"
                            data-compare-amount={`actual-${key}`}
                            aria-label={`귀속금액 ${key}`}
                            disabled={editingLocked || done(line.scheduleLineId!) || !eligible(line)}
                            type="number"
                            min="0"
                            step="1"
                            value={edit.amount}
                            onChange={(event) => editRow(key, { amount: event.target.value })}
                          />
                        </td>
                        <td>
                          <Button data-guide-feature="features.contract-payment-form.button-21" variant="ghost" title={checks[key]?.blockers?.map(blocker => blocker.message).filter(Boolean).join(' / ')} onClick={() => setSelectedResult(String(key))}>
                            {done(key)
                              ? '확정 완료'
                              : checks[key]
                                ? checks[key].confirmable
                                  ? '검증 통과'
                                  : '검증 차단'
                                : saved[key]
                                  ? saved[key].fingerprint === fingerprint(line)
                                    ? '임시저장'
                                    : '변경됨'
                                  : eligible(line)
                                    ? '미저장'
                                    : '등록 제외'}
                          </Button>
                        </td>
                      </tr>
                    )
                  })}
                  {!visible.length && (
                    <tr>
                      <td colSpan={6}>계약과 회차를 선택하면 지급 내역이 자동으로 채워집니다.</td>
                    </tr>
                  )}
                </tbody>
                <tfoot>
                  <tr>
                    <th colSpan={4}>귀속금액 합계</th>
                    <td colSpan={2}>
                      {int(total)}원{' '}
                      {selected.length > 0 && !balanced && <span className="field-error">귀속금액을 확인하세요.</span>}
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </section>
        </div>
        <div className="transaction-form-column">
          <ContractPaymentGates
            rows={selected.length}
            inputsReady={selected.length > 0 && selected.every((line) => !!fingerprint(line))}
            amountsReady={balanced}
            savedRows={
              selected.filter(
                (line) => !!fingerprint(line) && saved[line.scheduleLineId!]?.fingerprint === fingerprint(line),
              ).length
            }
            checks={selected.map((line) => checks[line.scheduleLineId!])}
            confirmedRows={selected.filter((line) => done(line.scheduleLineId!)).length}
            uncertain={uncertain}
          />
          <section data-guide-feature="features.contract-payment-form.section-22" className="surface contract-payment-preview" data-guide-target="payment.batch.expected" aria-labelledby="contract-payment-preview-title">
            <header className="surface-header transaction-surface-header">
              <h2 className="surface-title" id="contract-payment-preview-title">
                설계사별 예상 스케줄
              </h2>
              <span className="transaction-panel-meta">{chosen ? `${chosen}회차` : '회차 선택'}</span>
            </header>
            <div className="surface-body">
              <ol className="contract-payment-preview-list">
                {visible.map((line, index) => (
                  <li key={line.scheduleLineId}>
                    <button data-guide-feature="features.contract-payment-form.button-23"
                      type="button"
                      className="contract-payment-preview-row"
                      disabled={!selected.some((row) => row.scheduleLineId === line.scheduleLineId)}
                      aria-pressed={line.scheduleLineId === currentResult}
                      onClick={() => setSelectedResult(String(line.scheduleLineId))}
                    >
                      <span className="contract-payment-preview-number">{index + 1}</span>
                      <span className="contract-payment-preview-person">
                        <strong title={line.recipientName ?? 'GA'}>{line.recipientName ?? 'GA'}</strong>
                        <span title={line.commissionItemName ?? '항목 확인 필요'}>{line.commissionItemName ?? '항목 확인 필요'}</span>
                      </span>
                      <span className="contract-payment-preview-amount" data-compare-amount={`expected-${line.scheduleLineId}`}>
                        {line.cashflowType === 'DEDUCTION' && '차감 '}
                        {line.expectedAmount == null ? '금액 미정' : `${int(scheduleAmount(line))}원`}
                      </span>
                    </button>
                  </li>
                ))}
              </ol>
              {!visible.length && (
                <p className="transaction-panel-meta">계약과 회차를 선택하면 예상 스케줄이 표시됩니다.</p>
              )}
            </div>
          </section>
          <ContractPaymentCapPanel
            rows={selected.map(line => ({
              id: line.scheduleLineId!, name: line.recipientName ?? 'GA',
              item: items.data.find(item => String(item.commissionItemId) === values(line).item)?.itemName ?? line.commissionItemName ?? '항목 확인 필요',
              amount: Number(values(line).amount), result: checks[line.scheduleLineId!],
            }))}
            pending={busy === 'precheck'}
          />
        </div>
      </ResizableSplit>
      <Modal
        open={confirmOpen}
        title="계약의 지급 건을 확정할까요?"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button data-guide-feature="features.contract-payment-form.button-24" variant="secondary" onClick={() => setConfirmOpen(false)}>
              돌아가기
            </Button>
            <Button data-guide-feature="features.contract-payment-form.button-25" onClick={() => void confirm()}>확인하고 확정</Button>
          </>
        }
      >
        <div className="data-table-viewport"><table data-guide-feature="features.contract-payment-form.table-26" className="data-table contract-payment-confirm-table">
          <caption className="visually-hidden">확정할 지급 내역</caption>
          <thead><tr><th>설계사</th><th>수수료 항목</th><th>귀속금액</th></tr></thead>
          <tbody>{remaining.map(line => <tr key={line.scheduleLineId}><td>{line.recipientName ?? 'GA'}</td>
            <td>{items.data.find(item => String(item.commissionItemId) === values(line).item)?.itemName ?? line.commissionItemName}</td>
            <td>{int(Number(values(line).amount))}원</td></tr>)}</tbody>
          <tfoot><tr><th colSpan={2}>합계 · {remaining.length}행</th><td>{int(remaining.reduce((sum, line) => sum + Number(values(line).amount), 0))}원</td></tr></tfoot>
        </table></div>
      </Modal>
    </div>
  )
}
