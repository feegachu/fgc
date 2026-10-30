import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useUnsavedWork } from '../../stores/unsavedWork'
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
import { SearchSelect } from '../../components/SearchSelect'
import { contractDetailQuery, createContract, updateContract } from './api'
import type { ContractDetail } from './api'
import { contractStatusLabels, paymentCycleLabels } from './labels'
import { contractErrorText } from './detailState'
import { createRequest, fillCyclePremium, initialDraft, validateDraft } from './form'
import type { ContractDraft, ContractField, ContractFieldErrors } from './form'
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
        <Link data-guide-feature="features.contract.contract-form-page.link" className="button button-secondary" to={returnTo}>
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
          <Button data-guide-feature="features.contract.contract-form-page.button" variant="secondary" onClick={() => void detail.refetch()}>
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
  const submitting = useRef(false)
  const [savedSuccessfully, setSavedSuccessfully] = useState(false)
  const [initialInput] = useState(() => JSON.stringify(initialDraft(currentDate, contract)))
  const clearUnsaved = useUnsavedWork(!savedSuccessfully && JSON.stringify(draft) !== initialInput)
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
  const product = products.data?.find((row) => String(row.productOfferingId) === draft.productOfferingId)
  const referenceError = insurers.error ?? products.error ?? agents.error
  const loadingReferences = insurers.isFetching || products.isFetching || agents.isFetching
  const missingToken = Boolean(id && !original?.updatedAt)
  const valid = Object.keys(validateDraft(draft, currentDate)).length === 0
  const disabled = !valid || saving || loadingReferences || Boolean(referenceError) || conflict || missingToken
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
      if (field === 'contractDate') {
        next.agentId = ''
        next.organizationId = ''
        next.organizationName = ''
      }
      if (field === 'agentId') {
        const agent = (selection as Agent | undefined) ?? availableAgents.find((row) => String(row.agentId) === value)
        const historical = value === String(original?.agentId) && current.contractDate === original?.contractDate
        next.organizationId = String((historical ? original?.organizationId : agent?.organizationId) ?? '')
        next.organizationName = (historical ? original?.organizationName : agent?.organizationName) ?? ''
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

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current || conflict || missingToken) return
    const validation = validateDraft(draft, currentDate)
    setErrors(validation)
    setSaveError('')
    if (Object.keys(validation).length || disabled) return
    submitting.current = true
    setSaving(true)
    try {
      const body = createRequest(draft)
      const saved = id
        ? await updateContract(id, { ...body, expectedUpdatedAt: original!.updatedAt! })
        : await createContract(body)
      const savedId = saved.contractId ?? id
      if (!savedId) throw new Error('저장 결과의 계약 번호를 확인하지 못했습니다. 목록에서 저장 여부를 확인해 주세요.')
      setSavedSuccessfully(true)
      clearUnsaved()
      // Invalidate inactive projections too: an update can regenerate schedules and financial snapshots.
      await queryClient.invalidateQueries({ queryKey: ['contracts'], refetchType: 'none' })
      await queryClient.invalidateQueries({ queryKey: ['schedules'], refetchType: 'none' })
      await queryClient.invalidateQueries({ queryKey: ['cap-checks'], refetchType: 'none' })
      // The save still updates caches after leaving, but must not navigate away from the user's new screen.
      if (!mounted.current) return
      const count = saved.scheduleHeaderIds?.length ?? 0
      const action = id ? '수정' : '저장'
      const message = `보험계약을 ${action}했습니다.${count ? ` 예상 스케줄 ${count}건을 함께 ${id ? '재생성' : '생성'}했습니다 — [예상 스케줄] 탭에서 확인하세요.` : ''}`
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
        if (error.code === 'FGC-CONT-003') setConflict(true)
        if (error.field && error.field in draft && error.code !== 'FGC-COMMON-500')
          setErrors({ [error.field]: error.message })
      }
      setSaveError(message)
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
      <input data-guide-feature="features.contract.contract-form-page.input"
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
          <Button data-guide-feature="features.contract.contract-form-page.button-2"
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
      <form data-guide-feature="features.contract.contract-form-page.form"
        className="contract-editor"
        onSubmit={(event) => void submit(event)}
        noValidate
        aria-label={`보험계약 ${id ? '수정' : '등록'} 입력`}
      >
        <fieldset className="contract-editor-fields contract-form-layout" disabled={saving || reloading}>
          <section data-guide-feature="features.contract.contract-form-page.section"
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
                <select data-guide-feature="features.contract.contract-form-page.insurer-id"
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
                  <SearchSelect data-guide-feature="features.contract.contract-form-page.product-offering-id"
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
                <input data-guide-feature="features.contract.contract-form-page.contract-no"
                  name="contractNo"
                  placeholder="계약번호 입력"
                  maxLength={80}
                  autoComplete="off"
                  value={draft.contractNo}
                  onChange={(event) => change('contractNo', event.target.value)}
                />
              </Field>
              <Field label="계약일" required error={errors.contractDate} helper="미래 날짜는 선택할 수 없습니다.">
                <input data-guide-feature="features.contract.contract-form-page.contract-date"
                  name="contractDate"
                  type="date"
                  max={currentDate}
                  value={draft.contractDate}
                  onChange={(event) => change('contractDate', event.target.value)}
                />
              </Field>
              <Field label="계약상태" required error={errors.contractStatus}>
                <select data-guide-feature="features.contract.contract-form-page.contract-status"
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
            </div>
          </section>
          <section data-guide-feature="features.contract.contract-form-page.section-2"
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
                  error={errors.agentId}
                  labelAccessory={
                    <EvidenceLink label="모집 설계사 근거" icon="help">
                      REG-07 · 제4-32조제12항제1·2호 [현행규정]. 여기서 저장하는 값은 모집 설계사입니다. 지급 대상은
                      최초 모집자로 고정되지 않고 계약을 유지관리하는 모집종사자이므로, 유지관리 담당 설계사는 별도로
                      관리합니다.
                    </EvidenceLink>
                  }
                >
                  <SearchSelect data-guide-feature="features.contract.contract-form-page.agent-id"
                    name="agentId"
                    value={draft.agentId}
                    source={agents.source}
                    selected={
                      availableAgents.find((row) => String(row.agentId) === draft.agentId) ??
                      (draft.agentId === String(original?.agentId) ? original : undefined)
                    }
                    placeholder="설계사 이름·코드 검색"
                    disabled={!draft.contractDate}
                    onSelect={(value, row) => {
                      agents.remember(row)
                      change('agentId', value, row)
                    }}
                  />
                </Field>
              </div>
              <Field
                label="소속 조직"
                required
                error={errors.organizationId}
                helper="직접 수정하지 않습니다. 계약일에 유효한 설계사 기준정보의 조직 ID를 계약에 함께 저장합니다."
              >
                <input data-guide-feature="features.contract.contract-form-page.organization-name"
                  name="organizationName"
                  value={draft.organizationName}
                  readOnly
                  placeholder="설계사를 선택하면 자동으로 채워집니다"
                />
              </Field>
            </div>
          </section>
          <section data-guide-feature="features.contract.contract-form-page.section-3"
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
                <select data-guide-feature="features.contract.contract-form-page.payment-cycle-code"
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
              ? '계약과 스케줄을 저장하고 있습니다.'
              : loadingReferences
                ? '기준정보를 불러오는 중입니다.'
                : conflict
                  ? '최신 계약을 다시 불러온 뒤 수정해 주세요.'
                  : missingToken
                    ? '수정 시각을 확인할 수 없습니다. 최신 계약을 다시 불러와 주세요.'
                    : valid && !referenceError
                      ? '저장할 준비가 되었습니다.'
                      : '필수 항목(*)과 자동 입력되는 소속 조직을 확인해 주세요.'}
          </p>
          <div className="contract-action-buttons">
            {(conflict || missingToken) && (
              <Button data-guide-feature="features.contract.contract-form-page.button-3" variant="secondary" onClick={() => setConfirmReload(true)}>
                최신 계약 다시 불러오기
              </Button>
            )}
            <Link data-guide-feature="features.contract.contract-form-page.link-2" className="button button-secondary" to={returnTo}>
              취소
            </Link>
            <Button data-guide-feature="features.contract.contract-form-page.button-4"
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
              {id ? '수정하고 스케줄 재생성' : '저장하고 예상 스케줄 만들기'}
            </Button>
          </div>
        </div>
      </form>
      <Modal
        open={confirmReload}
        title="최신 계약 다시 불러오기"
        onClose={() => {
          if (!reloading) setConfirmReload(false)
        }}
        closeOnEscape={!reloading}
        footer={
          <>
            <Button data-guide-feature="features.contract.contract-form-page.button-5" variant="secondary" disabled={reloading} onClick={() => setConfirmReload(false)}>
              입력 유지
            </Button>
            <Button data-guide-feature="features.contract.contract-form-page.button-6" loading={reloading} onClick={() => void reload()}>
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
