import { useContext, useRef, useState } from 'react'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { ApiError } from '../../lib/api/client'
import { ClawbackTargets, CommissionRow, DraftInput, DraftSelect } from './PolicyDraftFields'
import {
  conditionOptions,
  emptyCommission,
  emptyDraft,
  emptyRegime,
  numberValue,
  optionsFor,
  policyTypes,
  PolicyPreviewContext,
  regimeFor,
  sources,
  sourceStatusTags,
} from './management'
import type { DraftDocument } from './management'
import { validateDraft } from './draftValidation'
import type { DraftErrors } from './draftValidation'
import { savePolicyDraft } from './writeApi'
import { usePolicyRequest } from './usePolicyRequest'

type Section = 'basic' | 'commissionRules' | 'feeRegimeRules' | 'parameters' | 'clawbackRules' | 'sourceDetails'
export function PolicyDraftEditor({
  initial,
  policyId,
  onClose,
  onSaved,
}: {
  initial?: DraftDocument
  policyId?: number
  onClose: () => void
  onSaved: (id: number) => void
}) {
  const preview = useContext(PolicyPreviewContext)
  const [draft, setDraft] = useState(() => structuredClone(initial ?? emptyDraft()))
  const [section, setSection] = useState<Section>('basic')
  const [errors, setErrors] = useState<DraftErrors>({})
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const [discard, setDiscard] = useState(false)
  const [dirty, setDirty] = useState(false)
  const saving = useRef(false)
  const requestKey = usePolicyRequest()
  const titleFocus = useRef<HTMLInputElement>(null)
  const set = <K extends keyof DraftDocument>(key: K, value: DraftDocument[K]) => {
    const next = { ...draft, [key]: value }
    if (key === 'sourceDetails') {
      const retained = new Set((value as DraftDocument['sourceDetails']).map((source) => source.ref))
      const removed = new Set(
        draft.sourceDetails.filter((source) => !retained.has(source.ref)).map((source) => source.ref),
      )
      next.regulationRefs = draft.regulationRefs.filter((ref) => !removed.has(ref))
      next.sourceRefs = draft.sourceRefs.filter((ref) => !removed.has(ref))
    }
    setDraft(next)
    setErrors((current) => {
      const remaining = validateDraft(next)
      return Object.fromEntries(Object.entries(current).filter(([path]) => remaining[path]))
    })
    setDirty(true)
  }
  const commission = draft.policyType.endsWith('_COMMISSION')
  const installment = ['FOUR_YEAR_COMMISSION', 'SEVEN_YEAR_COMMISSION'].includes(draft.policyType)
  const sections: { id: Section; label: string; count?: number }[] = [
    { id: 'basic', label: '기본정보' },
    ...(commission
      ? [{ id: 'commissionRules' as const, label: '수수료 규칙', count: draft.commissionRules.length }]
      : []),
    ...(installment ? [{ id: 'feeRegimeRules' as const, label: '분급 룰셋', count: draft.feeRegimeRules.length }] : []),
    ...(draft.policyType === 'SCHEDULE_ELIGIBILITY'
      ? [{ id: 'parameters' as const, label: '상태별 지급조건', count: draft.parameters.length }]
      : []),
    ...(draft.policyType === 'CLAWBACK'
      ? [{ id: 'clawbackRules' as const, label: '환수 규칙', count: draft.clawbackRules.length }]
      : []),
    { id: 'sourceDetails', label: '근거 자료', count: draft.sourceDetails.length },
  ]
  const close = () => {
    if (saving.current) return
    if (dirty) setDiscard(true)
    else onClose()
  }
  function focusError(path: string) {
    const group = ['regulationRefs', 'sourceRefs'].includes(path.split('.')[0])
      ? 'sourceDetails'
      : (path.split('.')[0] as Section)
    setSection(sections.some((item) => item.id === group) ? group : 'basic')
    const parameter = /^parameters\.(\d+)\./.exec(path)
    const focusPath = parameter ? `parameters.${draft.parameters[Number(parameter[1])]?.key}` : path
    requestAnimationFrame(() => document.getElementById(`policy-${focusPath}`)?.focus())
  }
  async function save() {
    if (saving.current) return
    const nextErrors = validateDraft(draft)
    setErrors(nextErrors)
    const first = Object.keys(nextErrors)[0]
    if (first) {
      focusError(first)
      return
    }
    saving.current = true
    setBusy(true)
    setFailure('')
    try {
      const document = {
        ...draft,
        feeRegimeRules: draft.feeRegimeRules.map((rule) => ({
          ...rule,
          sourceRefs: [...new Set(rule.sourceRefs.map((ref) => ref.trim()).filter(Boolean))],
        })),
        regulationRefs: [
          ...new Set([
            ...draft.regulationRefs,
            ...draft.sourceDetails.map((s) => s.ref.trim()).filter((ref) => ref.startsWith('REG-')),
          ]),
        ],
        sourceRefs: [
          ...new Set([
            ...draft.sourceRefs,
            ...draft.sourceDetails.map((s) => s.ref.trim()).filter((ref) => ref.startsWith('SRC-')),
          ]),
        ],
      }
      const id = preview
        ? await preview.save(document, policyId)
        : (await savePolicyDraft(document, policyId, requestKey({ policyId, document }))).policyVersionId
      onSaved(id)
    } catch (cause) {
      const message =
        cause instanceof Error ? cause.message : '저장하지 못했습니다. 입력 내용을 확인하고 다시 시도하세요.'
      setFailure(message)
      if (cause instanceof ApiError && cause.field) {
        const path = cause.field.replace(/\[(\d+)\]/g, '.$1')
        setErrors({ [path]: message })
        focusError(path)
      }
    } finally {
      saving.current = false
      setBusy(false)
    }
  }
  const field = (
    key: 'policyCode' | 'policyName' | 'effectiveFrom' | 'effectiveTo' | 'basicDocumentVersion',
    label: string,
    required = false,
    type = 'text',
    maxLength?: number,
  ) => (
    <DraftInput
      path={key}
      label={label}
      required={required}
      type={type}
      maxLength={maxLength}
      value={draft[key]}
      errors={errors}
      onChange={(v) => set(key, v)}
      disabled={key === 'policyCode' && policyId !== undefined}
    />
  )
  return (
    <Modal
      open
      title={policyId === undefined ? '정책 등록' : '정책 초안 수정'}
      onClose={close}
      closeOnEscape={!busy}
      className="policy-editor-modal"
      initialFocusRef={titleFocus}
      footer={
        <>
          <span className="policy-editor-footer-note">
            {preview
              ? '미리보기 · 변경 내용은 이 화면에서만 유지됩니다.'
              : '초안으로 저장한 후 검토를 요청할 수 있습니다.'}
          </span>
          <Button variant="secondary" disabled={busy} onClick={close}>
            취소
          </Button>
          <Button loading={busy} disabled={discard} onClick={() => void save()}>
            초안 저장
          </Button>
        </>
      }
    >
      <div className="policy-editor-intro">
        <StatusBadge tone="warning">DRAFT · 초안</StatusBadge>
        <span>기본정보와 유형별 규칙을 작성한 후 검토를 요청하세요.</span>
        <span className="policy-required-note">* 필수 입력</span>
      </div>
      {discard && (
        <div className="policy-notice policy-notice-warning" role="alert">
          <span>저장하지 않은 내용이 있습니다. 편집을 취소할까요?</span>
          <div className="policy-actions">
            <Button variant="secondary" onClick={() => setDiscard(false)}>
              계속 편집
            </Button>
            <Button onClick={onClose}>편집 취소</Button>
          </div>
        </div>
      )}
      {failure && (
        <p role="alert" className="policy-notice policy-notice-warning">
          {failure}
        </p>
      )}
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
        className="policy-editor-layout"
      >
        <nav className="policy-editor-nav" aria-label="정책 작성 단계">
          {sections.map((item, index) => (
            <button
              key={item.id}
              type="button"
              aria-current={section === item.id ? 'step' : undefined}
              onClick={() => setSection(item.id)}
            >
              <span className="policy-step-index">{String(index + 1).padStart(2, '0')}</span>
              <span>{item.label}</span>
              {item.count !== undefined && <span className="policy-section-count">{item.count}</span>}
              {Object.keys(errors).some((key) =>
                item.id === 'basic' ? !key.includes('.') : key.startsWith(item.id),
              ) && (
                <span className="policy-field-error-dot" aria-label="입력 오류">
                  !
                </span>
              )}
            </button>
          ))}
        </nav>
        <fieldset disabled={busy} className="policy-editor-content">
          <div className="policy-section-heading">
            <div>
              <p className="policy-eyebrow">POLICY CONFIGURATION</p>
              <h2>{sections.find((item) => item.id === section)?.label}</h2>
            </div>
          </div>
          {errors[section] && (
            <p role="alert" className="policy-notice policy-notice-warning">
              {errors[section]}
            </p>
          )}
          {section === 'basic' && (
            <>
              <p className="policy-section-description">
                정책의 적용 범위와 기간을 설정합니다. 정책코드는 생성 후 변경할 수 없습니다.
              </p>
              <div className="policy-form-grid policy-form-grid-two">
                {field('policyCode', '정책코드', true, 'text', 80)}
                <Field label="정책명" required error={errors.policyName}>
                  <input
                    ref={titleFocus}
                    id="policy-policyName"
                    maxLength={200}
                    value={draft.policyName}
                    onChange={(e) => set('policyName', e.target.value)}
                    placeholder="정책의 목적을 알 수 있는 이름"
                  />
                </Field>
                <DraftSelect
                  path="policyType"
                  label="정책 유형"
                  required
                  errors={errors}
                  value={draft.policyType}
                  options={optionsFor(policyTypes)}
                  disabled={
                    policyId !== undefined ||
                    draft.commissionRules.length +
                      draft.feeRegimeRules.length +
                      draft.clawbackRules.length +
                      draft.parameters.length >
                      0
                  }
                  helper="작성 중인 규칙이 있으면 유형을 변경할 수 없습니다."
                  onChange={(v) => {
                    setDraft((current) => ({ ...current, policyType: v, feeRegimeCode: regimeFor(v) }))
                    setDirty(true)
                    setErrors((current) =>
                      Object.fromEntries(Object.entries(current).filter(([path]) => path !== 'policyType')),
                    )
                  }}
                />
                <DraftSelect
                  path="sourceClass"
                  label="출처 분류"
                  required
                  errors={errors}
                  value={draft.sourceClass}
                  options={optionsFor(sources)}
                  onChange={(v) => set('sourceClass', v)}
                />
                {field('effectiveFrom', '적용 시작일', true, 'date')}
                {field('effectiveTo', '적용 종료일', false, 'date')}
                <Field label="수수료 체계" helper="정책 유형에 따라 자동으로 선택됩니다.">
                  <input
                    readOnly
                    value={
                      { CURRENT: '현행', FOUR_YEAR_2027: '4년 분급', SEVEN_YEAR_2029: '7년 분급' }[
                        draft.feeRegimeCode
                      ] ?? '해당 없음'
                    }
                  />
                </Field>
                {field('basicDocumentVersion', '기초서류 버전', false, 'text', 80)}
              </div>
              <div className="policy-notice">
                <span className="material-symbols-rounded" aria-hidden="true">
                  info
                </span>
                <span>종료일을 비워두면 계속 적용됩니다. 정책 버전·상태·작성자·승인 정보는 저장 시 결정됩니다.</span>
              </div>
            </>
          )}
          {section === 'commissionRules' && (
            <>
              <p className="policy-section-description">
                지급 대상과 회차별 계산 방식을 설정합니다. 요율은 100%를 초과해 입력할 수 있습니다.
                {installment && ' 상품 판매버전을 지정하면 해당 버전의 판매개시일·기초서류에 맞는 분급 룰셋을 적용합니다.'}
              </p>
              {draft.commissionRules.map((rule, index) => (
                <CommissionRow
                  key={index}
                  rule={rule}
                  index={index}
                  errors={errors}
                  asOf={draft.effectiveFrom}
                  onChange={(value) =>
                    set(
                      'commissionRules',
                      draft.commissionRules.map((r, i) => (i === index ? value : r)),
                    )
                  }
                  onRemove={() => {
                    set(
                      'commissionRules',
                      draft.commissionRules.filter((_, i) => i !== index),
                    )
                    setErrors({})
                  }}
                />
              ))}
              {!draft.commissionRules.length && (
                <EditorEmpty
                  text="등록된 수수료 규칙이 없습니다."
                  description="지급단계, 상품 판매버전, 회차와 계산 방식을 추가하세요."
                />
              )}
              <Button
                variant="secondary"
                onClick={() => set('commissionRules', [...draft.commissionRules, emptyCommission()])}
              >
                + 수수료 규칙 추가
              </Button>
            </>
          )}
          {section === 'feeRegimeRules' && (
            <>
              <div className="policy-notice">
                {draft.policyType === 'FOUR_YEAR_COMMISSION'
                  ? '4년 분급은 최대 48회차이며 장기유지 구간을 설정하지 않습니다.'
                  : '7년 분급은 최대 84회차입니다. 장기유지 구간은 확정된 근거에 따라 직접 입력하세요.'}
              </div>
              <p className="policy-section-description">
                최대 회차는 체계의 상한입니다. 실제 지급 회차·월 요율·월 동일 금액은 수수료 규칙에서 설정합니다.
                정책 설정과 승인은 예정 스케줄의 지급 확정을 뜻하지 않습니다.
              </p>
              {draft.feeRegimeRules.map((rule, index) => {
                const prefix = `feeRegimeRules.${index}.`
                const update = (patch: Partial<typeof rule>) =>
                  set(
                    'feeRegimeRules',
                    draft.feeRegimeRules.map((r, i) => (i === index ? { ...r, ...patch } : r)),
                  )
                const numeric = (
                  key: 'maxInstallments' | 'longTermMaintenanceFrom' | 'longTermMaintenanceTo',
                  label: string,
                  required = false,
                ) => (
                  <DraftInput
                    path={prefix + key}
                    label={label}
                    required={required}
                    type="number"
                    min="1"
                    max={rule.feeRegimeCode === 'FOUR_YEAR_2027' ? 48 : 84}
                    disabled={key !== 'maxInstallments' && rule.feeRegimeCode === 'FOUR_YEAR_2027'}
                    errors={errors}
                    value={rule[key]}
                    onChange={(v) => update({ [key]: numberValue(v) })}
                  />
                )
                return (
                  <section className="policy-rule-editor" key={index}>
                    <header>
                      <h3>
                        <span className="policy-row-number">{String(index + 1).padStart(2, '0')}</span>분급 룰셋
                      </h3>
                      <Button
                        variant="ghost"
                        onClick={() => {
                          set(
                            'feeRegimeRules',
                            draft.feeRegimeRules.filter((_, i) => i !== index),
                          )
                          setErrors({})
                        }}
                      >
                        행 삭제
                      </Button>
                    </header>
                    <div className="policy-form-grid">
                      <Field label="체계" required>
                        <input readOnly value={rule.feeRegimeCode === 'FOUR_YEAR_2027' ? '4년 분급' : '7년 분급'} />
                      </Field>
                      <DraftInput
                        path={prefix + 'salesStartDateFrom'}
                        label="판매 시작일 범위 · 시작"
                        required
                        type="date"
                        errors={errors}
                        value={rule.salesStartDateFrom}
                        onChange={(v) => update({ salesStartDateFrom: v })}
                      />
                      <DraftInput
                        path={prefix + 'salesStartDateTo'}
                        label="판매 시작일 범위 · 종료"
                        type="date"
                        errors={errors}
                        value={rule.salesStartDateTo}
                        onChange={(v) => update({ salesStartDateTo: v })}
                      />
                      <DraftInput
                        path={prefix + 'basicDocumentVersion'}
                        label="기초서류 버전"
                        maxLength={80}
                        errors={errors}
                        value={rule.basicDocumentVersion}
                        onChange={(v) => update({ basicDocumentVersion: v })}
                      />
                      <DraftInput
                        path={prefix + 'basicDocumentDateFrom'}
                        label="기초서류 일자 · 시작"
                        type="date"
                        errors={errors}
                        value={rule.basicDocumentDateFrom}
                        onChange={(v) => update({ basicDocumentDateFrom: v })}
                      />
                      <DraftInput
                        path={prefix + 'basicDocumentDateTo'}
                        label="기초서류 일자 · 종료"
                        type="date"
                        errors={errors}
                        value={rule.basicDocumentDateTo}
                        onChange={(v) => update({ basicDocumentDateTo: v })}
                      />
                      {numeric('maxInstallments', '최대 회차', true)}
                      {numeric('longTermMaintenanceFrom', '장기유지 시작 회차')}
                      {numeric('longTermMaintenanceTo', '장기유지 끝 회차')}
                      <DraftInput
                        path={prefix + 'sourceRefs'}
                        label="근거 코드"
                        errors={errors}
                        value={rule.sourceRefs.join(',')}
                        placeholder="REG/SRC 코드를 쉼표로 구분"
                        onChange={(v) => update({ sourceRefs: v.split(',') })}
                      />
                      <label className="policy-checkbox">
                        <input
                          type="checkbox"
                          checked={rule.reviewRequiredYn}
                          onChange={(e) => update({ reviewRequiredYn: e.target.checked })}
                        />
                        검토 필요
                      </label>
                    </div>
                    {rule.feeRegimeCode === 'FOUR_YEAR_2027' &&
                      (rule.longTermMaintenanceFrom !== '' || rule.longTermMaintenanceTo !== '') && (
                        <div className="policy-notice policy-notice-warning">
                          <span>4년 분급에 남아 있는 장기유지 값을 초기화한 후 저장하세요.</span>
                          <Button
                            variant="secondary"
                            id={`policy-${prefix}longTermMaintenance`}
                            onClick={() => update({ longTermMaintenanceFrom: '', longTermMaintenanceTo: '' })}
                          >
                            장기유지 값 초기화
                          </Button>
                        </div>
                      )}
                  </section>
                )
              })}
              {!draft.feeRegimeRules.length && (
                <EditorEmpty
                  text="등록된 분급 룰셋이 없습니다."
                  description="판매 시작일 범위와 최대 회차를 설정하세요."
                />
              )}
              <Button
                variant="secondary"
                onClick={() => set('feeRegimeRules', [...draft.feeRegimeRules, emptyRegime(draft.policyType)])}
              >
                + 분급 룰셋 추가
              </Button>
            </>
          )}
          {section === 'parameters' && (
            <>
              <p className="policy-section-description">
                기존에 지원하는 지급조건을 선택합니다. 각 조건의 적용 근거는 근거 자료에서 연결하세요.
              </p>
              {conditionOptions.map((option) => (
                <section className="policy-condition-card" key={option.key}>
                  <header>
                    <h3>{option.label}</h3>
                    <StatusBadge tone="info">지원 조건</StatusBadge>
                  </header>
                  <p>{option.description}</p>
                  <code>{option.key}</code>
                  <DraftSelect
                    path={`parameters.${option.key}`}
                    label={option.label + ' 설정'}
                    errors={errors}
                    value={String(draft.parameters.find((p) => p.key === option.key)?.value ?? '')}
                    onChange={(v) =>
                      set('parameters', [
                        ...draft.parameters.filter((p) => p.key !== option.key),
                        ...(v ? [{ key: option.key, value: v, description: option.description }] : []),
                      ])
                    }
                    options={[{ value: option.value, label: option.valueLabel }]}
                  />
                </section>
              ))}
            </>
          )}
          {section === 'clawbackRules' && (
            <>
              <div className="policy-notice policy-notice-warning">
                환수 대상금액과 경과월 산정 기준(D-02)이 미확정입니다. 기준이 확정될 때까지 환수 규칙을 등록할 수
                없습니다.
              </div>
              {draft.clawbackRules.map((rule, index) => {
                const prefix = `clawbackRules.${index}.`
                const update = (patch: Partial<typeof rule>) =>
                  set(
                    'clawbackRules',
                    draft.clawbackRules.map((r, i) => (i === index ? { ...r, ...patch } : r)),
                  )
                return (
                  <section className="policy-rule-editor" key={index}>
                    <header>
                      <h3>환수 규칙 {index + 1}</h3>
                      <Button
                        variant="ghost"
                        onClick={() => {
                          set(
                            'clawbackRules',
                            draft.clawbackRules.filter((_, i) => i !== index),
                          )
                          setErrors({})
                        }}
                      >
                        행 삭제
                      </Button>
                    </header>
                    <div className="policy-form-grid">
                      <ClawbackTargets
                        rule={rule}
                        prefix={prefix}
                        errors={errors}
                        asOf={draft.effectiveFrom}
                        onChange={update}
                      />
                      <DraftInput
                        path={prefix + 'elapsedMonthFrom'}
                        label="경과월 시작"
                        required
                        type="number"
                        min="0"
                        errors={errors}
                        value={rule.elapsedMonthFrom}
                        onChange={(v) => update({ elapsedMonthFrom: numberValue(v) })}
                      />
                      <DraftInput
                        path={prefix + 'elapsedMonthTo'}
                        label="경과월 끝"
                        required
                        type="number"
                        min="0"
                        errors={errors}
                        value={rule.elapsedMonthTo}
                        onChange={(v) => update({ elapsedMonthTo: numberValue(v) })}
                      />
                      <Field label="기준금액 코드" helper="업무 기준 확정 후 설정할 수 있습니다.">
                        <input readOnly value={rule.clawbackBasisCode || '설정 필요'} />
                      </Field>
                      <DraftInput
                        path={prefix + 'clawbackRatePct'}
                        label="환수율 (%)"
                        required
                        type="number"
                        min="0"
                        max="100"
                        step="0.000001"
                        errors={errors}
                        value={rule.clawbackRatePct}
                        onChange={(v) => update({ clawbackRatePct: numberValue(v) })}
                      />
                    </div>
                  </section>
                )
              })}
              {!draft.clawbackRules.length && (
                <EditorEmpty text="등록된 환수 규칙이 없습니다." description="업무 기준 확정 후 등록할 수 있습니다." />
              )}
              <Button
                variant="secondary"
                disabled
                onClick={() =>
                  set('clawbackRules', [
                    ...draft.clawbackRules,
                    {
                      insurerId: '',
                      productOfferingId: '',
                      commissionItemId: '',
                      elapsedMonthFrom: '',
                      elapsedMonthTo: '',
                      clawbackBasisCode: '',
                      clawbackRatePct: '',
                    },
                  ])
                }
              >
                + 환수 규칙 추가
              </Button>
            </>
          )}
          {section === 'sourceDetails' && (
            <>
              <p className="policy-section-description">
                정책에 사용한 REG/SRC 근거의 제목과 설명, 원문 링크를 연결하세요.
              </p>
              <div className="policy-form-grid policy-form-grid-two">
                <DraftInput
                  path="regulationRefs"
                  label="규정 근거 코드"
                  errors={errors}
                  value={draft.regulationRefs.join(',')}
                  placeholder="REG 코드를 쉼표로 구분"
                  onChange={(value) => set('regulationRefs', value.split(','))}
                />
                <DraftInput
                  path="sourceRefs"
                  label="출처 근거 코드"
                  errors={errors}
                  value={draft.sourceRefs.join(',')}
                  placeholder="SRC 코드를 쉼표로 구분"
                  onChange={(value) => set('sourceRefs', value.split(','))}
                />
              </div>
              {draft.sourceDetails.map((source, index) => {
                const prefix = `sourceDetails.${index}.`
                const update = (patch: Partial<typeof source>) =>
                  set(
                    'sourceDetails',
                    draft.sourceDetails.map((s, i) => (i === index ? { ...s, ...patch } : s)),
                  )
                return (
                  <section className="policy-rule-editor" key={index}>
                    <header>
                      <h3>근거 자료 {index + 1}</h3>
                      <Button
                        variant="ghost"
                        onClick={() => {
                          set(
                            'sourceDetails',
                            draft.sourceDetails.filter((_, i) => i !== index),
                          )
                          setErrors({})
                        }}
                      >
                        행 삭제
                      </Button>
                    </header>
                    <div className="policy-form-grid policy-form-grid-two">
                      <DraftInput
                        path={prefix + 'ref'}
                        label="근거 코드"
                        required
                        errors={errors}
                        value={source.ref}
                        onChange={(v) => update({ ref: v })}
                        placeholder="REG- 또는 SRC-"
                      />
                      <DraftInput
                        path={prefix + 'title'}
                        label="제목"
                        required
                        maxLength={200}
                        errors={errors}
                        value={source.title}
                        onChange={(v) => update({ title: v })}
                      />
                      <Field label="설명" className="policy-span-all">
                        <textarea
                          rows={3}
                          maxLength={2000}
                          value={source.explanation}
                          onChange={(e) => update({ explanation: e.target.value })}
                        />
                      </Field>
                      <fieldset disabled={!preview} className="policy-span-all policy-source-status-options">
                        <legend>근거 상태 (복수 선택)</legend>
                        {!preview && <p className="policy-note">근거 상태 분류는 아직 저장할 수 없습니다.</p>}
                        {[...new Set([...sourceStatusTags, ...(source.statusTags ?? [])])].map((tag) => (
                          <label key={tag} className="policy-checkbox">
                            <input
                              type="checkbox"
                              checked={source.statusTags?.includes(tag) ?? false}
                              onChange={(e) =>
                                update({
                                  statusTags: e.target.checked
                                    ? [...(source.statusTags ?? []), tag]
                                    : source.statusTags?.filter((value) => value !== tag),
                                })
                              }
                            />
                            {tag}
                          </label>
                        ))}
                      </fieldset>
                      <div className="policy-span-all">
                        <DraftInput
                          path={prefix + 'url'}
                          label="원문 링크"
                          type="url"
                          maxLength={500}
                          errors={errors}
                          value={source.url ?? ''}
                          onChange={(v) => update({ url: v })}
                          placeholder="https://"
                        />
                      </div>
                    </div>
                  </section>
                )
              })}
              {!draft.sourceDetails.length && (
                <EditorEmpty
                  text="연결된 근거 자료가 없습니다."
                  description="제출 전에 정책을 뒷받침하는 근거를 추가하세요."
                />
              )}
              <Button
                variant="secondary"
                onClick={() =>
                  set('sourceDetails', [...draft.sourceDetails, { ref: '', title: '', explanation: '', url: null }])
                }
              >
                + 근거 자료 추가
              </Button>
            </>
          )}
        </fieldset>
      </form>
    </Modal>
  )
}
function EditorEmpty({ text, description }: { text: string; description: string }) {
  return (
    <div className="policy-editor-empty">
      <span className="material-symbols-rounded" aria-hidden="true">
        description
      </span>
      <strong>{text}</strong>
      <p>{description}</p>
    </div>
  )
}
