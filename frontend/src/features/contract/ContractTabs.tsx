import { plannedBasisPresentation, UPPER_BOUND_NOTE } from '../arbitrage/planned-basis'
import { returnLink } from '../transaction/payment-entry'
import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { guideFeatureId } from '../assistant/feature-ids'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router'
import { Button } from '../../components/Button'
import { CellDisclosure } from '../../components/CellDisclosure'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { StatusBadge, CategoryBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { date, int, month, won } from '../../lib/format'
import { ContractAcquisitionCost } from '../acquisition-cost/ContractAcquisitionCost'
import { CapDetailModal } from '../cap/CapDetailModal'
import { Amount, CapStatus, CapUsage } from '../cap/badges'
import { JOURNAL_TYPE } from '../ledger/labels'
import { SchedulePreviewForm } from '../schedule/SchedulePreviewForm'
import { EvidenceLink } from '../../components/EvidenceLink'
import { ScheduleStatusBadge } from '../schedule/badges'
import { LINE_STATUS, PAYMENT_STAGE, responseLabel } from '../schedule/labels'
import {
  contractArbitrageChecksQuery,
  contractCapChecksQuery,
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
import { ContractStatusHistory } from './ContractStatusHistory'
import { ContractFacts, QueryContent } from './detailParts'
import { arbitrageStatusLabels, paymentCycleLabels } from './labels'
import { contractTabs } from './detailState'
import { screenFor } from '../../app/screens'
import type { ContractTab } from './detailState'
import { contractTabDescriptions, contractTabIcons } from './detailState'

const RevisedDetail = createContext(false)
const tabs = contractTabs.filter(([name]) => name !== 'acquisition' || screenFor('/acquisition-cost-checks')?.enabled !== false)

const text = (value: unknown) => (value == null || value === '' ? '-' : String(value))
const stageLabel = (code?: string, label?: string) => responseLabel(label, PAYMENT_STAGE, code)
const Text = ({ value }: { value: unknown }) => <CellDisclosure singleLine preview={text(value)} />

const transactionLabels: Record<string, string> = { DRAFT: '작성중', CONFIRMED: '확정', CANCELLED: '취소' }
const statusTones: Record<string, StatusTone> = {
  RUNNING: 'info',
  DRAFT: 'neutral',
  POSTED: 'success',
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
  return (
    <StatusBadge tablePresentation="dot" tone={statusTones[code ?? ''] ?? 'neutral'}>
      {responseLabel(label, labels, code)}
    </StatusBadge>
  )
}

export function ContractTabs({
  id,
  contract,
  active,
  onSelect,
  agentOnly = false,
  revised = false,
}: {
  id: string
  contract: ContractDetail
  active: ContractTab
  onSelect: (tab: ContractTab) => void
  agentOnly?: boolean
  revised?: boolean
}) {
  const tabButtons = useRef<(HTMLButtonElement | null)[]>([])
  const previewRoot = useRef<HTMLDivElement>(null)
  const [preview, setPreview] = useState<ContractTab | null>(null)
  const activeRef = useRef(active)
  useLayoutEffect(() => { activeRef.current = active }, [active])
  useEffect(() => {
    const root = previewRoot.current
    if (!revised || !root) return
    let timer: ReturnType<typeof setInterval> | undefined
    const stop = () => { clearInterval(timer); setPreview(null) }
    const start = () => {
      clearInterval(timer)
      setPreview(activeRef.current)
      timer = setInterval(() => {
        if (document.querySelector('[role="dialog"]')) return
        setPreview(current => tabs[(tabs.findIndex(([name]) => name === (current ?? activeRef.current)) + 1) % tabs.length][0])
      }, 2000)
    }
    root.addEventListener('fgc-contract-tabs-start', start)
    root.addEventListener('fgc-contract-tabs-stop', stop)
    return () => {
      clearInterval(timer)
      root.removeEventListener('fgc-contract-tabs-start', start)
      root.removeEventListener('fgc-contract-tabs-stop', stop)
    }
  }, [revised])
  const selected = revised ? preview ?? active : active
  const displayed = tabs.some(([name]) => name === selected) ? selected : 'basic'
  useEffect(() => {
    if (!revised) return
    const button = tabButtons.current[tabs.findIndex(([name]) => name === displayed)]
    const list = button?.parentElement
    if (!button || !list) return
    const current = button.getBoundingClientRect(), viewport = list.getBoundingClientRect()
    const offset = current.right > viewport.right ? current.right - viewport.right
      : current.left < viewport.left ? current.left - viewport.left : 0
    if (offset) list.scrollBy({ left: offset, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })
  }, [displayed, revised])
  const choose = (tab: ContractTab) => preview !== null ? setPreview(tab) : onSelect(tab)
  const visibleTabs = agentOnly ? tabs.filter(([name]) => name === 'basic') : tabs
  return (
    <RevisedDetail.Provider value={revised}>
    <div className={revised ? 'contract-detail-workspace' : 'contract-detail-legacy-wrapper'}>
    <div ref={previewRoot} className={revised ? 'contract-detail-primary' : 'contract-detail-legacy-wrapper'}
      data-guide-target="contract.tabContent" data-guide-description={contractTabDescriptions[displayed]}>
      <div className="tab-list contract-detail-tabs" role="tablist" aria-label="계약 상세 영역">
        {visibleTabs.map(([name, label], index) => (
          <button data-guide-feature={guideFeatureId('contract.tab', name)}
            key={name}
            ref={(element) => {
              tabButtons.current[index] = element
            }}
            type="button"
            className="tab-button"
            role="tab"
            id={`contract-tab-${name}`}
            aria-selected={displayed === name}
            aria-controls={`contract-panel-${name}`}
            tabIndex={displayed === name ? 0 : -1}
            onClick={() => choose(name)}
            onKeyDown={(event) => {
              const next =
                event.key === 'ArrowRight'
                  ? (index + 1) % visibleTabs.length
                  : event.key === 'ArrowLeft'
                    ? (index - 1 + visibleTabs.length) % visibleTabs.length
                    : event.key === 'Home'
                      ? 0
                      : event.key === 'End'
                        ? visibleTabs.length - 1
                        : null
              if (next == null) return
              event.preventDefault()
              choose(visibleTabs[next][0])
              tabButtons.current[next]?.focus()
            }}
          >
            {revised && <span className="material-symbols-rounded" aria-hidden="true">{contractTabIcons[name]}</span>}{label}
          </button>
        ))}
      </div>
      {visibleTabs.map(([name]) => (
        <section data-guide-feature={guideFeatureId('contract.panel', name)}
          key={name}
          id={`contract-panel-${name}`}
          role="tabpanel"
          aria-labelledby={`contract-tab-${name}`}
          tabIndex={0}
          hidden={displayed !== name}
          data-guide-target={name === 'schedules' ? 'contract.schedules' : undefined}
          className={`surface contract-detail-panel ${['basic', 'schedules', 'cap', 'payment'].includes(name) ? 'contract-basic-card' : ''}`}
        >
          {displayed === name &&
            (name === 'basic' ? (
              <BasicTab id={id} contract={contract} />
            ) : name === 'schedules' ? (
              <SchedulesTab id={id} />
            ) : name === 'cap' ? (
              <CapTab id={id} />
            ) : name === 'arbitrage' ? (
              <ArbitrageTab id={id} />
            ) : name === 'acquisition' ? (
              <>
                <PanelHeading title="계약체결비용 한도">
                  선지급과 유지관리 예정 금액을 계약체결비용 한도와 각각 비교합니다.
                </PanelHeading>
                <ContractAcquisitionCost contractId={id} concise={revised} />
              </>
            ) : name === 'ledger' ? (
              <LedgerTab id={id} />
            ) : (
              <PaymentTab id={id} />
            ))}
        </section>
      ))}
    </div>
    {revised && <aside className="contract-detail-status"><ContractStatusHistory id={id} compact /></aside>}
    </div>
    </RevisedDetail.Provider>
  )
}

function PanelHeading({ title, children, legacy = false }: { title: string; children: ReactNode; legacy?: boolean }) {
  const revised = useContext(RevisedDetail)
  return (
    <header className={`surface-header ${legacy ? 'contract-basic-header contract-lazy-panel-header' : ''}`}>
      <div>
        <h2 className="surface-title">{title}</h2>
        {!revised && <p>{children}</p>}
      </div>
    </header>
  )
}

function BasicTab({ id, contract }: { id: string; contract: ContractDetail }) {
  const revised = useContext(RevisedDetail)
  return (
    <>
      <header className="surface-header contract-basic-header">
        <h2 id={revised ? 'contract-information-title' : undefined} className="surface-title">계약 기본정보</h2>
        {!revised && <p className="contract-lazy-note" role="status">
          계약 정보를 불러왔습니다.
        </p>}
      </header>
      <div className="contract-basic-layout">
        <section data-guide-feature="features.contract.contract-tabs.section-2" className="contract-information-panel" aria-labelledby="contract-information-title">
          {!revised && <h3 id="contract-information-title">계약 정보</h3>}
          <ContractFacts
            className="contract-information-grid"
            items={[
              ['계약번호', contract.contractNo],
              ['상품명', contract.productName],
              ['계약일', date(contract.contractDate)],
              ['계약상태', <ContractStatusBadge code={contract.contractStatus} label={contract.contractStatusLabel} />],
              ['주기 보험료', won(contract.premiumPerCycleAmount)],
              [
                '납입주기',
                responseLabel(contract.paymentCycleCodeLabel, paymentCycleLabels, contract.paymentCycleCode),
              ],
              ['초회보험료', won(contract.firstPremiumAmount)],
              ['월납환산 초회보험료', won(contract.monthlyEquivalentFirstPremium)],
              ['납입기간', contract.paymentTermMonths == null ? '-' : `${int(contract.paymentTermMonths)}개월`],
              ['표준해약공제액', won(contract.standardSurrenderDeductionAmount)],
              ['데이터 출처', <DataOriginBadge code={contract.dataOrigin} label={contract.dataOriginLabel} />],
            ]}
          />
        </section>
        {!revised && <div className="contract-side-panels">
          <ContractStatusHistory id={id} />
        </div>}
      </div>
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
    align: 'center',
    render: (row) => (
      <ScheduleStatusBadge code={row.lineStatus} label={responseLabel(undefined, LINE_STATUS, row.lineStatus)} />
    ),
  },
]
const revisedScheduleColumns: TableColumn<ScheduleLine>[] = [
  ['installment', '60px'], ['amount', '120px'], ['recipient', '90px'],
  ['item', '140px'], ['dueDate', '110px'], ['status', '90px'],
].map(([key, width]) => ({ ...scheduleColumns.find(column => column.key === key)!, width }))
/** 예외 처리 모달에서도 계약 상세와 동일한 예상 스케줄을 표시한다. */
export function ContractSchedulesView({ id }: { id: string }) {
  return <RevisedDetail.Provider value><SchedulesTab id={id} /></RevisedDetail.Provider>
}

function SchedulesTab({ id }: { id: string }) {
  return (
    <>
      <PanelHeading title="예상 스케줄" legacy>
        지급단계별 운영·예정 스케줄과 회차를 조회합니다.
      </PanelHeading>
      <div className="contract-tab-body">
        <div className="contract-detail-schedule-stages">
          <ScheduleStage id={id} stage="INSURER_TO_GA" />
          <ScheduleStage id={id} stage="GA_TO_FC" />
        </div>
      </div>
    </>
  )
}

function ScheduleStage({ id, stage }: { id: string; stage: 'INSURER_TO_GA' | 'GA_TO_FC' }) {
  const revised = useContext(RevisedDetail)
  const query = useQuery(contractSchedulesQuery(id, stage))
  const location = useLocation()
  return (
    <section data-guide-feature={guideFeatureId('contract.schedule-stage', stage)} aria-label={PAYMENT_STAGE[stage]} className="contract-tab-section">
      <QueryContent query={query}>
        {(data) => (
          <>
            <header className="surface-header contract-basic-header">
              <div>
                <h3 className="surface-title">{PAYMENT_STAGE[stage]}</h3>
                <p>
                  {data.headers?.[0]
                    ? `버전 ${data.headers[0].scheduleVersionNo ?? '-'}, ${int(data.headers[0].installmentCount)}회차`
                    : '생성된 스케줄이 없습니다.'}
                </p>
                {!revised && data.headers?.[0]?.schedulePurpose === 'SIMULATION' && (
                  <p>예정 결과 · 지급 확정 대상이 아닙니다.</p>
                )}
              </div>
            </header>
            {data.headers
              ?.filter((header) => header.schedulePurpose === 'SIMULATION')
              .map((header) => (
                <p key={header.scheduleHeaderId}>
                  <Link data-guide-feature="contract.schedule.preview-detail" to={returnLink(`/schedules/${header.scheduleHeaderId}`, `${location.pathname}${location.search}`)}>
                    예상 스케줄 v{header.scheduleVersionNo} · {header.installmentCount}회차 상세 보기
                  </Link>
                </p>
              ))}
            <SchedulePreviewForm context={data.previewContext} contractId={id} stage={stage} />
            <DataTable
              viewportClassName="contract-tab-viewport"
              caption={`${PAYMENT_STAGE[stage]} 예상 스케줄`}
              columns={revised ? revisedScheduleColumns.map(column => stage === 'INSURER_TO_GA' && column.key === 'recipient'
                ? { ...column, render: (row: ScheduleLine) => <Text value={row.recipientName?.trim() && row.recipientName.trim() !== '-' ? row.recipientName : 'GA'} /> }
                : column) : scheduleColumns}
              rows={revised ? [...(data.lines ?? [])].sort((a, b) =>
                (a.installmentNo ?? Number.MAX_SAFE_INTEGER) - (b.installmentNo ?? Number.MAX_SAFE_INTEGER)
                || (a.lineNo ?? 0) - (b.lineNo ?? 0)) : data.lines ?? []}
              rowKey={(row) => String(row.scheduleLineId ?? row.lineNo ?? data.lines!.indexOf(row))}
              emptyMessage="조회된 스케줄 회차가 없습니다."
            />
          </>
        )}
      </QueryContent>
    </section>
  )
}

function CapTab({ id }: { id: string }) {
  const query = useQuery(contractCapChecksQuery(id))
  const [params, setParams] = useSearchParams()
  const requested = params.get('capCheckId')
  // 이 계약에 속한 판정만 모달로 연다. URL을 직접 바꾼 경우에도 다른 계약의 근거를 섞지 않는다.
  const capCheckId = query.data?.find((row) => String(row.capCheckId) === requested)?.capCheckId ?? null
  const select = (capId: number | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (capId == null) next.delete('capCheckId')
        else next.set('capCheckId', String(capId))
        return next
      },
      { replace: capId == null },
    )
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
      align: 'number',
      render: (row) => (
        <CapUsage
          value={row.result?.usagePct == null ? undefined : String(row.result.usagePct)}
          status={row.result?.resultStatus}
        />
      ),
    },
    { key: 'status', label: '판정', align: 'center', render: (row) => <CapStatus code={row.result?.resultStatus} /> },
    {
      key: 'basis',
      label: '계산근거',
      align: 'center',
      render: (row) => (
        <Button data-guide-feature="features.contract.contract-tabs.button-5"
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
      <PanelHeading title="1,200% 한도 검증" legacy>
        지급단계별 최신 판정을 합산하지 않고 각각 표시합니다.
      </PanelHeading>
      <div className="contract-tab-body">
        <QueryContent query={query}>
          {(rows) => (
            <TabSection title="지급단계별 판정" count={rows.length}>
              <DataTable
                caption="1,200% 한도 판정"
                columns={columns}
                rows={rows}
                rowKey={(row) => String(row.capCheckId ?? rows.indexOf(row))}
                emptyMessage="조회된 한도 판정이 없습니다."
              />
            </TabSection>
          )}
        </QueryContent>
      </div>
      <CapDetailModal capCheckId={capCheckId} onClose={() => select(null)} />
    </>
  )
}

const arbitrageColumns: TableColumn<ContractArbitrageCheck>[] = [
  { key: 'date', label: '기준일', render: (row) => date(row.asOfDate) },
  { key: 'month', label: '계약차월', render: (row) => int(row.contractMonthNo) },
  {
    key: 'premium',
    label: '누적 납입보험료',
    align: 'number',
    render: (row) => <Amount value={row.cumulativePaidPremium} />,
  },
  {
    key: 'paid',
    label: '확정 수수료 순액',
    align: 'number',
    render: (row) => <Amount value={row.paidCommissionAmount} />,
  },
  {
    key: 'planned',
    label: (
      <>
        지급예정액·계산 기준{' '}
        <EvidenceLink icon="help" label="지급예정액 상한 설명">
          {UPPER_BOUND_NOTE}
        </EvidenceLink>
      </>
    ),
    labelText: '지급예정액·계산 기준',
    align: 'number',
    render: (row) => {
      const basis = plannedBasisPresentation(row.plannedCommissionBasis)
      return (
        <>
          <Amount value={row.plannedCommissionAmount} />
          <small style={{ display: 'block' }}>{basis.label}</small>
          <small className="contract-basis-explanation">{basis.description}</small>
        </>
      )
    },
  },
  {
    key: 'surrender',
    label: '합산 해약환급금',
    align: 'number',
    render: (row) => <Amount value={row.includedSurrenderValueAmount} />,
  },
  { key: 'difference', label: '초과액', align: 'number', render: (row) => <Amount value={row.netDifferenceAmount} /> },
  {
    key: 'status',
    label: '판정',
    align: 'center',
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
  {
    key: 'type',
    label: '유형',
    align: 'center',
    render: (row) => (
      <CategoryBadge value={row.journalType} optionCount={Object.keys(JOURNAL_TYPE).length}>
        <Text value={JOURNAL_TYPE[row.journalType as keyof typeof JOURNAL_TYPE]} />
      </CategoryBadge>
    ),
  },
  { key: 'stage', label: '지급단계', render: (row) => stageLabel(row.paymentStage, row.paymentStageLabel) },
  { key: 'debit', label: '차변', align: 'number', render: (row) => <Amount value={row.debitTotal} /> },
  { key: 'credit', label: '대변', align: 'number', render: (row) => <Amount value={row.creditTotal} /> },
  { key: 'difference', label: '차액', align: 'number', render: (row) => <Amount value={row.differenceAmount} /> },
  {
    key: 'status',
    label: '상태',
    align: 'center',
    render: (row) => <ResultStatus code={row.status} label={row.statusLabel} />,
  },
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
  {
    key: 'status',
    label: '상태',
    align: 'center',
    render: (row) => <ResultStatus code={row.status} label={row.statusLabel} />,
  },
]
const reconciliationColumns: TableColumn<Reconciliation>[] = [
  { key: 'result', label: '결과', render: (row) => <Text value={row.resultTypeLabel || row.resultType} /> },
  { key: 'installment', label: '회차', render: (row) => int(row.installmentNo) },
  { key: 'expected', label: '예상액', align: 'number', render: (row) => <Amount value={row.expectedTotalAmount} /> },
  { key: 'actual', label: '실제액', align: 'number', render: (row) => <Amount value={row.actualTotalAmount} /> },
  { key: 'difference', label: '차액', align: 'number', render: (row) => <Amount value={row.differenceAmount} /> },
]
function TabSection({ title, count, children }: { title: string; count: number; children: ReactNode }) {
  return (
    <section data-guide-feature="features.contract.contract-tabs.section-5" className="contract-tab-section" aria-label={title}>
      <header className="surface-header contract-basic-header">
        <div>
          <h3 className="surface-title">{title}</h3>
          <p>{int(count)}건</p>
        </div>
      </header>
      {count ? children : <div className="empty-state contract-tab-state is-empty">조회된 자료가 없습니다.</div>}
    </section>
  )
}
function PaymentTab({ id }: { id: string }) {
  const query = useQuery(contractTransactionsQuery(id))
  return (
    <>
      <PanelHeading title="지급/대사" legacy>
        수수료 지급 건과 대사 결과를 함께 표시합니다.
      </PanelHeading>
      <div className="contract-tab-body">
        <QueryContent query={query}>
          {(data) => (
            <>
              <TabSection title="수수료 지급" count={data.transactions?.length ?? 0}>
                <DataTable
                  className="contract-payment-table"
                  viewportClassName="contract-tab-viewport"
                  caption="수수료 지급 건"
                  columns={transactionColumns}
                  rows={data.transactions ?? []}
                  rowKey={(row) => String(row.commissionTransactionId ?? data.transactions!.indexOf(row))}
                  emptyMessage="조회된 수수료 지급 건이 없습니다."
                />
              </TabSection>
              <TabSection title="대사 결과" count={data.reconciliations?.length ?? 0}>
                <DataTable
                  className="contract-payment-table"
                  viewportClassName="contract-tab-viewport"
                  caption="대사 결과"
                  columns={reconciliationColumns}
                  rows={data.reconciliations ?? []}
                  rowKey={(row) => String(row.reconciliationResultId ?? data.reconciliations!.indexOf(row))}
                  emptyMessage="조회된 대사 결과가 없습니다."
                />
              </TabSection>
            </>
          )}
        </QueryContent>
      </div>
    </>
  )
}
