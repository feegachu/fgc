import { invalidateJournalCorrectionQueries } from './correction-cache'
import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { Field } from '../../components/Field'
import { FilterBar } from '../../components/FilterBar'
import { Modal } from '../../components/Modal'
import { ListPagination } from '../../components/ListPagination'
import { StatusBadge } from '../../components/StatusBadge'
import { usePermission } from '../auth/useAuth'
import { journalAccountsQueryOptions, journalDetailQueryOptions } from '../exception/api'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { ApiError } from '../../lib/api/errors'
import { date, errorText, int, isNegative, won } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { useWorkspaceStore, withMonth } from '../../stores/workspace'
import {
  correctionTarget,
  criteriaFromParams,
  FILTER_KEYS,
  imbalanceQuery,
  journalSearchQuery,
  positiveId,
  requestCorrection,
  reverseJournal,
} from './api'
import type { Journal, JournalCriteria, JournalDetail, JournalLine } from './api'
import { accountDisplayName } from './accountName'
import { JOURNAL_ACCOUNT, JOURNAL_STATUS, JOURNAL_TYPE } from './labels'
import './ledger.css'

const EMPTY = '—'
const text = (value: unknown) => (value == null || value === '' ? EMPTY : String(value))
function Failure({ error }: { error: unknown }) {
  if (!error) return null
  return (
    <p role="alert" className="guidance guidance-warning ledger-action-guidance">
      {error instanceof ApiError
        ? error.code === 'FGC-COMMON-500'
          ? `요청 ID: ${error.requestId ?? EMPTY}`
          : errorText(error)
        : errorText(error instanceof Error ? error : null)}
    </p>
  )
}
function Money({ value }: { value?: number }) {
  return <span className={isNegative(value) ? 'is-negative-amount' : 'tabular-nums'}>{won(value)}</span>
}
function Badge({ status, label }: { status?: string; label?: string }) {
  return (
    <StatusBadge tone={status === 'POSTED' ? 'success' : status === 'REVERSED' ? 'review' : 'neutral'}>
      {label || JOURNAL_STATUS[status as keyof typeof JOURNAL_STATUS] || text(status)}
    </StatusBadge>
  )
}
const disclose = (value: unknown) => <CellDisclosure singleLine preview={text(value)} full={text(value)} />
const columns: TableColumn<Journal>[] = [
  // 행 선택 버튼에 disclosure(summary)를 중첩하지 않고 긴 번호도 전체를 표시한다.
  {
    key: 'no',
    label: '분개번호',
    width: '14rem',
    render: (row) => <span className="ledger-react-number">{text(row.journalNo)}</span>,
  },
  { key: 'date', label: '분개일', width: '7.5rem', render: (row) => date(row.journalDate) },
  {
    key: 'type',
    label: '유형',
    width: '12rem',
    render: (row) => row.journalTypeLabel || JOURNAL_TYPE[row.journalType!] || EMPTY,
  },
  {
    key: 'source',
    label: '원천',
    width: '10rem',
    render: (row) =>
      disclose(`${row.sourceEntityTypeLabel || row.sourceEntityType || EMPTY} #${row.sourceEntityId ?? EMPTY}`),
  },
  { key: 'contract', label: '계약', width: '10rem', render: (row) => text(row.contractNo || row.contractId) },
  {
    key: 'debit',
    label: '차변 합계',
    width: '9rem',
    align: 'number',
    render: (row) => <Money value={row.debitTotal} />,
  },
  {
    key: 'credit',
    label: '대변 합계',
    width: '9rem',
    align: 'number',
    render: (row) => <Money value={row.creditTotal} />,
  },
  {
    key: 'status',
    label: '상태',
    width: '7rem',
    render: (row) => <Badge status={row.status} label={row.statusLabel} />,
  },
  { key: 'reversal', label: '역분개 표시', width: '12rem', render: (row) => disclose(row.reversalOfJournalNo) },
]
const lineColumns: TableColumn<JournalLine>[] = [
  { key: 'no', label: '번호', width: '4rem', render: (row) => int(row.lineNo) },
  {
    key: 'account',
    label: '계정과목',
    width: '16rem',
    render: (row) => disclose(accountDisplayName(row.accountCode, row.accountName)),
  },
  { key: 'debit', label: '차변', width: '9rem', align: 'number', render: (row) => <Money value={row.debitAmount} /> },
  { key: 'credit', label: '대변', width: '9rem', align: 'number', render: (row) => <Money value={row.creditAmount} /> },
  { key: 'agent', label: '설계사', width: '7rem', render: (row) => text(row.agentName) },
  { key: 'item', label: '항목', width: '10rem', render: (row) => disclose(row.commissionItemName) },
  { key: 'memo', label: '적요', width: '16rem', render: (row) => disclose(row.memo) },
]

/** LEDG-W01: searched=true 목록 계약과 selected 독립 딥링크를 유지한다. */
export default function LedgerPage() {
  const { params, update, reset } = useSearchParamsState(FILTER_KEYS)
  const month = useWorkspaceStore((state) => state.month)
  const criteria = criteriaFromParams(params)
  const searched = params.get('searched') === 'true'
  const selected = positiveId(params.get('selected'))
  const list = useQuery(journalSearchQuery(criteria, searched))
  const detail = useQuery({ ...journalDetailQueryOptions(selected), enabled: !!selected })
  const imbalance = useQuery(imbalanceQuery(detail.data?.validationRunId))
  const canReverse = usePermission('canReverseJournal')
  const [action, setAction] = useState<'reverse' | 'correction' | null>(null)
  const data = searched ? list.data : undefined
  const eligible =
    detail.data?.status === 'POSTED' && detail.data.journalType !== 'REVERSAL' && !detail.data.reversedByJournalHeaderId
  const select = (row: Journal) =>
    row.journalHeaderId != null && update({ selected: String(row.journalHeaderId) }, { resetPage: false })
  return (
    <div className="ledger-react-page">
      <header className="page-header">
        <h1 className="page-title">검증원장 조회</h1>
      </header>
      <aside className="guidance guidance-neutral">
        이 원장은 회사의 정식 회계장부가 아닙니다. 정산이 맞는지 확인하려고 FGC가 따로 만드는 보조 장부입니다.
      </aside>
      {params.get('imbalanceOnly') === 'true' && (
        <p className="guidance guidance-neutral" role="status">
          불균형만 조회하는 목록 필터는 지원하지 않습니다. 분개를 선택하면 연결된 검증 실행의 불균형을 확인할 수
          있습니다.
        </p>
      )}
      <LedgerFilters
        key={JSON.stringify(criteria) + month}
        criteria={criteria}
        month={month}
        onSearch={(fields) => update({ ...fields, searched: 'true', selected: '' })}
        onReset={reset}
      />
      <div className="ledger-react-grid">
        <section className="surface" aria-busy={searched && list.isFetching}>
          <header className="surface-header">
            <h2 className="surface-title">분개 목록</h2>
            <span>{int(data?.totalElements ?? 0)}건 · 한 화면 20행</span>
          </header>
          <Failure error={searched ? list.error : null} />
          {searched && list.isPending && <p role="status">분개 목록을 불러오는 중입니다.</p>}
          <DataTable
            columns={columns}
            rows={data?.content ?? []}
            rowKey={(row) => String(row.journalHeaderId)}
            caption="조회 조건에 해당하는 검증원장 분개 목록"
            onRowClick={select}
            onRowActivate={select}
            rowClassName={(row) => (String(row.journalHeaderId) === selected ? 'is-selected' : '')}
            emptyMessage={searched ? '조건에 맞는 자료가 없습니다.' : '조회 조건을 설정하고 조회 버튼을 눌러 주세요.'}
          />
          <ListPagination
            className="ledger-pagination"
            label="검증원장 목록 페이지 선택"
            page={criteria.page}
            totalPages={data?.totalPages ?? 0}
            onPageChange={(page) => update({ page: String(page) }, { resetPage: false })}
          />
        </section>
        <section className="surface" aria-label="분개 상세" aria-busy={!!selected && detail.isFetching}>
          <header className="surface-header">
            <h2 className="surface-title">분개 상세</h2>
            {detail.data && <Badge status={detail.data.status} label={detail.data.statusLabel} />}
          </header>
          <Failure error={detail.error} />
          {!selected ? (
            <p className="empty-state">목록에서 분개를 선택하면 상세를 볼 수 있습니다.</p>
          ) : detail.isPending ? (
            <p role="status">분개 상세를 불러오는 중입니다.</p>
          ) : (
            detail.data && (
              <>
                <DetailSummary detail={detail.data} />
                {detail.data.balanced === false && (
                  <p className="guidance guidance-warning" role="alert">
                    차변·대변이 맞지 않습니다. 차액 <Money value={detail.data.differenceAmount} />
                  </p>
                )}
                <Failure error={imbalance.error} />
                {!detail.data.validationRunId ? (
                  <p role="status" className="guidance guidance-neutral">
                    이 분개에는 연결된 검증 실행이 없어 실행 단위 불균형을 조회할 수 없습니다.
                  </p>
                ) : imbalance.isPending ? (
                  <p role="status">원장 불균형 여부를 확인하는 중입니다.</p>
                ) : (
                  imbalance.data && (
                    <p
                      role={(imbalance.data.totalCount ?? 0) > 0 ? 'alert' : 'status'}
                      className={`guidance ${(imbalance.data.totalCount ?? 0) > 0 ? 'guidance-warning' : 'guidance-info'}`}
                    >
                      {(imbalance.data.totalCount ?? 0) > 0
                        ? `이 검증 실행에 차변·대변이 맞지 않는 분개가 ${imbalance.data.totalCount}건 있습니다.`
                        : '선택한 분개의 검증 실행은 원장 불균형이 0건입니다.'}
                    </p>
                  )
                )}
                <DataTable
                  columns={lineColumns}
                  rows={detail.data.lines ?? []}
                  rowKey={(row) => String(row.lineNo)}
                  caption="선택한 분개의 차변·대변 상세행"
                  emptyMessage="분개 상세행이 없습니다."
                />
                <div className="ledger-react-actions">
                  {eligible ? (
                    <>
                      <Button disabled={!canReverse} onClick={() => setAction('reverse')}>
                        역분개
                      </Button>
                      <Button variant="secondary" disabled={!canReverse} onClick={() => setAction('correction')}>
                        역분개 + 재기표
                      </Button>
                      {!canReverse && <p>역분개는 SETTLEMENT·GA_ADMIN·SYSTEM_ADMIN 권한이 필요합니다.</p>}
                    </>
                  ) : (
                    <p>POSTED 상태이고 아직 역분개되지 않은 원분개만 처리할 수 있습니다.</p>
                  )}
                </div>
              </>
            )
          )}
        </section>
      </div>
      {action && detail.data?.journalHeaderId != null && (
        <JournalActionModal
          key={`${action}-${detail.data.journalHeaderId}`}
          action={action}
          detail={detail.data}
          allowed={canReverse && !!eligible}
          month={month}
          onClose={() => setAction(null)}
        />
      )}
    </div>
  )
}

function LedgerFilters({
  criteria,
  month,
  onSearch,
  onReset,
}: {
  criteria: JournalCriteria
  month: string | null
  onSearch: (fields: Omit<JournalCriteria, 'page'>) => void
  onReset: () => void
}) {
  const initial = {
    from: criteria.from,
    to: criteria.to,
    type: criteria.type,
    account: criteria.account,
    contractNo: criteria.contractNo,
    status: criteria.status,
  }
  const lastDay = month ? new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).getUTCDate() : 0
  const [fields, setFields] = useState({
    ...initial,
    from: initial.from || (month ? `${month}-01` : ''),
    to: initial.to || (month ? `${month}-${lastDay}` : ''),
  })
  const accounts = useQuery(journalAccountsQueryOptions())
  const field = (key: keyof typeof fields, label: string, options?: Record<string, string>, type = 'text') => (
    <Field label={label}>
      {options ? (
        <select value={fields[key]} onChange={(e) => setFields({ ...fields, [key]: e.target.value })}>
          <option value="">전체</option>
          {Object.entries(options).map(([value, name]) => (
            <option key={value} value={value}>
              {name}
            </option>
          ))}
        </select>
      ) : (
        <input
          type={type}
          maxLength={key === 'contractNo' ? 80 : undefined}
          value={fields[key]}
          onChange={(e) => setFields({ ...fields, [key]: e.target.value })}
        />
      )}
    </Field>
  )
  return (
    <>
      <FilterBar className="ledger-react-filters" icons resetFirst onSubmit={() => onSearch(fields)} onReset={onReset}>
        {field('from', '분개일 (시작)', undefined, 'date')}
        {field('to', '분개일 (끝)', undefined, 'date')}
        {field('type', '분개 유형', JOURNAL_TYPE)}
        {field(
          'account',
          '계정과목',
          Object.fromEntries([
            ...Object.entries(JOURNAL_ACCOUNT),
            ...(accounts.data ?? [])
              .filter((a) => a.accountCode)
              .map((a) => [a.accountCode!, accountDisplayName(a.accountCode, a.accountName)]),
          ]),
        )}
        {field('contractNo', '계약번호', undefined, 'text')}
        {field('status', '상태', JOURNAL_STATUS)}
      </FilterBar>
      <Failure error={accounts.error} />
    </>
  )
}

function DetailSummary({ detail }: { detail: JournalDetail }) {
  const values = [
    ['분개번호', disclose(detail.journalNo)],
    ['분개일', date(detail.journalDate)],
    ['유형', detail.journalTypeLabel || JOURNAL_TYPE[detail.journalType!] || EMPTY],
    ['계약', text(detail.contractNo || detail.contractId)],
    ['차변 합계', <Money value={detail.debitTotal} />],
    ['대변 합계', <Money value={detail.creditTotal} />],
    ['원분개', disclose(detail.reversalOfJournalNo)],
    ['역분개', disclose(detail.reversedByJournalNo)],
    ['재기표', disclose(detail.repostedJournalNo)],
    ['정정그룹', disclose(detail.correctionGroupKey)],
    ['적요', disclose(detail.description)],
  ] as const
  return (
    <dl className="ledger-react-summary">
      {values.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

function JournalActionModal({
  action,
  detail,
  allowed,
  month,
  onClose,
}: {
  action: 'reverse' | 'correction'
  detail: JournalDetail
  allowed: boolean
  month: string | null
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const [evidence, setEvidence] = useState('')
  const [reasonError, setReasonError] = useState(false)
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  const client = useQueryClient()
  const navigate = useNavigate()
  const mutation = useMutation({
    mutationFn: async () => {
      const body = { reason: reason.trim(), evidenceRef: evidence.trim() || undefined }
      if (!allowed || detail.journalHeaderId == null) throw new Error('처리 권한을 확인할 수 없습니다.')
      if (action === 'reverse') return reverseJournal(detail.journalHeaderId, body)
      const result = await requestCorrection(detail.journalHeaderId, body)
      const target = correctionTarget(result.redirectUrl)
      if (!target)
        throw new Error('원장 정정 예외는 생성됐지만 이동할 주소가 올바르지 않습니다. 예외함에서 직접 확인하세요.')
      return { target }
    },
    meta: { errorToast: false },
    onSuccess: async (result) => {
      if ('target' in result) {
        void client.invalidateQueries({ queryKey: ['exceptions'] })
        onClose()
        navigate(month ? withMonth(result.target, month) : result.target)
      } else {
        toast(`역분개 ${result.journalHeaderId}을 생성했습니다.`, 'success')
        await invalidateJournalCorrectionQueries(client)
        onClose()
      }
    },
  })
  function submit() {
    if (!reason.trim()) {
      setReasonError(true)
      reasonRef.current?.focus()
      return
    }
    if (allowed && !mutation.isPending) mutation.mutate()
  }
  const close = () => {
    if (!mutation.isPending) onClose()
  }
  const isReverse = action === 'reverse'
  return (
    <Modal
      open
      title={isReverse ? '원분개 역분개' : '역분개 + 재기표 요청'}
      onClose={close}
      closeOnEscape={!mutation.isPending}
      initialFocusRef={reasonRef}
      footer={
        <>
          <Button variant="secondary" disabled={mutation.isPending} onClick={close}>
            취소
          </Button>
          <Button loading={mutation.isPending} disabled={!allowed} onClick={submit}>
            {isReverse ? '역분개 생성' : '예외함에서 정정 계속'}
          </Button>
        </>
      }
    >
      <p>
        {detail.journalNo} · {detail.journalTypeLabel}
      </p>
      <p className="guidance guidance-neutral ledger-action-guidance">
        {isReverse
          ? '원분개를 수정하거나 삭제하지 않고 반대 차변·대변의 새 분개를 만듭니다. 처리 후 되돌리려면 별도 정정 절차가 필요합니다.'
          : '원분개를 바로 바꾸지 않고 원장 정정 예외를 만든 뒤 예외함에서 올바른 신규 분개를 입력합니다.'}
      </p>
      <Failure error={mutation.error} />
      <Field
        label={isReverse ? '역분개 사유' : '정정 요청 사유'}
        required
        error={reasonError ? (isReverse ? '역분개 사유를 입력하세요.' : '정정 요청 사유를 입력하세요.') : undefined}
      >
        <textarea
          ref={reasonRef}
          rows={5}
          maxLength={1000}
          value={reason}
          disabled={mutation.isPending}
          onChange={(e) => {
            setReason(e.target.value)
            setReasonError(false)
          }}
        />
      </Field>
      <Field label="증빙 참조">
        <input
          maxLength={500}
          value={evidence}
          disabled={mutation.isPending}
          onChange={(e) => setEvidence(e.target.value)}
        />
      </Field>
    </Modal>
  )
}
