import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { date, errorText, int } from '../../lib/format'
import type { JournalCriteria } from './api'
import { explorerQuery, type LedgerContract } from './explorer-api'
import { JOURNAL_TYPE } from './labels'

const shortType = (type: string) => (JOURNAL_TYPE[type as keyof typeof JOURNAL_TYPE] ?? type).replace(/\(.*\)/, '')

export function LedgerContractTree({ contracts, criteria, contractPage, selectedContractId, selectedJournal, onSelect }: {
  contracts: LedgerContract[]; criteria: JournalCriteria; contractPage: number; selectedContractId?: number | null
  selectedJournal: string; onSelect: (contractId: number, journalId?: number) => void
}) {
  return <nav className="ledger-contract-tree" aria-label="계약별 분개 목록"><ul role="tree" aria-label="계약별 분개 목록">
    {contracts.map(contract => <ContractBranch key={`${JSON.stringify(criteria)}:${contract.contractId}`} contract={contract} criteria={criteria}
      contractPage={contractPage} active={contract.contractId === selectedContractId} selectedJournal={selectedJournal} onSelect={onSelect} />)}
  </ul></nav>
}

function ContractBranch({ contract, criteria, contractPage, active, selectedJournal, onSelect }: {
  contract: LedgerContract; criteria: JournalCriteria; contractPage: number; active: boolean
  selectedJournal: string; onSelect: (contractId: number, journalId?: number) => void
}) {
  const [open, setOpen] = useState(active)
  const [page, setPage] = useState(1)
  const query = useQuery(explorerQuery(criteria, { contractId: String(contract.contractId), all: false, contractPage, page }, open))
  return <li role="treeitem" aria-expanded={open} aria-selected={active && !selectedJournal}>
    <div className={`ledger-contract-node${active && !selectedJournal ? ' is-active' : ''}`}>
      <button data-guide-feature="ledger.contract-tree.button-1" type="button" className="ledger-contract-toggle" aria-label={`${contract.contractNo} ${open ? '접기' : '펼치기'}`} onClick={() => setOpen(!open)}>
        <span className="material-symbols-rounded" aria-hidden="true">{open ? 'expand_more' : 'chevron_right'}</span>
      </button>
      <button data-guide-feature="ledger.contract-tree.button-2" type="button" className="ledger-contract-select" title={`${contract.contractNo} ${contract.agentName ?? '계약 연결 없음'} 분개 ${contract.journalCount}건`} onClick={() => { setOpen(true); onSelect(contract.contractId) }}>
        <span className="ledger-contract-name"><span className="material-symbols-rounded" aria-hidden="true">{contract.contractId === 0 ? 'folder' : 'description'}</span><strong>{contract.contractNo}</strong></span>
        <span className="ledger-contract-meta"><span>{contract.contractId === 0 ? '계약 연결 없음' : contract.agentName ?? '설계사 미등록'}</span><small>분개 {int(contract.journalCount)}건</small></span>
      </button>
    </div>
    {open && <ul role="group" className="ledger-contract-entries">
      {query.isPending && <li role="none" className="ledger-tree-message">불러오는 중…</li>}
      {query.error && <li role="none" className="ledger-tree-message"><span role="alert">{errorText(query.error)}</span><button data-guide-feature="ledger.contract-tree.button-3" type="button" onClick={() => void query.refetch()}>다시 조회</button></li>}
      {query.data?.journals.content.map(entry => <li role="treeitem" key={entry.journalHeaderId} aria-selected={active && selectedJournal === String(entry.journalHeaderId)}>
        <button data-guide-feature="ledger.contract-tree.button-4" type="button" className="ledger-journal-select" aria-current={active && selectedJournal === String(entry.journalHeaderId) ? 'true' : undefined}
          title={entry.journalNo} onClick={() => onSelect(contract.contractId, entry.journalHeaderId)}>
          <span>{date(entry.journalDate).slice(5)}  {shortType(entry.journalType)}</span><small>#{entry.journalNo.split('-').at(-1)}</small>
        </button>
      </li>)}
      {(query.data?.journals.totalPages ?? 0) > 1 && <li role="none" className="ledger-tree-paging">
        <button data-guide-feature="ledger.contract-tree.button-5" type="button" aria-label={`${contract.contractNo} 이전 분개`} disabled={page === 1} onClick={() => setPage(page - 1)}>이전</button>
        <span>{page} / {query.data?.journals.totalPages}</span>
        <button data-guide-feature="ledger.contract-tree.button-6" type="button" aria-label={`${contract.contractNo} 다음 분개`} disabled={page >= (query.data?.journals.totalPages ?? 0)} onClick={() => setPage(page + 1)}>다음</button>
      </li>}
    </ul>}
  </li>
}
