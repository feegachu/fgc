import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { Button } from '../../components/Button'
import { ViewModeSwitch } from '../../components/HierarchyTree'
import { LedgerContractTree } from './LedgerContractTree'
import { KpiCard } from '../../components/KpiCard'
import { ListPagination } from '../../components/ListPagination'
import { DisabledButtonHint } from '../../components/DisabledButtonHint'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { useWorkspaceStore } from '../../stores/workspace'
import { date, errorText, int, won } from '../../lib/format'
import { journalDetailQueryOptions } from '../exception/api'
import { usePermission } from '../auth/useAuth'
import { criteriaFromParams, effectiveCriteria, positiveId } from './api'
import { explorerQuery, type LedgerEntry } from './explorer-api'
import { LedgerFilters, DetailSummary, JournalActionModal } from './LedgerPage'
import { LedgerOverview } from './LedgerOverview'
import { JOURNAL_TYPE, JOURNAL_STATUS } from './labels'
import './explorer.css'

export function LedgerExplorer() {
  const [params, setParams] = useSearchParams()
  const month = useWorkspaceStore(state => state.month)
  const criteria = criteriaFromParams(params)
  const searched = params.get('searched') === 'true'
  const scope = effectiveCriteria(criteria, searched, month)
  const all = params.get('ledgerView') === 'all'
  const contractId = params.get('contractId') === '0' ? '0' : positiveId(params.get('contractId'))
  const selected = positiveId(params.get('selected'))
  const evidenceId = positiveId(params.get('evidence')) || selected
  const selection = { contractId, all, contractPage: Number(positiveId(params.get('contractPage')) || 1), page: Number(positiveId(params.get('journalPage')) || 1) }
  const enabled = searched || !!scope.from || !!scope.to
  const query = useQuery({ ...explorerQuery(scope, selection, enabled), placeholderData: keepPreviousData })
  const single = useQuery(explorerQuery(scope, { ...selection, page: 1, journalId: selected }, enabled && !!selected))
  const canReverse = usePermission('canReverseJournal')
  const [action, setAction] = useState<'reverse' | 'correction' | null>(null)
  const data = query.data
  const contracts = data?.contracts.content ?? []
  const contract = contracts.find(row => row.contractId === data?.selectedContractId)
  const entries = selected ? single.data?.journals.content ?? [] : data?.journals.content ?? []
  const active = selected ? single.data : data
  const evidenceVisible = !!evidenceId && entries.some(entry => String(entry.journalHeaderId) === evidenceId)
  const detail = useQuery({ ...journalDetailQueryOptions(evidenceId), enabled: evidenceVisible })
  const totals = active?.totals
  const eligible = detail.data?.status === 'POSTED' && detail.data.journalType !== 'REVERSAL' && !detail.data.reversedByJournalHeaderId
  function update(values: Record<string, string>) {
    setParams(previous => { const next = new URLSearchParams(previous); Object.entries(values).forEach(([key, value]) => value === '' ? next.delete(key) : next.set(key, value)); return next })
  }
  return <div className="ledger-react-page list-page">
    <header className="page-header ledger-explorer-page-header"><div className="page-header-copy"><h1 className="page-title">검증원장</h1><p>기준 정산월 {month?.replace('-', '년 ')}월</p></div>
      <ScreenHelp screenId="LEDG-W01" /></header>
    <LedgerFilters key={JSON.stringify(criteria) + month + searched} criteria={criteria} month={month} searched={searched}
      onSearch={fields => update({ ...fields, searched: 'true', contractId: '', selected: '', evidence: '', contractPage: '', journalPage: '' })}
      onReset={() => setParams(month ? { month } : {})} />
    {query.error && <p role="alert">{errorText(query.error)} <Button data-guide-feature="ledger.explorer.retry" variant="secondary" onClick={() => void query.refetch()}>다시 조회</Button></p>}
    <div className={`ledger-explorer-layout${all ? ' is-all' : ''}`}>
      {!all && <LedgerOverview className="ledger-contracts" busy={query.isFetching}>
        <header className="surface-header"><h2 className="surface-title">계약 목록</h2><small>총 {int(data?.contracts.totalElements ?? 0)}건</small></header>
        <LedgerContractTree contracts={contracts} criteria={scope} contractPage={selection.contractPage} selectedContractId={data?.selectedContractId} selectedJournal={selected}
          onSelect={(id, journalId) => update({ contractId: String(id), selected: journalId == null ? '' : String(journalId), evidence: '', journalPage: '' })} />
        <ListPagination label="계약 목록 페이지" page={selection.contractPage} totalPages={data?.contracts.totalPages ?? 0}
          onPageChange={page => update({ contractPage: String(page), contractId: '', selected: '', evidence: '', journalPage: '' })} />
      </LedgerOverview>}
      <div className="ledger-explorer-right" aria-busy={query.isFetching || single.isFetching}>
      <section data-guide-feature="ledger.explorer.section-1" className="ledger-summary-group" aria-label="분개 합계">
        <header className="surface surface-header ledger-contract-summary"><h2 className="surface-title">{all ? '전체 분개' : contract?.contractNo ?? '분개 내역'}</h2>
          {contract?.agentName && <span>{contract.agentName} · {contract.insurerName}</span>}<small>{selected ? '선택한 분개' : `분개 ${int(totals?.journalCount ?? 0)}건`}</small>
          {selected && <Button data-guide-feature="ledger.explorer.all-entries" variant="ghost" onClick={() => update({ selected: '' })}>전체 내역</Button>}</header>
        <div className="ledger-balance-summary kpi-group">
          <KpiCard guideId="ledger.kpi.debit" label="차변 합계" value={totals?.debitTotal ?? '—'} unit="원" format={int} footer="연결된 분개 전체 금액 · 계약별 배분액 아님" />
          <KpiCard guideId="ledger.kpi.credit" label="대변 합계" value={totals?.creditTotal ?? '—'} unit="원" format={int} footer="연결된 분개 전체 금액 · 계약별 배분액 아님" />
          <KpiCard guideId="ledger.kpi.balance" label="차변 − 대변" value={totals ? totals.debitTotal - totals.creditTotal : '—'} unit="원" format={int}
            tone={!totals ? 'neutral' : totals.debitTotal === totals.creditTotal ? 'success' : 'error'} footer={!totals ? '조회 대기' : totals.debitTotal === totals.creditTotal ? '차변·대변 균형' : '차변·대변 불일치'} />
        </div>
      </section>
      <section data-guide-feature="ledger.explorer.section-2" className="surface ledger-journals" aria-label="계약별 분개 내역" data-guide-completed={params.has('evidence') && evidenceVisible}>
        <header className="surface-header"><h2 className="surface-title">분개 내역</h2><span>{int(active?.journals.totalElements ?? 0)}건 · {int(totals?.lineCount ?? 0)}행</span>
          <div className="ledger-journal-tools"><small>단위: 원</small>
            <ViewModeSwitch value={all ? 'table' : 'tree'} label="검증원장 보기 방식" treeLabel="계약별 보기" tableLabel="전체 분개 보기"
              onChange={view => update({ ledgerView: view === 'table' ? 'all' : '', selected: '', evidence: '', journalPage: '' })} />
          </div>
        </header>
        {(query.isFetching || selected && single.isFetching) && <p role="status" className="empty-state">분개 내역을 불러오고 있습니다.</p>}
        {single.error && selected && <p role="alert">{errorText(single.error)}</p>}
        {!query.isFetching && !entries.length && <p className="empty-state">조건에 맞는 분개가 없습니다.</p>}
        {!!entries.length && <JournalTable entries={entries} selected={evidenceId} onSelect={id => {
          update({ evidence: String(id) })
          document.querySelector('.ledger-journals')?.dispatchEvent(new Event('fgc-ledger-evidence-opened'))
        }} paginated={(active?.journals.totalPages ?? 0) > 1} />}
        {!selected && <ListPagination label="분개 내역 페이지" page={selection.page} totalPages={data?.journals.totalPages ?? 0} onPageChange={page => update({ journalPage: String(page), evidence: '' })} />}
      </section>
    {evidenceVisible && <section data-guide-feature="ledger.explorer.section-3" className="surface ledger-linked-evidence" aria-label="분개 상세">
      <header className="surface-header"><h2 className="surface-title">연결 근거</h2><span>{detail.data?.journalNo}</span><small>{detail.data ? detail.data.balanced ? '차변·대변 일치' : '차변·대변 불일치' : ''}</small></header>
      {detail.error && <p role="alert">{errorText(detail.error)}</p>}
      {detail.isPending && <p role="status">분개 근거를 불러오는 중입니다.</p>}
      {detail.data && <><DetailSummary detail={detail.data} /><div className="ledger-react-actions" data-guide-target="ledger.actions">
        <DisabledButtonHint disabled={!canReverse || !eligible} reason={!canReverse ? '역분개 권한이 없습니다.' : '기표됨 상태이며 아직 역분개하지 않은 분개만 처리할 수 있습니다.'}>
          <Button data-guide-feature="ledger.explorer.reverse" disabled={!canReverse || !eligible} onClick={() => setAction('reverse')}>역분개</Button>
        </DisabledButtonHint>
        <DisabledButtonHint disabled={!canReverse || !eligible} reason={!canReverse ? '정정 권한이 없습니다.' : '기표됨 상태이며 아직 역분개하지 않은 분개만 정정할 수 있습니다.'}>
          <Button data-guide-feature="ledger.explorer.correction" variant="secondary" disabled={!canReverse || !eligible} onClick={() => setAction('correction')}>역분개 + 재기표</Button>
        </DisabledButtonHint>
      </div></>}
    </section>}
      </div>
    </div>
    {action && detail.data && evidenceVisible && <JournalActionModal key={`${action}-${evidenceId}`} action={action} detail={detail.data} allowed={canReverse && !!eligible} onClose={() => setAction(null)} />}
  </div>
}

export function JournalTable({ entries, selected, onSelect, paginated }: { entries: LedgerEntry[]; selected: string; onSelect?: (id: number) => void; paginated: boolean }) {
  const total = (key: 'debitTotal' | 'creditTotal') => entries.reduce((sum, entry) => sum + entry[key], 0)
  return <div className="data-table-viewport"><table data-guide-feature="ledger.explorer.table-1" className="data-table ledger-entry-table"><caption className="visually-hidden">분개별 차변·대변 내역</caption>
    <thead><tr><th>분개일 · 분개번호</th><th>계정과목</th><th>적요</th><th className="text-right">차변</th><th className="text-right">대변</th></tr></thead>
    {entries.map(entry => <tbody key={entry.journalHeaderId} className={selected === String(entry.journalHeaderId) ? 'is-selected' : undefined}>
      {(entry.lines.length ? entry.lines : [null]).map((line, index) => <tr key={line?.lineNo ?? 'empty'}>
        {index === 0 && <td rowSpan={Math.max(1, entry.lines.length)} className="ledger-journal-cell"><span>{date(entry.journalDate)}</span>{onSelect ? <button data-guide-feature="ledger.explorer.button-3" type="button" aria-pressed={selected === String(entry.journalHeaderId)} onClick={() => onSelect(entry.journalHeaderId)}>{entry.journalNo}</button> : <strong>{entry.journalNo}</strong>}
          <div><span className="ledger-type-label">{JOURNAL_TYPE[entry.journalType as keyof typeof JOURNAL_TYPE] ?? entry.journalType}</span><span className={entry.debitTotal === entry.creditTotal ? 'is-balanced' : 'is-unbalanced'}>{entry.debitTotal === entry.creditTotal ? '✓ 균형' : '불일치'}</span><small>{JOURNAL_STATUS[entry.status as keyof typeof JOURNAL_STATUS] ?? entry.status}</small></div></td>}
        <td>{line?.accountName ?? '상세 행 없음'}</td><td>{line?.memo || entry.description || '—'}</td><td className="text-right">{line?.debitAmount ? int(line.debitAmount) : '—'}</td><td className="text-right">{line?.creditAmount ? int(line.creditAmount) : '—'}</td>
      </tr>)}
    </tbody>)}
    <tfoot><tr><th colSpan={3}>{paginated ? '현재 페이지 합계' : '조회 합계'}</th><td>{won(total('debitTotal'))}</td><td>{won(total('creditTotal'))}</td></tr></tfoot>
  </table></div>
}
