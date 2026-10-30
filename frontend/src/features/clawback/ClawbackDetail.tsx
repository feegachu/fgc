import type { ReactNode } from 'react'
import { Button } from '../../components/Button'
import { ContextLink } from '../../components/ContextLink'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { date, dateTime, int } from '../../lib/format'
import { present } from '../arbitrage/utils'
import type { CandidateDetail } from './api'
import { Amount } from './badges'
import { reasonText, triggerLabels } from './labels'
type Detail = Required<CandidateDetail>
function Evidence<T>({
  title,
  rows,
  columns,
  rowKey,
  empty,
}: {
  title: string
  rows: T[] | undefined
  columns: TableColumn<T>[]
  rowKey: (row: T, index: number) => string
  empty: string
}) {
  return (
    <section className="claw-evidence" aria-label={title}>
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
      <ContextLink to={`/arbitrage-checks?${query({ month: r.asOfDate.slice(0, 7), contractNo: c.contractNo })}`}>
        차익거래 검증
      </ContextLink>
    )
  if (c.contractId != null) return <ContextLink to={`/contracts/${c.contractId}`}>계약 사건 이력</ContextLink>
  return '—'
}
const sourceColumns = (c: Candidate): TableColumn<Detail['sourceRefs'][number]>[] => [
  { key: 'type', label: '원천', render: (r) => triggerLabels[r.triggerType ?? ''] ?? present(r.triggerType) },
  { key: 'id', label: '사건·검증 ID', cellClassName: 'tabular-nums', render: (r) => present(r.sourceId) },
  {
    key: 'date',
    label: '효력일 · 검증기준일',
    cellClassName: 'tabular-nums',
    // 차익거래는 effectiveAt이 없다. 검증기준일을 효력일처럼 바꿔 쓰지 않는다.
    render: (r) => (r.effectiveAt ? `효력 ${dateTime(r.effectiveAt)}` : `검증기준 ${date(r.asOfDate)}`),
  },
  { key: 'status', label: '상태', render: (r) => present(r.status) },
  { key: 'open', label: '원천 열기', render: (r) => sourceLink(c, r) },
]
const paymentColumns = (c: Candidate): TableColumn<Detail['paymentAttributions'][number]>[] => [
  { key: 'id', label: '귀속 ID', cellClassName: 'tabular-nums', render: (r) => present(r.transactionAttributionId) },
  {
    key: 'tx',
    label: '지급 ID',
    cellClassName: 'tabular-nums',
    // 지급 목록을 이 계약으로 거르고 해당 지급 행을 선택한 상태로 연다.
    render: (r) =>
      r.commissionTransactionId != null && c.contractNo ? (
        <ContextLink
          to={`/transactions?${query({ contractNo: c.contractNo, selectedId: String(r.commissionTransactionId) })}`}
        >
          {r.commissionTransactionId}
        </ContextLink>
      ) : (
        present(r.commissionTransactionId)
      ),
  },
  { key: 'item', label: '수수료 항목', cellClassName: 'tabular-nums', render: (r) => present(r.commissionItemId) },
  { key: 'no', label: '회차', align: 'left', render: (r) => int(r.installmentNo) },
  { key: 'paidOn', label: '지급일', cellClassName: 'tabular-nums', render: (r) => date(r.paidOn) },
  { key: 'status', label: '상태', render: (r) => present(r.status) },
  { key: 'amount', label: '귀속 금액', align: 'number', render: (r) => <Amount value={r.attributedAmount} /> },
]
const scheduleColumns: TableColumn<Detail['scheduleLines'][number]>[] = [
  { key: 'no', label: '회차', align: 'left', render: (r) => int(r.installmentNo) },
  { key: 'due', label: '예정일', cellClassName: 'tabular-nums', render: (r) => date(r.dueDate) },
  { key: 'status', label: '상태', render: (r) => present(r.lineStatus) },
  { key: 'expected', label: '예정액', align: 'number', render: (r) => <Amount value={r.expectedAmount} /> },
  { key: 'paid', label: '확정 지급', align: 'number', render: (r) => <Amount value={r.confirmedPaidAmount} /> },
  { key: 'unpaid', label: '미지급', align: 'number', render: (r) => <Amount value={r.unpaidAmount} /> },
]
const effectColumns: TableColumn<Detail['effects'][number]>[] = [
  { key: 'job', label: '후속 작업', render: (r) => present(r.processingJob) },
  { key: 'status', label: '처리 상태', render: (r) => present(r.processingStatus) },
  { key: 'at', label: '처리 시각', cellClassName: 'tabular-nums', render: (r) => dateTime(r.processedAt) },
  { key: 'reason', label: '실패 사유', render: (r) => present(r.failureReason) },
]
const policyColumns: TableColumn<Detail['policyCandidates'][number]>[] = [
  { key: 'code', label: '정책', render: (r) => `${present(r.policyCode)} · ${present(r.policyName)}` },
  { key: 'version', label: '판', align: 'left', render: (r) => `v${int(r.versionNo)}` },
  {
    key: 'period',
    label: '유효 기간',
    cellClassName: 'tabular-nums',
    render: (r) => `${date(r.effectiveFrom)} ~ ${r.effectiveTo ? date(r.effectiveTo) : ''}`,
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
export function ClawbackDetail({ detail }: { detail: CandidateDetail }) {
  const c = detail.candidate ?? {}
  const reasons = c.reviewReasons ?? []
  const canCalculate = c.allowedActions?.includes('CALCULATE') ?? false
  return (
    <>
      <dl className="claw-detail-list">
        <Row label="후보 ID · 계약번호">
          {present(c.candidateId)} ·{' '}
          {c.contractId != null ? (
            <ContextLink to={`/contracts/${c.contractId}`}>{present(c.contractNo)}</ContextLink>
          ) : (
            present(c.contractNo)
          )}
        </Row>
        <Row label="보험회사 · 상품">
          {present(c.insurerName)} · {present(c.productName)}
        </Row>
        <Row label="설계사">{present(c.agentName)}</Row>
        <Row label="발생 사유">
          {triggerLabels[c.triggerType ?? ''] ?? present(c.triggerType)} · 원천 ID {present(c.sourceId)}
        </Row>
        <Row label={`${present(c.dateBasisLabel)} · 계약일`}>
          {date(c.eventDate)} · {date(c.contractDate)}
        </Row>
        <Row label="기지급액 · 미지급액">
          <Amount value={c.paidAmount} /> · <Amount value={c.unpaidAmount} />
        </Row>
        <Row label="경과월">
          {detail.elapsedMonths == null
            ? '계산 불가 (사건 기준일이 계약일보다 앞섬)'
            : `${int(detail.elapsedMonths)}개월`}
        </Row>
        {/* 서버의 policyVersionId는 후보가 하나일 때 채워 주는 값이지 적용 선택 결과가 아니다(인터페이스 §4-4-5). */}
        <Row label="정책 후보">
          {c.policyVersionId != null
            ? `후보 1개 · 정책버전 ${c.policyVersionId} (적용 선택 전)`
            : detail.policyCandidates?.length
              ? `후보 ${detail.policyCandidates.length}개 · 적용 선택 필요`
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
            '—'
          )}
        </Row>
      </dl>
      <Evidence
        title="원천 사건 · 검증 근거"
        rows={detail.sourceRefs}
        columns={sourceColumns(c)}
        rowKey={(r, i) => `${r.triggerType}-${r.sourceId}-${i}`}
        empty="연결된 원천 근거가 없습니다."
      />
      <Evidence
        title="연결 지급 · 귀속"
        rows={detail.paymentAttributions}
        columns={paymentColumns(c)}
        rowKey={(r, i) => `${r.transactionAttributionId ?? 'x'}-${i}`}
        empty="연결된 기지급 내역이 없습니다."
      />
      <Evidence
        title="지급 스케줄"
        rows={detail.scheduleLines}
        columns={scheduleColumns}
        rowKey={(r, i) => `${r.scheduleLineId ?? 'x'}-${i}`}
        empty="연결된 지급 스케줄이 없습니다."
      />
      <Evidence
        title="보류 · 후속 처리 근거"
        rows={detail.effects}
        columns={effectColumns}
        rowKey={(r, i) => `${r.processingJob}-${r.validationRunId ?? 'x'}-${i}`}
        empty="기록된 후속 처리가 없습니다."
      />
      <section className="claw-evidence claw-calculation" aria-label="환수 계산">
        <h3 className="claw-evidence-title">
          환수 계산 <span className="claw-policy-badge">회사 정책</span>
        </h3>
        {detail.policyCandidates?.length ? (
          <DataTable
            viewportClassName="claw-evidence-table"
            caption="적용 가능한 환수 정책 버전"
            rows={detail.policyCandidates}
            rowKey={(r) => String(r.policyVersionId)}
            columns={policyColumns}
          />
        ) : (
          <p className="claw-evidence-empty">적용 가능한 환수 정책 버전이 없습니다.</p>
        )}
        <p className="claw-evidence-empty">
          {detail.latestCalculation
            ? `최신 계산판 v${int(detail.latestCalculation.calculationVersion)} · ${dateTime(detail.latestCalculation.calculatedAt)}`
            : '저장된 계산판이 없습니다.'}
        </p>
        <div className="claw-calculation-actions">
          {/* 계산 API(IF-API-85)는 있지만 정책 선택·사유 입력·결과표 화면은 #629 후속에서 연결한다.
              그 전에는 서버가 CALCULATE를 허용해도 눌러도 아무 일이 없는 버튼을 켜지 않는다. */}
          <Button disabled>환수액 계산</Button>
          <p className="field-helper">
            {canCalculate
              ? '계산할 수 있는 후보입니다. 정책 선택·사유 입력 화면은 후속 작업에서 연결합니다.'
              : '서버가 계산을 허용한 후보만 계산할 수 있습니다. 위 검토 사유를 확인하세요.'}
          </p>
        </div>
      </section>
    </>
  )
}
