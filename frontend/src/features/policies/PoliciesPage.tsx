import { present, useReferenceDate } from '../reference/utils'
import { useSearchParams } from 'react-router'
import { DataTable } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { EvidenceLink } from '../../components/EvidenceLink'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { date, int } from '../../lib/format'
import { QueryState, ReferenceHeader, ReferenceTabs } from '../reference/ReferenceUI'
import { usePolicies, usePolicyDetail } from './api'
import { CapRuleSets, CommissionRules, RefundTables } from './PolicyDetailTables'
import '../reference/reference.css'
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
    <>
      <ReferenceHeader title="정책·룰셋 조회" description="기준일에 적용되는 정책 버전과 계산 규칙을 조회합니다." />
      <div className="filter-bar">
        <Field label="기준일" required>
          <input
            type="date"
            value={asOf}
            required
            onChange={(e) => patch({ asOf: e.target.value, policyVersionId: null })}
          />
        </Field>
      </div>
      <QueryState loading={policies.isLoading} error={policies.error} />
      <p className="reference-count">
        총 {int(policies.data?.length ?? 0)}건 · 선택 정책:{' '}
        {selected ? `${selected.policyCode} v${selected.versionNo}` : '-'}
      </p>
      <ReferenceTabs
        panelClassName={`reference-policy-${active}`}
        tabs={tabs}
        active={active}
        onChange={(tab) => patch({ tab, ...(id !== undefined ? { policyVersionId: String(id) } : {}) })}
      >
        <div hidden={active !== 'versions'}>
          <DataTable
            caption="정책 버전"
            rows={policies.data ?? []}
            rowKey={(r) => String(r.policyVersionId)}
            selectedKeys={id === undefined ? [] : [String(id)]}
            rowClassName={(r) => (r.regulationRefs?.length ? '' : 'reference-missing-evidence')}
            onRowActivate={(r) => patch({ policyVersionId: String(r.policyVersionId) })}
            columns={[
              {
                key: 'select',
                label: '선택',
                render: (r) => (
                  <input
                    type="radio"
                    name="policy-version"
                    aria-label={`${r.policyCode} v${r.versionNo} 선택`}
                    checked={id === r.policyVersionId}
                    onChange={() => patch({ policyVersionId: String(r.policyVersionId) })}
                  />
                ),
              },
              { key: 'code', label: '정책코드', render: (r) => present(r.policyCode) },
              { key: 'name', label: '정책명', render: (r) => present(r.policyName) },
              { key: 'type', label: '정책유형', render: (r) => present(r.policyTypeLabel) },
              {
                key: 'source',
                label: '출처분류',
                render: (r) => (
                  <StatusBadge tone={sourceTones[r.sourceClass ?? ''] ?? 'neutral'}>
                    {present(r.sourceClassLabel)}
                  </StatusBadge>
                ),
              },
              { key: 'version', label: '버전', render: (r) => `v${r.versionNo}` },
              { key: 'from', label: '적용시작일', render: (r) => date(r.effectiveFrom) },
              { key: 'to', label: '적용종료일', render: (r) => (r.effectiveTo ? date(r.effectiveTo) : '계속') },
              {
                key: 'status',
                label: '상태',
                render: (r) => (
                  <StatusBadge tone={r.status === 'ACTIVE' ? 'success' : r.status === 'DRAFT' ? 'warning' : 'neutral'}>
                    {present(r.statusLabel)}
                  </StatusBadge>
                ),
              },
              {
                key: 'refs',
                label: '근거',
                render: (r) =>
                  r.regulationRefs?.length
                    ? r.regulationRefs.map((ref) => (
                        <EvidenceLink key={ref} label={ref}>
                          {r.policyName} · {ref}
                        </EvidenceLink>
                      ))
                    : '근거 미기재',
              },
            ]}
          />
        </div>
        {active !== 'versions' && policies.isSuccess && (
          <>
            {id === undefined ? (
              <p>정책 버전 탭에서 정책을 선택하세요.</p>
            ) : (
              <>
                <QueryState loading={detail.isLoading} error={detail.error} />
                {detail.data && !detail.error && (
                  <>
                    <div className="reference-meta">
                      {detail.data.sourceRefs?.map((ref) => (
                        <EvidenceLink key={ref} label={ref}>
                          {detail.data?.header?.policyName} · {ref}
                        </EvidenceLink>
                      ))}
                    </div>
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
    </>
  )
}
