import { useEffect, useRef, useState, type ReactNode } from 'react'
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
import { contractSchedulesQuery } from '../contract/api'
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
import { GatePanel, CapPanel } from './ConfirmationPanels'
import { ScreenWalkthrough } from '../assistant/guide/ScreenWalkthrough'
import type { ScreenHelpStep } from '../assistant/guide/ScreenHelp'

const helpSteps: ScreenHelpStep[] = [
  {
    targetId: 'payment.batch.scope',
    title: '지급 계약 건',
    instruction: '계약과 회차를 고르면 귀속행이 채워집니다.',
    interaction: '수수료 항목과 지급 금액을 확인하세요.',
  },
  {
    targetId: 'payment.batch.lines',
    title: '설계사별 귀속행',
    instruction: '설계사별 수수료와 금액을 수정합니다.',
    interaction: '귀속합계를 위쪽 지급 금액과 맞추세요.',
  },
  {
    targetId: 'payment.batch.actions',
    title: '저장·사전검증·확정',
    instruction: '임시저장 후 모든 행을 사전검증합니다.',
    interaction: '결과를 확인하고 확정을 진행하세요.',
  },
].map((step) => ({ ...step, group: '계약별 지급 등록', details: [], concise: true }))
type Edit = { amount: string; item: string; date: string; due: string }
type Saved = { payment: Payment; fingerprint: string }

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
  comparison,
  initialContractId = '',
}: {
  month: string
  canProcess: boolean
  returnTo: string
  comparison: (busy: boolean) => ReactNode
  initialContractId?: string
}) {
  const client = useQueryClient()
  const [contractId, setContractId] = useState(initialContractId)
  const [stage, setStage] = useState<'GA_TO_FC' | 'INSURER_TO_GA'>('GA_TO_FC')
  const [settlementMonth, setMonth] = useState(month)
  const [installment, setInstallment] = useState('')
  const [excluded, setExcluded] = useState<number[]>([])
  const [edits, setEdits] = useState<Record<number, Partial<Edit>>>({})
  const [amount, setAmount] = useState<string | null>(null)
  const [itemId, setItemId] = useState('')
  const [flow, setFlow] = useState('')
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
  const contracts = useEntityChoices('contracts', {}, [contractId])
  const schedules = useQuery({
    ...contractSchedulesQuery(contractId, stage),
    enabled: !!contractId,
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
    enabled: !!header?.scheduleHeaderId,
  })
  const lines = savedLines ?? detail.data?.lines ?? []
  const installments = [...new Set(lines.map((line) => line.installmentNo).filter((n): n is number => n != null))].sort(
    (a, b) => a - b,
  )
  const chosen =
    installment ||
    String(lines.find((line) => line.dueDate?.startsWith(settlementMonth))?.installmentNo ?? installments[0] ?? '')
  const visible = lines.filter((line) => String(line.installmentNo) === chosen)
  const eligible = (line: ScheduleLine) =>
    ['PLANNED', 'CONFIRMED', 'RESTARTED'].includes(line.lineStatus ?? '') && !!line.scheduleLineId
  const selected = visible.filter((line) => eligible(line) && !excluded.includes(line.scheduleLineId!))
  const values = (line: ScheduleLine): Edit => ({
    amount: String(line.expectedAmount ?? ''),
    item: String(line.commissionItemId ?? ''),
    date: line.attributionDate ?? '',
    due: line.dueDate ?? '',
    ...edits[line.scheduleLineId!],
  })
  const items = useEntityChoices(
    'items',
    { asOf: `${settlementMonth}-01` },
    [itemId, ...selected.map((line) => values(line).item)],
    !!settlementMonth,
    (item) => stage !== 'INSURER_TO_GA' || item.itemCode !== 'NEWCOMER_SUPPORT',
    stage,
  )
  const rowPayload = (line: ScheduleLine) => {
    const edit = values(line)
    if (!/^\d+$/.test(edit.amount)) throw new Error('귀속금액을 0원 이상 원 단위로 입력하세요.')
    const item = items.data.find((item) => String(item.commissionItemId) === edit.item)
    if (!item) throw new Error('검색 결과에서 수수료 항목을 선택하세요.')
    const cashflowType = flow || item.cashflowType || line.cashflowType
    if (item.cashflowType && item.cashflowType !== cashflowType)
      throw new Error('수수료 항목과 금액 흐름을 맞춰 주세요.')
    return payload(
      {
        ...line,
        expectedAmount: Number(edit.amount),
        commissionItemId: Number(edit.item),
        attributionDate: edit.date,
        dueDate: edit.due,
        cashflowType,
      },
      Number(contractId),
      stage,
      settlementMonth,
      evidence,
    )
  }
  const total = selected.reduce((sum, line) => sum + Number(values(line).amount || 0), 0)
  const expected = amount ?? String(total)
  const balanced = /^\d+$/.test(expected) && Number.isSafeInteger(total) && Number(expected) === total
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
  const editingLocked = !!busy || uncertain || selected.some((line) => done(line.scheduleLineId!))
  const currentResult = selected.some((line) => String(line.scheduleLineId) === selectedResult)
    ? Number(selectedResult)
    : remaining[0]?.scheduleLineId || selected[0]?.scheduleLineId
  useUnsavedWork(!!busy || (!!contractId && (!clean || uncertain)))
  const reset = () => {
    setInstallment('')
    setExcluded([])
    setEdits({})
    setAmount(null)
    setItemId('')
    setChecks({})
    setError('')
    setSelectedResult('')
  }
  const editRow = (id: number, patch: Partial<Edit>) => {
    setEdits((previous) => ({ ...previous, [id]: { ...previous[id], ...patch } }))
    setChecks({})
  }
  async function run(action: string, task: () => Promise<void>) {
    if (lock.current || !canProcess) return
    lock.current = true
    setBusy(action)
    setError('')
    try {
      await task()
      void client.invalidateQueries({ queryKey: ['transactions'], refetchType: 'none' })
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
      if (!Object.keys(saved).length) {
        const response = await apiClient.request<Payment[]>('/api/v1/transactions/batch', {
          method: 'POST',
          body: { payments: bodies },
        })
        if (response.data?.length !== selected.length) {
          setUncertain(true)
          throw new Error('저장 결과를 확인하지 못했습니다. 지급 목록에서 등록 여부를 확인하세요.')
        }
        const next: Record<number, Saved> = {}
        selected.forEach((line, index) => {
          next[line.scheduleLineId!] = { payment: response.data![index], fingerprint: JSON.stringify(bodies[index]) }
        })
        setSavedLines(lines)
        setSaved(next)
      } else {
        for (const [index, line] of selected.entries()) {
          ensureMounted()
          const old = saved[line.scheduleLineId!]
          if (!old?.payment.commissionTransactionId || !old.payment.updatedAt)
            throw new Error('저장본을 지급 목록에서 다시 확인하세요.')
          const payment = await saveDraft(
            String(old.payment.commissionTransactionId),
            bodies[index],
            old.payment.updatedAt,
          )
          setSaved((previous) => ({
            ...previous,
            [line.scheduleLineId!]: { payment, fingerprint: JSON.stringify(bodies[index]) },
          }))
        }
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
    <div className="publishing-page transaction-page transaction-form-page contract-payment-editor">
      <header className="page-header transaction-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">지급 계약 건 등록, 귀속, 확정</h1>
        </div>
        <div className="page-header-actions">
          {comparison(!!busy)}
          <ScreenWalkthrough title="계약별 지급 등록" steps={helpSteps} />
          <Link className="button button-secondary" to={returnTo}>
            목록으로
          </Link>
        </div>
      </header>
      <ResizableSplit
        className="transaction-form-layout"
        storageKey="contract-payment-workspace"
        label="지급 계약 건과 검증 결과 너비"
        defaultRatio={65}
      >
        <div className="transaction-form-column">
          <section className="surface transaction-form-panel" data-guide-target="payment.batch.scope">
            <header className="surface-header transaction-surface-header">
              <h2 className="surface-title">① 지급 계약 건</h2>
              <span className="transaction-panel-meta">{clean ? '저장됨' : '미저장'}</span>
            </header>
            <fieldset
              className="surface-body transaction-form-grid contract-payment-fields"
              disabled={!!busy || uncertain}
            >
              <Field label="계약" required>
                <SearchSelect
                  disabled={scopeLocked}
                  value={contractId}
                  source={contracts.source}
                  selected={contracts.data.find((row) => String(row.contractId) === contractId)}
                  placeholder="계약번호 검색"
                  onSelect={(value, row) => {
                    contracts.remember(row)
                    setContractId(value)
                    reset()
                  }}
                />
              </Field>
              <Field label="지급단계" required>
                <select
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
              <Field label="원천유형">
                <input value="GA 수기지급" readOnly />
              </Field>
              <Field label="정산월" required>
                <input
                  disabled={scopeLocked}
                  type="month"
                  value={settlementMonth}
                  onChange={(event) => {
                    setMonth(event.target.value)
                    reset()
                  }}
                />
              </Field>
              <Field label="회차" required>
                <select
                  disabled={scopeLocked || !installments.length}
                  value={chosen}
                  onChange={(event) => {
                    setInstallment(event.target.value)
                    setEdits({})
                    setExcluded([])
                    setAmount(null)
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
              <Field label="수수료 항목 일괄 적용" helper="행별로 다른 항목도 선택할 수 있습니다.">
                <SearchSelect
                  disabled={editingLocked}
                  value={itemId}
                  source={items.source}
                  selected={items.data.find((row) => String(row.commissionItemId) === itemId)}
                  placeholder="수수료 항목 이름/검색"
                  onSelect={(value, row) => {
                    items.remember(row)
                    setItemId(value)
                    if (value) {
                      setEdits((previous) => ({
                        ...previous,
                        ...Object.fromEntries(
                          selected.map((line) => [
                            line.scheduleLineId!,
                            { ...previous[line.scheduleLineId!], item: value },
                          ]),
                        ),
                      }))
                      setChecks({})
                    }
                  }}
                />
              </Field>
              <Field label="금액 (원)" required helper="아래 귀속금액 합계와 맞춰 주세요.">
                <input
                  disabled={editingLocked}
                  type="number"
                  min="0"
                  step="1"
                  value={expected}
                  onChange={(event) => {
                    setAmount(event.target.value)
                    setChecks({})
                  }}
                />
              </Field>
              <Field label="흐름">
                <select
                  disabled={editingLocked}
                  value={flow}
                  onChange={(event) => {
                    setFlow(event.target.value)
                    setChecks({})
                  }}
                >
                  <option value="">행별 수수료 항목 기준</option>
                  <option value="PAYMENT">지급</option>
                  <option value="DEDUCTION">차감</option>
                </select>
              </Field>
              <Field label="증빙">
                <input
                  disabled={editingLocked}
                  value={evidence}
                  maxLength={500}
                  placeholder="문서번호, 링크"
                  onChange={(event) => {
                    setEvidence(event.target.value)
                    setChecks({})
                  }}
                />
              </Field>
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
          <section className="surface transaction-attribution-panel" data-guide-target="payment.batch.lines">
            <header className="surface-header transaction-surface-header">
              <h2 className="surface-title">② 귀속행, 이 계약의 설계사별 지급 내역</h2>
              <span className="transaction-panel-meta">
                {selected.length}행 · 스케줄 {header?.scheduleVersionNo ?? '-'} 버전
              </span>
            </header>
            <div className="data-table-viewport">
              <table className="data-table contract-payment-table">
                <caption className="visually-hidden">계약의 지급 귀속행</caption>
                <thead>
                  <tr>
                    <th>선택</th>
                    <th>설계사</th>
                    <th>수수료 항목</th>
                    <th>귀속일</th>
                    <th>지급 예정일</th>
                    <th>귀속금액</th>
                    <th>처리 상태</th>
                  </tr>
                </thead>
                <tbody>
                  {visible.map((line) => {
                    const key = line.scheduleLineId!,
                      edit = values(line),
                      selectedItem = items.data.find((item) => String(item.commissionItemId) === edit.item)
                    return (
                      <tr key={key} className={String(key) === String(currentResult) ? 'is-selected' : ''}>
                        <td>
                          <input
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
                        <td>{line.recipientName ?? (stage === 'INSURER_TO_GA' ? 'GA' : '대상 확인 필요')}</td>
                        <td>
                          <SearchSelect
                            disabled={editingLocked || !eligible(line)}
                            aria-label={`${line.recipientName} 수수료 항목 ${key}`}
                            value={edit.item}
                            source={items.source}
                            selected={selectedItem}
                            placeholder="수수료 항목 검색"
                            onSelect={(value, row) => {
                              items.remember(row)
                              editRow(key, { item: value })
                            }}
                          />
                        </td>
                        <td>
                          <input
                            aria-label={`귀속일 ${key}`}
                            disabled={editingLocked || !eligible(line)}
                            type="date"
                            value={edit.date}
                            onChange={(event) => editRow(key, { date: event.target.value })}
                          />
                        </td>
                        <td>
                          <input
                            aria-label={`지급 예정일 ${key}`}
                            disabled={editingLocked || !eligible(line)}
                            type="date"
                            value={edit.due}
                            onChange={(event) => editRow(key, { due: event.target.value })}
                          />
                        </td>
                        <td>
                          <input
                            aria-label={`귀속금액 ${key}`}
                            disabled={editingLocked || !eligible(line)}
                            type="number"
                            min="0"
                            step="1"
                            value={edit.amount}
                            onChange={(event) => editRow(key, { amount: event.target.value })}
                          />
                        </td>
                        <td>
                          <Button variant="ghost" onClick={() => setSelectedResult(String(key))}>
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
                      <td colSpan={7}>계약과 회차를 선택하면 귀속행이 자동으로 채워집니다.</td>
                    </tr>
                  )}
                </tbody>
                <tfoot>
                  <tr>
                    <th colSpan={5}>귀속금액 합계</th>
                    <td colSpan={2}>
                      {int(total)}원 {!balanced && <span className="field-error">지급 금액과 다릅니다.</span>}
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
          </section>
        </div>
        <div className="transaction-form-column">
          <div className="surface surface-body">
            <Field label="검증 결과를 볼 귀속행">
              <select value={String(currentResult ?? '')} onChange={(event) => setSelectedResult(event.target.value)}>
                {selected.map((line) => (
                  <option key={line.scheduleLineId} value={line.scheduleLineId}>
                    {line.recipientName ?? 'GA'} ·{' '}
                    {items.data.find((item) => String(item.commissionItemId) === values(line).item)?.itemName ??
                      line.commissionItemName}
                  </option>
                ))}
              </select>
            </Field>
            <p>
              <span role="status">
                {selected.filter((line) => done(line.scheduleLineId!)).length}/{selected.length}행 확정 완료
              </span>
            </p>
          </div>
          <GatePanel result={checks[currentResult!] ?? null} />
          <CapPanel
            result={checks[currentResult!] ?? null}
            pending={busy === 'precheck'}
            error=""
            contracts={contracts.data}
          />
          <section className="surface surface-body transaction-action-panel" data-guide-target="payment.batch.actions">
            <div className="transaction-action-list">
              <Button
                variant="secondary"
                loading={busy === 'save'}
                disabled={
                  !!busy ||
                  !canProcess ||
                  !header ||
                  !selected.length ||
                  selected.length > 200 ||
                  !balanced ||
                  clean ||
                  editingLocked ||
                  schedules.isFetching ||
                  detail.isFetching ||
                  items.isFetching
                }
                onClick={() => void save()}
              >
                임시저장 (검증 없음)
              </Button>
              <Button
                variant="secondary"
                loading={busy === 'precheck'}
                disabled={!!busy || !canProcess || !clean || !balanced || !remaining.length || uncertain}
                onClick={() => void precheck()}
              >
                사전검증
              </Button>
              <Button
                loading={busy === 'confirm'}
                disabled={!!busy || !canProcess || !allChecked || uncertain}
                onClick={() => setConfirmOpen(true)}
              >
                확정
              </Button>
              {uncertain && Object.keys(saved).length > 0 && (
                <Button variant="secondary" loading={busy === 'reload'} onClick={() => void reconcile()}>
                  확정 결과 다시 확인
                </Button>
              )}
            </div>
            <p className="transaction-panel-meta">임시저장 → 사전검증 → 확정 순서로 진행합니다.</p>
            {selected.length > 200 && (
              <p className="field-error">한 번에 200행까지 등록할 수 있습니다. 등록할 행을 나눠 선택하세요.</p>
            )}
            {error && (
              <p role="alert" className="field-error">
                {error}
              </p>
            )}
            {uncertain && <Link to={returnTo}>지급 목록에서 저장·확정 결과 확인</Link>}
          </section>
        </div>
      </ResizableSplit>
      <Modal
        open={confirmOpen}
        title="계약의 지급 건을 확정할까요?"
        onClose={() => setConfirmOpen(false)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmOpen(false)}>
              돌아가기
            </Button>
            <Button onClick={() => void confirm()}>확인하고 확정</Button>
          </>
        }
      >
        <p>
          {remaining.length}행 · {int(remaining.reduce((sum, line) => sum + Number(values(line).amount), 0))}원을
          확정합니다.
        </p>
        <p>
          행마다 최신 한도를 확인하며 순서대로 확정합니다. 중간에 차단되면 완료된 행은 유지하고 나머지는 중단합니다.
        </p>
      </Modal>
    </div>
  )
}
