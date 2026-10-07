import { useReferenceDate } from '../reference/utils'
import { useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { DataTable } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { Pagination } from '../../components/Pagination'
import { int } from '../../lib/format'
import { QueryState, ReferenceTabs } from '../reference/ReferenceUI'
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
const tabs = [
  { id: 'organization', label: '조직' },
  { id: 'insurer', label: '보험회사' },
  { id: 'product', label: '상품' },
  { id: 'agent', label: '설계사' },
  { id: 'commission-item', label: '수수료 항목' },
] as const
const resultCopy = {
  organization: { title: '조직 목록', description: '적용기간 안의 조직을 표시하며 사용중지 조직도 조회됩니다.' },
  insurer: { title: '보험회사 목록', description: '상품 판매버전 조회의 선행 기준정보입니다.' },
  product: { title: '상품 판매버전 목록', description: '같은 상품이라도 판매버전과 채널이 다르면 별도로 표시합니다.' },
  agent: { title: '설계사 목록', description: '위촉 유효기간과 상태, 소속 조직 및 신인활동지원 정보를 확인합니다.' },
  'commission-item': { title: '수수료 항목 목록', description: '선택한 기준일에 유효한 항목입니다.' },
}
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
        className="base-filter-form"
        wrapFields={false}
        resetFirst
        onSubmit={() => onSubmit(draft)}
        onReset={() => {
          setDraft(resetValues)
          onDraftChange(resetValues)
          onReset()
        }}
      >
        {tab === 'agent' && (
          <Field label="소속 조직" className="base-filter-select">
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
        {(tab === 'organization' || tab === 'insurer' || tab === 'agent') && (
          <Field
            className="base-filter-keyword"
            label={tab === 'agent' ? '설계사 검색' : tab === 'insurer' ? '보험회사 검색' : '조직 검색'}
          >
            <input
              type="search"
              placeholder={
                tab === 'agent'
                  ? '설계사 코드 또는 이름'
                  : tab === 'insurer'
                    ? '보험회사 코드 또는 이름'
                    : '조직코드 또는 조직명'
              }
              value={draft.keyword}
              onChange={(e) => change('keyword', e.target.value)}
            />
          </Field>
        )}
        {tab === 'product' && (
          <Field label="보험회사" className="base-filter-select">
            <select value={draft.insurerId} required onChange={(e) => change('insurerId', e.target.value)}>
              <option value="">보험회사를 선택하세요</option>
              {insurers.data?.map((r) => (
                <option key={r.insurerId} value={r.insurerId}>
                  {r.insurerName}
                  {r.activeYn === false ? ' · 사용중지' : ''}
                </option>
              ))}
            </select>
          </Field>
        )}
        {tab !== 'insurer' && (
          <Field label={tab === 'product' ? '계약 기준일' : '기준일'}>
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
  const hasRows = tab === 'commission-item' ? Boolean(commission.data?.length) : Boolean(paged?.content?.length)
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
    <div className="base-page">
      <header className="page-header base-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">기준정보 조회</h1>
        </div>
        <p className="base-as-of-summary">
          기본 조회 기준일<strong>{defaultDate}</strong>
        </p>
      </header>
      <div className="base-workspace">
        <ReferenceTabs tabs={tabs} active={tab} onChange={changeTab} label="기준정보 유형" panelClassName="base-panel">
          <BaseFilters
            key={`${tab}:${params.toString()}:${defaultDate}`}
            tab={tab}
            values={drafts[tab]?.source === source ? drafts[tab]!.values : values}
            resetValues={resetValues}
            onDraftChange={(draft) => setDrafts((current) => ({ ...current, [tab]: { source, values: draft } }))}
            onSubmit={submit}
            onReset={() => submit({ keyword: '', asOf: defaultDate, insurerId: '', organizationId: '' })}
          />
          <div className="surface base-result-surface">
            <header className="surface-header base-result-header">
              <div>
                <h2 className="surface-title">{resultCopy[tab].title}</h2>
                <p className="base-result-description">{resultCopy[tab].description}</p>
              </div>
              <span className="base-result-count">
                <strong className="tabular-nums">
                  {int(tab === 'commission-item' ? (commission.data?.length ?? 0) : (paged?.totalElements ?? 0))}
                </strong>
                건
              </span>
            </header>
            {(query.isLoading || query.error) && (
              <div className="empty-state base-result-message">
                <QueryState loading={query.isLoading} error={query.error} />
              </div>
            )}
            {tab === 'product' && !values.insurerId ? (
              <p className="empty-state base-result-message">
                보험회사를 선택하면 기준일에 판매 가능한 상품을 조회합니다.
              </p>
            ) : (
              !query.error &&
              !query.isLoading &&
              (!hasRows ? (
                <div className="empty-state base-result-message" role="status">
                  조건에 맞는 기준정보가 없습니다.
                </div>
              ) : (
                <>
                  {tab === 'organization' && (
                    <DataTable
                      className="base-table"
                      viewportClassName="base-table-viewport"
                      caption="조직"
                      columns={organizationColumns}
                      rows={organization.data?.content ?? []}
                      rowKey={(r) => String(r.organizationId)}
                    />
                  )}
                  {tab === 'insurer' && (
                    <DataTable
                      className="base-table"
                      viewportClassName="base-table-viewport"
                      caption="보험회사"
                      columns={insurerColumns}
                      rows={insurer.data?.content ?? []}
                      rowKey={(r) => String(r.insurerId)}
                    />
                  )}
                  {tab === 'product' && (
                    <DataTable
                      className="base-table base-product-table"
                      viewportClassName="base-table-viewport"
                      caption="상품"
                      columns={productColumns}
                      rows={product.data?.content ?? []}
                      rowKey={(r) => String(r.productOfferingId)}
                    />
                  )}
                  {tab === 'agent' && (
                    <DataTable
                      className="base-table base-agent-table"
                      viewportClassName="base-table-viewport"
                      caption="설계사"
                      columns={agentColumns}
                      rows={agent.data?.content ?? []}
                      rowKey={(r) => String(r.agentId)}
                    />
                  )}
                  {tab === 'commission-item' && (
                    <DataTable
                      className="base-table"
                      viewportClassName="base-table-viewport"
                      caption="수수료 항목"
                      columns={commissionColumns}
                      rows={commission.data ?? []}
                      rowKey={(r) => String(r.commissionItemId)}
                    />
                  )}
                  {paged && (
                    <div className="base-pagination-footer">
                      <Pagination
                        compact
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
                    </div>
                  )}
                </>
              ))
            )}
          </div>
        </ReferenceTabs>
      </div>
    </div>
  )
}
