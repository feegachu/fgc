import { useContext, useRef, useState } from 'react'
import { Button } from '../../components/Button'
import { Modal } from '../../components/Modal'
import { Field } from '../../components/Field'
import { DataTable } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { date, dateTime, rate, won, today } from '../../lib/format'
import { ReferenceTabs } from '../reference/ReferenceUI'
import { useAuth } from '../auth/useAuth'
import type { Policy } from './api'
import {
  actionLabels,
  permittedActions,
  policyTypes,
  PolicyPreviewContext,
  ruleCount,
  safeSourceUrl,
  sourceReferenceNotice,
  statusLabels,
} from './management'
import type { DraftDocument, ManagedPolicyDetail, PolicyAction, SourceDetail } from './management'
import { PolicyDraftEditor } from './PolicyDraftEditor'
import { CapRuleSets, CommissionRules, RefundTables } from './PolicyDetailTables'
import { performPolicyAction } from './writeApi'
import { usePolicyRequest } from './usePolicyRequest'
import type { components } from '../../lib/api/schema'
type OfferingGovernance = components['schemas']['PolicyOfferingGovernanceResponse']

const tones: Record<string, StatusTone> = {
  DRAFT: 'warning',
  REVIEW: 'review',
  APPROVED: 'info',
  ACTIVE: 'success',
  RETIRED: 'neutral',
}
const steps = ['DRAFT', 'REVIEW', 'APPROVED', 'ACTIVE']
const historyLabels: Record<string, string> = {
  ...actionLabels,
  CREATE: '초안 등록',
  UPDATE: '초안 수정',
  RETIRE: '승계로 종료',
}
const panels = [
  { id: 'rules', label: '설정 규칙' },
  { id: 'sources', label: '근거 자료' },
  { id: 'history', label: '승인 이력' },
] as const
export function PolicyManagement({
  policy,
  detail,
  refreshing,
  onSaved,
  initialPanel = 'rules',
}: {
  policy: Policy
  detail: ManagedPolicyDetail
  refreshing: boolean
  onSaved: (id: number) => void
  initialPanel?: (typeof panels)[number]['id']
}) {
  const { user } = useAuth()
  const [editing, setEditing] = useState(false)
  const [action, setAction] = useState<PolicyAction | null>(null)
  const [panel, setPanel] = useState<(typeof panels)[number]['id']>(initialPanel)
  const draft = detail.editDocument
  const allowed = permittedActions(policy, detail, user?.roleCode)
  const own = (policy.createdBy ?? detail.createdBy) === user?.loginId
  const sources = detail.sourceDetails ?? draft?.sourceDetails ?? []
  const supported = Boolean(policy.policyType && policy.policyType in policyTypes)
  const successorRef = detail.approvalActions
    ?.find((entry) => entry.actionType === 'RETIRE')
    ?.evidenceRefs?.find((ref) => /^policy:\d+$/.test(ref))
  const refs = [
    ...new Set([...(policy.regulationRefs ?? []), ...(detail.sourceRefs ?? []), ...(draft?.sourceRefs ?? [])]),
  ]
  return (
    <div className="policy-management">
      <header className="policy-detail-hero">
        <div>
          <p className="policy-eyebrow">
            {policy.policyCode} <span>VERSION {policy.versionNo}</span>
          </p>
          <h2>{policy.policyName ?? '정책명 자료 없음'}</h2>
          <p>
            {policy.policyTypeLabel ?? '유형 자료 없음'} <span>·</span> {policy.sourceClassLabel ?? '출처 자료 없음'}
          </p>
        </div>
        <StatusBadge tone={tones[policy.status ?? '']}>
          {statusLabels[policy.status ?? ''] ?? policy.statusLabel ?? '상태 자료 없음'}
        </StatusBadge>
      </header>
      <div className="policy-workflow" aria-label="정책 승인 단계">
        {steps.map((step, i) => (
          <div
            key={step}
            className={
              policy.status === step ? 'is-current' : steps.indexOf(policy.status ?? '') > i ? 'is-complete' : ''
            }
            aria-current={policy.status === step ? 'step' : undefined}
          >
            <span>{String(i + 1).padStart(2, '0')}</span>
            <strong>{statusLabels[step]}</strong>
            {i < steps.length - 1 && <span className="policy-workflow-line" />}
          </div>
        ))}
      </div>
      <dl className="policy-metadata">
        <div>
          <dt>적용 기간</dt>
          <dd>
            {date(policy.effectiveFrom)} ~ {policy.effectiveTo ? date(policy.effectiveTo) : '계속'}
          </dd>
        </div>
        {detail.applicationPeriod && (
          <div>
            <dt>계약일 적용 기간</dt>
            <dd>
              {date(detail.applicationPeriod.from)} ~{' '}
              {detail.applicationPeriod.to ? date(detail.applicationPeriod.to) : '계속'}
              {detail.applicationPeriod.successorPolicyVersionId && <small>이후 계약은 후속 버전 적용</small>}
            </dd>
          </div>
        )}
        {!detail.applicationPeriod && policy.status === 'RETIRED' && (
          <div>
            <dt>계약일 적용 기간</dt>
            <dd>
              {successorRef ? '정정·승계로 계산 적용에서 제외됨' : '현재 계산 적용 대상 아님'}
              {successorRef && <small>후속 정책 {successorRef.replace('policy:', '#')}</small>}
            </dd>
          </div>
        )}
        <div>
          <dt>작성자</dt>
          <dd>{policy.createdBy ?? detail.createdBy ?? '자료 없음'}</dd>
        </div>
        <div>
          <dt>승인자 · 승인 일시</dt>
          <dd>
            {policy.approvedBy ?? detail.approvedBy ?? '미승인'}
            <small>
              {policy.approvedAt || detail.approvedAt
                ? dateTime(policy.approvedAt ?? detail.approvedAt)
                : '승인 후 표시됩니다.'}
            </small>
          </dd>
        </div>
        <div>
          <dt>기초서류 버전</dt>
          <dd>{draft?.basicDocumentVersion || '자료 없음'}</dd>
        </div>
        <div>
          <dt>승인 근거 문서</dt>
          <dd>{detail.approvalEvidenceRef || '자료 없음'}</dd>
        </div>
      </dl>
      <div className="policy-action-bar">
        <p>
          {refreshing
            ? '최신 정책을 불러오는 중입니다.'
            : policy.status === 'DRAFT'
              ? '초안을 수정한 후 검토를 요청할 수 있습니다.'
              : policy.status === 'REVIEW'
                ? '검토 중인 정책은 반려 후 수정할 수 있습니다.'
                : '승인된 정책은 새 버전으로 변경사항을 작성하세요.'}
        </p>
        <div className="policy-actions">
          {allowed.includes('EDIT') && supported && (
            <Button variant="secondary" disabled={refreshing || !draft} onClick={() => setEditing(true)}>
              초안 수정
            </Button>
          )}
          {allowed
            .filter((a) => a !== 'EDIT' && (a !== 'NEW_VERSION' || supported))
            .map((a) => (
              <Button
                key={a}
                variant={a === 'REJECT' || a === 'NEW_VERSION' ? 'secondary' : 'primary'}
                disabled={refreshing || (a === 'APPROVE' && own)}
                title={a === 'APPROVE' && own ? '작성자는 본인이 작성한 정책을 승인할 수 없습니다.' : undefined}
                onClick={() => setAction(a as PolicyAction)}
              >
                {actionLabels[a as PolicyAction]}
              </Button>
            ))}
        </div>
      </div>
      {own && policy.status === 'REVIEW' && ['GA_ADMIN', 'SYSTEM_ADMIN'].includes(user?.roleCode ?? '') && (
        <p className="policy-notice policy-notice-warning">
          작성자는 본인이 작성한 정책을 승인할 수 없습니다. 다른 승인자의 검토가 필요합니다.
        </p>
      )}
      {!detail.allowedActions && <p className="policy-note">작업 권한 정보가 제공되지 않아 조회만 가능합니다.</p>}
      <ReferenceTabs
        tabs={panels}
        active={panel}
        onChange={setPanel}
        label="정책 관리 상세"
        panelClassName="policy-management-panel"
      >
        {panel === 'rules' &&
          (draft ? <DraftRuleSummary draft={draft} detail={detail} /> : <ReadOnlyRuleSummary detail={detail} />)}
        {panel === 'sources' && (
          <PolicySources sources={sources} refs={refs} offerings={detail.productOfferings ?? []} />
        )}
        {panel === 'history' && (
          <>
            <div className="policy-section-heading">
              <h3>승인 이력</h3>
              <span>{detail.approvalActions?.length ?? 0}건</span>
            </div>
            {!detail.approvalActions?.length ? (
              <div className="policy-editor-empty">
                <strong>기록된 승인 이력이 없습니다.</strong>
                <p>제출·승인·반려·활성화 이력이 시간순으로 표시됩니다.</p>
              </div>
            ) : (
              <ol className="policy-timeline">
                {detail.approvalActions.map((entry, index) => (
                  <li key={index}>
                    <span className="policy-timeline-dot" />
                    <div>
                      <header>
                        <strong>{historyLabels[entry.actionType] ?? entry.actionType}</strong>
                        {entry.status && (
                          <span>
                            {entry.previousStatus ? statusLabels[entry.previousStatus] : '신규'} →{' '}
                            {statusLabels[entry.status] ?? entry.status}
                          </span>
                        )}
                        <time>{dateTime(entry.actedAt)}</time>
                      </header>
                      <p>{entry.reason}</p>
                      <small>
                        {entry.actor}
                        {entry.evidenceRefs?.length ? ` · ${entry.evidenceRefs.join(', ')}` : ''}
                      </small>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </>
        )}
      </ReferenceTabs>
      {editing && draft && (
        <PolicyDraftEditor
          initial={draft}
          policyId={policy.policyVersionId}
          onClose={() => setEditing(false)}
          onSaved={(id) => {
            setEditing(false)
            onSaved(id)
          }}
        />
      )}
      {action && (
        <PolicyActionDialog
          policy={policy}
          detail={detail}
          action={action}
          onClose={() => setAction(null)}
          onSaved={(id) => {
            setAction(null)
            onSaved(id)
          }}
        />
      )}
    </div>
  )
}
export function PolicySources({
  sources,
  refs,
  offerings = [],
}: {
  sources: SourceDetail[]
  refs: string[]
  offerings?: OfferingGovernance[]
}) {
  const missing = refs.filter((ref) => !sources.some((s) => s.ref === ref))
  return (
    <div className="policy-sources">
      <div className="policy-section-heading">
        <h3>정책 근거 자료</h3>
        <span>{sources.length + missing.length}건</span>
      </div>
      <p className="policy-notice">{sourceReferenceNotice}</p>
      {!sources.length && !missing.length && (
        <div className="policy-editor-empty">
          <strong>연결된 근거 자료가 없습니다.</strong>
          <p>정책에 연결한 근거의 제목·설명·링크를 확인할 수 있습니다.</p>
        </div>
      )}
      {sources.map((source) => (
        <article className="policy-source-card" key={source.ref}>
          <span className="material-symbols-rounded" aria-hidden="true">
            description
          </span>
          <div>
            <StatusBadge tone={source.ref.startsWith('REG-') ? 'info' : 'neutral'}>{source.ref}</StatusBadge>
            <SourceStatusTags tags={source.statusTags} />
            <h4>{source.title}</h4>
            <p>{source.explanation || '설명 자료 없음'}</p>
          </div>
          {safeSourceUrl(source.url) && (
            <a href={safeSourceUrl(source.url)!} target="_blank" rel="noopener noreferrer">
              원문 보기 ↗
            </a>
          )}
        </article>
      ))}
      {missing.map((ref) => (
        <article className="policy-source-card" key={ref}>
          <div>
            <StatusBadge>{ref}</StatusBadge>
            <SourceStatusTags />
            <p>제목·설명·링크 자료가 아직 제공되지 않았습니다.</p>
          </div>
        </article>
      ))}
      {offerings.length > 0 && <OfferingGovernanceList offerings={offerings} />}
    </div>
  )
}
/** FUN-016 상품위원회 메타데이터. 판매버전에 저장된 참조정보이며 법정 심의·의결을 대체하지 않는다. */
function OfferingGovernanceList({ offerings }: { offerings: OfferingGovernance[] }) {
  return (
    <section className="policy-offering-governance" aria-label="상품위원회 정보">
      <div className="policy-section-heading">
        <h3>상품위원회 정보</h3>
        <span>{offerings.length}건</span>
      </div>
      <dl className="policy-offering-list">
        {offerings.map((offering) => (
          <div key={offering.productOfferingId} className="policy-offering-row">
            <dt>
              {offering.productName} · {offering.offeringVersion}
            </dt>
            <dd>
              승인일 {offering.committeeApprovalDate ? date(offering.committeeApprovalDate) : '미등록'} · 승인문서{' '}
              {offering.committeeDocumentRef || '미등록'} · 기초서류 {offering.basicDocumentVersion} · 판매개시{' '}
              {date(offering.salesStartDate)}
              {offering.salesEndDate ? ` ~ ${date(offering.salesEndDate)}` : ''} · 판매 상태{' '}
              {governanceLabels[offering.governanceStatus ?? ''] ?? '미등록'}
            </dd>
          </div>
        ))}
      </dl>
    </section>
  )
}
const governanceLabels: Record<string, string> = {
  APPROVED: '판매',
  REVISED: '개정',
  SUSPENDED: '판매보류',
  STOPPED: '판매중지',
}
function SourceStatusTags({ tags }: { tags?: string[] }) {
  const labels = [...new Set(tags?.map((tag) => tag.trim()).filter(Boolean))]
  return (
    <div className="policy-source-status" aria-label="근거 상태">
      {labels.length ? (
        labels.map((tag) => <StatusBadge key={tag}>{tag}</StatusBadge>)
      ) : (
        <StatusBadge>상태 자료 없음</StatusBadge>
      )}
    </div>
  )
}
function ReadOnlyRuleSummary({ detail }: { detail: ManagedPolicyDetail }) {
  const commission = detail.commissionRules ?? []
  const caps = detail.capRuleSets ?? []
  const refunds = detail.refundRateTables ?? []
  if (!commission.length && !caps.length && !refunds.length)
    return (
      <div className="policy-editor-empty">
        <strong>조회된 설정 규칙이 없습니다.</strong>
        <p>이 정책에 수수료·한도·환급률 규칙 자료가 제공되지 않았습니다.</p>
      </div>
    )
  return (
    <div className="policy-rule-summary">
      {commission.length > 0 && (
        <section>
          <h3 className="policy-detail-title">수수료 규칙</h3>
          <CommissionRules rows={commission} />
        </section>
      )}
      {caps.length > 0 && (
        <section>
          <h3 className="policy-detail-title">1,200% 룰셋</h3>
          <CapRuleSets rows={caps} />
        </section>
      )}
      {refunds.length > 0 && (
        <section>
          <h3 className="policy-detail-title">예상 해약환급률표</h3>
          <RefundTables rows={refunds} />
        </section>
      )}
    </div>
  )
}
function DraftRuleSummary({ draft, detail }: { draft: DraftDocument; detail: ManagedPolicyDetail }) {
  return (
    <div className="policy-rule-summary">
      <div className="policy-section-heading">
        <h3>유형별 설정 규칙</h3>
        <span>총 {ruleCount(draft)}개</span>
      </div>
      {!ruleCount(draft) && (
        <div className="policy-editor-empty">
          <strong>등록된 설정 규칙이 없습니다.</strong>
          <p>초안 수정에서 정책 유형에 맞는 규칙을 작성하세요.</p>
        </div>
      )}
      {draft.commissionRules.length > 0 && (
        <DataTable
          caption="설정된 수수료 규칙"
          rows={draft.commissionRules.map((r, i) => ({ ...r, row: i + 1 }))}
          rowKey={(r) => String(r.row)}
          columns={[
            { key: 'row', label: '행', render: (r) => r.row },
            {
              key: 'stage',
              label: '지급단계',
              render: (r) => (r.paymentStage === 'INSURER_TO_GA' ? '보험사 → GA' : 'GA → 설계사'),
            },
            {
              key: 'product',
              label: '상품 · 판매버전',
              render: (r) =>
                r.productOfferingId ? (
                  <span>
                    {detail.commissionRules?.[r.row - 1]?.productName ?? '상품명 자료 없음'}
                    <small className="policy-cell-subtitle">판매버전 ID · {r.productOfferingId}</small>
                  </span>
                ) : (
                  '전체'
                ),
            },
            {
              key: 'item',
              label: '수수료 항목',
              render: (r) =>
                detail.commissionRules?.[r.row - 1]?.itemName ??
                (r.commissionItemId ? `항목 ID · ${r.commissionItemId}` : '자료 없음'),
            },
            { key: 'range', label: '회차', render: (r) => `${r.installmentFrom} ~ ${r.installmentTo}` },
            {
              key: 'type',
              label: '계산 방식',
              render: (r) => <StatusBadge tone="info">{r.calculationType}</StatusBadge>,
            },
            {
              key: 'value',
              label: '요율 / 고정금액',
              render: (r) => (r.calculationType === 'RATE' ? `${rate(r.ratePct)}%` : won(r.fixedAmount)),
            },
            { key: 'equal', label: '월 동일액', render: (r) => (r.equalMonthlyYn ? '예' : '아니오') },
          ]}
        />
      )}
      {draft.feeRegimeRules.map((rule, i) => (
        <section key={i} className="policy-regime-summary">
          <header>
            <StatusBadge tone="info">{rule.feeRegimeCode === 'FOUR_YEAR_2027' ? '4년 분급' : '7년 분급'}</StatusBadge>
            <h4>분급 룰셋 {i + 1}</h4>
            {rule.reviewRequiredYn && <StatusBadge tone="warning">검토 필요</StatusBadge>}
          </header>
          <dl className="policy-metadata">
            <div>
              <dt>판매 시작일 범위</dt>
              <dd>
                {date(rule.salesStartDateFrom)} ~ {rule.salesStartDateTo ? date(rule.salesStartDateTo) : '계속'}
              </dd>
            </div>
            <div>
              <dt>최대 회차</dt>
              <dd>{rule.maxInstallments === '' ? '설정 필요' : `${rule.maxInstallments}회차`}</dd>
            </div>
            <div>
              <dt>기초서류 버전</dt>
              <dd>{rule.basicDocumentVersion || '전체 버전'}</dd>
            </div>
            <div>
              <dt>기초서류 일자 범위</dt>
              <dd>
                {rule.basicDocumentDateFrom ? date(rule.basicDocumentDateFrom) : '시작 제한 없음'}
                {' ~ '}
                {rule.basicDocumentDateTo ? date(rule.basicDocumentDateTo) : '종료 제한 없음'}
              </dd>
            </div>
            <div>
              <dt>장기유지 구간</dt>
              <dd>
                {rule.longTermMaintenanceFrom === ''
                  ? rule.feeRegimeCode === 'FOUR_YEAR_2027'
                    ? '해당 없음'
                    : '설정 필요'
                  : `${rule.longTermMaintenanceFrom} ~ ${rule.longTermMaintenanceTo}회차`}
              </dd>
            </div>
            <div>
              <dt>근거</dt>
              <dd>{rule.sourceRefs.join(', ') || '자료 없음'}</dd>
            </div>
          </dl>
        </section>
      ))}
      {draft.parameters.map((parameter) => (
        <article className="policy-condition-card" key={parameter.key}>
          <header>
            <h4>{parameter.key}</h4>
            <StatusBadge tone="info">지원 조건</StatusBadge>
          </header>
          <strong>{parameter.value == null || parameter.value === '' ? '설정 필요' : String(parameter.value)}</strong>
          <p>{parameter.description}</p>
        </article>
      ))}
      {draft.clawbackRules.length > 0 && (
        <DataTable
          caption="환수 규칙"
          rows={draft.clawbackRules.map((r, i) => ({ ...r, row: i + 1 }))}
          rowKey={(r) => String(r.row)}
          columns={[
            { key: 'insurer', label: '보험사 ID', render: (r) => r.insurerId || '전체' },
            { key: 'product', label: '상품 판매버전 ID', render: (r) => r.productOfferingId || '전체' },
            { key: 'item', label: '항목 ID', render: (r) => r.commissionItemId || '전체' },
            { key: 'range', label: '경과월', render: (r) => `${r.elapsedMonthFrom} ~ ${r.elapsedMonthTo}` },
            {
              key: 'basis',
              label: '기준금액 코드',
              render: (r) => r.clawbackBasisCode || <StatusBadge tone="warning">설정 필요</StatusBadge>,
            },
            {
              key: 'rate',
              label: '환수율',
              render: (r) => (r.clawbackRatePct === '' ? '설정 필요' : `${rate(r.clawbackRatePct)}%`),
            },
          ]}
        />
      )}
    </div>
  )
}
function PolicyActionDialog({
  policy,
  detail,
  action,
  onClose,
  onSaved,
}: {
  policy: Policy
  detail: ManagedPolicyDetail
  action: PolicyAction
  onClose: () => void
  onSaved: (id: number) => void
}) {
  const preview = useContext(PolicyPreviewContext)
  const [reason, setReason] = useState('')
  const [evidenceRef, setEvidenceRef] = useState('')
  const [effectiveFrom, setFrom] = useState('')
  const [effectiveTo, setTo] = useState(policy.effectiveTo ?? '')
  const [error, setError] = useState('')
  const [dateError, setDateError] = useState('')
  const [busy, setBusy] = useState(false)
  const saving = useRef(false)
  const requestKey = usePolicyRequest()
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const dateRef = useRef<HTMLInputElement>(null)
  const newVersion = action === 'NEW_VERSION'
  const label = actionLabels[action]
  async function submit() {
    if (saving.current || !policy.policyVersionId) return
    setError('')
    setDateError('')
    if (newVersion && (!effectiveFrom || (effectiveTo && effectiveTo < effectiveFrom))) {
      setDateError('유효한 적용 기간을 입력하세요.')
      dateRef.current?.focus()
      return
    }
    // 복제 원본이 현재 ACTIVE일 필요는 없다. 승계 시작일 관계는 서버가 현재 ACTIVE와 비교한다.
    if (newVersion && effectiveFrom <= today()) {
      setDateError('새 적용일은 한국시간 기준 내일 이후로 지정하세요.')
      dateRef.current?.focus()
      return
    }
    if (!reason.trim()) {
      setError(`${action === 'REJECT' ? '반려' : '작업'} 사유를 입력하세요.`)
      reasonRef.current?.focus()
      return
    }
    saving.current = true
    setBusy(true)
    try {
      const dates = newVersion ? { effectiveFrom, effectiveTo } : undefined
      const context = {
        expectedStatus: policy.status,
        expectedUpdatedAt: detail.editDocument?.expectedUpdatedAt ?? detail.updatedAt,
        ...(action === 'APPROVE' && evidenceRef.trim() ? { evidenceRef: evidenceRef.trim() } : {}),
        ...dates,
      }
      const id = preview
        ? await preview.action(policy.policyVersionId, action, reason.trim(), dates)
        : (
            await performPolicyAction(
              policy.policyVersionId,
              action,
              reason.trim(),
              context,
              requestKey({ id: policy.policyVersionId, action, reason: reason.trim(), context }),
            )
          ).policyVersionId
      onSaved(id)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '처리하지 못했습니다. 다시 시도하세요.')
    } finally {
      saving.current = false
      setBusy(false)
    }
  }
  return (
    <Modal
      open
      title={`정책 ${label}`}
      className="policy-action-modal"
      onClose={() => {
        if (!saving.current) onClose()
      }}
      closeOnEscape={!busy}
      initialFocusRef={newVersion ? dateRef : reasonRef}
      footer={
        <>
          <Button variant="secondary" disabled={busy} onClick={onClose}>
            취소
          </Button>
          <Button loading={busy} onClick={() => void submit()}>
            {newVersion ? '새 초안 만들기' : `${label} 확인`}
          </Button>
        </>
      }
    >
      <p className="policy-section-description">
        {newVersion
          ? '기존 정책의 규칙과 근거를 복사합니다. 새 적용일 당일부터 체결한 계약에 변경 요율을 적용합니다. 그 이전 계약은 재계산할 때도 기존 버전과 요율을 유지합니다. 새 적용일은 내일 이후로 지정하며, 아직 적용 전인 미래 버전은 같은 적용일로 정정할 수 있습니다.'
          : action === 'REJECT'
            ? '반려하면 작성자가 초안을 다시 수정할 수 있습니다.'
            : `아래 정책의 정보와 작업 사유를 확인한 후 ${label}하세요.`}
      </p>
      <dl className="policy-confirm-summary">
        <div>
          <dt>정책코드</dt>
          <dd>{policy.policyCode}</dd>
        </div>
        <div>
          <dt>정책명</dt>
          <dd>{policy.policyName}</dd>
        </div>
        <div>
          <dt>버전 · 상태</dt>
          <dd>
            v{policy.versionNo} · {statusLabels[policy.status ?? '']}
          </dd>
        </div>
        <div>
          <dt>적용 기간</dt>
          <dd>
            {date(policy.effectiveFrom)} ~ {policy.effectiveTo ? date(policy.effectiveTo) : '계속'}
          </dd>
        </div>
        <div>
          <dt>규칙 행 수</dt>
          <dd>{detail.editDocument ? `${ruleCount(detail.editDocument)}개` : '자료 없음'}</dd>
        </div>
      </dl>
      {newVersion && (
        <div className="policy-form-grid policy-form-grid-two">
          <Field label="새 버전 적용 시작일" required error={dateError}>
            <input
              ref={dateRef}
              type="date"
              value={effectiveFrom}
              onChange={(e) => setFrom(e.target.value)}
              disabled={busy}
            />
          </Field>
          <Field label="새 버전 적용 종료일">
            <input type="date" value={effectiveTo} onChange={(e) => setTo(e.target.value)} disabled={busy} />
          </Field>
        </div>
      )}
      <Field
        label={action === 'REJECT' ? '반려 사유' : '작업 사유'}
        required
        error={error}
        helper={`${reason.length.toLocaleString()} / 2,000자`}
      >
        <textarea
          ref={reasonRef}
          value={reason}
          rows={4}
          maxLength={2000}
          disabled={busy}
          onChange={(e) => {
            setReason(e.target.value)
            setError('')
          }}
          placeholder="변경 또는 검토 사유를 작성하세요."
        />
      </Field>
      {action === 'APPROVE' && !preview && (
        <Field label="승인 근거 문서" helper="상품위원회 의결서 등 승인 근거가 있으면 문서 참조를 입력하세요.">
          <input
            value={evidenceRef}
            onChange={(event) => setEvidenceRef(event.target.value)}
            maxLength={500}
            disabled={busy}
          />
        </Field>
      )}
      {action === 'ACTIVATE' && (
        <p className="policy-notice policy-notice-warning">
          이 버전의 적용일 당일부터 체결한 계약에 새 기준을 적용합니다. 이전 계약은 기존 버전과 요율을 유지합니다.
          승계에 실패하면 기존 버전을 유지합니다.
        </p>
      )}
    </Modal>
  )
}
