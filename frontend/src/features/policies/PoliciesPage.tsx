import { present, useReferenceDate } from '../reference/utils'
import { useContext, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { useAuth } from '../auth/useAuth'
import { PolicyDraftEditor } from './PolicyDraftEditor'
import { PolicyManagement } from './PolicyManagement'
import { PolicyPreviewContext, sourceReferenceNotice } from './management'
import './policies.css'
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
import { PolicyReferenceInfo } from './PolicyReferenceInfo'
import type { Policy } from './api'
/** 목록 근거 info 대상: 규제(REG)와 근거자료(SRC) 모두. 둘 다 없을 때만 '근거 미기재'다. */
const policyRefs = (policy: Policy) => [...(policy.regulationRefs ?? []), ...(policy.sourceRefs ?? [])]
const tabs = [
  { id: 'versions', label: '정책 버전' },
  { id: 'management', label: '관리 상세' },
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
  const { user } = useAuth()
  const preview = useContext(PolicyPreviewContext)
  const cache = useQueryClient()
  const [creating, setCreating] = useState(false)
  const [message, setMessage] = useState('')
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
  function saved(nextId: number) {
    setCreating(false)
    setMessage(
      preview ? '미리보기에 변경사항을 반영했습니다. 실제 정책은 저장되지 않습니다.' : '정책 변경사항을 저장했습니다.',
    )
    void cache.invalidateQueries({ queryKey: ['policies'] })
    patch({ tab: 'management', policyVersionId: String(nextId) })
  }
  return (
    <div className="policy-page">
      <header className="page-header policy-page-header">
        <div className="page-header-copy">
          <p className="policy-eyebrow">POL-W01 · POLICY MANAGEMENT</p>
          <h1 className="page-title">정책 · 룰셋 관리</h1>
          <p className="policy-page-description">정책의 적용 기준과 규칙을 확인하고, 초안부터 활성화까지 관리합니다.</p>
        </div>
        <div className="policy-header-actions">
          <Field className="policy-date-field" label="적용 기준일">
            <input type="date" value={asOf} onChange={(e) => patch({ asOf: e.target.value, policyVersionId: null })} />
          </Field>
          <Button variant="secondary" onClick={() => patch({ asOf: defaultDate, policyVersionId: null })}>
            초기화
          </Button>
          {['SETTLEMENT', 'GA_ADMIN', 'SYSTEM_ADMIN'].includes(user?.roleCode ?? '') && (
            <Button onClick={() => setCreating(true)}>
              <span aria-hidden="true">＋</span> 정책 등록
            </Button>
          )}
        </div>
      </header>
      {preview && (
        <div className="policy-preview-banner">
          <StatusBadge tone="info">UI 미리보기</StatusBadge>
          <span>예시 데이터입니다. 등록·수정·승인 결과는 현재 화면에서만 유지되며 새로고침하면 초기화됩니다.</span>
        </div>
      )}
      {message && (
        <p className="policy-notice" role="status">
          {message}
          <Button variant="ghost" aria-label="안내 닫기" onClick={() => setMessage('')}>
            닫기
          </Button>
        </p>
      )}
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
                ? `기준일 ${asOf}${policies.isSuccess ? ` · ${int(policies.data?.length ?? 0)}건` : ''} · 행을 선택하면 나머지 탭에서 상세를 확인할 수 있습니다`
                : selected
                  ? `선택 정책: ${selected.policyCode} v${selected.versionNo}`
                  : ''}
            </p>
          </header>
          <QueryState loading={policies.isLoading} error={policies.error} />
          {policies.error && (
            <Button variant="secondary" onClick={() => void policies.refetch()}>
              다시 조회
            </Button>
          )}
          {policies.isFetching && !policies.isLoading && (
            <p className="policy-note" role="status">
              목록을 갱신하고 있습니다.
            </p>
          )}
          <div hidden={active !== 'versions' || !policies.isSuccess}>
            <DataTable
              className="policy-version-table"
              viewportClassName="policy-version-table-viewport"
              caption="정책 버전"
              emptyMessage="기준일에 적용되는 정책 버전이 없습니다. 기준일을 바꿔 보세요."
              rows={policies.data ?? []}
              rowKey={(r) => String(r.policyVersionId)}
              selectedKeys={id === undefined ? [] : [String(id)]}
              rowClassName={(r) => `policy-row ${policyRefs(r).length ? '' : 'policy-row-missing-reference'}`}
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
                  align: 'center',
                  render: (r) => (
                    <StatusBadge tone={sourceTones[r.sourceClass ?? ''] ?? 'neutral'}>
                      {present(r.sourceClassLabel)}
                    </StatusBadge>
                  ),
                },
                { key: 'version', label: '버전', width: '4.5rem', align: 'left', render: (r) => `v${r.versionNo}` },
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
                  align: 'center',
                  render: (r) => (
                    <StatusBadge
                      tone={
                        r.status === 'ACTIVE'
                          ? 'success'
                          : r.status === 'DRAFT'
                            ? 'warning'
                            : r.status === 'REVIEW'
                              ? 'review'
                              : r.status === 'APPROVED'
                                ? 'info'
                                : 'neutral'
                      }
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
                    policyRefs(r).length ? (
                      policyRefs(r).map((ref) => (
                        <EvidenceLink key={ref} label={ref}>
                          {r.policyName} · {ref}
                          {r.policyVersionId != null ? (
                            <PolicyReferenceInfo policyVersionId={r.policyVersionId} refText={ref} />
                          ) : null}
                          <p>{sourceReferenceNotice}</p>
                          <Button
                            variant="ghost"
                            onClick={() =>
                              patch({
                                policyVersionId: String(r.policyVersionId),
                                tab: 'management',
                                section: 'sources',
                              })
                            }
                          >
                            근거 상세 보기
                          </Button>
                        </EvidenceLink>
                      ))
                    ) : (
                      <StatusBadge>근거 미기재</StatusBadge>
                    ),
                },
              ]}
            />
            {selected && (
              <div className="policy-selection-footer">
                <span>
                  선택한 정책{' '}
                  <strong>
                    {selected.policyCode} · v{selected.versionNo}
                  </strong>
                </span>
                <Button variant="secondary" onClick={() => patch({ tab: 'management', policyVersionId: String(id) })}>
                  선택 정책 관리 <span aria-hidden="true">→</span>
                </Button>
              </div>
            )}
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
                  {detail.error && (
                    <Button variant="secondary" onClick={() => void detail.refetch()}>
                      상세 다시 조회
                    </Button>
                  )}
                  {detail.data && !detail.error && (
                    <>
                      {active === 'management' && (
                        <PolicyManagement
                          key={`${id}-${params.get('section')}`}
                          initialPanel={params.get('section') === 'sources' ? 'sources' : 'rules'}
                          policy={detail.data.header ?? selected ?? {}}
                          detail={detail.data}
                          refreshing={detail.isFetching || policies.isFetching}
                          onSaved={saved}
                        />
                      )}
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
      {creating && <PolicyDraftEditor onClose={() => setCreating(false)} onSaved={saved} />}
    </div>
  )
}
