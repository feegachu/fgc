import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useOutletContext, useSearchParams } from 'react-router'
import type { ShellContext } from '../../app/shell/AppShell'
import { AnimatedNumber } from '../../components/AnimatedNumber'
import { Button } from '../../components/Button'
import { ContextLink } from '../../components/ContextLink'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { ListPagination } from '../../components/ListPagination'
import { date, int, rate, today, won } from '../../lib/format'
import { sortBinding } from '../../lib/tableSort'
import { useAgentOptions, useCommissionItems } from '../base/api'
import {
  ENTRY_TYPE_OPTIONS,
  entryTypeLabel,
  filterParams,
  readFilters,
  statementDetailQuery,
  statementError,
  statementListQuery,
  STATEMENT_SORT_KEYS,
} from './api'
import type { EntryType, StatementBasis, StatementDetail, StatementEntry, StatementFilters, StatementSummary } from './api'
import './agent-statement.css'

const DOTS: Record<EntryType, string> = { PAYMENT: 'pay', HELD: 'held', ADJUSTMENT: 'adj', CLAWBACK: 'claw' }
const AMOUNT_KEYS = ['basisAmount', 'fixedAmount', 'originallyPaidAmount']
const PERCENT_KEYS = ['ratePct', 'appliedRatePct']
/** 서버가 근거(evidence)로 내려주는 원천 항목의 화면 이름. 목록에 없는 키는 그대로 보여 주지 않고 숨긴다. */
const EVIDENCE_LABELS: Record<string, string> = {
  commissionTransactionId: '지급 건 ID',
  sourceType: '원천 유형',
  attributionMethod: '귀속 방식',
  settlementMonth: '정산월',
  paidOn: '지급일',
  cashflowType: '흐름',
  note: '비고',
  replacesPaymentId: '대체한 지급 건 ID',
  replacedByPaymentId: '대체된 지급 건 ID',
  scheduleHeaderId: '스케줄 ID',
  scheduleVersionNo: '스케줄 버전',
  paymentConditionCode: '지급 조건',
  basisCode: '기준 코드',
  basisAmount: '기준 금액',
  calculationType: '계산 방식',
  ratePct: '적용률(%)',
  fixedAmount: '고정 금액',
  clawbackCaseId: '환수 케이스 ID',
  caseNo: '환수 케이스 번호',
  clawbackRuleId: '환수 규칙 ID',
  transactionAttributionId: '기지급 귀속행 ID',
  originallyPaidAmount: '기지급 기준액',
  appliedRatePct: '적용 환수율(%)',
}

export default function AgentStatementPage() {
  const { month, user } = useOutletContext<ShellContext>()
  const isAgent = user.roleCode === 'AGENT'
  const defaultMonth = month || today().slice(0, 7)
  const [params, setParams] = useSearchParams()
  const filters = readFilters(params, defaultMonth)
  // 본인 화면에는 설계사 선택이 없다. 내부 조회에서만 agentId 를 고른다.
  const agentId = isAgent ? null : filters.agentId || null
  const entryKey = params.get('entryKey') ?? ''
  const ready = isAgent || agentId !== null
  const list = useQuery(statementListQuery(filters, agentId, ready))
  const detail = useQuery(statementDetailQuery(entryKey, agentId))
  const asOf = `${filters.month || defaultMonth}-01`
  const agents = useAgentOptions(asOf, '', !isAgent)
  const items = useCommissionItems(asOf)

  function apply(next: StatementFilters, keepEntry = false) {
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
      align: 'center',
      render: (row) => <span title={row.commissionItemCode}>{row.commissionItemName || row.commissionItemCode}</span>,
    },
    {
      key: 'installmentNo',
      label: '회차',
      sortable: true,
      width: '4.5rem',
      align: 'center',
      render: (row) => (row.installmentNo == null ? '-' : `${row.installmentNo}회`),
    },
    { key: 'basisDate', label: '기준일', sortable: true, width: '6.5rem', align: 'center', render: (row) => date(row.basisDate) },
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
      align: 'center',
      render: (row) =>
        row.amount == null || row.dataStatus === 'MISSING' ? <span className="ast-muted">자료 없음</span> : won(row.amount),
    },
    {
      key: 'evidence',
      label: '근거',
      width: '6rem',
      align: 'center',
      render: (row) => (
        <Button variant="secondary" onClick={() => select(row.entryKey)} aria-label={`${row.contractNo ?? row.commissionItemName} 근거 보기`}>
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
          <p className="ast-lede">
            {isAgent ? '내 귀속 기준의 지급·보류·조정·환수 내역입니다.' : '설계사를 선택해 본인 귀속 기준 명세를 조회합니다.'}
          </p>
        </div>
      </header>
      <StatementFiltersBar
        key={params.toString()}
        initial={filters}
        isAgent={isAgent}
        agents={(agents.data ?? []).map((row) => [String(row.agentId), `${row.agentCode} · ${row.agentName}`])}
        items={(items.data ?? []).map((row) => [String(row.commissionItemId), `${row.itemCode} · ${row.itemName}`])}
        onSearch={(next) => apply({ ...next, contractNo: next.contractNo.trim(), page: 1 })}
        onReset={() => apply({ ...readFilters(new URLSearchParams(), defaultMonth), agentId: filters.agentId })}
      />
      {!ready ? (
        <p className="ast-state" role="status">
          조회할 설계사를 선택하세요.
        </p>
      ) : (
        <>
          <SummaryCards
            summary={list.data?.summary} basis={list.data?.basis} loading={list.isPending} failed={list.isError} />
          <section className="surface ast-list-panel" aria-labelledby="ast-list-title" aria-busy={list.isFetching}>
            <header className="surface-header">
              <h2 className="surface-title" id="ast-list-title">
                명세 내역
              </h2>
              <span className="ast-count">
                <span className="tabular-nums">{int(total)}</span>건 · 한 화면 20행
              </span>
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
                    {list.isError ? '명세를 불러오지 못했습니다' : list.isPending ? '불러오는 중' : '조회 결과가 없습니다'}
                  </strong>
                  <span>
                    {list.isError
                      ? statementError(list.error)
                      : list.isPending
                        ? '수수료 명세를 불러오는 중입니다.'
                        : '해당 기준월에 조건에 맞는 명세가 없습니다.'}
                  </span>
                  {list.isError && (
                    <Button variant="secondary" onClick={() => void list.refetch()}>
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
          </section>
          {entryKey && (
            <DetailPanel
              detail={detail.data}
              loading={detail.isPending}
              error={detail.isError ? statementError(detail.error) : null}
              onClose={() => select(null)}
            />
          )}
        </>
      )}
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
}: {
  summary?: StatementSummary
  basis?: StatementBasis
  loading: boolean
  failed: boolean
}) {
  const cards: [EntryType, keyof StatementSummary, string, string][] = [
    ['PAYMENT', 'payment', '지급', '확정된 본인 귀속 지급'],
    ['HELD', 'held', '보류', '아직 확정되지 않은 지급 보류'],
    ['ADJUSTMENT', 'adjustment', '조정', '정정·역분개로 반영된 금액(차감은 음수)'],
    ['CLAWBACK', 'clawback', '환수', '환수 계산액이며 실제 회수 완료액이 아닙니다'],
  ]
  return (
    <section className="ast-summary" aria-label="유형별 요약">
      {cards.map(([type, key, label, note]) => {
        const bucket = summary?.[key]
        return (
          <article className="ast-card" key={type} aria-label={`${label} 요약`}>
            <span className="ast-card-label">
              <TypeLabel type={type} />
            </span>
            <strong className="ast-card-amount tabular-nums">
              {bucket ? <AnimatedNumber value={bucket.amount} format={won} /> : failed ? '조회 실패' : loading ? '…' : '-'}
            </strong>
            <span className="ast-card-note" title={note}>
              {bucket ? `${int(bucket.count)}건` : ''}
              {basis?.dateBasis[type] ? ` · ${basis.dateBasis[type]}` : ''}
            </span>
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
    <section className="surface ast-detail" aria-labelledby="ast-detail-title" aria-live="polite">
      <header className="surface-header">
        <h2 className="surface-title" id="ast-detail-title">
          명세 근거
        </h2>
        <Button variant="secondary" onClick={onClose}>
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
                {entry.amount == null ? '자료 없음' : won(entry.amount)}
              </strong>
              <span className="ast-muted">
                {entry.statusLabel} · {date(entry.basisDate)}
              </span>
            </div>
            <section className="ast-group" aria-label="기본 정보">
              <h3 className="ast-group-title">기본 정보</h3>
              <dl className="ast-dl">
                <Row label="계약번호" value={entry.contractNo ?? '연결 계약 없음'} />
                <Row label="수수료 항목" value={`${entry.commissionItemName} (${entry.commissionItemCode})`} />
                <Row label="회차" value={entry.installmentNo == null ? '-' : `${entry.installmentNo}회`} />
                <Row label="기준 설명" value={detail?.basis.dateBasis[entry.entryType]} />
                <Row label="정책 버전 ID" value={entry.policyVersionId == null ? '-' : String(entry.policyVersionId)} />
                <Row label="원천" value={`${entry.source.entityType} #${entry.source.id}`} />
              </dl>
            </section>
            {evidence.length > 0 && (
              <section className="ast-group" aria-label="계산 근거">
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
          <ContextLink className="button button-secondary ast-link" to={`/contracts/${entry.contractId}`}>
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
  if (AMOUNT_KEYS.includes(key)) return won(value)
  if (PERCENT_KEYS.includes(key)) return `${rate(value)}%`
  return String(value)
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
    <FilterBar
      className="ast-filter"
      wrapFields={false}
      icons
      resetFirst
      onSubmit={() => draft.month && onSearch(draft)}
      onReset={onReset}
    >
      <div className="filter-fields ast-filter-fields">
        <Field label="기준월" required className="filter-field">
          <input id="ast-month" type="month" required value={draft.month} onChange={(event) => set('month', event.target.value)} />
        </Field>
        {!isAgent && (
          <Field label="설계사" required className="filter-field">
            <select id="ast-agent" value={draft.agentId} onChange={(event) => set('agentId', event.target.value)}>
              {options(agents, draft.agentId)}
            </select>
          </Field>
        )}
        <Field label="계약번호" className="filter-field">
          <input
            id="ast-contract"
            type="search"
            placeholder="계약번호 입력"
            autoComplete="off"
            value={draft.contractNo}
            onChange={(event) => set('contractNo', event.target.value)}
          />
        </Field>
        <Field label="수수료 항목" className="filter-field">
          <select id="ast-item" value={draft.commissionItemId} onChange={(event) => set('commissionItemId', event.target.value)}>
            {options(items, draft.commissionItemId)}
          </select>
        </Field>
        <Field label="내역 구분" className="filter-field">
          <select id="ast-type" value={draft.entryType} onChange={(event) => set('entryType', event.target.value)}>
            {options(ENTRY_TYPE_OPTIONS, draft.entryType)}
          </select>
        </Field>
      </div>
    </FilterBar>
  )
}
