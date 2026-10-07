import { present } from '../reference/utils'
import { DataTable } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { EvidenceLink } from '../../components/EvidenceLink'
import { date, int, isNegative, rate, won } from '../../lib/format'
import type { PolicyDetail } from './api'
export function CommissionRules({ rows }: { rows: NonNullable<PolicyDetail['commissionRules']> }) {
  return (
    <DataTable
      caption="수수료 규칙"
      rows={rows}
      rowKey={(r) => String(r.commissionRuleId)}
      emptyMessage="이 정책에는 수수료 규칙이 없습니다."
      columns={[
        { key: 'stage', label: '지급단계', render: (r) => present(r.paymentStageLabel) },
        { key: 'insurer', label: '보험회사', render: (r) => r.insurerName ?? '전체' },
        { key: 'product', label: '상품', render: (r) => r.productName ?? '전체' },
        { key: 'rank', label: '직급', render: (r) => r.agentRankCode ?? '전체' },
        { key: 'item', label: '항목', render: (r) => present(r.itemName) },
        {
          key: 'range',
          label: '회차구간',
          render: (r) =>
            r.installmentFrom == null && r.installmentTo == null
              ? '-'
              : `${int(r.installmentFrom)} ~ ${int(r.installmentTo)}회차`,
        },
        {
          key: 'calculation',
          label: '계산방식',
          render: (r) =>
            r.calculationType === 'RATE' ? '요율' : r.calculationType === 'FIXED' ? '정액' : present(r.calculationType),
        },
        { key: 'basis', label: '기준코드', render: (r) => present(r.basisCode) },
        { key: 'rate', label: '요율(%)', align: 'number', render: (r) => rate(r.ratePct) },
        {
          key: 'amount',
          label: '정액(원)',
          align: 'number',
          render: (r) => (
            <span className={isNegative(r.fixedAmount) ? 'is-negative-amount' : undefined}>{won(r.fixedAmount)}</span>
          ),
        },
      ]}
    />
  )
}
export function CapRuleSets({ rows }: { rows: NonNullable<PolicyDetail['capRuleSets']> }) {
  if (!rows.length) return <p>이 정책에는 1,200% 룰셋이 없습니다.</p>
  return rows.map((set) => (
    <section className="reference-section" key={set.capRuleSetId}>
      <h2>{present(set.paymentStageLabel)} 룰셋</h2>
      <DataTable
        caption={`${set.paymentStageLabel} 한도 룰셋`}
        rows={[set]}
        rowKey={(r) => String(r.capRuleSetId)}
        columns={[
          { key: 'stage', label: '지급단계', render: (r) => present(r.paymentStageLabel) },
          { key: 'from', label: '적용 계약일 시작', render: (r) => date(r.contractDateFrom) },
          { key: 'to', label: '적용 계약일 종료', render: (r) => (r.contractDateTo ? date(r.contractDateTo) : '계속') },
          { key: 'months', label: '초년도 개월', align: 'number', render: (r) => present(r.firstYearMonths) },
          { key: 'multiplier', label: '배수', align: 'number', render: (r) => rate(r.premiumMultiplier) },
          {
            key: 'deduction',
            label: '준법경영비 공제율(%)',
            align: 'number',
            render: (r) => rate(r.complianceDeductionPct),
          },
          { key: 'warning', label: '주의 기준(%)', align: 'number', render: (r) => rate(r.warningUsagePct) },
          { key: 'refund', label: '환급금 가산 조건', render: (r) => present(r.refundAdditionCondition) },
        ]}
      />
      <DataTable
        caption={`${set.paymentStageLabel} 항목별 판정`}
        rows={set.items ?? []}
        rowKey={(r) => String(r.capRuleItemId)}
        columns={[
          {
            key: 'item',
            label: '수수료 항목',
            render: (r) => (
              <>
                {present(r.itemName)} <small>{r.itemCode}</small>
              </>
            ),
          },
          {
            key: 'status',
            label: '판정',
            render: (r) => (
              <StatusBadge
                tone={
                  r.inclusionStatus === 'INCLUDED'
                    ? 'success'
                    : r.inclusionStatus === 'REVIEW_REQUIRED'
                      ? 'review'
                      : 'neutral'
                }
              >
                {r.inclusionStatus === 'INCLUDED'
                  ? '산입'
                  : r.inclusionStatus === 'EXCLUDED'
                    ? '제외'
                    : r.inclusionStatus === 'REVIEW_REQUIRED'
                      ? '검토 필요'
                      : '-'}
              </StatusBadge>
            ),
          },
          { key: 'exclusion', label: '제외유형', render: (r) => present(r.exclusionType) },
          { key: 'attribution', label: '귀속방법', expandable: true, render: (r) => present(r.attributionMethod) },
          {
            key: 'reason',
            label: '판단 이유(필수 저장값)',
            expandable: true,
            render: (r) => (
              <>
                {present(r.decisionReason)}
                {r.evidenceRequiredYn && <StatusBadge tone="warning">증빙필수</StatusBadge>}
              </>
            ),
          },
        ]}
      />
    </section>
  ))
}
export function RefundTables({ rows }: { rows: NonNullable<PolicyDetail['refundRateTables']> }) {
  if (!rows.length) return <p>이 정책에는 예상 해약환급률표가 없습니다.</p>
  return rows.map((table) => (
    <section className="reference-section" key={table.refundRateTableId}>
      <h2>
        {table.insurerName} · {table.productName} · 납입 {table.paymentTermMonths}개월 · 채널 {table.channelCode}
      </h2>
      <div className="reference-meta">
        <span>평균공시이율: {rate(table.averageDeclaredRatePct)}%</span>
        <span>표준해약공제 80% 대상: {table.standardDeduction80Yn ? '예' : '아니오'}</span>
        <span>
          적용기간: {date(table.effectiveFrom)} ~ {table.effectiveTo ? date(table.effectiveTo) : '계속'}
        </span>
        <span>상품/버전: {present(table.sourceProductCode)}</span>
        {table.sourceDocumentRef && (
          <EvidenceLink label={table.sourceDocumentRef}>{table.sourceDocumentRef}</EvidenceLink>
        )}
      </div>
      <DataTable
        caption={`${table.productName} 차월별 예상 해약환급률`}
        rows={table.lines ?? []}
        rowKey={(r) => String(r.contractMonthNo)}
        columns={[
          { key: 'month', label: '차월', render: (r) => `${r.contractMonthNo}차월` },
          { key: 'rate', label: '예상 해약환급률(%)', align: 'number', render: (r) => rate(r.refundRatePct) },
          {
            key: 'note',
            label: '비고',
            render: (r) =>
              r.contractMonthNo === 12 && table.standardDeduction80Yn ? (
                <StatusBadge tone="info">1,200% 한도 가산에 쓰는 값</StatusBadge>
              ) : (
                '-'
              ),
          },
        ]}
      />
    </section>
  ))
}
