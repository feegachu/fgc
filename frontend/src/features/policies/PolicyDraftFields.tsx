import type { InputHTMLAttributes } from 'react'
import { Field } from '../../components/Field'
import { Button } from '../../components/Button'
import { QueryState } from '../reference/ReferenceUI'
import { useCommissionItems, useInsurerOptions, useOrganizationOptions, useProductOptions } from '../base/api'
import type { ClawbackDraft, CommissionDraft } from './management'
import { numberValue, optionsFor } from './management'
import type { DraftErrors } from './draftValidation'

export function DraftInput({
  path,
  label,
  errors,
  value,
  onChange,
  ...props
}: {
  path: string
  label: string
  errors: DraftErrors
  value: string | number
  onChange: (value: string) => void
} & Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value'>) {
  return (
    <Field label={label} required={props.required} error={errors[path]}>
      <input {...props} id={`policy-${path}`} value={value} onChange={(event) => onChange(event.target.value)} />
    </Field>
  )
}
export function DraftSelect({
  path,
  label,
  errors,
  value,
  onChange,
  options,
  required,
  disabled,
  placeholder = '선택하세요',
  helper,
}: {
  path: string
  label: string
  errors: DraftErrors
  value: string | number
  onChange: (value: string) => void
  options: { value: string | number; label: string; disabled?: boolean }[]
  required?: boolean
  disabled?: boolean
  placeholder?: string
  helper?: string
}) {
  return (
    <Field label={label} required={required} error={errors[path]} helper={helper}>
      <select
        id={`policy-${path}`}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
      >
        <option value="" disabled={required}>
          {placeholder}
        </option>
        {value !== '' && !options.some((option) => String(option.value) === String(value)) && (
          <option value={value}>기존 값 · {value}</option>
        )}
        {options.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
    </Field>
  )
}

export function ClawbackTargets({
  rule,
  prefix,
  errors,
  asOf,
  onChange,
}: {
  rule: ClawbackDraft
  prefix: string
  errors: DraftErrors
  asOf: string
  onChange: (patch: Partial<ClawbackDraft>) => void
}) {
  const insurers = useInsurerOptions()
  const products = useProductOptions(asOf, String(rule.insurerId))
  const items = useCommissionItems(asOf)
  return (
    <>
      <div className="policy-span-all">
        <QueryState
          loading={insurers.isLoading || products.isFetching || items.isLoading}
          error={insurers.error || products.error || items.error}
        />
        {!asOf && (
          <p className="policy-note">적용 시작일을 입력하면 상품 판매버전과 수수료 항목을 선택할 수 있습니다.</p>
        )}
        {(insurers.error || products.error || items.error) && (
          <Button
            variant="ghost"
            onClick={() => {
              void insurers.refetch()
              if (asOf) {
                void items.refetch()
                if (rule.insurerId) void products.refetch()
              }
            }}
          >
            선택 항목 다시 조회
          </Button>
        )}
      </div>
      <DraftSelect
        path={prefix + 'insurerId'}
        label="보험사"
        errors={errors}
        value={rule.insurerId}
        placeholder="전체 보험사"
        onChange={(v) => onChange({ insurerId: numberValue(v), productOfferingId: '' })}
        options={(insurers.data ?? []).map((i) => ({
          value: i.insurerId!,
          label: i.insurerName ?? '',
          disabled: i.activeYn === false,
        }))}
      />
      <DraftSelect
        path={prefix + 'productOfferingId'}
        label="상품 · 판매버전"
        errors={errors}
        value={rule.productOfferingId}
        placeholder="전체 상품"
        disabled={!rule.insurerId || !asOf}
        onChange={(v) => onChange({ productOfferingId: numberValue(v) })}
        options={(products.data ?? []).map((p) => ({
          value: p.productOfferingId!,
          label: `${p.productName} · ${p.offeringVersion ?? '판매버전 자료 없음'} · ${p.salesStartDate ?? ''}`,
        }))}
      />
      <DraftSelect
        path={prefix + 'commissionItemId'}
        label="수수료 항목"
        errors={errors}
        value={rule.commissionItemId}
        placeholder="전체 항목"
        onChange={(v) => onChange({ commissionItemId: numberValue(v) })}
        options={(items.data ?? []).map((i) => ({ value: i.commissionItemId!, label: i.itemName ?? '' }))}
      />
    </>
  )
}

export function CommissionRow({
  rule,
  index,
  errors,
  asOf,
  onChange,
  onRemove,
}: {
  rule: CommissionDraft
  index: number
  errors: DraftErrors
  asOf: string
  onChange: (rule: CommissionDraft) => void
  onRemove: () => void
}) {
  const insurers = useInsurerOptions()
  const organizations = useOrganizationOptions(asOf)
  const items = useCommissionItems(asOf)
  const products = useProductOptions(asOf, String(rule.insurerId))
  const prefix = `commissionRules.${index}.`
  const set = <K extends keyof CommissionDraft>(key: K, value: CommissionDraft[K]) =>
    onChange({ ...rule, [key]: value })
  return (
    <section className="policy-rule-editor" aria-label={`수수료 규칙 ${index + 1}`}>
      <header>
        <h3>
          <span className="policy-row-number">{String(index + 1).padStart(2, '0')}</span>수수료 규칙
        </h3>
        <Button variant="ghost" onClick={onRemove} aria-label={`수수료 규칙 ${index + 1} 삭제`}>
          행 삭제
        </Button>
      </header>
      <QueryState
        loading={insurers.isLoading || organizations.isLoading || items.isLoading || products.isFetching}
        error={insurers.error || organizations.error || items.error || products.error}
      />
      {(insurers.error || organizations.error || items.error || products.error) && (
        <Button
          variant="ghost"
          onClick={() => {
            void insurers.refetch()
            if (asOf) {
              void organizations.refetch()
              void items.refetch()
              if (rule.insurerId) void products.refetch()
            }
          }}
        >
          선택 항목 다시 조회
        </Button>
      )}
      {!asOf && (
        <p className="policy-note">기본정보의 적용 시작일을 입력하면 상품·조직·수수료 항목을 선택할 수 있습니다.</p>
      )}
      <div className="policy-form-grid">
        <DraftSelect
          path={prefix + 'paymentStage'}
          label="지급단계"
          required
          errors={errors}
          value={rule.paymentStage}
          onChange={(v) => set('paymentStage', v)}
          options={optionsFor({ INSURER_TO_GA: '보험사 → GA', GA_TO_FC: 'GA → 설계사' })}
        />
        <DraftSelect
          path={prefix + 'insurerId'}
          label="보험사"
          errors={errors}
          value={rule.insurerId}
          placeholder="전체 보험사"
          onChange={(v) => onChange({ ...rule, insurerId: numberValue(v), productOfferingId: '' })}
          options={(insurers.data ?? []).map((i) => ({
            value: i.insurerId!,
            label: i.insurerName ?? '',
            disabled: i.activeYn === false,
          }))}
        />
        <DraftSelect
          path={prefix + 'productOfferingId'}
          label="상품 · 판매버전"
          errors={errors}
          value={rule.productOfferingId}
          disabled={!rule.insurerId || !asOf}
          placeholder="전체 상품"
          onChange={(v) => set('productOfferingId', numberValue(v))}
          options={(products.data ?? []).map((p) => ({
            value: p.productOfferingId!,
            label: `${p.productName} · ${p.offeringVersion ?? '판매버전 자료 없음'} · ${p.salesStartDate ?? ''}`,
          }))}
        />
        <DraftSelect
          path={prefix + 'organizationId'}
          label="조직"
          errors={errors}
          value={rule.organizationId}
          placeholder="전체 조직"
          onChange={(v) => set('organizationId', numberValue(v))}
          options={(organizations.data ?? []).map((o) => ({
            value: o.organizationId!,
            label: o.organizationName ?? '',
            disabled: o.activeYn === false,
          }))}
        />
        <DraftSelect
          path={prefix + 'commissionItemId'}
          label="수수료 항목"
          required
          errors={errors}
          value={rule.commissionItemId}
          onChange={(v) => set('commissionItemId', numberValue(v))}
          options={(items.data ?? []).map((i) => ({ value: i.commissionItemId!, label: i.itemName ?? '' }))}
        />
        <DraftSelect
          path={prefix + 'feeComponentType'}
          label="수수료 구성 유형"
          required
          errors={errors}
          value={rule.feeComponentType}
          onChange={(v) => set('feeComponentType', v)}
          options={optionsFor({
            CURRENT: '현행',
            UPFRONT: '선지급',
            MAINTENANCE: '유지관리',
            LONG_TERM_MAINTENANCE: '장기유지',
            INCENTIVE: '시책',
            SETTLEMENT_SUPPORT: '정착지원',
            NEWCOMER_SUPPORT: '신인지원',
            COMMON_COST: '공통비',
            OTHER: '기타',
          })}
        />
        <DraftInput
          path={prefix + 'installmentFrom'}
          label="회차 시작"
          type="number"
          min="1"
          required
          errors={errors}
          value={rule.installmentFrom}
          onChange={(v) => set('installmentFrom', numberValue(v))}
        />
        <DraftInput
          path={prefix + 'installmentTo'}
          label="회차 끝"
          type="number"
          min="1"
          required
          errors={errors}
          value={rule.installmentTo}
          onChange={(v) => set('installmentTo', numberValue(v))}
        />
        <DraftSelect
          path={prefix + 'basisCode'}
          label="금액 기준"
          required
          errors={errors}
          value={rule.basisCode}
          onChange={(v) => set('basisCode', v)}
          options={optionsFor({ MONTHLY_EQUIVALENT_FIRST_PREMIUM: '월납환산 초회보험료' })}
        />
        <DraftSelect
          path={prefix + 'calculationType'}
          label="계산 방식"
          required
          errors={errors}
          value={rule.calculationType}
          onChange={(v) => onChange({ ...rule, calculationType: v as 'RATE' | 'FIXED', ratePct: '', fixedAmount: '' })}
          options={optionsFor({ RATE: 'RATE · 요율', FIXED: 'FIXED · 고정금액' })}
        />
        <DraftInput
          path={prefix + 'ratePct'}
          label="요율 (%)"
          required={rule.calculationType === 'RATE'}
          disabled={rule.calculationType !== 'RATE'}
          type="number"
          min="0"
          step="0.000001"
          errors={errors}
          value={rule.ratePct}
          onChange={(v) => set('ratePct', numberValue(v))}
        />
        <DraftInput
          path={prefix + 'fixedAmount'}
          label="고정금액 (원)"
          required={rule.calculationType === 'FIXED'}
          disabled={rule.calculationType !== 'FIXED'}
          type="number"
          min="0"
          step="0.01"
          errors={errors}
          value={rule.fixedAmount}
          onChange={(v) => set('fixedAmount', numberValue(v))}
        />
        <DraftInput
          path={prefix + 'priorityNo'}
          label="우선순번"
          required
          type="number"
          step="1"
          errors={errors}
          value={rule.priorityNo}
          onChange={(v) => set('priorityNo', numberValue(v))}
        />
        <DraftSelect
          path={prefix + 'paymentConditionCode'}
          label="지급조건"
          errors={errors}
          value={rule.paymentConditionCode}
          placeholder="설정 안 함"
          onChange={(v) => set('paymentConditionCode', v)}
          options={optionsFor({ PAY_IF_ACTIVE_ON_DUE_DATE: '지급예정일 정상 계약' })}
        />
        <DraftSelect
          path={prefix + 'agentRankCode'}
          label="설계사 직급"
          errors={errors}
          value={rule.agentRankCode}
          placeholder="전체 직급"
          onChange={(v) => set('agentRankCode', v)}
          options={optionsFor({ FC: 'FC', TEAM_LEADER: '팀장', BRANCH_MANAGER: '지점장', DIVISION_HEAD: '본부장' })}
        />
        <label className="policy-checkbox">
          <input
            type="checkbox"
            checked={rule.equalMonthlyYn}
            onChange={(e) => set('equalMonthlyYn', e.target.checked)}
          />
          월 동일 금액 지급
        </label>
      </div>
    </section>
  )
}
