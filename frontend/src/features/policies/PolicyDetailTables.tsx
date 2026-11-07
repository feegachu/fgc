import { Fragment } from 'react'
import { ReferenceText } from '../reference/ReferenceUI'
import { present } from '../reference/utils'
import { DataTable } from '../../components/DataTable'
import { StatusBadge, CategoryBadge } from '../../components/StatusBadge'
import { EvidenceLink } from '../../components/EvidenceLink'
import { date, int, isNegative, rate, won } from '../../lib/format'
import type { PolicyDetail } from './api'
import { policyAgentRankLabel, policyCodeLabel, policySeedText } from './labels'
import { screenMessage } from '../../lib/screenText'
import { channelLabels } from '../base/columns'

const refundConditions: Record<string, string> = {
  NONE: '없음',
  STANDARD_DEDUCTION_80: '표준해약공제액 80% 이상 상품',
}
export function CommissionRules({ rows }: { rows: NonNullable<PolicyDetail['commissionRules']> }) {
  if (!rows.length)
    return (
      <div className="empty-state policy-empty-state" role="status">
        이 정책에는 수수료 규칙이 없습니다.
      </div>
    )
  return (
    <DataTable
      className="policy-rule-table"
      viewportClassName="policy-detail-table-viewport"
      caption="수수료 규칙"
      rows={rows}
      rowKey={(r) => String(r.commissionRuleId)}
      emptyMessage="이 정책에는 수수료 규칙이 없습니다."
      columns={[
        { key: 'stage', label: '지급단계', render: (r) => present(r.paymentStageLabel) },
        {
          key: 'insurer',
          label: '보험사',
          cellClassName: 'policy-disclosure-cell',
          render: (r) => <ReferenceText value={r.insurerName ?? '전체'} limit={24} singleLine />,
        },
        {
          key: 'product',
          label: '상품',
          cellClassName: 'policy-disclosure-cell',
          render: (r) => <ReferenceText value={r.productName ?? '전체'} limit={28} />,
        },
        { key: 'rank', label: '직급', render: (r) => policyAgentRankLabel(r.agentRankCode, r.paymentStage) },
        {
          key: 'item',
          label: '수수료 항목',
          cellClassName: 'policy-disclosure-cell',
          render: (r) => <ReferenceText value={present(r.itemName)} limit={28} />,
        },
        {
          key: 'range',
          label: '회차 구간',
          render: (r) =>
            r.installmentFrom == null && r.installmentTo == null
              ? '-'
              : `${int(r.installmentFrom)} ~ ${int(r.installmentTo)}회차`,
        },
        {
          key: 'calculation',
          label: '계산방식',
          render: (r) => (
            <CategoryBadge value={r.calculationType} optionCount={2}>
              {r.calculationType === 'RATE'
                ? '요율'
                : r.calculationType === 'FIXED'
                  ? '정액'
                  : present(r.calculationType)}
            </CategoryBadge>
          ),
        },
        {
          key: 'basis',
          label: '계산 기준',
          cellClassName: 'policy-disclosure-cell',
          render: (r) => (
            <CategoryBadge value={r.basisCode} optionCount={1}>
              <ReferenceText value={policyCodeLabel(r.basisCode)} limit={20} singleLine />
            </CategoryBadge>
          ),
        },
        { key: 'rate', label: '요율(%)', align: 'left', render: (r) => rate(r.ratePct) },
        {
          key: 'amount',
          label: '정액(원)',
          align: 'number',
          render: (r) => (
            <span className={isNegative(r.fixedAmount) ? 'is-negative-amount' : undefined}>
              {won(r.fixedAmount).replace(/원$/, '')}
            </span>
          ),
        },
      ]}
    />
  )
}
export function CapRuleSets({ rows }: { rows: NonNullable<PolicyDetail['capRuleSets']> }) {
  if (!rows.length)
    return (
      <div className="empty-state policy-empty-state" role="status">
        이 정책에는 1,200% 한도 규칙이 없습니다.
      </div>
    )
  return (
    <>
      <DataTable
        className="policy-cap-table"
        viewportClassName="policy-detail-table-viewport"
        caption="선택 정책의 1,200% 한도 규칙"
        rows={rows}
        rowKey={(r) => String(r.capRuleSetId)}
        columns={[
          { key: 'stage', label: '지급단계', render: (r) => present(r.paymentStageLabel) },
          {
            key: 'dates',
            label: '계약일 범위',
            render: (r) => `${date(r.contractDateFrom)} ~ ${r.contractDateTo ? date(r.contractDateTo) : '계속'}`,
          },
          { key: 'months', label: '초년도 개월', align: 'left', render: (r) => present(r.firstYearMonths) },
          { key: 'multiplier', label: '배수', align: 'left', render: (r) => rate(r.premiumMultiplier) },
          {
            key: 'deduction',
            label: '준법경영비 공제율(%)',
            align: 'left',
            render: (r) => rate(r.complianceDeductionPct),
          },
          { key: 'warning', label: '주의 기준(%)', align: 'left', render: (r) => rate(r.warningUsagePct) },
          {
            key: 'refund',
            label: '환급금 가산 조건',
            cellClassName: 'policy-disclosure-cell',
            render: (r) => (
              <ReferenceText
                value={refundConditions[r.refundAdditionCondition ?? ''] ?? r.refundAdditionCondition}
                limit={28}
              />
            ),
          },
        ]}
      />
      {rows
        .filter((set) => set.items?.length)
        .map((set) => (
          <Fragment key={set.capRuleSetId}>
            <h3 className="policy-detail-title">항목별 산입 판정, {set.paymentStageLabel}</h3>
            <DataTable
              className="policy-cap-item-table"
              viewportClassName="policy-detail-table-viewport"
              caption={`${set.paymentStageLabel} 항목별 판정`}
              rows={set.items ?? []}
              rowKey={(r) => String(r.capRuleItemId)}
              columns={[
                {
                  key: 'item',
                  label: '수수료 항목',
                  cellClassName: 'policy-disclosure-cell',
                  render: (r) => <ReferenceText value={r.itemName} limit={28} />,
                },
                {
                  key: 'status',
                  label: '판정',
                  align: 'center',
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
                            ? '검토필요'
                            : '-'}
                    </StatusBadge>
                  ),
                },
                {
                  key: 'exclusion',
                  label: '제외 유형',
                  cellClassName: 'policy-disclosure-cell',
                  render: (r) => (
                    <CategoryBadge value={r.exclusionType} optionCount={2}>
                      <ReferenceText value={policyCodeLabel(r.exclusionType)} limit={24} singleLine />
                    </CategoryBadge>
                  ),
                },
                {
                  key: 'attribution',
                  label: '귀속 방법',
                  cellClassName: 'policy-disclosure-cell',
                  render: (r) => (
                    <CategoryBadge value={r.attributionMethod} optionCount={5}>
                      <ReferenceText value={policyCodeLabel(r.attributionMethod)} limit={24} singleLine />
                    </CategoryBadge>
                  ),
                },
                {
                  key: 'reason',
                  label: '판단 이유',
                  cellClassName: 'policy-disclosure-cell',
                  render: (r) => <ReferenceText value={screenMessage(r.decisionReason)} limit={36} />,
                },
              ]}
            />
          </Fragment>
        ))}
    </>
  )
}
export function RefundTables({ rows }: { rows: NonNullable<PolicyDetail['refundRateTables']> }) {
  if (!rows.length)
    return (
      <div className="empty-state policy-empty-state" role="status">
        이 정책에는 예상 해약환급률표가 없습니다.
      </div>
    )
  return rows.map((table) => (
    <Fragment key={table.refundRateTableId}>
      <h3 className="policy-detail-title">
        {table.insurerName}, {table.productName}, 납입 {table.paymentTermMonths}개월, 채널{' '}
        {channelLabels[table.channelCode ?? ''] ?? table.channelCode}
      </h3>
      <dl className="policy-refund-metadata">
        <div>
          <dt>표준해약공제액 80% 이상 공제 대상</dt>
          <dd>{table.standardDeduction80Yn ? '예' : '아니오'}</dd>
        </div>
        <div>
          <dt>적용 기간</dt>
          <dd>
            {date(table.effectiveFrom)} ~ {table.effectiveTo ? date(table.effectiveTo) : '계속'}
          </dd>
        </div>
        <div>
          <dt>자료 출처</dt>
          <dd>
            {table.sourceDocumentRef ? (
              <EvidenceLink label={policySeedText(table.sourceDocumentRef)}>
                {policySeedText(table.sourceDocumentRef)}
              </EvidenceLink>
            ) : (
              '-'
            )}
          </dd>
        </div>
      </dl>
      <DataTable
        className="policy-refund-table"
        viewportClassName="policy-detail-table-viewport"
        caption={`${table.productName} 차월별 예상 해약환급률`}
        rows={table.lines ?? []}
        rowKey={(r) => String(r.contractMonthNo)}
        columns={[
          { key: 'month', label: '차월', align: 'left', render: (r) => `${r.contractMonthNo}차월` },
          { key: 'rate', label: '예상 해약환급률(%)', align: 'left', render: (r) => `${rate(r.refundRatePct)}%` },
          {
            key: 'note',
            label: '비고',
            align: 'center',
            render: (r) =>
              r.contractMonthNo === 12 && table.standardDeduction80Yn ? (
                <StatusBadge kind="text" tone="warning">
                  1,200% 한도 가산에 쓰는 값
                </StatusBadge>
              ) : (
                ''
              ),
          },
        ]}
      />
    </Fragment>
  ))
}
