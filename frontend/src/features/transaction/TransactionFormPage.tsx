import { settlementEntry, subscribeSettlementEntry } from '../assistant/settlement/entry'
import { ResizableSplit } from '../../components/ResizableSplit'
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useOutletContext, useSearchParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { StatusBadge } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/client'
import { today } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { withMonth } from '../../stores/workspace'
import { SearchSelect } from '../../components/SearchSelect'
import { readPaymentEntry, safeReturnTo } from './payment-entry'
import type { PaymentEntry } from './payment-entry'
import { useEntityChoices } from '../base/selection'
import { AttributionEditor } from './AttributionEditor'
import { CapPanel, ConfirmationModal, GatePanel } from './ConfirmationPanels'
import { confirmPayment, contractQuery, draftQuery, formError, precheckPayment, saveDraft } from './form-api'
import type { Payment, Precheck } from './form-api'
import {
  availableItems,
  blockedContractId,
  blockedContractNo,
  buildPayload,
  draftRows,
  filterAgentId,
  FormValidationError,
  hasRecordableBlocker,
  initialForm,
  isNewcomer,
  isRetryableConfirmation,
  newRow,
  normalizeRow,
  RECORDABLE_CODES,
  rowWasNormalized,
  SOURCE_TYPE,
} from './form-model'
import type { FormValues } from './form-model'
import { connectPaymentGuide, PaymentGuideObserver } from './payment-guide'
import { PaymentGuide } from './PaymentGuide'
import './transaction.css'
import './form.css'

export default function TransactionFormPage() {
  const { user, month } = useOutletContext<ShellContext>()
  const [params] = useSearchParams()
  const id = params.get('id')
  const draft = useQuery({
    ...draftQuery(id),
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  const activeMonth = month ?? today().slice(0, 7)
  // A cached draft is only a lookup hint. Hydrate the editable snapshot from this
  // opening's GET; subsequent focus/reconnect must not overwrite unsaved edits.
  if (id && (draft.error || !draft.isFetchedAfterMount || !draft.data))
    return (
      <div className="transaction-screen-state" role={draft.error ? 'alert' : 'status'}>
        {draft.error ? (
          <>
            {formError(draft.error, '지급 건 화면을 초기화하지 못했습니다. 화면을 새로고침하세요.')}{' '}
            <Button variant="secondary" onClick={() => void draft.refetch()}>
              다시 시도
            </Button>
          </>
        ) : (
          '기준정보를 불러오는 중입니다.'
        )}
      </div>
    )
  if (id && draft.data?.status !== 'DRAFT')
    return (
      <div className="transaction-screen-state is-error" role="alert">
        수정 가능한 지급 초안이 아닙니다. <Link to={withMonth('/transactions', activeMonth)}>목록으로</Link>
      </div>
    )
  return (
    <TransactionForm
      key={`${id ?? 'new'}:${activeMonth}:${id ? '' : params.toString()}`}
      entry={id ? {} : readPaymentEntry(params)}
      returnTo={safeReturnTo(params.get('returnTo'), withMonth('/transactions', activeMonth))}
      initialId={id}
      draft={draft.data}
      month={activeMonth}
      canProcess={user.canProcess}
      role={user.roleCode}
    />
  )
}

function TransactionForm({
  entry,
  returnTo,
  initialId,
  draft,
  month,
  canProcess,
  role,
}: {
  entry: PaymentEntry
  returnTo: string
  initialId: string | null
  draft?: Payment
  month: string
  canProcess: boolean
  role: string
}) {
  const navigate = useNavigate()
  const root = useRef<HTMLDivElement>(null)
  const help = useRef<HTMLButtonElement>(null)
  const [helpOpen, setHelpOpen] = useState(false)
  const fullFlow = useSyncExternalStore(subscribeSettlementEntry, settlementEntry)
  const closeHelp = () => {
    setHelpOpen(false)
    help.current?.focus()
  }
  const queryClient = useQueryClient()
  const [form, setForm] = useState(() => ({
    ...initialForm(month, draft),
    ...(!draft
      ? {
          agentId: entry.agentId ?? '',
          commissionItemId: entry.commissionItemId ?? '',
          paymentStage: entry.paymentStage ?? 'GA_TO_FC',
          amount: entry.amount ?? '250000',
          cashflowType: entry.cashflowType ?? 'PAYMENT',
        }
      : {}),
  }))
  const [rawRows, setRows] = useState(() =>
    !draft && entry.contractId
      ? [
          {
            ...newRow(1, entry.attributionDate ?? '', entry.amount ?? '250000'),
            contractId: entry.contractId,
          },
        ]
      : draftRows(draft),
  )
  const initialPayment = useRef(draft)
  const guide = useRef<PaymentGuideObserver | null>(null)
  const disconnectGuide = useRef<(() => void) | null>(null)
  useLayoutEffect(() => {
    const observer = new PaymentGuideObserver(initialPayment.current)
    guide.current = observer
    return () => {
      if (disconnectGuide.current) disconnectGuide.current()
      else observer.dispose()
      disconnectGuide.current = null
      guide.current = null
    }
  }, [])
  const sequence = useRef(rawRows.length)
  const [paymentId, setPaymentId] = useState(initialId)
  const [locked, setLocked] = useState(false)
  const [dirty, setDirty] = useState(!initialId)
  const [busy, setBusy] = useState<'save' | 'precheck' | 'confirm' | null>(null)
  const actionLock = useRef(false)
  const [fieldError, setFieldError] = useState<{ field: string; message: string }>()
  const [saveError, setSaveError] = useState('')
  const [result, setResult] = useState<Precheck | null>(null)
  const [precheckError, setPrecheckError] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [confirmFailure, setConfirmFailure] = useState<ApiError | null>(null)
  const [confirmError, setConfirmError] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const agentQuery = useEntityChoices(
    'agents',
    { asOf: form.settlementMonth + '-01' },
    [form.agentId],
    Boolean(form.settlementMonth),
    (agent) => agent.activeYn !== false && agent.agentStatus === 'ACTIVE',
  )
  const itemQuery = useEntityChoices(
    'items',
    { asOf: form.settlementMonth + '-01', cashflowType: form.cashflowType },
    [form.commissionItemId],
    Boolean(form.settlementMonth),
    (item) => form.paymentStage !== 'INSURER_TO_GA' || item.itemCode !== 'NEWCOMER_SUPPORT',
    form.paymentStage,
  )
  const contractListQuery = useEntityChoices(
    'contracts',
    { agentId: form.paymentStage === 'INSURER_TO_GA' ? '' : filterAgentId(form.agentId, agentQuery.data) },
    rawRows.map((row) => row.contractId),
  )
  const agents = agentQuery.data ?? []
  const items = itemQuery.data ?? []
  const contracts = contractListQuery.data ?? []
  const referenceError = agentQuery.error ?? itemQuery.error ?? contractListQuery.error
  const loading = agentQuery.isFetching || itemQuery.isFetching || contractListQuery.isFetching
  const insurer = form.paymentStage === 'INSURER_TO_GA'
  const newcomer = isNewcomer(form, items)
  const rows = rawRows.map((row) => normalizeRow(row, insurer, newcomer))
  const normalized = !loading && !referenceError && rows.some((row, index) => rowWasNormalized(rawRows[index], row))
  const needsSave = dirty || normalized
  const confirmationNeedsRetry = Boolean(confirmError) && isRetryableConfirmation(confirmFailure)
  const confirmationRejected = confirmFailure != null && !isRetryableConfirmation(confirmFailure)
  const blockerId = result && confirmFailure ? blockedContractId(result, confirmFailure.code) : undefined
  const knownContractNo =
    result && confirmFailure ? blockedContractNo(result, confirmFailure.code, contracts) : undefined
  const blockerContract = useQuery({
    ...contractQuery(blockerId),
    enabled:
      blockerId != null &&
      blockerId > 0 &&
      Boolean(confirmationRejected && confirmFailure && RECORDABLE_CODES.includes(confirmFailure.code)) &&
      !knownContractNo,
    retry: false,
  })
  const inputDisabled =
    !canProcess || confirmed || locked || busy !== null || loading || Boolean(referenceError) || confirmationNeedsRetry
  const selectedAgent = agents.find((agent) => String(agent.agentId) === form.agentId)
  const agentLabel = selectedAgent ? `${selectedAgent.agentCode} ${selectedAgent.agentName}` : '—'
  const ready = canProcess && !loading && !referenceError && busy == null && !confirmed
  const fieldMessage = (field: string) => (fieldError?.field === field ? fieldError.message : undefined)
  useEffect(() => {
    if (fieldError && busy == null) document.getElementById(fieldError.field)?.focus()
  }, [fieldError, busy])

  function validatedPayload() {
    // An as-of lookup may no longer contain an old draft's recipient/item.
    // Like an empty legacy select, it must require a fresh choice before saving.
    const validatedForm = {
      ...form,
      agentId: agents.some((agent) => String(agent.agentId) === form.agentId) ? form.agentId : '',
      commissionItemId: availableItems(form, items).some(
        (item) => String(item.commissionItemId) === form.commissionItemId,
      )
        ? form.commissionItemId
        : '',
    }
    const validatedRows = rows.map((row) => ({
      ...row,
      contractId: contracts.some((contract) => String(contract.contractId) === row.contractId) ? row.contractId : '',
    }))
    return buildPayload(validatedForm, validatedRows, newcomer)
  }
  let guideInputValid = false
  let guideValidationMessage: string | undefined
  try {
    validatedPayload()
    guideInputValid = true
  } catch (error) {
    guideValidationMessage = error instanceof Error ? error.message : undefined
  }
  useLayoutEffect(() => {
    guide.current?.update({
      role,
      canProcess,
      paymentId,
      form,
      rows,
      dirty: needsSave,
      ready: !loading && !referenceError,
      valid: guideInputValid,
      validationMessage: guideValidationMessage,
    })
    if (guide.current && !disconnectGuide.current) disconnectGuide.current = connectPaymentGuide(guide.current)
  })

  function invalidatePreview() {
    guide.current?.invalidateInput()
    setDirty(true)
    setResult(null)
    setPrecheckError('')
    setConfirmFailure(null)
    setConfirmError('')
  }
  function changeForm(patch: Partial<FormValues>) {
    invalidatePreview()
    const next = { ...form, ...patch }
    const nextItems = availableItems(next, items)
    if (patch.cashflowType && !nextItems.some((item) => String(item.commissionItemId) === next.commissionItemId))
      next.commissionItemId = ''
    if (patch.paymentStage && patch.paymentStage !== form.paymentStage) {
      next.agentId = ''
      next.commissionItemId = ''
      setRows([])
      toast('지급단계가 변경되어 수수료 항목과 귀속행을 초기화했습니다.', 'warning')
    } else if (patch.agentId !== undefined && patch.agentId !== form.agentId) {
      setRows([])
      if (rows.length) toast('수령 설계사가 변경되어 기존 귀속행을 비웠습니다. 계약을 다시 선택하세요.', 'warning')
    } else if (patch.settlementMonth !== undefined) {
      next.agentId = ''
    } else {
      setRows(rows.map((row) => normalizeRow(row, next.paymentStage === 'INSURER_TO_GA', isNewcomer(next, items))))
    }
    setForm(next)
  }
  function showError(error: unknown, fallback: string) {
    const message = formError(error, fallback)
    toast(message, 'error')
    return message
  }
  function showFieldError(error: { field: string; message: string }) {
    setFieldError(error)
  }
  async function save() {
    if (!ready || locked || confirmationNeedsRetry || actionLock.current) return
    setFieldError(undefined)
    setSaveError('')
    let payload
    try {
      payload = validatedPayload()
    } catch (error) {
      if (error instanceof FormValidationError) showFieldError(error)
      return
    }
    actionLock.current = true
    setBusy('save')
    const observer = guide.current!
    const operation = observer.begin('save')
    try {
      const saved = await saveDraft(paymentId, payload)
      if (!observer.isCurrent(operation)) return
      if (!observer.acceptSaved(operation, saved)) throw new Error('저장된 지급 초안을 확인할 수 없습니다.')
      setPaymentId(String(saved.commissionTransactionId))
      setForm(initialForm(month, saved))
      setRows(draftRows(saved))
      setLocked(true)
      setDirty(false)
      setResult(null)
      void queryClient.invalidateQueries({ queryKey: ['transactions', 'list'] })
      queryClient.setQueryData(draftQuery(String(saved.commissionTransactionId)).queryKey, saved)
      toast('작성중(DRAFT)으로 저장했습니다. 저장만 했을 뿐 검증은 하지 않았습니다.', 'success')
    } catch (error) {
      if (!observer.isCurrent(operation)) return
      observer.fail(operation)
      const message = showError(error, '수수료 지급 건을 저장하지 못했습니다.')
      setSaveError(message)
      const fields: Record<string, string> = {
        sourceType: 'sourceType',
        sourceBusinessKey: 'bizKey',
        settlementMonth: 'settlementMonth',
        agentId: 'recipient',
        commissionItemId: 'item',
        amount: 'amount',
        evidenceRef: 'evidence',
        paymentStage: 'stage',
      }
      if (error instanceof ApiError && error.field && fields[error.field])
        showFieldError({ field: fields[error.field], message })
    } finally {
      actionLock.current = false
      setBusy(null)
    }
  }
  async function precheck() {
    if (!ready || !paymentId || needsSave || confirmationNeedsRetry || actionLock.current) return
    actionLock.current = true
    setBusy('precheck')
    const observer = guide.current!
    const operation = observer.begin('precheck')
    setResult(null)
    setPrecheckError('')
    setConfirmFailure(null)
    setConfirmError('')
    try {
      const before = await observer.readOperationPayment(operation).catch(() => null)
      if (!observer.isCurrent(operation)) return
      const checked = await precheckPayment(paymentId)
      if (!observer.isCurrent(operation)) return
      const after = await observer.readOperationPayment(operation).catch(() => null)
      if (!observer.isCurrent(operation)) return
      if (
        checked.paymentId !== Number(paymentId) ||
        (before && after && !observer.acceptPrecheck(operation, before, checked, after))
      )
        throw new Error('지급 저장본이 변경되어 검증 결과를 사용할 수 없습니다. 초안을 다시 확인하세요.')
      if (!before || !after) observer.fail(operation)
      setResult(checked)
      toast(
        checked.confirmable
          ? '사전검증을 통과했습니다. 이제 확정할 수 있습니다.'
          : '사전검증에서 확정 차단 사유가 발견되었습니다.',
        checked.confirmable ? 'success' : 'warning',
      )
    } catch (error) {
      if (!observer.isCurrent(operation)) return
      observer.fail(operation)
      setPrecheckError(showError(error, '1,200% 사전검증을 수행하지 못했습니다.'))
    } finally {
      actionLock.current = false
      setBusy(null)
    }
  }
  async function confirm() {
    if (
      !ready ||
      !paymentId ||
      needsSave ||
      confirmationRejected ||
      !result ||
      (!result.confirmable && !hasRecordableBlocker(result)) ||
      actionLock.current
    )
      return
    actionLock.current = true
    setBusy('confirm')
    const observer = guide.current!
    const operation = observer.begin('confirm')
    setConfirmFailure(null)
    setConfirmError('')
    try {
      const response = await confirmPayment(paymentId)
      if (!observer.isSamePayment(operation)) return
      if (response.commissionTransactionId !== Number(paymentId) || response.status !== 'CONFIRMED')
        throw new Error('지급 확정 결과를 확인하지 못했습니다.')
      setConfirmed(true)
      setModalOpen(false)
      // Keep the verified completion screen mounted; the list/detail refresh on their next opening.
      void queryClient.invalidateQueries({ queryKey: ['transactions'], refetchType: 'none' })
      toast('수수료 지급 건을 확정했습니다.', 'success')
      if (!helpOpen) {
        void navigate(returnTo)
      } else {
        // A guide read must never turn a committed business success into a retryable failure.
        try {
          const refreshed = await observer.readOperationPayment(operation)
          if (!observer.acceptConfirmed(operation, response, refreshed)) observer.fail(operation)
        } catch {
          observer.fail(operation)
        }
      }
    } catch (error) {
      if (!observer.isSamePayment(operation)) return
      observer.fail(operation)
      setConfirmError(showError(error, '수수료 지급 건을 확정하지 못했습니다.'))
      setConfirmFailure(error instanceof ApiError ? error : null)
    } finally {
      actionLock.current = false
      setBusy(null)
    }
  }

  return (
    <div
      ref={root}
      className={`publishing-page transaction-page transaction-form-page${helpOpen ? ' has-payment-guide' : ''}`}
      data-guide-target="payment.form"
      data-transaction-form
      aria-busy={loading || busy !== null}
    >
      <header className="page-header transaction-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">지급 건 등록 · 귀속 · 확정</h1>
        </div>
        <div className="page-header-actions">
          <button
            ref={help}
            type="button"
            className="button button-secondary"
            disabled={fullFlow}
            title={fullFlow ? '전체 업무 가이드가 진행 중입니다.' : undefined}
            aria-expanded={helpOpen}
            onClick={() => setHelpOpen(true)}
          >
            도움말
          </button>
          <Link className="button button-secondary" to={returnTo}>
            <span className="material-symbols-rounded" aria-hidden="true">
              arrow_back
            </span>{' '}
            목록으로
          </Link>
        </div>
      </header>
      <aside
        className="guidance guidance-info transaction-boundary-guidance"
        aria-labelledby="transaction-boundary-title"
      >
        <span className="material-symbols-rounded guidance-icon" aria-hidden="true">
          event
        </span>
        <div>
          <h2 className="guidance-title" id="transaction-boundary-title">
            초년도 구간은 [계약일, 1주년일)입니다
          </h2>
          <p className="guidance-copy">
            계약일이 2026-07-18이면 귀속일 2027-07-17까지 초년도이고, 2027-07-18부터는 아닙니다. 회차 번호가 아닌
            귀속일로 판단하므로 초년도 지급을 13회차로 미뤄도 한도를 피할 수 없습니다.
          </p>
        </div>
      </aside>
      {(loading || referenceError) && (
        <div
          id="transaction-form-status"
          className={`transaction-screen-state${referenceError ? ' is-error' : ''}`}
          role={referenceError ? 'alert' : 'status'}
        >
          {referenceError ? (
            <>
              {formError(referenceError, '기준정보를 불러오지 못했습니다.')}{' '}
              <Button
                variant="secondary"
                onClick={() => {
                  void agentQuery.refetch()
                  void itemQuery.refetch()
                  void contractListQuery.refetch()
                }}
              >
                다시 시도
              </Button>
            </>
          ) : (
            '기준정보를 불러오는 중입니다.'
          )}
        </div>
      )}
      {locked && (
        <div id="draft-lock-notice" className="guidance guidance-neutral transaction-lock-notice" role="status">
          <span className="material-symbols-rounded guidance-icon" aria-hidden="true">
            lock
          </span>
          <div>
            <strong className="guidance-title">저장된 초안을 검증하는 동안 입력이 잠겼습니다</strong>
            <p className="guidance-copy">내용을 다시 편집하려면 목록에서 이 초안을 다시 여세요.</p>
          </div>
        </div>
      )}
      <ResizableSplit
        className="transaction-form-layout"
        storageKey="transaction-form-workspace"
        label="지급 건·귀속행과 확정 게이트 카드 너비"
        defaultRatio={61}
        id="main-grid"
      >
        <div className="transaction-form-column">
          <section
            data-guide-target="payment.scope"
            className="surface transaction-form-panel"
            aria-labelledby="transaction-input-title"
          >
            <header className="surface-header transaction-surface-header">
              <h2 className="surface-title" id="transaction-input-title">
                ① 지급 건
              </h2>
              <span id="tx-status">
                <StatusBadge tone={confirmed ? 'success' : 'neutral'}>
                  {paymentId ? `${confirmed ? '확정' : '작성중'} · #${paymentId}` : '미저장'}
                </StatusBadge>
              </span>
            </header>
            <div className="surface-body transaction-form-grid">
              <Field label="지급단계" required error={fieldMessage('stage')}>
                <select
                  id="stage"
                  value={form.paymentStage}
                  disabled={inputDisabled}
                  onChange={(event) => changeForm({ paymentStage: event.target.value as FormValues['paymentStage'] })}
                >
                  <option value="GA_TO_FC">GA→설계사</option>
                  <option value="INSURER_TO_GA">원수사→GA</option>
                </select>
              </Field>
              <Field label="원천유형" required error={fieldMessage('sourceType')}>
                <select id="sourceType" value={SOURCE_TYPE} disabled={inputDisabled} onChange={() => {}}>
                  <option value={SOURCE_TYPE}>GA 수기지급</option>
                </select>
              </Field>
              <Field
                label="업무키"
                required
                helper="같은 원천유형 안에서 중복될 수 없습니다."
                error={fieldMessage('bizKey')}
              >
                <input
                  id="bizKey"
                  className="transaction-mono"
                  maxLength={160}
                  placeholder="자동 생성됩니다"
                  value={form.sourceBusinessKey}
                  disabled={inputDisabled}
                  onChange={(event) => changeForm({ sourceBusinessKey: event.target.value })}
                />
              </Field>
              <Field
                label="정산월"
                required
                helper="DB에는 항상 그 달 1일로 저장됩니다."
                error={fieldMessage('settlementMonth')}
              >
                <input
                  id="settlementMonth"
                  type="month"
                  value={form.settlementMonth}
                  disabled={inputDisabled}
                  onChange={(event) => changeForm({ settlementMonth: event.target.value })}
                />
              </Field>
              {!insurer && (
                <Field
                  label="수령 설계사"
                  required
                  helper="GA→설계사 단계에서는 반드시 있어야 합니다."
                  error={fieldMessage('recipient')}
                >
                  <SearchSelect
                    id="recipient"
                    value={form.agentId}
                    disabled={inputDisabled}
                    source={agentQuery.source}
                    selected={selectedAgent}
                    placeholder="설계사 이름·코드 검색"
                    onSelect={(agentId, row) => {
                      agentQuery.remember(row)
                      changeForm({ agentId })
                    }}
                  />
                </Field>
              )}
              <Field label="수수료 항목" required error={fieldMessage('item')}>
                <SearchSelect
                  id="item"
                  value={form.commissionItemId}
                  disabled={inputDisabled}
                  source={itemQuery.source}
                  selected={items.find((row) => String(row.commissionItemId) === form.commissionItemId)}
                  placeholder="수수료 항목 이름·코드 검색"
                  onSelect={(commissionItemId, row) => {
                    itemQuery.remember(row)
                    changeForm({ commissionItemId })
                  }}
                />
              </Field>
              <Field
                label="금액 (원)"
                required
                helper="금액은 0 이상으로 입력하고 지급·차감은 흐름으로 구분합니다."
                error={fieldMessage('amount')}
              >
                <input
                  id="amount"
                  data-guide-target="payment.amount"
                  className="transaction-number"
                  type="number"
                  min="0"
                  step="1"
                  value={form.amount}
                  disabled={inputDisabled}
                  onChange={(event) => changeForm({ amount: event.target.value })}
                />
              </Field>
              <Field label="흐름" required>
                <select
                  id="cashflow"
                  value={form.cashflowType}
                  disabled={inputDisabled}
                  onChange={(event) => changeForm({ cashflowType: event.target.value })}
                >
                  <option value="PAYMENT">지급</option>
                  <option value="DEDUCTION">차감</option>
                </select>
              </Field>
              <Field
                label="증빙"
                helper="귀속행에서 제외를 고르면 증빙이 반드시 필요합니다."
                error={fieldMessage('evidence')}
              >
                <input
                  id="evidence"
                  data-guide-target="payment.evidence"
                  maxLength={200}
                  placeholder="문서번호 · 링크"
                  value={form.evidenceRef}
                  disabled={inputDisabled}
                  onChange={(event) => changeForm({ evidenceRef: event.target.value })}
                />
              </Field>
            </div>
          </section>
          <div data-guide-target="payment.attributions">
            <AttributionEditor
              rows={rows}
              contracts={contracts}
              contractSource={contractListQuery.source}
              onContractSelected={contractListQuery.remember}
              agentLabel={agentLabel}
              amount={form.amount}
              insurer={insurer}
              newcomer={newcomer}
              disabled={inputDisabled}
              error={fieldError}
              onChange={(next) => {
                invalidatePreview()
                setRows(next)
              }}
              onAdd={() => {
                invalidatePreview()
                setRows([
                  ...rows,
                  normalizeRow(
                    newRow(++sequence.current, '', rows.length ? '0' : String(Number(form.amount) || 0)),
                    insurer,
                    newcomer,
                  ),
                ])
              }}
            />
          </div>
        </div>
        <div className="transaction-form-column">
          <GatePanel result={result} />
          <div data-guide-target="payment.precheck.result">
            <CapPanel result={result} pending={busy === 'precheck'} error={precheckError} />
          </div>
          <section className="surface transaction-action-panel" aria-labelledby="transaction-actions-title">
            <h2 className="visually-hidden" id="transaction-actions-title">
              지급 건 작업
            </h2>
            <div className="surface-body transaction-action-list">
              <Button
                id="btn-save"
                data-guide-target="payment.save"
                variant="secondary"
                loading={busy === 'save'}
                disabled={!ready || locked || confirmationNeedsRetry}
                onClick={() => void save()}
                aria-describedby="transaction-action-help transaction-permission-help"
              >
                <span className="material-symbols-rounded" aria-hidden="true">
                  save
                </span>
                {busy === 'save' ? '저장 중' : initialId && !locked ? '수정 저장' : '임시저장 (검증 없음)'}
              </Button>
              <Button
                id="btn-precheck"
                data-guide-target="payment.precheck"
                variant="secondary"
                loading={busy === 'precheck'}
                disabled={!ready || !paymentId || needsSave || confirmationNeedsRetry}
                onClick={() => void precheck()}
                aria-describedby="transaction-action-help transaction-permission-help"
              >
                <span className="material-symbols-rounded" aria-hidden="true">
                  calculate
                </span>
                {busy === 'precheck' ? '검증 중' : '사전검증'}
              </Button>
              <Button
                id="btn-confirm"
                data-guide-target="payment.confirm"
                disabled={!ready || !result || needsSave || confirmationRejected}
                onClick={() => setModalOpen(true)}
                aria-describedby="transaction-action-help transaction-permission-help"
              >
                <span className="material-symbols-rounded" aria-hidden="true">
                  verified
                </span>
                확정
              </Button>
              <p className="field-helper" id="transaction-action-help">
                확정은 서버가 판정한 6단계를 모두 통과해야 합니다.
              </p>
              {needsSave && initialId && (
                <p className="field-helper">
                  {normalized
                    ? '현재 지급 단계·항목에 맞춰 귀속 입력을 조정했습니다. 먼저 저장한 뒤 사전검증하세요.'
                    : '변경한 내용을 먼저 저장한 뒤 사전검증하세요.'}
                </p>
              )}
              {confirmationNeedsRetry && (
                <p className="field-helper" role="status">
                  확정 결과를 확인하지 못했습니다. 확정 창을 다시 열어 같은 요청으로 결과를 확인하세요.
                </p>
              )}
              {!canProcess && (
                <p className="field-error" id="transaction-permission-help">
                  현재 계정은 조회만 가능하며 저장·사전검증·확정을 실행할 수 없습니다.
                </p>
              )}
              {saveError && (
                <p className="field-error" role="alert">
                  {saveError}
                </p>
              )}
            </div>
          </section>
        </div>
      </ResizableSplit>
      {helpOpen && (
        <PaymentGuide
          root={root}
          modalOpen={modalOpen}
          confirmed={confirmed}
          onClose={closeHelp}
          onList={() => void navigate(returnTo)}
        />
      )}
      <ConfirmationModal
        open={modalOpen}
        result={result}
        form={form}
        paymentId={paymentId}
        month={month}
        pending={busy === 'confirm'}
        failure={confirmFailure}
        failureText={confirmError}
        contractNo={knownContractNo || blockerContract.data?.contractNo}
        onClose={() => setModalOpen(false)}
        onConfirm={() => void confirm()}
      />
    </div>
  )
}
