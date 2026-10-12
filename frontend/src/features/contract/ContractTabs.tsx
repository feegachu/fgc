import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { date, dateTime, int, month, won } from '../../lib/format'
import { CapDetailModal } from '../cap/CapDetailModal'
import { Amount, CapStatus, CapUsage } from '../cap/badges'
import { ScheduleStatusBadge } from '../schedule/badges'
import { LINE_STATUS, PAYMENT_STAGE, responseLabel } from '../schedule/labels'
import {
  contractArbitrageChecksQuery,
  contractCapChecksQuery,
  contractEventsQuery,
  contractJournalsQuery,
  contractSchedulesQuery,
  contractTransactionsQuery,
} from './api'
import type {
  ContractArbitrageCheck,
  ContractCapCheck,
  ContractDetail,
  ContractJournal,
  ContractSchedule,
  ContractTransactions,
} from './api'
import { ContractStatusBadge, DataOriginBadge } from './badges'
import { arbitrageStatusLabels, contractStatusLabels, paymentCycleLabels, processingStatusLabels } from './labels'
import { contractErrorText, contractTabs as tabs, sortStatusEvents } from './detailState'
import type { ContractTab, StatusSort } from './detailState'

const text = (value: unknown) => (value == null || value === '' ? '—' : String(value))
const stageLabel = (code?: string, label?: string) => responseLabel(label, PAYMENT_STAGE, code)
const Text = ({ value }: { value: unknown }) => <CellDisclosure singleLine preview={text(value)} />

export function ContractLoadState({ message, error, retry }: { message: string; error?: boolean; retry?: () => void }) {
  return (
    <div className={`contract-detail-state ${error ? 'is-error' : ''}`} role={error ? 'alert' : 'status'}>
      <p>{message}</p>
      {retry && (
        <Button variant="secondary" onClick={retry}>
          다시 시도
        </Button>
      )}
    </div>
  )
}

export function ContractFacts({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="contract-detail-facts">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value ?? '—'}</dd>
        </div>
      ))}
    </dl>
  )
}

function QueryContent<T>({ query, children }: { query: UseQueryResult<T, Error>; children: (data: T) => ReactNode }) {
  if (query.isError)
    return (
      <ContractLoadState
        message={contractErrorText(query.error, '자료를 불러오지 못했습니다.')}
        error
        retry={() => void query.refetch()}
      />
    )
  if (!query.isSuccess) return <ContractLoadState message="불러오는 중입니다." />
  return children(query.data)
}

const transactionLabels: Record<string, string> = { DRAFT: '작성중', CONFIRMED: '확정', CANCELLED: '취소' }
const statusTones: Record<string, StatusTone> = {
  RUNNING: 'info',
  DRAFT: 'info',
  COMPLETED: 'success',
  SUCCESS: 'success',
  SUCCEEDED: 'success',
  CONFIRMED: 'success',
  CLEAR: 'success',
  FAILED: 'error',
  CANDIDATE: 'warning',
  REVIEW_REQUIRED: 'review',
}
function ResultStatus({
  code,
  label,
  labels = transactionLabels,
}: {
  code?: string
  label?: string
  labels?: Record<string, string>
}) {
  return <StatusBadge tone={statusTones[code ?? ''] ?? 'neutral'}>{responseLabel(label, labels, code)}</StatusBadge>
}

export function ContractTabs({
  id,
  contract,
  active,
  onSelect,
}: {
  id: string
  contract: ContractDetail
  active: ContractTab
  onSelect: (tab: ContractTab) => void
}) {
  const tabButtons = useRef<(HTMLButtonElement | null)[]>([])
  return (
    <>
      <div className="tab-list contract-detail-tabs" role="tablist" aria-label="계약 상세 영역">
        {tabs.map(([name, label], index) => (
          <button
            key={name}
            ref={(element) => {
              tabButtons.current[index] = element
            }}
            type="button"
            className="tab-button"
            role="tab"
            id={`contract-tab-${name}`}
            aria-selected={active === name}
            aria-controls={`contract-panel-${name}`}
            tabIndex={active === name ? 0 : -1}
            onClick={() => onSelect(name)}
            onKeyDown={(event) => {
              const next =
                event.key === 'ArrowRight'
                  ? (index + 1) % tabs.length
                  : event.key === 'ArrowLeft'
                    ? (index - 1 + tabs.length) % tabs.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? tabs.length - 1
                        : null
              if (next == null) return
              event.preventDefault()
              onSelect(tabs[next][0])
              tabButtons.current[next]?.focus()
            }}
          >
            {label}
          </button>
        ))}
      </div>
      {tabs.map(([name]) => (
        <section
          key={name}
          id={`contract-panel-${name}`}
          role="tabpanel"
          aria-labelledby={`contract-tab-${name}`}
          tabIndex={0}
          hidden={active !== name}
          className="surface contract-detail-panel"
        >
          {active === name &&
            (name === 'basic' ? (
              <BasicTab id={id} contract={contract} />
            ) : name === 'schedules' ? (
              <SchedulesTab id={id} />
            ) : name === 'cap' ? (
              <CapTab id={id} />
            ) : name === 'arbitrage' ? (
              <ArbitrageTab id={id} />
            ) : name === 'ledger' ? (
              <LedgerTab id={id} />
            ) : (
              <PaymentTab id={id} />
            ))}
        </section>
      ))}
    </>
  )
}

function PanelHeading({ title, children }: { title: string; children: ReactNode }) {
  return (
    <header className="surface-header">
      <div>
        <h2 className="surface-title">{title}</h2>
        <p>{children}</p>
      </div>
    </header>
  )
}

function BasicTab({ id, contract }: { id: string; contract: ContractDetail }) {
  const history = useQuery(contractEventsQuery(id))
  const [sort, setSort] = useState<StatusSort>('effectiveAt')
  return (
    <>
      <PanelHeading title="계약 기본정보">계약 정보와 상태 사건의 후속 처리 결과를 조회합니다.</PanelHeading>
      <ContractFacts
        items={[
          ['계약번호', contract.contractNo],
          ['상품명', contract.productName],
          ['계약일', date(contract.contractDate)],
          ['계약상태', <ContractStatusBadge code={contract.contractStatus} label={contract.contractStatusLabel} />],
          ['주기 보험료', won(contract.premiumPerCycleAmount)],
          ['납입주기', responseLabel(contract.paymentCycleCodeLabel, paymentCycleLabels, contract.paymentCycleCode)],
          ['초회보험료', won(contract.firstPremiumAmount)],
          ['월납환산 초회보험료', won(contract.monthlyEquivalentFirstPremium)],
          ['납입기간', contract.paymentTermMonths == null ? '—' : `${int(contract.paymentTermMonths)}개월`],
          ['표준해약공제액', won(contract.standardSurrenderDeductionAmount)],
          ['데이터 출처', <DataOriginBadge code={contract.dataOrigin} label={contract.dataOriginLabel} />],
          ['통화 단위', 'KRW (원)'],
        ]}
      />
      <section className="contract-detail-history" aria-labelledby="contract-history-title">
        <header>
          <div>
            <h3 id="contract-history-title">상태이력</h3>
            <p>사건 발생과 후속 처리 작업을 분리해 표시합니다.</p>
          </div>
          <div role="group" aria-label="상태이력 정렬 기준">
            <Button variant="secondary" aria-pressed={sort === 'effectiveAt'} onClick={() => setSort('effectiveAt')}>
              효력 순
            </Button>
            <Button variant="secondary" aria-pressed={sort === 'receivedAt'} onClick={() => setSort('receivedAt')}>
              도착 순
            </Button>
          </div>
        </header>
        <QueryContent query={history}>
          {(events) =>
            events.length ? (
              <ol className="contract-detail-events" aria-label="상태 사건" aria-live="polite">
                {sortStatusEvents(events, sort).map((event, index) => (
                  <li key={`${event.sourceSystem ?? ''}-${event.sourceEventKey ?? event.eventSeq ?? index}`}>
                    <article>
                      <header>
                        <h4>
                          #{event.eventSeq ?? '—'}{' '}
                          {event.previousStatus
                            ? responseLabel(event.previousStatusLabel, contractStatusLabels, event.previousStatus)
                            : '최초'}{' '}
                          → {responseLabel(event.newStatusLabel, contractStatusLabels, event.newStatus)}
                        </h4>
                        <span>
                          {event.sourceSystem || '계약 상태 사건'}
                          {!event.processings?.length ? ' · 처리 대기' : ''}
                        </span>
                      </header>
                      <ContractFacts
                        items={[
                          ['효력', dateTime(event.effectiveAt)],
                          ['수신', dateTime(event.receivedAt)],
                        ]}
                      />
                      {!!event.processings?.length && (
                        <ul className="contract-detail-processings" aria-label="후속 처리 작업">
                          {event.processings.map((job, jobIndex) => (
                            <li key={`${job.processingJob}-${jobIndex}`}>
                              <strong>{job.processingJob || '처리 작업'}</strong>
                              <ResultStatus code={job.processingStatus} labels={processingStatusLabels} />
                              <time dateTime={job.processedAt}>{dateTime(job.processedAt)}</time>
                            </li>
                          ))}
                        </ul>
                      )}
                    </article>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="contract-detail-state">등록된 상태이력이 없습니다.</p>
            )
          }
        </QueryContent>
      </section>
    </>
  )
}

type ScheduleLine = NonNullable<ContractSchedule['lines']>[number]
const scheduleColumns: TableColumn<ScheduleLine>[] = [
  { key: 'installment', label: '회차', render: (row) => int(row.installmentNo) },
  { key: 'dueDate', label: '예정일', render: (row) => date(row.dueDate) },
  { key: 'item', label: '수수료 항목', render: (row) => <Text value={row.commissionItemName} /> },
  { key: 'recipient', label: '수취인', render: (row) => <Text value={row.recipientName} /> },
  { key: 'amount', label: '예정 지급액', align: 'number', render: (row) => <Amount value={row.expectedAmount} /> },
  {
    key: 'status',
    label: '상태',
    render: (row) => (
      <ScheduleStatusBadge code={row.lineStatus} label={responseLabel(undefined, LINE_STATUS, row.lineStatus)} />
    ),
  },
]
function SchedulesTab({ id }: { id: string }) {
  const [params, setParams] = useSearchParams()
  const stage = params.get('stage') === 'GA_TO_FC' ? 'GA_TO_FC' : 'INSURER_TO_GA'
  const query = useQuery(contractSchedulesQuery(id, stage))
  return (
    <>
      <PanelHeading title="예상 스케줄">지급단계별 현재 운영 스케줄과 회차를 조회합니다.</PanelHeading>
      <div className="field contract-detail-stage">
        <label htmlFor="contract-schedule-stage" className="field-label">
          지급단계
        </label>
        <select
          id="contract-schedule-stage"
          className="select-control"
          value={stage}
          onChange={(event) =>
            setParams((current) => {
              const next = new URLSearchParams(current)
              next.set('stage', event.target.value)
              return next
            })
          }
        >
          {Object.entries(PAYMENT_STAGE).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
      <QueryContent query={query}>
        {(data) => (
          <>
            <div className="contract-detail-schedule-headers">
              {data.headers?.length ? (
                data.headers.map((header, index) => (
                  <div key={header.scheduleHeaderId ?? index}>
                    <strong>
                      {header.scheduleHeaderId == null ? (
                        `버전 ${header.scheduleVersionNo ?? '—'}`
                      ) : (
                        <Link to={`/schedules/${header.scheduleHeaderId}`}>
                          버전 {header.scheduleVersionNo ?? '—'} 상세
                        </Link>
                      )}
                    </strong>
                    <span>{int(header.lineCount ?? data.lines?.length)}회차</span>
                    <ScheduleStatusBadge
                      code={header.status}
                      label={responseLabel(header.statusLabel, LINE_STATUS, header.status)}
                    />
                  </div>
                ))
              ) : (
                <p>현재 운영 스케줄이 없습니다.</p>
              )}
            </div>
            <DataTable
              caption={`${PAYMENT_STAGE[stage]} 예상 스케줄`}
              columns={scheduleColumns}
              rows={data.lines ?? []}
              rowKey={(row) => String(row.lineNo ?? data.lines!.indexOf(row))}
              emptyMessage="조회된 스케줄 회차가 없습니다."
            />
          </>
        )}
      </QueryContent>
    </>
  )
}

function CapTab({ id }: { id: string }) {
  const query = useQuery(contractCapChecksQuery(id))
  const [params, setParams] = useSearchParams()
  const requested = params.get('capCheckId')
  // 이 계약에 속한 판정만 모달로 연다. URL을 직접 바꾼 경우에도 다른 계약의 근거를 섞지 않는다.
  const capCheckId = query.data?.find((row) => String(row.capCheckId) === requested)?.capCheckId ?? null
  const select = (capId: number | null) =>
    setParams((current) => {
      const next = new URLSearchParams(current)
      if (capId == null) next.delete('capCheckId')
      else next.set('capCheckId', String(capId))
      return next
    })
  const columns: TableColumn<ContractCapCheck>[] = [
    { key: 'stage', label: '지급단계', render: (row) => stageLabel(row.result?.paymentStage) },
    { key: 'date', label: '기준일', render: (row) => date(row.result?.asOfDate) },
    { key: 'limit', label: '한도', align: 'number', render: (row) => <Amount value={row.result?.limitAmount} /> },
    {
      key: 'included',
      label: '산입액',
      align: 'number',
      render: (row) => <Amount value={row.result?.includedAmount} />,
    },
    {
      key: 'remaining',
      label: '잔여액',
      align: 'number',
      render: (row) => <Amount value={row.result?.remainingAmount} />,
    },
    {
      key: 'usage',
      label: '사용률',
      render: (row) => (
        <CapUsage
          value={row.result?.usagePct == null ? undefined : String(row.result.usagePct)}
          status={row.result?.resultStatus}
        />
      ),
    },
    { key: 'status', label: '판정', render: (row) => <CapStatus code={row.result?.resultStatus} /> },
    {
      key: 'basis',
      label: '계산근거',
      render: (row) => (
        <Button
          variant="ghost"
          disabled={row.capCheckId == null}
          onClick={() => select(row.capCheckId ?? null)}
          aria-label={`${stageLabel(row.result?.paymentStage)} 계산근거`}
        >
          계산근거
        </Button>
      ),
    },
  ]
  return (
    <>
      <PanelHeading title="1,200% 한도 검증">지급단계별 최신 판정을 합산하지 않고 각각 표시합니다.</PanelHeading>
      <p className="guidance guidance-warning contract-detail-note">
        두 규제를 더하지 마세요 — 원수사→GA 한도와 GA→설계사 한도는 별개로 판정합니다.
      </p>
      <QueryContent query={query}>
        {(rows) => (
          <DataTable
            caption="1,200% 한도 판정"
            columns={columns}
            rows={rows}
            rowKey={(row) => String(row.capCheckId ?? rows.indexOf(row))}
            emptyMessage="조회된 한도 판정이 없습니다."
          />
        )}
      </QueryContent>
      <CapDetailModal capCheckId={capCheckId} onClose={() => select(null)} />
    </>
  )
}

const arbitrageColumns: TableColumn<ContractArbitrageCheck>[] = [
  { key: 'date', label: '기준일', render: (row) => date(row.asOfDate) },
  { key: 'month', label: '계약차월', render: (row) => int(row.contractMonthNo) },
  {
    key: 'premium',
    label: '납입보험료',
    align: 'number',
    render: (row) => <Amount value={row.cumulativePaidPremium} />,
  },
  { key: 'paid', label: '기지급', align: 'number', render: (row) => <Amount value={row.paidCommissionAmount} /> },
  {
    key: 'planned',
    label: '지급예정',
    align: 'number',
    render: (row) => <Amount value={row.plannedCommissionAmount} />,
  },
  {
    key: 'surrender',
    label: '해약환급금',
    align: 'number',
    render: (row) => <Amount value={row.includedSurrenderValueAmount} />,
  },
  { key: 'difference', label: '차액', align: 'number', render: (row) => <Amount value={row.netDifferenceAmount} /> },
  {
    key: 'status',
    label: '판정',
    render: (row) => (
      <ResultStatus code={row.resultStatus} label={row.resultStatusLabel} labels={arbitrageStatusLabels} />
    ),
  },
]
function ArbitrageTab({ id }: { id: string }) {
  const query = useQuery(contractArbitrageChecksQuery(id))
  return (
    <>
      <PanelHeading title="차익거래 검증">계약 전 기간의 납입보험료와 지급 수수료등을 대조합니다.</PanelHeading>
      <QueryContent query={query}>
        {(rows) => (
          <DataTable
            caption="차익거래 검증 결과"
            columns={arbitrageColumns}
            rows={rows}
            rowKey={(row) => String(row.arbitrageCheckId ?? rows.indexOf(row))}
            emptyMessage="조회된 차익거래 판정이 없습니다."
          />
        )}
      </QueryContent>
    </>
  )
}

const journalColumns: TableColumn<ContractJournal>[] = [
  { key: 'number', label: '분개번호', render: (row) => <Text value={row.journalNo} /> },
  { key: 'date', label: '일자', render: (row) => date(row.journalDate) },
  { key: 'type', label: '유형', render: (row) => <Text value={row.journalType} /> },
  { key: 'stage', label: '지급단계', render: (row) => stageLabel(row.paymentStage, row.paymentStageLabel) },
  { key: 'debit', label: '차변', align: 'number', render: (row) => <Amount value={row.debitTotal} /> },
  { key: 'credit', label: '대변', align: 'number', render: (row) => <Amount value={row.creditTotal} /> },
  { key: 'difference', label: '차액', align: 'number', render: (row) => <Amount value={row.differenceAmount} /> },
  { key: 'status', label: '상태', render: (row) => <ResultStatus code={row.status} label={row.statusLabel} /> },
]
function LedgerTab({ id }: { id: string }) {
  const query = useQuery(contractJournalsQuery(id))
  return (
    <>
      <PanelHeading title="원장">이 계약에서 만들어진 분개를 조회합니다.</PanelHeading>
      <QueryContent query={query}>
        {(rows) => (
          <DataTable
            caption="이 계약의 분개 목록"
            columns={journalColumns}
            rows={rows}
            rowKey={(row) => String(row.journalHeaderId ?? rows.indexOf(row))}
            emptyMessage="조회된 분개가 없습니다."
          />
        )}
      </QueryContent>
    </>
  )
}

type Transaction = NonNullable<ContractTransactions['transactions']>[number]
type Reconciliation = NonNullable<ContractTransactions['reconciliations']>[number]
const transactionColumns: TableColumn<Transaction>[] = [
  { key: 'month', label: '정산월', render: (row) => month(row.settlementMonth) },
  { key: 'stage', label: '지급단계', render: (row) => stageLabel(row.paymentStage, row.paymentStageLabel) },
  { key: 'amount', label: '금액', align: 'number', render: (row) => <Amount value={row.amount} /> },
  { key: 'attributed', label: '귀속금액', align: 'number', render: (row) => <Amount value={row.attributionTotal} /> },
  { key: 'difference', label: '차액', align: 'number', render: (row) => <Amount value={row.differenceAmount} /> },
  { key: 'status', label: '상태', render: (row) => <ResultStatus code={row.status} label={row.statusLabel} /> },
  { key: 'source', label: '원천', render: (row) => <Text value={row.sourceType} /> },
]
const reconciliationColumns: TableColumn<Reconciliation>[] = [
  { key: 'result', label: '결과', render: (row) => <Text value={row.resultTypeLabel || row.resultType} /> },
  { key: 'installment', label: '회차', render: (row) => int(row.installmentNo) },
  { key: 'expected', label: '예상액', align: 'number', render: (row) => <Amount value={row.expectedTotalAmount} /> },
  { key: 'actual', label: '실제액', align: 'number', render: (row) => <Amount value={row.actualTotalAmount} /> },
  { key: 'difference', label: '차액', align: 'number', render: (row) => <Amount value={row.differenceAmount} /> },
  { key: 'reason', label: '주원인', render: (row) => <Text value={row.primaryReasonCode} /> },
]
function PaymentTab({ id }: { id: string }) {
  const query = useQuery(contractTransactionsQuery(id))
  return (
    <>
      <PanelHeading title="지급·대사">수수료 지급 건과 대사 결과를 함께 표시합니다.</PanelHeading>
      <QueryContent query={query}>
        {(data) => (
          <>
            <h3 className="contract-detail-subheading">수수료 지급</h3>
            <DataTable
              caption="수수료 지급 건"
              columns={transactionColumns}
              rows={data.transactions ?? []}
              rowKey={(row) => String(row.commissionTransactionId ?? data.transactions!.indexOf(row))}
              emptyMessage="조회된 수수료 지급 건이 없습니다."
            />
            <h3 className="contract-detail-subheading">대사 결과</h3>
            <DataTable
              caption="대사 결과"
              columns={reconciliationColumns}
              rows={data.reconciliations ?? []}
              rowKey={(row) => String(row.reconciliationResultId ?? data.reconciliations!.indexOf(row))}
              emptyMessage="조회된 대사 결과가 없습니다."
            />
          </>
        )}
      </QueryContent>
    </>
  )
}
