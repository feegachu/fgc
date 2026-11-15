import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useOutletContext, useSearchParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { Button } from '../../components/Button'
import { ContextLink } from '../../components/ContextLink'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { KpiCard } from '../../components/KpiCard'
import { date, int, month as formatMonth, rate, today, won } from '../../lib/format'
import { nameWithCode } from '../../lib/screenText'
import { sortBinding } from '../../lib/tableSort'
import { useAgentOptions, useCommissionItems } from '../base/api'
import { policyCodeLabel } from '../policies/labels'
import { sourceOptions } from '../transaction/list-api'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { StatementResults } from './StatementResults'
import type { ScreenHelpStep } from '../assistant/guide/ScreenHelp'
import { StatementExplorer, StatementViewSwitch } from './StatementExplorer'
import {
  ENTRY_TYPE_OPTIONS,
  InvalidStatementAgentIdError,
  entryTypeLabel,
  filterParams,
  readFilters,
  statementDetailQuery,
  statementError,
  statementListQuery,
  statementOverviewQuery,
  STATEMENT_SORT_KEYS,
} from './api'
import type { EntryType, StatementBasis, StatementDetail, StatementEntry, StatementFilters, StatementSummary } from './api'
import './agent-statement.css'

const DOTS: Record<EntryType, string> = { PAYMENT: 'pay', HELD: 'held', ADJUSTMENT: 'adj', CLAWBACK: 'claw' }
const AMOUNT_KEYS = ['basisAmount', 'fixedAmount', 'originallyPaidAmount']
const PERCENT_KEYS = ['ratePct', 'appliedRatePct']
/** 서버가 근거(evidence)로 내려주는 원천 항목의 화면 이름. 목록에 없는 키는 그대로 보여 주지 않고 숨긴다. */
const EVIDENCE_LABELS: Record<string, string> = {
  sourceType: '자료 구분',
  attributionMethod: '귀속 방식',
  settlementMonth: '정산월',
  paidOn: '지급일',
  cashflowType: '지급/차감',
  note: '비고',
  scheduleVersionNo: '스케줄 버전',
  paymentConditionCode: '지급 조건',
  basisCode: '계산 기준',
  basisAmount: '기준 금액',
  calculationType: '계산 방식',
  ratePct: '적용률(%)',
  fixedAmount: '고정 금액',
  caseNo: '환수 관리번호',
  originallyPaidAmount: '기지급 기준액',
  appliedRatePct: '적용 환수율(%)',
}

/** 근거 값 중 코드로 오는 항목의 화면 이름. 저장 코드는 화면에 쓰지 않는다(SRC-074). */
const EVIDENCE_VALUE_LABELS: Record<string, Record<string, string>> = {
  sourceType: Object.fromEntries(sourceOptions),
  cashflowType: { PAYMENT: '지급', DEDUCTION: '차감' },
}
const CODE_VALUE_KEYS = ['attributionMethod', 'paymentConditionCode', 'basisCode', 'calculationType']

export default function AgentStatementPage() {
  const { month, user, revised, helpVersion } = useOutletContext<ShellContext>()
  const isAgent = user.roleCode === 'AGENT'
  const enhanced = !isAgent && helpVersion !== 'before'
  const [view, setView] = useState<'tree' | 'table'>('tree')
  const currentView = useRef(view)
  useEffect(() => { currentView.current = view }, [view])
  const helpSteps = useMemo<ScreenHelpStep[]>(() => [
    {
      targetId: 'statement.filters',
      title: '조회 조건',
      instruction: isAgent ? '기준월과 계약번호로 본인의 명세 조회 범위를 정합니다.' : '기준월과 설계사, 계약번호로 조회 범위를 정합니다.',
      interaction: '조건을 선택하고 조회를 누르세요.',
    },
    {
      targetId: 'features.agent-statement.agent-statement-page.summary',
      attribute: 'data-guide-feature',
      title: '지급, 보류, 조정 및 환수',
      instruction: isAgent ? '본인의 지급, 보류, 조정 및 환수 합계를 확인합니다.' : '선택한 지사 및 설계사의 합계를 확인합니다.',
      interaction: isAgent ? '유형별 금액을 확인하세요.' : '지사는 전체 합계, 설계사는 개인 통계입니다.',
    },
    {
      targetId: 'statement.results',
      title: isAgent ? '본인 계약별 명세' : '지점과 설계사 명세',
      instruction: isAgent ? '계약 및 수수료 항목별 금액과 계산 근거를 확인합니다.' : '트리에서 지점 및 설계사별 내역을 확인합니다.',
      interaction: '표에서는 계약별 금액과 근거를 볼 수 있습니다.',
      onEnter: () => {
        if (isAgent) return
        const original = currentView.current
        const timer = window.setInterval(() => setView(previous => previous === 'tree' ? 'table' : 'tree'), 3000)
        return () => { clearInterval(timer); setView(original) }
      },
    },
  ].map(step => ({ ...step, group: '설계사 수수료 명세', details: [], concise: true } as ScreenHelpStep)), [isAgent])
  const defaultMonth = month || today().slice(0, 7)
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params, defaultMonth)
  const branchFilterKey = JSON.stringify([filters.month, filters.contractNo, filters.commissionItemId, filters.entryType])
  const [branchSelection, setBranchSelection] = useState<{ id: number; filterKey: string; groupId?: string; agents?: number[]; label?: string } | null>(null)
  // 본인 화면에는 설계사 선택이 없다. 내부 조회에서만 agentId 를 고른다.
  const overview = useQuery(statementOverviewQuery(filters, enhanced))
  const overviewRows = overview.isError ? [] : overview.data ?? []
  const selectedBranchId = enhanced && view === 'tree' && branchSelection?.filterKey === branchFilterKey
    && (overview.isPending || overview.isError || overviewRows.some(row => row.branchId === branchSelection.id)) ? branchSelection.id : null
  const branchRows = overviewRows.filter(row => branchSelection?.agents ? branchSelection.agents.includes(row.agentId) : row.branchId === selectedBranchId)
  const branchSummary: StatementSummary | undefined = selectedBranchId === null ? undefined : {
    payment: { amount: branchRows.reduce((sum, row) => sum + row.paymentAmount, 0), count: 0 },
    held: { amount: branchRows.reduce((sum, row) => sum + row.heldAmount, 0), count: 0 },
    adjustment: { amount: branchRows.reduce((sum, row) => sum + row.adjustmentAmount, 0), count: 0 },
    clawback: { amount: branchRows.reduce((sum, row) => sum + row.clawbackAmount, 0), count: 0 },
  }
  const agentId = isAgent ? null : filters.agentId || (enhanced && overviewRows[0] ? String(overviewRows[0].agentId) : null)
  const entryKey = params.get('entryKey') ?? ''
  const ready = isAgent || agentId !== null
  // 조회 조건이 같으면 입력 중인 필터를 지우지 않도록, entryKey 를 뺀 정규화한 조건을 key 로 쓴다.
  const filterKey = filterParams(filters, !isAgent).toString()
  const [resetCount, setResetCount] = useState(0)
  const list = useQuery(statementListQuery(filters, agentId, ready))
  const detail = useQuery(statementDetailQuery(entryKey, agentId, ready && (!enhanced || view === 'table')))
  const asOf = `${filters.month || defaultMonth}-01`
  const agents = useAgentOptions(asOf, '', !isAgent)
  const items = useCommissionItems(asOf)

  function apply(next: StatementFilters, keepEntry = false) {
    setBranchSelection(null)
    const query = filterParams(next, !isAgent)
    if (keepEntry && entryKey) query.set('entryKey', entryKey)
    setParams(query, { replace: true })
  }
  function select(key: string | null) {
    const query = filterParams(filters, !isAgent)
    if (key) query.set('entryKey', key)
    setParams(query, { replace: true })
  }

  const rows = list.isError ? [] : (list.data?.items.content ?? [])
  const total = list.isError ? 0 : (list.data?.items.totalElements ?? 0)
  const columns: TableColumn<StatementEntry>[] = [
    {
      key: 'contractNo',
      label: '계약번호',
      sortable: true,
      width: '11rem',
      render: (row) => (row.contractNo ? row.contractNo : <span className="ast-muted">연결 계약 없음</span>),
    },
    {
      key: 'commissionItemCode',
      label: '수수료 항목',
      sortable: true,
      width: '10rem',
      render: (row) => row.commissionItemName || '-',
    },
    {
      key: 'installmentNo',
      label: '회차',
      sortable: true,
      width: '4.5rem',
      render: (row) => (row.installmentNo == null ? '-' : `${row.installmentNo}회`),
    },
    { key: 'basisDate', label: '기준일', sortable: true, width: '6.5rem', render: (row) => date(row.basisDate) },
    {
      key: 'entryType',
      label: '내역 구분',
      sortable: true,
      width: '6rem',
      align: 'center',
      render: (row) => <TypeLabel type={row.entryType} />,
    },
    { key: 'statusCode', label: '상태', sortable: true, width: '8rem', align: 'center', render: (row) => row.statusLabel || row.statusCode },
    {
      key: 'amount',
      label: '금액',
      sortable: true,
      width: '8rem',
      align: 'number',
      render: (row) =>
        row.amount == null || row.dataStatus === 'MISSING' ? <span className="ast-muted">금액 미확인</span> : won(row.amount),
    },
    {
      key: 'evidence',
      label: '근거',
      width: '6rem',
      align: 'center',
      render: (row) => (
        <Button data-guide-feature="features.agent-statement.agent-statement-page.row-evidence-button" variant="secondary" onClick={() => select(row.entryKey)} aria-label={`${row.contractNo ?? row.commissionItemName} 근거 보기`}>
          근거 보기
        </Button>
      ),
    },
  ]

  return (
    <div className="ast-page">
      <header className="page-header">
        <div className="page-header-copy">
          <h1 className="page-title">설계사 수수료 명세</h1>
          {!isAgent && !revised && (
            <p className="ast-lede">설계사를 선택해 본인 귀속 기준 명세를 조회합니다.</p>
          )}
        </div>
        <div className="page-header-actions">
        {(!isAgent || revised) && <ScreenHelp screenId="RPT-W02" walkthrough={(enhanced || isAgent && revised) && helpVersion === 'after' ? helpSteps : undefined} />}
        </div>
      </header>
      <StatementFiltersBar
        key={`${filterKey}:${resetCount}:${enhanced ? agentId : ''}:${selectedBranchId}`}
        initial={enhanced ? { ...filters, agentId: selectedBranchId !== null ? '' : agentId ?? '' } : filters}
        isAgent={isAgent}
        agents={(agents.data ?? []).map((row) => [String(row.agentId), nameWithCode(row.agentName, row.agentCode)])}
        items={(items.data ?? []).map((row) => [String(row.commissionItemId), row.itemName ?? '-'])}
        onSearch={(next) => apply({ ...next, contractNo: next.contractNo.trim(), page: 1 })}
        onReset={() => {
          setResetCount((count) => count + 1)
          apply({ ...readFilters(new URLSearchParams(), defaultMonth), agentId: enhanced ? '' : filters.agentId })
        }}
      />
      {(ready || selectedBranchId !== null) && <SummaryCards summary={branchSummary ?? list.data?.summary} basis={list.data?.basis} loading={selectedBranchId !== null ? overview.isPending : list.isPending} failed={selectedBranchId !== null ? overview.isError : list.isError} dashboardStyle={enhanced || isAgent && revised} showNotes={isAgent} scopeLabel={selectedBranchId !== null ? `${branchSelection?.label ?? branchRows[0]?.branchName ?? '지사'} 전체 합계` : undefined} />}
      {enhanced && view === 'tree' && (overview.isPending || overview.isError ? <div className="surface ast-state" role={overview.isError ? 'alert' : 'status'}>
        {overview.isError ? statementError(overview.error) : '지점과 설계사 명세를 불러오는 중입니다.'}
        {overview.isError && <Button data-guide-feature="features.agent-statement-page.button-1" variant="secondary" onClick={() => void overview.refetch()}>다시 시도</Button>}
      </div> : overviewRows.length ? <StatementExplorer key={`${filters.month}:${filters.contractNo}:${filters.commissionItemId}:${filters.entryType}`} viewControl={<StatementViewSwitch value={view} onChange={setView} />} rows={overviewRows} selectedId={selectedBranchId !== null ? null : agentId} selectedBranchId={selectedBranchId} onSelectGroup={(groupId, members, label) => { if (members[0]) setBranchSelection({ id: members[0].branchId, filterKey: branchFilterKey, groupId, agents: members.map(row => row.agentId), label }) }} onSelectBranch={id => setBranchSelection({ id, filterKey: branchFilterKey })} onSelect={id => apply({ ...filters, agentId: id, page: 1 })} /> : <p className="surface ast-state" role="status">조회 조건에 해당하는 설계사가 없습니다.</p>)}
      {enhanced && view === 'table' && !ready && <div className="surface surface-header"><span>조회할 명세가 없습니다.</span><StatementViewSwitch value={view} onChange={setView} /></div>}
      {!ready && !enhanced ? (
        <p className="ast-state" role="status">
          설계사를 고른 뒤 조회를 누르세요.
        </p>
      ) : ready && (!enhanced || view === 'table') ? (
        <>
          <StatementResults className="ast-list-panel" titleId="ast-list-title" busy={list.isFetching}>
            <header className="surface-header">
              <h2 className="surface-title" id="ast-list-title">
                명세 내역
              </h2>
              <div className="ast-list-tools">
                <span className="ast-count"><span className="tabular-nums">{int(total)}</span>건 20건씩</span>
                {enhanced && view === 'table' && <StatementViewSwitch value={view} onChange={setView} />}
              </div>
            </header>
            <DataTable
              className="ast-table"
              caption="검색 조건에 해당하는 설계사 수수료 명세"
              columns={columns}
              {...sortBinding(filters.sort, STATEMENT_SORT_KEYS, (sort) => apply({ ...filters, sort, page: 1 }, true))}
              rows={rows}
              rowKey={(row) => row.entryKey}
              selectedKeys={entryKey ? [entryKey] : []}
              emptyMessage={
                <div className="ast-state" role={list.isError ? 'alert' : 'status'}>
                  <strong>
                    {list.isError ? '명세를 불러오지 못했습니다' : list.isPending ? '불러오는 중' : '해당 조건의 명세가 없습니다'}
                  </strong>
                  <span>
                    {list.isError
                      ? statementError(list.error)
                      : list.isPending
                        ? '수수료 명세를 불러오는 중입니다.'
                        : '기준월이나 조회 조건을 바꿔 다시 조회해 보세요.'}
                  </span>
                  {list.isError && !(list.error instanceof InvalidStatementAgentIdError) && (
                    <Button data-guide-feature="features.agent-statement.agent-statement-page.list-retry-button" variant="secondary" onClick={() => void list.refetch()}>
                      다시 시도
                    </Button>
                  )}
                </div>
              }
            />
            {total > 0 && (
              <ListPagination
                label="수수료 명세 페이지 선택"
                page={list.data?.items.page ?? filters.page}
                totalPages={Math.max(1, list.data?.items.totalPages ?? 1)}
                onPageChange={(next) => apply({ ...filters, page: next }, true)}
              />
            )}
          </StatementResults>
          {entryKey && (
            <DetailPanel
              detail={detail.data}
              loading={detail.isPending}
              error={detail.isError ? statementError(detail.error) : null}
              onClose={() => select(null)}
            />
          )}
        </>
      ) : null}
    </div>
  )
}

/** 채운 배지 대신 작은 점과 글자로 내역 구분을 조용히 표시한다. */
function TypeLabel({ type }: { type: EntryType }) {
  return (
    <span className="ast-type">
      <span className={`ast-dot ast-dot-${DOTS[type]}`} aria-hidden="true" />
      {entryTypeLabel(type)}
    </span>
  )
}

function SummaryCards({
  summary,
  basis,
  loading,
  failed,
  dashboardStyle = false,
  showNotes = false,
  scopeLabel,
}: {
  summary?: StatementSummary
  basis?: StatementBasis
  loading: boolean
  failed: boolean
  dashboardStyle?: boolean
  showNotes?: boolean
  scopeLabel?: string
}) {
  const cards: [EntryType, keyof StatementSummary, string, string][] = [
    ['PAYMENT', 'payment', '지급', '확정된 본인 귀속 지급'],
    ['HELD', 'held', '보류', '아직 확정되지 않은 지급 보류'],
    ['ADJUSTMENT', 'adjustment', '조정', '정정 및 역분개로 반영된 금액(차감은 음수)'],
    ['CLAWBACK', 'clawback', '환수', '환수 계산액이며 실제 회수 완료액이 아닙니다'],
  ]
  if (dashboardStyle) {
    return (
      <section data-guide-feature="features.agent-statement.agent-statement-page.summary" className="kpi-grid kpi-group ast-dashboard-summary" aria-label="유형별 요약">
        {cards.map(([type, key, label, note]) => {
          const bucket = failed ? undefined : summary?.[key]
          return <KpiCard guideId="features.agent-statement-page.kpicard-2"
            key={type}

            label={label}
            value={bucket ? bucket.amount : failed ? '조회 실패' : loading ? '…' : '-'}
            unit={bucket ? '원' : undefined}
            format={int}
            zero={bucket?.amount === 0}
            footer={<><span className="kpi-comparison" title={scopeLabel ?? note}>
              {scopeLabel ?? basis?.dateBasis[type] ?? ''}
            </span>{showNotes && <span className="ast-kpi-note">{note}</span>}</>}
          />
        })}
      </section>
    )
  }
  return (
    <section data-guide-feature="features.agent-statement.agent-statement-page.legacy-summary" className="ast-summary" aria-label="유형별 요약">
      {cards.map(([type, key, label, note]) => {
        const bucket = summary?.[key]
        return (
          <article className="ast-card" key={type} aria-label={`${label} 요약`}>
            <span className="ast-card-label">
              <TypeLabel type={type} />
            </span>
            <strong className="ast-card-amount tabular-nums">
              {bucket ? won(bucket.amount) : failed ? '조회 실패' : loading ? '…' : '-'}
            </strong>
            <span className="ast-card-note">
              {basis?.dateBasis[type] ?? ''}
            </span>
            {/* 해석 안내는 호버 툴팁이 아니라 화면에 보이는 문구로 둔다(터치·키보드 사용자). */}
            <span className="ast-card-help">{note}</span>
          </article>
        )
      })}
    </section>
  )
}

function DetailPanel({
  detail,
  loading,
  error,
  onClose,
}: {
  detail?: StatementDetail
  loading: boolean
  error: string | null
  onClose: () => void
}) {
  const entry = detail?.entry
  useEffect(() => {
    const close = (event: KeyboardEvent) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [onClose])
  const evidence = Object.entries(detail?.evidence ?? {}).filter(
    ([key, value]) => value != null && value !== '' && EVIDENCE_LABELS[key],
  )
  return (
    <section data-guide-feature="features.agent-statement.agent-statement-page.detail-panel" className="surface ast-detail" aria-labelledby="ast-detail-title" aria-live="polite">
      <header className="surface-header">
        <h2 className="surface-title" id="ast-detail-title">
          명세 근거
        </h2>
        <Button data-guide-feature="features.agent-statement.agent-statement-page.detail-close-button" variant="secondary" onClick={onClose}>
          닫기
        </Button>
      </header>
      <div className="surface-body">
        {error ? (
          <p className="field-error" role="alert">
            {error}
          </p>
        ) : loading || !entry ? (
          <p className="ast-state" role="status">
            불러오는 중입니다.
          </p>
        ) : (
          <>
            <div className="ast-hero">
              <TypeLabel type={entry.entryType} />
              <strong className="ast-hero-amount tabular-nums">
                {entry.amount == null || entry.dataStatus === 'MISSING' ? '금액 미확인' : won(entry.amount)}
              </strong>
              <span className="ast-muted">
                {entry.statusLabel}  {date(entry.basisDate)}
              </span>
            </div>
            <section data-guide-feature="features.agent-statement.agent-statement-page.detail-basic" className="ast-group" aria-label="기본 정보">
              <h3 className="ast-group-title">기본 정보</h3>
              <dl className="ast-dl">
                <Row label="계약번호" value={entry.contractNo ?? '연결 계약 없음'} />
                <Row label="수수료 항목" value={entry.commissionItemName} />
                <Row label="회차" value={entry.installmentNo == null ? '-' : `${entry.installmentNo}회`} />
                <Row label="기준 설명" value={detail?.basis.dateBasis[entry.entryType]} />
                <Row label="적용 정책" value={entry.policyName ? `${entry.policyName} v${entry.policyVersionNo}` : '-'} />
              </dl>
            </section>
            {evidence.length > 0 && (
              <section data-guide-feature="features.agent-statement.agent-statement-page.detail-evidence" className="ast-group" aria-label="계산 근거">
                <h3 className="ast-group-title">계산 근거</h3>
                <dl className="ast-dl">
                  {evidence.map(([key, value]) => (
                    <Row key={key} label={EVIDENCE_LABELS[key]} value={evidenceValue(key, value as string | number)} />
                  ))}
                </dl>
              </section>
            )}
          </>
        )}
        {entry?.contractId != null && (
          <ContextLink data-guide-feature="features.agent-statement.agent-statement-page.contract-link" className="button button-secondary ast-link" to={`/contracts/${entry.contractId}`}>
            계약 상세 보기
          </ContextLink>
        )}
      </div>
    </section>
  )
}

function Row({ label, value }: { label: string; value?: string | null }) {
  return (
    <div className="ast-dl-row">
      <dt>{label}</dt>
      <dd>{value ?? '-'}</dd>
    </div>
  )
}

function evidenceValue(key: string, value: string | number) {
  if (key === 'settlementMonth') return formatMonth(value)
  if (AMOUNT_KEYS.includes(key)) return won(value)
  if (PERCENT_KEYS.includes(key)) return `${rate(value)}%`
  if (CODE_VALUE_KEYS.includes(key)) return policyCodeLabel(String(value))
  return EVIDENCE_VALUE_LABELS[key]?.[value] ?? String(value)
}

function StatementFiltersBar({
  initial,
  isAgent,
  agents,
  items,
  onSearch,
  onReset,
}: {
  initial: StatementFilters
  isAgent: boolean
  agents: (readonly [string, string])[]
  items: (readonly [string, string])[]
  onSearch: (filters: StatementFilters) => void
  onReset: () => void
}) {
  // 입력 중인 값은 이 안에서만 들고 있다가 [조회]에서 URL 로 올린다. URL 이 바뀌면 부모가 key 로 새로 만든다.
  const [draft, setDraft] = useState(initial)
  const set = (key: keyof StatementFilters, value: string) => setDraft((previous) => ({ ...previous, [key]: value }))
  const options = (entries: readonly (readonly [string, string])[], selected: string) => (
    <>
      <option value="">전체</option>
      {selected && !entries.some(([id]) => id === selected) && <option value={selected}>{selected}</option>}
      {entries.map(([id, label]) => (
        <option key={id} value={id}>
          {label}
        </option>
      ))}
    </>
  )
  return (
    <FilterBar data-guide-target="statement.filters"
      className="ast-filter"
      wrapFields={false}
      icons
      resetFirst
      onSubmit={() => draft.month && onSearch(draft)}
      onReset={onReset}
    >
      <div className="filter-fields ast-filter-fields">
        <Field label="기준월" required className="filter-field">
          <input data-guide-feature="features.agent-statement.agent-statement-page.month-input" id="ast-month" type="month" required value={draft.month} onChange={(event) => set('month', event.target.value)} />
        </Field>
        {!isAgent && (
          <Field label="설계사" required className="filter-field">
            <select data-guide-feature="features.agent-statement.agent-statement-page.agent-select" id="ast-agent" value={draft.agentId} onChange={(event) => set('agentId', event.target.value)}>
              {options(agents, draft.agentId)}
            </select>
          </Field>
        )}
        <Field label="계약번호" className="filter-field">
          <input data-guide-feature="features.agent-statement.agent-statement-page.contract-input"
            id="ast-contract"
            type="search"
            placeholder="계약번호 입력"
            autoComplete="off"
            value={draft.contractNo}
            onChange={(event) => set('contractNo', event.target.value)}
          />
        </Field>
        <Field label="수수료 항목" className="filter-field">
          <select data-guide-feature="features.agent-statement.agent-statement-page.item-select" id="ast-item" value={draft.commissionItemId} onChange={(event) => set('commissionItemId', event.target.value)}>
            {options(items, draft.commissionItemId)}
          </select>
        </Field>
        <Field label="내역 구분" className="filter-field">
          <select data-guide-feature="features.agent-statement.agent-statement-page.type-select" id="ast-type" value={draft.entryType} onChange={(event) => set('entryType', event.target.value)}>
            {options(ENTRY_TYPE_OPTIONS, draft.entryType)}
          </select>
        </Field>
      </div>
    </FilterBar>
  )
}
