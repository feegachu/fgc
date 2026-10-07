import { present, useReferenceDate } from '../reference/utils'
import { useSearchParams } from 'react-router'
import { DataTable } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { EvidenceLink } from '../../components/EvidenceLink'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { date, int } from '../../lib/format'
import { QueryState, ReferenceTabs, ReferenceText } from '../reference/ReferenceUI'
import { usePolicies, usePolicyDetail } from './api'
import { CapRuleSets, CommissionRules, RefundTables } from './PolicyDetailTables'
const tabs = [
  { id: 'versions', label: '정책 버전' },
  { id: 'commission', label: '수수료 규칙' },
  { id: 'cap', label: '1,200% 룰셋' },
  { id: 'refund', label: '예상 해약환급률표' },
] as const
const sourceTones: Record<string, StatusTone> = {
  REGULATORY: 'info',
  INSURER_RULE: 'success',
  GA_POLICY: 'neutral',
  PROJECT_ASSUMPTION: 'warning',
}
export function PoliciesPage() {
  const defaultDate = useReferenceDate()
  const [params, setParams] = useSearchParams()
  const asOf = params.get('asOf') ?? defaultDate
  const active = tabs.find((tab) => tab.id === params.get('tab'))?.id ?? 'versions'
  const policies = usePolicies(asOf)
  const requestedId = params.get('policyVersionId')
  const parsedId = Number(requestedId)
  const id =
    requestedId !== null && Number.isSafeInteger(parsedId) && parsedId > 0
      ? parsedId
      : policies.data?.[0]?.policyVersionId
  const selected = policies.data?.find((p) => p.policyVersionId === id)
  const detail = usePolicyDetail(id, active !== 'versions' && policies.isSuccess)
  function patch(values: Record<string, string | null>) {
    setParams((current) => {
      const next = new URLSearchParams(current)
      Object.entries(values).forEach(([key, value]) => {
        if (value === null) next.delete(key)
        else next.set(key, value)
      })
      return next
    })
  }
  return (
    <div className="policy-page">
      <header className="page-header policy-page-header">
        <div className="page-header-copy">
          <h1 className="page-title">정책 · 룰셋 조회</h1>
        </div>
        <Field className="policy-date-field" label="적용 기준일">
          <input type="date" value={asOf} onChange={(e) => patch({ asOf: e.target.value, policyVersionId: null })} />
        </Field>
      </header>
      <div className="policy-tabs">
        <ReferenceTabs
          panelClassName="surface policy-tab-panel"
          label="정책 상세 구분"
          tabs={tabs}
          active={active}
          onChange={(tab) => patch({ tab, ...(id !== undefined ? { policyVersionId: String(id) } : {}) })}
        >
          <header className="surface-header policy-panel-header">
            <h2 className="surface-title">{tabs.find((tab) => tab.id === active)?.label}</h2>
            <p className="policy-panel-caption">
              {active === 'versions'
                ? `기준일 ${asOf} · ${int(policies.data?.length ?? 0)}건 · 행을 선택하면 나머지 탭에서 상세를 확인할 수 있습니다`
                : selected
                  ? `선택 정책: ${selected.policyCode} v${selected.versionNo}`
                  : ''}
            </p>
          </header>
          <QueryState loading={policies.isLoading} error={policies.error} />
          <div hidden={active !== 'versions'}>
            <DataTable
              className="policy-version-table"
              viewportClassName="policy-version-table-viewport"
              caption="정책 버전"
              emptyMessage="기준일에 적용되는 정책 버전이 없습니다. 기준일을 바꿔 보세요."
              rows={policies.data ?? []}
              rowKey={(r) => String(r.policyVersionId)}
              selectedKeys={id === undefined ? [] : [String(id)]}
              rowClassName={(r) => `policy-row ${r.regulationRefs?.length ? '' : 'policy-row-missing-reference'}`}
              onRowActivate={(r) => patch({ policyVersionId: String(r.policyVersionId) })}
              columns={[
                {
                  key: 'select',
                  label: <span className="visually-hidden">선택</span>,
                  width: '3rem',
                  cellClassName: 'policy-select-cell',
                  render: (r) => (
                    <input
                      className="policy-row-select"
                      type="radio"
                      name="policy-version"
                      aria-label={`${r.policyCode} v${r.versionNo} 선택`}
                      checked={id === r.policyVersionId}
                      onChange={() => patch({ policyVersionId: String(r.policyVersionId) })}
                    />
                  ),
                },
                {
                  key: 'code',
                  label: '정책코드',
                  width: '12.5rem',
                  cellClassName: 'tabular-nums policy-disclosure-cell',
                  render: (r) => <ReferenceText value={r.policyCode} limit={22} singleLine />,
                },
                {
                  key: 'name',
                  label: '정책이름',
                  width: '15rem',
                  cellClassName: 'policy-disclosure-cell',
                  render: (r) => <ReferenceText value={r.policyName} limit={28} />,
                },
                { key: 'type', label: '유형', width: '9rem', render: (r) => present(r.policyTypeLabel) },
                {
                  key: 'source',
                  label: '출처분류',
                  width: '7.5rem',
                  render: (r) => (
                    <StatusBadge tone={sourceTones[r.sourceClass ?? ''] ?? 'neutral'}>
                      {present(r.sourceClassLabel)}
                    </StatusBadge>
                  ),
                },
                { key: 'version', label: '버전', width: '4.5rem', align: 'number', render: (r) => `v${r.versionNo}` },
                { key: 'from', label: '적용 시작', width: '7.5rem', render: (r) => date(r.effectiveFrom) },
                {
                  key: 'to',
                  label: '적용 종료',
                  width: '7.5rem',
                  render: (r) => (r.effectiveTo ? date(r.effectiveTo) : '계속'),
                },
                {
                  key: 'status',
                  label: '상태',
                  width: '6rem',
                  render: (r) => (
                    <StatusBadge
                      tone={r.status === 'ACTIVE' ? 'success' : r.status === 'DRAFT' ? 'warning' : 'neutral'}
                    >
                      {present(r.statusLabel)}
                    </StatusBadge>
                  ),
                },
                {
                  key: 'refs',
                  label: '근거',
                  width: '11.5rem',
                  cellClassName: 'policy-disclosure-cell',
                  render: (r) =>
                    r.regulationRefs?.length ? (
                      r.regulationRefs.map((ref) => (
                        <EvidenceLink key={ref} label={ref}>
                          {r.policyName} · {ref}
                        </EvidenceLink>
                      ))
                    ) : (
                      <StatusBadge>근거 미기재</StatusBadge>
                    ),
                },
              ]}
            />
          </div>
          {active !== 'versions' && policies.isSuccess && (
            <>
              {id === undefined ? (
                <div className="empty-state policy-empty-state" role="status">
                  정책 버전 탭에서 정책을 선택하세요.
                </div>
              ) : (
                <>
                  <QueryState loading={detail.isLoading} error={detail.error} />
                  {detail.data && !detail.error && (
                    <>
                      {active === 'commission' && <CommissionRules rows={detail.data.commissionRules ?? []} />}
                      {active === 'cap' && <CapRuleSets rows={detail.data.capRuleSets ?? []} />}
                      {active === 'refund' && <RefundTables rows={detail.data.refundRateTables ?? []} />}
                    </>
                  )}
                </>
              )}
            </>
          )}
        </ReferenceTabs>
      </div>
    </div>
  )
}
