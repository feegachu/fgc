import { useState, type ReactNode } from 'react'
import { ContextLink } from '../../components/ContextLink'
import { DataTable } from '../../components/DataTable'
import { CategoryBadge, StatusValue } from '../../components/StatusBadge'
import type { TableColumn } from '../../components/DataTable'
import { date, dateTime, int } from '../../lib/format'
import { isDeveloperText } from '../../lib/screenText'
import { present } from '../arbitrage/utils'
import { useCommissionItems } from '../base/api'
import type { CandidateDetail } from './api'
import { Amount } from './badges'
import { ClawbackCalculation } from './ClawbackCalculation'
import { policyLabel, reasonText, triggerLabels } from './labels'
import { guideFeatureId } from '../assistant/feature-ids'
import {
  arbitrageStatusLabels,
  contractStatusLabels,
  paymentStatusLabels,
  processingJobLabels,
  processingStatusLabels,
} from '../contract/labels'
import { LINE_STATUS } from '../schedule/labels'

const sourceStatusLabels: Record<string, string> = { ...contractStatusLabels, ...arbitrageStatusLabels }
type Detail = Required<CandidateDetail>
function Evidence<T>({
  scope,
  title,
  rows,
  columns,
  rowKey,
  empty,
  hidden = false,
  tab,
}: {
  scope: 'source' | 'payment' | 'schedule' | 'effects'
  title: string
  rows: T[] | undefined
  columns: TableColumn<T>[]
  rowKey: (row: T, index: number) => string
  empty: string
  hidden?: boolean
  tab?: string
}) {
  return (
    <section data-guide-feature={guideFeatureId('clawback.evidence', scope)} className="claw-evidence" aria-label={title}
      hidden={hidden} id={tab ? `claw-panel-${scope}` : undefined} role={tab ? 'tabpanel' : undefined} aria-labelledby={tab}>
      <h3 className="claw-evidence-title">{title}</h3>
      {rows?.length ? (
        <DataTable
          viewportClassName="claw-evidence-table"
          caption={title}
          rows={rows}
          rowKey={(row) => rowKey(row, rows.indexOf(row))}
          columns={columns}
        />
      ) : (
        <p className="claw-evidence-empty">{empty}</p>
      )}
    </section>
  )
}
type Candidate = NonNullable<CandidateDetail['candidate']>
const query = (values: Record<string, string>) => new URLSearchParams(values).toString()
/** 원천으로 이동. 상태 사건은 계약 상세의 사건 이력, 차익거래는 검증기준월의 차익거래 검증 화면. */
function sourceLink(c: Candidate, r: Detail['sourceRefs'][number]) {
  if (r.triggerType === 'ARBITRAGE' && r.asOfDate && c.contractNo)
    return (
      <ContextLink data-guide-feature="features.clawback.clawback-detail.contextlink" to={`/arbitrage-checks?${query({ month: r.asOfDate.slice(0, 7), contractNo: c.contractNo })}`}>
        차익거래 검증
      </ContextLink>
    )
  if (c.contractId != null) return <ContextLink data-guide-feature="features.clawback.clawback-detail.contextlink-2" to={`/contracts/${c.contractId}`}>계약 상태이력</ContextLink>
  return '-'
}
const sourceColumns = (c: Candidate): TableColumn<Detail['sourceRefs'][number]>[] => [
  {
    key: 'type',
    label: '근거',
    render: (r) => (
      <CategoryBadge value={r.triggerType} optionCount={Object.keys(triggerLabels).length}>
        {triggerLabels[r.triggerType ?? ''] ?? present(r.triggerType)}
      </CategoryBadge>
    ),
  },
  {
    key: 'date',
    label: '효력일, 검증기준일',
    cellClassName: 'tabular-nums',
    // 차익거래는 effectiveAt이 없다. 검증기준일을 효력일처럼 바꿔 쓰지 않는다.
    render: (r) => (r.effectiveAt ? `효력 ${date(r.effectiveAt)}` : `검증기준 ${date(r.asOfDate)}`),
  },
  {
    key: 'status',
    label: '상태',
    align: 'center',
    render: (r) => (
      <StatusValue
        value={r.status}
        optionCount={8}
        tablePresentation="dot"
        label={sourceStatusLabels[r.status ?? ''] ?? '확인 필요'}
      />
    ),
  },
  { key: 'open', label: '원본 보기', render: (r) => sourceLink(c, r) },
]
const paymentColumns = (
  c: Candidate,
  itemName: (commissionItemId?: number) => string,
): TableColumn<Detail['paymentAttributions'][number]>[] => [
  // 좁은 상세 칸에서 귀속 금액이 가로 스크롤 뒤로 숨지 않게 항목, 회차, 금액을 앞에 두고 추적용 번호는 뒤에 둔다.
  { key: 'item', label: '수수료 항목', render: (r) => itemName(r.commissionItemId) },
  { key: 'no', label: '회차', align: 'left', render: (r) => int(r.installmentNo) },
  { key: 'amount', label: '귀속 금액', align: 'number', render: (r) => <Amount value={r.attributedAmount} /> },
  { key: 'paidOn', label: '지급일', cellClassName: 'tabular-nums', render: (r) => date(r.paidOn) },
  {
    key: 'status',
    label: '상태',
    align: 'center',
    render: (r) => (
      <StatusValue
        value={r.status}
        optionCount={3}
        tablePresentation="dot"
        label={paymentStatusLabels[r.status ?? ''] ?? '확인 필요'}
      />
    ),
  },
  {
    key: 'tx',
    label: '지급 건',
    cellClassName: 'tabular-nums',
    // 지급 목록을 이 계약으로 거르고 해당 지급 행을 선택한 상태로 연다.
    // 지급 목록은 정산월 키가 없으면 기준 정산월로 거르므로(#752) 다른 월 지급도 보이게 전체월(빈 값)로 연다.
    render: (r) =>
      r.commissionTransactionId != null && c.contractNo ? (
        <ContextLink data-guide-feature="features.clawback.clawback-detail.contextlink-3"
          to={`/transactions?${query({ contractNo: c.contractNo, settlementMonth: '', selectedId: String(r.commissionTransactionId) })}`}
        >
          지급 건 보기
        </ContextLink>
      ) : (
        '-'
      ),
  },
]
const scheduleColumns: TableColumn<Detail['scheduleLines'][number]>[] = [
  { key: 'no', label: '회차', align: 'left', render: (r) => int(r.installmentNo) },
  { key: 'due', label: '예정일', cellClassName: 'tabular-nums', render: (r) => date(r.dueDate) },
  {
    key: 'status',
    label: '상태',
    align: 'center',
    render: (r) => (
      <StatusValue
        value={r.lineStatus}
        optionCount={6}
        tablePresentation="dot"
        label={LINE_STATUS[r.lineStatus ?? ''] ?? '확인 필요'}
      />
    ),
  },
  { key: 'expected', label: '예정액', align: 'number', render: (r) => <Amount value={r.expectedAmount} /> },
  { key: 'paid', label: '확정 지급', align: 'number', render: (r) => <Amount value={r.confirmedPaidAmount} /> },
  { key: 'unpaid', label: '미지급', align: 'number', render: (r) => <Amount value={r.unpaidAmount} /> },
]
const effectColumns: TableColumn<Detail['effects'][number]>[] = [
  { key: 'job', label: '후속 작업', render: (r) => processingJobLabels[r.processingJob ?? ''] ?? '처리 작업' },
  {
    key: 'status',
    label: '처리 상태',
    render: (r) => (
      <StatusValue
        value={r.processingStatus}
        optionCount={7}
        tablePresentation="dot"
        label={processingStatusLabels[r.processingStatus ?? ''] ?? '확인 필요'}
      />
    ),
  },
  { key: 'at', label: '처리 시각', cellClassName: 'tabular-nums', render: (r) => dateTime(r.processedAt) },
  {
    key: 'reason',
    label: '실패 사유',
    render: (r) => (r.failureReason && isDeveloperText(r.failureReason) ? '시스템 오류' : present(r.failureReason)),
  },
]
function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}
export function ClawbackDetail({ detail, revised = false }: { detail: CandidateDetail; revised?: boolean }) {
  const [activeTab, setActiveTab] = useState('summary')
  const tabs = [['summary', '계약·검토 정보'], ['source', '원천 사건'], ['payment', '지급·귀속'], ['schedule', '지급 스케줄'], ['effects', '후속 처리']] as const
  const c = detail.candidate ?? {}
  const reasons = c.reviewReasons ?? []
  const policies = detail.policyCandidates ?? []
  const items = useCommissionItems(c.eventDate ?? c.contractDate ?? '')
  const itemName = (commissionItemId?: number) => {
    if (commissionItemId == null) return '-'
    const name = items.data?.find((item) => item.commissionItemId === commissionItemId)?.itemName
    return name ?? (items.isLoading ? '…' : '항목 확인 필요')
  }
  return (
    <>
      {revised && <div className="claw-detail-tabs" role="tablist" aria-label="환수 검토 근거">
        {tabs.map(([key, label], index) => <button data-guide-feature="features.clawback-detail.button-1" key={key} type="button" id={`claw-tab-${key}`} role="tab"
          aria-selected={activeTab === key} aria-controls={`claw-panel-${key}`} tabIndex={activeTab === key ? 0 : -1}
          onClick={() => setActiveTab(key)} onKeyDown={event => {
            const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length
              : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : null
            if (next != null) { event.preventDefault(); setActiveTab(tabs[next][0]); document.getElementById(`claw-tab-${tabs[next][0]}`)?.focus() }
          }}>{label}</button>)}
      </div>}
      <div id={revised ? 'claw-panel-summary' : undefined} role={revised ? 'tabpanel' : undefined} aria-labelledby={revised ? 'claw-tab-summary' : undefined} hidden={revised && activeTab !== 'summary'}>
      <dl className="claw-detail-list">
        <Row label="계약번호">
          {c.contractId != null ? (
            <ContextLink data-guide-feature="features.clawback.clawback-detail.contextlink-4" to={`/contracts/${c.contractId}`}>{present(c.contractNo)}</ContextLink>
          ) : (
            present(c.contractNo)
          )}
        </Row>
        <Row label="보험회사, 상품">
          {present(c.insurerName)}, {present(c.productName)}
        </Row>
        <Row label="설계사">{present(c.agentName)}</Row>
        <Row label="발생 사유">{triggerLabels[c.triggerType ?? ''] ?? '확인 필요'}</Row>
        <Row label={`${present(c.dateBasisLabel)}, 계약일`}>
          {date(c.eventDate)}, {date(c.contractDate)}
        </Row>
        <Row label="기지급액, 미지급액">
          <Amount value={c.paidAmount} />, <Amount value={c.unpaidAmount} />
        </Row>
        <Row label="경과월">
          {detail.elapsedMonths == null
            ? '계산 불가 (발생 기준일이 계약일보다 앞섬)'
            : `${int(detail.elapsedMonths)}개월`}
        </Row>
        {/* 서버의 policyVersionId는 후보가 하나일 때 채워 주는 값이지 적용 선택 결과가 아니다(인터페이스 §4-4-5). */}
        <Row label="정책 후보">
          {policies.length === 1
            ? `${policyLabel(policies[0])}, 아래 환수 계산에서 확인`
            : policies.length
              ? `${int(policies.length)}개, 아래 환수 계산에서 선택`
              : '유효한 환수 정책 없음'}
        </Row>
        <Row label="검토 사유">
          {reasons.length ? (
            <ul className="claw-reason-list">
              {reasons.map((code) => (
                <li key={code}>{reasonText(code)}</li>
              ))}
            </ul>
          ) : (
            '-'
          )}
        </Row>
      </dl>
      </div>
      <Evidence
        scope="source"
        tab={revised ? 'claw-tab-source' : undefined} hidden={revised && activeTab !== 'source'}
        title="발생 원인과 검증 근거"
        rows={detail.sourceRefs}
        columns={sourceColumns(c)}
        rowKey={(r, i) => `${r.triggerType}-${r.sourceId}-${i}`}
        empty="연결된 근거가 없습니다."
      />
      <Evidence
        scope="payment"
        tab={revised ? 'claw-tab-payment' : undefined} hidden={revised && activeTab !== 'payment'}
        title="연결 지급, 귀속"
        rows={detail.paymentAttributions}
        columns={paymentColumns(c, itemName)}
        rowKey={(r, i) => `${r.transactionAttributionId ?? 'x'}-${i}`}
        empty="연결된 기지급 내역이 없습니다."
      />
      <Evidence
        scope="schedule"
        tab={revised ? 'claw-tab-schedule' : undefined} hidden={revised && activeTab !== 'schedule'}
        title="지급 스케줄"
        rows={detail.scheduleLines}
        columns={scheduleColumns}
        rowKey={(r, i) => `${r.scheduleLineId ?? 'x'}-${i}`}
        empty="연결된 지급 스케줄이 없습니다."
      />
      <Evidence
        scope="effects"
        tab={revised ? 'claw-tab-effects' : undefined} hidden={revised && activeTab !== 'effects'}
        title="보류, 후속 처리 근거"
        rows={detail.effects}
        columns={effectColumns}
        rowKey={(r, i) => `${r.processingJob}-${r.validationRunId ?? 'x'}-${i}`}
        empty="기록된 후속 처리가 없습니다."
      />
      {/* 후보가 바뀌면 고른 정책과 입력한 사유를 새로 시작한다. */}
      <ClawbackCalculation key={c.candidateId} detail={detail} itemName={itemName} />
    </>
  )
}
