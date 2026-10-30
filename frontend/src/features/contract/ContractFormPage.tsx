import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { returnLink, safeReturnTo } from '../transaction/payment-entry'
import { Button } from '../../components/Button'
import { EvidenceLink } from '../../components/EvidenceLink'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { ApiError } from '../../lib/api/client'
import { int, today } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { useInsurerOptions } from '../base/api'
import type { Agent, Product } from '../base/api'
import { useEntityChoices } from '../base/selection'
import { usePolicyRequest } from '../policies/usePolicyRequest'
import { SearchSelect } from '../../components/SearchSelect'
import { contractDetailQuery, createContract, updateContract } from './api'
import type { ContractDetail, ContractUpdateRequest } from './api'
import { ContractStatusBadge } from './badges'
import {
  contractStatusLabels,
  followUpStatusLabels,
  paymentCycleLabels,
  responseLabel,
  statusReasonLabels,
} from './labels'
import { contractErrorText } from './detailState'
import {
  changesSchedule,
  createRequest,
  fillCyclePremium,
  initialDraft,
  initialStatusEvent,
  seoulNow,
  statusEventRequest,
  validateDraft,
  validateStatusEvent,
} from './form'
import type { ContractDraft, ContractField, ContractFieldErrors, StatusEventDraft } from './form'
import './legacy.css'
import './form.css'

export default function ContractFormPage() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const returnTo = safeReturnTo(params.get('returnTo'), '/contracts')
  const [revision, setRevision] = useState(0)
  const detail = useQuery({
    ...contractDetailQuery(id ?? ''),
    queryKey: ['contracts', 'editor', id ?? ''],
    enabled: Boolean(id),
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  async function reload() {
    await detail.refetch({ throwOnError: true })
    setRevision((current) => current + 1)
  }
  return (
    <div className="contract-form-page">
      <header className="page-header contract-form-page-header">
        <h1 className="page-title">보험계약 {id ? '수정' : '등록'}</h1>
        <Link className="button button-secondary" to={returnTo}>
          <span className="material-symbols-rounded" aria-hidden="true">
            arrow_back
          </span>
          목록으로
        </Link>
      </header>
      {id && detail.isPending ? (
        <p role="status">계약을 불러오는 중입니다.</p>
      ) : id && !detail.data ? (
        <div className="surface contract-form-error" role="alert">
          <p>{contractErrorText(detail.error ?? new Error('계약을 불러오지 못했습니다.'))}</p>
          <Button variant="secondary" onClick={() => void detail.refetch()}>
            다시 불러오기
          </Button>
        </div>
      ) : (
        <ContractEditor
          key={`${id ?? 'new'}:${revision}`}
          id={id}
          contract={id ? detail.data : undefined}
          onReload={reload}
        />
      )}
    </div>
  )
}

function ContractEditor({
  id,
  contract,
  onReload,
}: {
  id?: string
  contract?: ContractDetail
  onReload: () => Promise<void>
}) {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const returnTo = safeReturnTo(params.get('returnTo'), '/contracts')
  const queryClient = useQueryClient()
  const currentDate = today()
  // Keep the original read token and values until the user explicitly reloads the editor.
  const [original] = useState(contract)
  const [draft, setDraft] = useState(() => initialDraft(currentDate, contract))
  const [errors, setErrors] = useState<ContractFieldErrors>({})
  const [saveError, setSaveError] = useState('')
  const [conflict, setConflict] = useState(false)
  const [saving, setSaving] = useState(false)
  const [confirmReload, setConfirmReload] = useState(false)
  const [reloading, setReloading] = useState(false)
  // 접수일시 기본값은 화면을 연 시각으로 한 번만 정한다. 다시 보내도 본문·멱등키가 같아야 한다.
  const [statusEvent, setStatusEvent] = useState(() => initialStatusEvent(seoulNow()))
  const [confirmSave, setConfirmSave] = useState(false)
  const requestKey = usePolicyRequest()
  const submitting = useRef(false)
  const mounted = useRef(true)
  const insurers = useInsurerOptions()
  const products = useEntityChoices(
    'products',
    { asOf: draft.contractDate, insurerId: draft.insurerId },
    [draft.productOfferingId],
    Boolean(draft.contractDate && draft.insurerId),
  )
  const agents = useEntityChoices(
    'agents',
    { asOf: draft.contractDate },
    [draft.agentId],
    Boolean(draft.contractDate),
    (agent) => agent.rankCode === 'FC' && agent.agentStatus === 'ACTIVE' && agent.activeYn === true,
  )
  const availableAgents = (agents.data ?? []).filter(
    (agent) => agent.rankCode === 'FC' && agent.agentStatus === 'ACTIVE' && agent.activeYn === true,
  )
  // 수정은 최초 연결 설계사를 고정한다(#653). 계약일을 바꾸면 그날 기준 그 설계사의 소속으로 저장한다.
  const redated = Boolean(original && draft.contractDate !== original.contractDate)
  const selectedAgent = availableAgents.find((row) => String(row.agentId) === draft.agentId)
  const form: ContractDraft = redated
    ? {
        ...draft,
        organizationId: String(selectedAgent?.organizationId ?? ''),
        organizationName: selectedAgent?.organizationName ?? '',
      }
    : draft
  const agentUnavailable = redated && !agents.isFetching && !selectedAgent
  const product = products.data?.find((row) => String(row.productOfferingId) === draft.productOfferingId)
  const referenceError = insurers.error ?? products.error ?? agents.error
  const loadingReferences = insurers.isFetching || products.isFetching || agents.isFetching
  const missingToken = Boolean(id && !original?.updatedAt)
  // 선택지는 서버 허용 전이 표(IF-API-12)만 쓴다. 상태를 바꿀 때만 사건을 입력·전송한다.
  const nextStatuses = original?.allowedNextStatuses ?? []
  const reasonCodes = original?.statusReasonCodes ?? []
  const statusChanged = Boolean(original) && draft.contractStatus !== original?.contractStatus
  const validate = () => ({
    ...validateDraft(form, currentDate),
    ...(statusChanged ? validateStatusEvent(statusEvent, seoulNow(), reasonCodes) : {}),
  })
  const valid = Object.keys(validate()).length === 0
  const disabled = !valid || saving || loadingReferences || Boolean(referenceError) || conflict || missingToken
  const changes = [
    statusChanged && '상태 사건 기록·해당 미확정 대상 처리',
    original && changesSchedule(createRequest(form), original) && '산정정보 변경에 따른 스케줄 새 버전',
  ].filter(Boolean)
  const changeSummary = changes.length ? changes.join(' · ') : '계약 정보만 저장(상태 사건·스케줄 새 버전 없음)'
  const productTerm = product?.paymentTermMonths
  const hasProductTerm = productTerm != null && Number.isInteger(productTerm) && productTerm > 0
  const missingRefundTable = product?.standardDeduction80Yn && product.paymentTermMonths == null

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    if (referenceError) toast(contractErrorText(referenceError, '기준정보를 불러오지 못했습니다.'), 'error')
  }, [referenceError])

  function change(field: ContractField, value: string, selection?: Agent | Product) {
    setErrors((current) => ({ ...current, [field]: undefined }))
    setDraft((current) => {
      let next = { ...current, [field]: value } as ContractDraft
      if (field === 'insurerId' || field === 'contractDate') next.productOfferingId = ''
      if (field === 'contractDate' && !id) {
        next.agentId = ''
        next.organizationId = ''
        next.organizationName = ''
      }
      if (field === 'agentId') {
        const agent = (selection as Agent | undefined) ?? availableAgents.find((row) => String(row.agentId) === value)
        next.organizationId = String(agent?.organizationId ?? '')
        next.organizationName = agent?.organizationName ?? ''
      }
      if (field === 'productOfferingId') {
        const selected =
          (selection as Product | undefined) ?? products.data?.find((row) => String(row.productOfferingId) === value)
        if (!selected?.standardDeduction80Yn) next.standardSurrenderDeductionAmount = ''
        if (selected?.paymentTermMonths != null && selected.paymentTermMonths > 0) {
          next.paymentTermMonths = String(selected.paymentTermMonths)
        }
      }
      if (field === 'premiumPerCycleAmount') next.premiumEntered = value !== ''
      if (
        field === 'premiumPerCycleAmount' ||
        field === 'paymentCycleCode' ||
        field === 'monthlyEquivalentFirstPremium'
      ) {
        next = fillCyclePremium(next)
      }
      return next
    })
  }

  function changeEvent(field: keyof StatusEventDraft, value: string) {
    setErrors((current) => ({ ...current, [`statusEvent.${field}`]: undefined }))
    setStatusEvent((current) => ({ ...current, [field]: value }))
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current || conflict || missingToken) return
    const validation = validate()
    setErrors(validation)
    setSaveError('')
    if (Object.keys(validation).length || disabled) return
    // 상태 변경은 전후 상태·일시·사유를 확인창에서 검토한 뒤에만 보낸다.
    if (statusChanged) setConfirmSave(true)
    else void save()
  }

  async function save() {
    if (submitting.current) return
    submitting.current = true
    setSaving(true)
    try {
      const body = createRequest(form)
      const request: ContractUpdateRequest | undefined = id
        ? {
            ...body,
            expectedUpdatedAt: original!.updatedAt!,
            statusEvent: statusChanged ? statusEventRequest(statusEvent) : undefined,
          }
        : undefined
      const updated = request ? await updateContract(id!, request, requestKey(request)) : undefined
      const saved = updated ?? (await createContract(body))
      const savedId = saved.contractId ?? id
      if (!savedId) throw new Error('저장 결과의 계약 번호를 확인하지 못했습니다. 목록에서 저장 여부를 확인해 주세요.')
      // Invalidate inactive projections too: an update can regenerate schedules and financial snapshots.
      await queryClient.invalidateQueries({ queryKey: ['contracts'], refetchType: 'none' })
      await queryClient.invalidateQueries({ queryKey: ['schedules'], refetchType: 'none' })
      await queryClient.invalidateQueries({ queryKey: ['cap-checks'], refetchType: 'none' })
      // The save still updates caches after leaving, but must not navigate away from the user's new screen.
      if (!mounted.current) return
      const count = saved.scheduleHeaderIds?.length ?? 0
      // 200은 계약·사건 저장 성공이다. 후속 처리 결과는 상세의 상태이력에서 따로 확인한다.
      const message = id
        ? `계약 수정이 저장되었습니다.${updated?.statusEventId != null ? ` 후속 처리 상태를 확인하세요. (후속 처리: ${responseLabel(followUpStatusLabels, updated.followUp?.status)})` : ''}${count ? ` 산정정보 변경에 따라 예상 스케줄 ${count}건을 새 버전으로 만들었습니다 — [예상 스케줄] 탭에서 확인하세요.` : ''}`
        : `보험계약을 저장했습니다.${count ? ` 예상 스케줄 ${count}건을 함께 생성했습니다 — [예상 스케줄] 탭에서 확인하세요.` : ''}`
      if (!id && missingRefundTable)
        toast(
          '선택한 상품버전에 적용할 해약환급률표가 없습니다. 계약은 저장되며, 월 통합검증에서 검토필요로 처리됩니다.',
          'warning',
          6000,
        )
      // Keep the synchronous guard closed through navigation, including repeated submit events.
      await navigate(returnLink(`/contracts/${encodeURIComponent(savedId)}`, returnTo, String(savedId)), {
        state: { message },
      })
    } catch (error) {
      if (!mounted.current) return
      const message = contractErrorText(error instanceof Error ? error : new Error('보험계약을 저장하지 못했습니다.'))
      if (error instanceof ApiError) {
        // 수정 시각 충돌과 그사이 바뀐 허용 전이는 최신 계약을 다시 불러와야 고칠 수 있다.
        if (error.code === 'FGC-CONT-003' || error.code === 'FGC-IMPL-TRANSITION_DENIED') setConflict(true)
        if (
          error.field &&
          (error.field in draft || error.field.startsWith('statusEvent.')) &&
          error.code !== 'FGC-COMMON-500'
        )
          setErrors({ [error.field]: error.message })
      }
      setSaveError(message)
      setConfirmSave(false)
      submitting.current = false
      setSaving(false)
    }
  }

  async function reload() {
    if (reloading) return
    setReloading(true)
    try {
      await onReload()
    } catch (error) {
      setSaveError(contractErrorText(error instanceof Error ? error : new Error('최신 계약을 불러오지 못했습니다.')))
      setReloading(false)
      setConfirmReload(false)
    }
  }

  const amountInput = (field: ContractField, label: string, helper?: string, optional = false) => (
    <Field
      label={label}
      required={!optional}
      error={errors[field]}
      helper={helper}
      labelAccessory={
        field === 'standardSurrenderDeductionAmount' ? (
          <EvidenceLink label="표준해약공제액 근거" icon="help">
            REG-08 · 제4-32조제5항 [현행규정]. 표준해약공제액 80% 이상을 공제하는 상품은 1,200% 한도 계산에서 1년
            경과시점 예상 해약환급금을 합산합니다. REG-12 · 제4-32조제6항 [현행규정]. 차익거래 판정에서도 일정 상품의
            36개월 이내 해지는 해약환급금을 합산합니다.
          </EvidenceLink>
        ) : undefined
      }
    >
      <input
        type="number"
        name={field}
        min={field === 'paymentTermMonths' ? 1 : 0}
        step="1"
        inputMode="numeric"
        max={field === 'paymentTermMonths' ? 2147483647 : Number.MAX_SAFE_INTEGER}
        placeholder={field === 'standardSurrenderDeductionAmount' ? '해당 상품만 입력' : undefined}
        value={draft[field]}
        onChange={(event) => change(field, event.target.value)}
        readOnly={field === 'paymentTermMonths' && hasProductTerm}
        disabled={field === 'standardSurrenderDeductionAmount' && !product?.standardDeduction80Yn}
      />
    </Field>
  )

  return (
    <>
      {saveError && (
        <div className="surface contract-form-error" role="alert">
          <strong>입력 내용을 확인해 주세요.</strong>
          <p>{saveError}</p>
        </div>
      )}
      {referenceError && (
        <div className="surface contract-form-error">
          <p>기준정보를 불러오지 못했습니다. 다시 불러온 뒤 저장해 주세요.</p>
          <Button
            variant="secondary"
            disabled={loadingReferences}
            onClick={() => {
              void insurers.refetch()
              if (draft.insurerId && draft.contractDate) void products.refetch()
              if (draft.contractDate) void agents.refetch()
            }}
          >
            기준정보 다시 불러오기
          </Button>
        </div>
      )}
      <form
        className="contract-editor"
        onSubmit={(event) => void submit(event)}
        noValidate
        aria-label={`보험계약 ${id ? '수정' : '등록'} 입력`}
      >
        <fieldset className="contract-editor-fields contract-form-layout" disabled={saving || reloading}>
          <section
            className="surface contract-form-card contract-basic-form-card contract-editor-basic"
            aria-labelledby="contract-basic-heading"
          >
            <header className="surface-header contract-form-card-header">
              <div>
                <h2 id="contract-basic-heading" className="surface-title">
                  계약 기본
                </h2>
                <p>보험회사와 계약일을 먼저 선택하면 판매 가능한 상품 버전을 조회합니다.</p>
              </div>
            </header>
            <div className="contract-form-fields">
              <Field label="보험회사" required error={errors.insurerId}>
                <select
                  name="insurerId"
                  className="select-control"
                  value={draft.insurerId}
                  disabled={insurers.isFetching}
                  onChange={(event) => change('insurerId', event.target.value)}
                >
                  <option value="">보험회사를 선택하세요</option>
                  {(insurers.data ?? []).map((row) => (
                    <option
                      key={row.insurerId}
                      value={row.insurerId}
                      disabled={row.activeYn === false && row.insurerId !== original?.insurerId}
                    >
                      {row.insurerName} ({row.insurerCode})
                    </option>
                  ))}
                  {original?.insurerId && !insurers.data?.some((row) => row.insurerId === original.insurerId) && (
                    <option value={original.insurerId}>{original.insurerName}</option>
                  )}
                </select>
              </Field>
              <div>
                <Field
                  label="상품 판매버전"
                  required
                  error={errors.productOfferingId}
                  labelAccessory={
                    <EvidenceLink label="판매버전 근거" icon="help">
                      REG-19 · 부칙 제2조 [개정고시]. 분급 관련 조항은 시행 후 최초로 작성·변경된 기초서류로서
                      판매개시일이 2027-01-01 이후인 상품부터 적용합니다. 1,200%룰·차익거래 적용 여부는 계약 체결일
                      기준으로 별도 판단하므로 계약일에 유효한 판매버전을 선택해야 합니다.
                    </EvidenceLink>
                  }
                >
                  <SearchSelect
                    name="productOfferingId"
                    value={draft.productOfferingId}
                    source={products.source}
                    selected={
                      product ??
                      (draft.productOfferingId === String(original?.productOfferingId) ? original : undefined)
                    }
                    placeholder="상품명·코드·판매버전 검색"
                    disabled={!draft.insurerId || !draft.contractDate}
                    onSelect={(value, row) => {
                      products.remember(row)
                      change('productOfferingId', value, row)
                    }}
                  />
                </Field>
              </div>
              <Field
                label="계약번호"
                required
                error={errors.contractNo}
                helper="같은 보험회사 안에서는 중복될 수 없습니다."
              >
                <input
                  name="contractNo"
                  placeholder="계약번호 입력"
                  maxLength={80}
                  autoComplete="off"
                  value={draft.contractNo}
                  onChange={(event) => change('contractNo', event.target.value)}
                />
              </Field>
              <Field label="계약일" required error={errors.contractDate} helper="미래 날짜는 선택할 수 없습니다.">
                <input
                  name="contractDate"
                  type="date"
                  max={currentDate}
                  value={draft.contractDate}
                  onChange={(event) => change('contractDate', event.target.value)}
                />
              </Field>
              {original ? (
                <>
                  <div className="field">
                    <span className="field-label">현재 상태</span>
                    <span>
                      <ContractStatusBadge code={original.contractStatus} label={original.contractStatusLabel} />
                    </span>
                  </div>
                  <Field
                    label="변경 상태"
                    required
                    error={errors.contractStatus}
                    helper={
                      nextStatuses.length
                        ? '허용된 상태로만 바꿀 수 있습니다.'
                        : '이 상태에서는 바꿀 수 있는 상태가 없습니다.'
                    }
                  >
                    <select
                      name="contractStatus"
                      className="select-control"
                      value={draft.contractStatus}
                      disabled={!nextStatuses.length}
                      onChange={(event) => change('contractStatus', event.target.value)}
                    >
                      <option value={original.contractStatus}>변경 안 함</option>
                      {nextStatuses.map((status) => (
                        <option key={status} value={status}>
                          {contractStatusLabels[status]}
                        </option>
                      ))}
                    </select>
                  </Field>
                </>
              ) : (
                <Field label="계약상태" required error={errors.contractStatus}>
                  <select
                    name="contractStatus"
                    className="select-control"
                    value={draft.contractStatus}
                    onChange={(event) => change('contractStatus', event.target.value)}
                  >
                    {Object.entries(contractStatusLabels).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </Field>
              )}
            </div>
            {statusChanged && (
              <fieldset className="contract-status-event">
                <legend>상태 변경 사유</legend>
                <p>상태를 바꿀 때만 입력합니다. 처리자와 처리 시각은 서버가 기록합니다.</p>
                <div className="contract-form-fields">
                  <Field
                    label="효력일시"
                    required
                    error={errors['statusEvent.effectiveAt']}
                    helper="실제 효력 시각(서울). 최신 사건의 효력일시 이후, 접수일시 이전이어야 합니다."
                  >
                    <input
                      type="datetime-local"
                      name="statusEvent.effectiveAt"
                      max={statusEvent.receivedAt || undefined}
                      value={statusEvent.effectiveAt}
                      onChange={(event) => changeEvent('effectiveAt', event.target.value)}
                    />
                  </Field>
                  <Field
                    label="접수일시"
                    required
                    error={errors['statusEvent.receivedAt']}
                    helper="실제로 접수한 시각(서울). 서버 처리 시각과 따로 기록합니다."
                  >
                    <input
                      type="datetime-local"
                      name="statusEvent.receivedAt"
                      value={statusEvent.receivedAt}
                      onChange={(event) => changeEvent('receivedAt', event.target.value)}
                    />
                  </Field>
                  <Field label="사유코드" required error={errors['statusEvent.reasonCode']}>
                    <select
                      name="statusEvent.reasonCode"
                      className="select-control"
                      value={statusEvent.reasonCode}
                      onChange={(event) => changeEvent('reasonCode', event.target.value)}
                    >
                      <option value="">사유코드를 선택하세요</option>
                      {reasonCodes.map((code) => (
                        <option key={code} value={code}>
                          {statusReasonLabels[code]}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field
                    className="contract-status-event-reason"
                    label="사유"
                    required
                    error={errors['statusEvent.reason']}
                    helper="1~2,000자"
                  >
                    <textarea
                      name="statusEvent.reason"
                      className="textarea-control"
                      maxLength={2000}
                      rows={3}
                      value={statusEvent.reason}
                      onChange={(event) => changeEvent('reason', event.target.value)}
                    />
                  </Field>
                </div>
              </fieldset>
            )}
          </section>
          <section
            className="surface contract-form-card contract-agent-card contract-editor-agent"
            aria-labelledby="contract-agent-heading"
          >
            <header className="surface-header contract-form-card-header">
              <div>
                <h2 id="contract-agent-heading" className="surface-title">
                  모집 설계사 · 소속
                </h2>
                <p>설계사를 선택하면 계약일 기준 소속 조직을 자동으로 채웁니다.</p>
              </div>
            </header>
            <div className="contract-form-fields contract-agent-fields">
              <div>
                <Field
                  label="모집 설계사"
                  required
                  error={
                    errors.agentId ??
                    (agentUnavailable
                      ? '바꾼 계약일에 이 설계사가 활동 중인 FC가 아닙니다. 설계사는 바꿀 수 없으니 계약일을 확인해 주세요.'
                      : undefined)
                  }
                  helper={id ? '최초 연결 설계사는 바꿀 수 없습니다.' : undefined}
                  labelAccessory={
                    <EvidenceLink label="모집 설계사 근거" icon="help">
                      REG-07 · 제4-32조제12항제1·2호 [현행규정]. 유지관리수수료·장기유지수수료의 지급 대상은 최초
                      모집자로 고정되지 않고 계약을 유지관리하는 모집종사자입니다. 이 화면이 저장하는 값은 계약에 처음
                      연결한 모집 설계사이며, 2차 범위에서는 이 설계사를 고정하고 유지관리 담당 변경·승계는 지원하지
                      않습니다.
                    </EvidenceLink>
                  }
                >
                  {original ? (
                    <input name="agentName" value={original.agentName ?? ''} readOnly />
                  ) : (
                    <SearchSelect
                      name="agentId"
                      value={draft.agentId}
                      source={agents.source}
                      selected={selectedAgent}
                      placeholder="설계사 이름·코드 검색"
                      disabled={!draft.contractDate}
                      onSelect={(value, row) => {
                        agents.remember(row)
                        change('agentId', value, row)
                      }}
                    />
                  )}
                </Field>
              </div>
              <Field
                label="소속 조직"
                required
                error={errors.organizationId}
                helper="직접 수정하지 않습니다. 계약일에 유효한 설계사 기준정보의 조직 ID를 계약에 함께 저장합니다."
              >
                <input
                  name="organizationName"
                  value={form.organizationName}
                  readOnly
                  placeholder="설계사를 선택하면 자동으로 채워집니다"
                />
              </Field>
            </div>
          </section>
          <section
            className="surface contract-form-card contract-premium-card contract-editor-premium"
            aria-labelledby="contract-premium-heading"
          >
            <header className="surface-header contract-form-card-header">
              <div>
                <h2 id="contract-premium-heading" className="surface-title">
                  보험료 · 납입 조건
                </h2>
                <p>원주기 보험료와 월납환산 초회보험료를 구분해 입력합니다.</p>
              </div>
            </header>
            <div className="contract-form-fields contract-premium-fields">
              <Field label="납입주기" required error={errors.paymentCycleCode}>
                <select
                  name="paymentCycleCode"
                  className="select-control"
                  value={draft.paymentCycleCode}
                  onChange={(event) => change('paymentCycleCode', event.target.value)}
                >
                  {Object.entries(paymentCycleLabels)
                    .filter(([value]) => value !== 'OTHER' || original?.paymentCycleCode === 'OTHER')
                    .map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                </select>
              </Field>
              {amountInput(
                'premiumPerCycleAmount',
                '주기 보험료(원)',
                draft.paymentCycleCode === 'SINGLE'
                  ? '일시납은 한 번 낼 실제 보험료를 입력합니다.'
                  : '비워 두면 월납환산 초회보험료 × 납입주기로 채웁니다. 실제 청구액이 다르면 직접 고치세요.',
              )}
              {amountInput('firstPremiumAmount', '초회 보험료(원)')}
              {amountInput(
                'monthlyEquivalentFirstPremium',
                '월납환산 초회보험료(원)',
                '서버의 1,200% 한도 계산 기준값입니다.',
              )}
              {amountInput(
                'paymentTermMonths',
                '납입기간(개월)',
                hasProductTerm ? '선택한 상품의 해약환급률표 기준 납입기간입니다.' : undefined,
              )}
              <div>
                {amountInput(
                  'standardSurrenderDeductionAmount',
                  '표준해약공제액(원)',
                  '비워 두면 한도 가산에 사용하지 않습니다.',
                  true,
                )}
              </div>
            </div>
            {missingRefundTable && (
              <p className="contract-editor-warning" role="status">
                선택한 상품버전에 적용할 해약환급률표가 없습니다. 저장 후 월 통합검증에서 검토필요로 처리됩니다.
              </p>
            )}
            <aside className="contract-limit-preview" aria-label="1,200% 한도 참고 미리보기">
              <p>GA→설계사 초년도 1,200% 한도 참고값</p>
              <div className="contract-limit-value-row">
                <strong>
                  {draft.monthlyEquivalentFirstPremium === '' ||
                  !Number.isSafeInteger(Number(draft.monthlyEquivalentFirstPremium) * 12)
                    ? '—'
                    : int(Number(draft.monthlyEquivalentFirstPremium) * 12)}
                </strong>
                <span>원</span>
              </div>
              <p>월납환산 초회보험료 × 12</p>
              <p>
                참고용 기본 계산식입니다. 실제 한도는 저장 후 적용 룰셋·해약환급금 합산 등을 반영해 서버가 다시
                계산합니다.
              </p>
            </aside>
          </section>
        </fieldset>
        <div className="surface contract-form-actions">
          <p id="contract-save-hint" className="contract-save-hint" role="status">
            {saving
              ? id
                ? '계약 수정을 저장하고 있습니다.'
                : '계약과 스케줄을 저장하고 있습니다.'
              : loadingReferences
                ? '기준정보를 불러오는 중입니다.'
                : conflict
                  ? '최신 계약을 다시 불러온 뒤 수정해 주세요.'
                  : missingToken
                    ? '수정 시각을 확인할 수 없습니다. 최신 계약을 다시 불러와 주세요.'
                    : valid && !referenceError
                      ? id
                        ? `저장할 준비가 되었습니다. 변경 요약: ${changeSummary}`
                        : '저장할 준비가 되었습니다.'
                      : '필수 항목(*)과 자동 입력되는 소속 조직을 확인해 주세요.'}
          </p>
          <div className="contract-action-buttons">
            {(conflict || missingToken) && (
              <Button variant="secondary" onClick={() => setConfirmReload(true)}>
                최신 계약 다시 불러오기
              </Button>
            )}
            <Link className="button button-secondary" to={returnTo}>
              취소
            </Link>
            <Button
              className="contract-save-button"
              type="submit"
              loading={saving}
              disabled={disabled}
              aria-describedby="contract-save-hint"
            >
              {!saving && (
                <span className="material-symbols-rounded" aria-hidden="true">
                  save
                </span>
              )}
              {id ? '수정 저장' : '저장하고 예상 스케줄 만들기'}
            </Button>
          </div>
        </div>
      </form>
      <Modal
        open={confirmSave}
        title="상태 변경 저장 확인"
        onClose={() => {
          if (!saving) setConfirmSave(false)
        }}
        closeOnEscape={!saving}
        footer={
          <>
            <Button variant="secondary" disabled={saving} onClick={() => setConfirmSave(false)}>
              다시 확인
            </Button>
            <Button loading={saving} onClick={() => void save()}>
              확인하고 저장
            </Button>
          </>
        }
      >
        <dl className="contract-status-confirm">
          <div>
            <dt>상태</dt>
            <dd>
              {responseLabel(contractStatusLabels, original?.contractStatus, original?.contractStatusLabel)} →{' '}
              {responseLabel(contractStatusLabels, draft.contractStatus)}
            </dd>
          </div>
          <div>
            <dt>효력일시</dt>
            <dd>{statusEvent.effectiveAt.replace('T', ' ')}</dd>
          </div>
          <div>
            <dt>접수일시</dt>
            <dd>{statusEvent.receivedAt.replace('T', ' ')}</dd>
          </div>
          <div>
            <dt>사유코드</dt>
            <dd>{responseLabel(statusReasonLabels, statusEvent.reasonCode)}</dd>
          </div>
          <div>
            <dt>사유</dt>
            <dd>{statusEvent.reason.trim()}</dd>
          </div>
          <div>
            <dt>변경 요약</dt>
            <dd>{changeSummary}</dd>
          </div>
        </dl>
        <p>
          저장 성공은 계약·상태 사건 기록까지입니다. 후속 처리는 저장 뒤 따로 진행되며 상세의 상태이력에서 확인합니다.
        </p>
      </Modal>
      <Modal
        open={confirmReload}
        title="최신 계약 다시 불러오기"
        onClose={() => {
          if (!reloading) setConfirmReload(false)
        }}
        closeOnEscape={!reloading}
        footer={
          <>
            <Button variant="secondary" disabled={reloading} onClick={() => setConfirmReload(false)}>
              입력 유지
            </Button>
            <Button loading={reloading} onClick={() => void reload()}>
              최신 정보로 바꾸기
            </Button>
          </>
        }
      >
        <p>현재 입력 내용을 최신 계약 정보로 바꿉니다. 입력한 변경 내용을 확인한 뒤 다시 불러오세요.</p>
      </Modal>
    </>
  )
}
