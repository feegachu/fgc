import { safeSourceUrl } from './management'
import type { DraftDocument } from './management'

export type DraftErrors = Record<string, string>
export function validateDraft(draft: DraftDocument): DraftErrors {
  const errors: DraftErrors = {}
  const required = (path: string, value: unknown) => {
    if (value === '' || value == null || (typeof value === 'string' && !value.trim()))
      errors[path] = '필수 항목을 입력하세요.'
  }
  const range = (prefix: string, fromKey: string, toKey: string, from: string | number, to: string | number) => {
    if (from !== '' && to !== '' && to < from)
      errors[`${prefix}${toKey}`] =
        `${fromKey.includes('Date') || fromKey.includes('effective') ? '시작일' : '시작 값'} 이상으로 입력하세요.`
  }
  for (const key of ['policyCode', 'policyName', 'policyType', 'sourceClass', 'effectiveFrom'] as const)
    required(key, draft[key])
  range('', 'effectiveFrom', 'effectiveTo', draft.effectiveFrom, draft.effectiveTo)
  draft.commissionRules.forEach((rule, i) => {
    const prefix = `commissionRules.${i}.`
    for (const key of [
      'paymentStage',
      'commissionItemId',
      'feeComponentType',
      'installmentFrom',
      'installmentTo',
      'basisCode',
      'priorityNo',
    ] as const)
      required(prefix + key, rule[key])
    const valueKey = rule.calculationType === 'RATE' ? 'ratePct' : 'fixedAmount'
    required(prefix + valueKey, rule[valueKey])
    if (Number(rule[valueKey]) < 0) errors[prefix + valueKey] = '0 이상으로 입력하세요.'
    if (rule.installmentFrom !== '' && rule.installmentFrom < 1)
      errors[prefix + 'installmentFrom'] = '1회차 이상으로 입력하세요.'
    range(prefix, 'installmentFrom', 'installmentTo', rule.installmentFrom, rule.installmentTo)
  })
  draft.feeRegimeRules.forEach((rule, i) => {
    const prefix = `feeRegimeRules.${i}.`
    required(prefix + 'salesStartDateFrom', rule.salesStartDateFrom)
    required(prefix + 'maxInstallments', rule.maxInstallments)
    const max = rule.feeRegimeCode === 'FOUR_YEAR_2027' ? 48 : 84
    if (rule.maxInstallments !== '' && (rule.maxInstallments < 1 || rule.maxInstallments > max))
      errors[prefix + 'maxInstallments'] = `1~${max}회차 사이로 입력하세요.`
    range(prefix, 'salesStartDateFrom', 'salesStartDateTo', rule.salesStartDateFrom, rule.salesStartDateTo)
    range(prefix, 'basicDocumentDateFrom', 'basicDocumentDateTo', rule.basicDocumentDateFrom, rule.basicDocumentDateTo)
    const from = rule.longTermMaintenanceFrom,
      to = rule.longTermMaintenanceTo
    if (from !== '' || to !== '') {
      required(prefix + 'longTermMaintenanceFrom', from)
      required(prefix + 'longTermMaintenanceTo', to)
      if (from !== '' && from < 1) errors[prefix + 'longTermMaintenanceFrom'] = '1회차 이상으로 입력하세요.'
      range(prefix, 'longTermMaintenanceFrom', 'longTermMaintenanceTo', from, to)
      if (to !== '' && rule.maxInstallments !== '' && to > rule.maxInstallments)
        errors[prefix + 'longTermMaintenanceTo'] = '최대 회차 이내로 입력하세요.'
    }
  })
  draft.clawbackRules.forEach((rule, i) => {
    const prefix = `clawbackRules.${i}.`
    for (const key of ['elapsedMonthFrom', 'elapsedMonthTo', 'clawbackRatePct'] as const)
      required(prefix + key, rule[key])
    range(prefix, 'elapsedMonthFrom', 'elapsedMonthTo', rule.elapsedMonthFrom, rule.elapsedMonthTo)
    if (Number(rule.elapsedMonthFrom) < 0) errors[prefix + 'elapsedMonthFrom'] = '0개월 이상으로 입력하세요.'
    if (Number(rule.clawbackRatePct) < 0 || Number(rule.clawbackRatePct) > 100)
      errors[prefix + 'clawbackRatePct'] = '0~100% 사이로 입력하세요.'
  })
  const refs = new Set<string>()
  draft.sourceDetails.forEach((source, i) => {
    const prefix = `sourceDetails.${i}.`
    required(prefix + 'ref', source.ref)
    required(prefix + 'title', source.title)
    if (source.ref && !/^(REG|SRC)-[A-Za-z0-9_-]+$/.test(source.ref))
      errors[prefix + 'ref'] = 'REG- 또는 SRC-로 시작하는 근거 코드를 입력하세요.'
    if (refs.has(source.ref)) errors[prefix + 'ref'] = '이미 추가한 근거 코드입니다.'
    refs.add(source.ref)
    if (source.url && !safeSourceUrl(source.url)) errors[prefix + 'url'] = 'http 또는 https 링크를 입력하세요.'
  })
  return errors
}
