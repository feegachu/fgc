import { useReferenceDate } from '../reference/utils'
import { useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { DataTable } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { int } from '../../lib/format'
import { QueryState, ReferenceHeader, ReferenceTabs } from '../reference/ReferenceUI'
import {
  useAgents,
  useCommissionItems,
  useInsurerOptions,
  useInsurers,
  useOrganizationOptions,
  useOrganizations,
  useProducts,
} from './api'
import { agentColumns, commissionColumns, insurerColumns, organizationColumns, productColumns } from './columns'
import '../reference/reference.css'
const tabs = [
  { id: 'organization', label: '조직' },
  { id: 'insurer', label: '보험회사' },
  { id: 'product', label: '상품' },
  { id: 'agent', label: '설계사' },
  { id: 'commission-item', label: '수수료 항목' },
] as const
type Tab = (typeof tabs)[number]['id']
const filterKeys = ['keyword', 'asOf', 'insurerId', 'organizationId', 'page']
interface Filters {
  keyword: string
  asOf: string
  insurerId: string
  organizationId: string
}
function BaseFilters({
  tab,
  values,
  onSubmit,
  onReset,
  resetValues,
  onDraftChange,
}: {
  tab: Tab
  values: Filters
  onSubmit: (values: Filters) => void
  onReset: () => void
  resetValues: Filters
  onDraftChange: (values: Filters) => void
}) {
  const [draft, setDraft] = useState(values)
  const insurers = useInsurerOptions(tab === 'product')
  const organizations = useOrganizationOptions(draft.asOf, tab === 'agent')
  function change(key: keyof Filters, value: string) {
    const next = { ...draft, [key]: value, ...(key === 'asOf' && tab === 'agent' ? { organizationId: '' } : {}) }
    setDraft(next)
    onDraftChange(next)
  }
  return (
    <>
      <FilterBar
        onSubmit={() => onSubmit(draft)}
        onReset={() => {
          setDraft(resetValues)
          onDraftChange(resetValues)
          onReset()
        }}
      >
        {(tab === 'organization' || tab === 'insurer' || tab === 'agent') && (
          <Field label={tab === 'agent' ? '설계사 검색' : tab === 'insurer' ? '보험회사 검색' : '조직 검색'}>
            <input value={draft.keyword} onChange={(e) => change('keyword', e.target.value)} />
          </Field>
        )}
        {tab === 'product' && (
          <Field label="보험회사" required>
            <select value={draft.insurerId} required onChange={(e) => change('insurerId', e.target.value)}>
              <option value="">선택하세요</option>
              {insurers.data?.map((r) => (
                <option key={r.insurerId} value={r.insurerId}>
                  {r.insurerName}
                  {r.activeYn === false ? ' · 사용중지' : ''}
                </option>
              ))}
            </select>
          </Field>
        )}
        {tab === 'agent' && (
          <Field label="소속 조직">
            <select value={draft.organizationId} onChange={(e) => change('organizationId', e.target.value)}>
              <option value="">전체</option>
              {organizations.data?.map((r) => (
                <option key={r.organizationId} value={r.organizationId}>
                  {r.organizationName}
                  {r.activeYn === false ? ' · 사용중지' : ''}
                </option>
              ))}
            </select>
          </Field>
        )}
        {tab !== 'insurer' && (
          <Field label="기준일" required>
            <input type="date" required value={draft.asOf} onChange={(e) => change('asOf', e.target.value)} />
          </Field>
        )}
      </FilterBar>
      <QueryState
        error={tab === 'product' ? insurers.error : tab === 'agent' ? organizations.error : null}
        loading={(tab === 'product' && insurers.isLoading) || (tab === 'agent' && organizations.isLoading)}
      />
    </>
  )
}
export function BasePage() {
  const defaultDate = useReferenceDate()
  const [params, setParams] = useSearchParams()
  const tab = tabs.find((t) => t.id === params.get('tab'))?.id ?? 'organization'
  const saved = useRef<Partial<Record<Tab, URLSearchParams>>>({})
  const values: Filters = {
    keyword: params.get('keyword') ?? '',
    asOf: params.get('asOf') ?? defaultDate,
    insurerId: params.get('insurerId') ?? '',
    organizationId: params.get('organizationId') ?? '',
  }
  const [drafts, setDrafts] = useState<Partial<Record<Tab, { source: string; values: Filters }>>>({})
  const source = JSON.stringify(values)
  const resetValues = { keyword: '', asOf: defaultDate, insurerId: '', organizationId: '' }
  const rawPage = Number(params.get('page') ?? 1)
  const page = Number.isSafeInteger(rawPage) && rawPage > 0 ? rawPage : 1
  const enabled = Boolean(defaultDate)
  const common = { page, size: 20 }
  const organization = useOrganizations(
    { ...common, keyword: values.keyword, asOf: values.asOf },
    enabled && tab === 'organization',
  )
  const insurer = useInsurers({ ...common, keyword: values.keyword }, enabled && tab === 'insurer')
  const product = useProducts(
    { ...common, insurerId: values.insurerId, asOf: values.asOf },
    enabled && tab === 'product',
  )
  const agent = useAgents(
    { ...common, organizationId: values.organizationId, keyword: values.keyword, asOf: values.asOf },
    enabled && tab === 'agent',
  )
  const commission = useCommissionItems(values.asOf, enabled && tab === 'commission-item')
  const query =
    tab === 'organization'
      ? organization
      : tab === 'insurer'
        ? insurer
        : tab === 'product'
          ? product
          : tab === 'agent'
            ? agent
            : commission
  const paged =
    tab === 'organization'
      ? organization.data
      : tab === 'insurer'
        ? insurer.data
        : tab === 'product'
          ? product.data
          : tab === 'agent'
            ? agent.data
            : undefined
  function changeTab(nextTab: Tab) {
    saved.current[tab] = new URLSearchParams(params)
    setParams((current) => {
      const next = new URLSearchParams(current)
      filterKeys.forEach((k) => next.delete(k))
      const prior = saved.current[nextTab]
      filterKeys.forEach((k) => {
        if (prior?.has(k)) next.set(k, prior.get(k)!)
      })
      next.set('tab', nextTab)
      return next
    })
  }
  function submit(nextValues: Filters) {
    setParams((current) => {
      const next = new URLSearchParams(current)
      filterKeys.forEach((k) => next.delete(k))
      next.set('tab', tab)
      Object.entries(nextValues).forEach(([k, v]) => {
        if (
          v &&
          (k !== 'keyword' || ['organization', 'insurer', 'agent'].includes(tab)) &&
          (k !== 'insurerId' || tab === 'product') &&
          (k !== 'organizationId' || tab === 'agent') &&
          (k !== 'asOf' || tab !== 'insurer')
        )
          next.set(k, v)
      })
      return next
    })
  }
  return (
    <>
      <ReferenceHeader
        title="기준정보 조회"
        description="조직·보험회사·상품·설계사·수수료 항목을 조회합니다. 등록·수정은 제공하지 않습니다."
      />
      <ReferenceTabs tabs={tabs} active={tab} onChange={changeTab} panelClassName={`reference-base-${tab}`}>
        <BaseFilters
          key={`${tab}:${params.toString()}:${defaultDate}`}
          tab={tab}
          values={drafts[tab]?.source === source ? drafts[tab]!.values : values}
          resetValues={resetValues}
          onDraftChange={(draft) => setDrafts((current) => ({ ...current, [tab]: { source, values: draft } }))}
          onSubmit={submit}
          onReset={() => submit({ keyword: '', asOf: defaultDate, insurerId: '', organizationId: '' })}
        />
        <QueryState loading={query.isLoading} error={query.error} />
        {tab === 'product' && !values.insurerId ? (
          <p>보험회사를 선택하면 기준일에 판매 가능한 상품을 조회합니다.</p>
        ) : (
          !query.error &&
          !query.isLoading && (
            <>
              <p className="reference-count">
                총 {int(tab === 'commission-item' ? (commission.data?.length ?? 0) : (paged?.totalElements ?? 0))}건
              </p>
              {tab === 'organization' && (
                <DataTable
                  caption="조직"
                  columns={organizationColumns}
                  rows={organization.data?.content ?? []}
                  rowKey={(r) => String(r.organizationId)}
                />
              )}
              {tab === 'insurer' && (
                <DataTable
                  caption="보험회사"
                  columns={insurerColumns}
                  rows={insurer.data?.content ?? []}
                  rowKey={(r) => String(r.insurerId)}
                />
              )}
              {tab === 'product' && (
                <DataTable
                  caption="상품"
                  columns={productColumns}
                  rows={product.data?.content ?? []}
                  rowKey={(r) => String(r.productOfferingId)}
                />
              )}
              {tab === 'agent' && (
                <DataTable
                  caption="설계사"
                  columns={agentColumns}
                  rows={agent.data?.content ?? []}
                  rowKey={(r) => String(r.agentId)}
                />
              )}
              {tab === 'commission-item' && (
                <DataTable
                  caption="수수료 항목"
                  columns={commissionColumns}
                  rows={commission.data ?? []}
                  rowKey={(r) => String(r.commissionItemId)}
                />
              )}
              {paged && (
                <Pagination
                  page={paged.page ?? page}
                  totalPages={paged.totalPages ?? 0}
                  onPageChange={(nextPage) =>
                    setParams((current) => {
                      const next = new URLSearchParams(current)
                      next.set('page', String(nextPage))
                      return next
                    })
                  }
                />
              )}
            </>
          )
        )}
      </ReferenceTabs>
    </>
  )
}
