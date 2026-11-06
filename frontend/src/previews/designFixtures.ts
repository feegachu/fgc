import type { CandidateRow } from '../features/clawback/api'
import type { CapBasis, CapItem } from '../features/cap/api'
import type { ArbitrageRow } from '../features/arbitrage/api'

// Synthetic, read-only fixtures for comparing the actual screens in /design-preview.
export const designPreviewUser = {
  loginId: 'design-preview',
  userName: '디자인 미리보기',
  roleCode: 'SETTLEMENT',
  demoMonth: '2026-07',
  canProcess: true,
  canViewAuditLog: false,
  canHandleException: true,
  canReverseJournal: false,
  canFinalizeValidation: false,
}
const page = { content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }
const capItems: CapItem[] = Array.from({ length: 16 }, (_, index) => {
  const violation = index < 3
  const paymentStage = index < 11 ? 'INSURER_TO_GA' : 'GA_TO_FC'
  const includedAmount = violation ? 1299600 : 894000
  return {
    capCheckId: index + 1,
    contractId: index + 1,
    contractNo: `PREVIEW-2026-${String(index + 1).padStart(3, '0')}`,
    paymentStage,
    paymentStageLabel: paymentStage === 'INSURER_TO_GA' ? '보험회사 → GA' : 'GA → 설계사',
    asOfDate: '2026-07-31',
    basePremiumAmount: 100000,
    refund12mAmount: 0,
    complianceDeductionAmount: 0,
    limitAmount: 1200000,
    includedAmount,
    remainingAmount: 1200000 - includedAmount,
    usagePct: violation ? '108.3' : '74.5',
    resultStatus: violation ? 'VIOLATION' : 'NORMAL',
    resultStatusLabel: violation ? '위반' : '정상',
    capRuleSetId: 1,
  }
})
const clawbackCandidates: CandidateRow[] = [12, 11, 10].map((candidateId, index) => ({
  candidateId,
  contractId: candidateId,
  contractNo: `PREVIEW-2026-00${index + 1}`,
  insurerName: '미리보기 생명',
  productName: '예시 건강보험',
  agentName: '예시 설계사',
  triggerType: 'ARBITRAGE',
  sourceId: candidateId,
  eventDate: '2026-07-31',
  dateBasisLabel: '검증기준일',
  contractDate: `2026-05-0${index + 1}`,
  calculationStatus: 'UNCALCULATED',
  reviewReasons: [],
  paidAmount: 100000,
  unpaidAmount: 0,
  calculatedAmount: null,
  allowedActions: [],
}))
const run = {
  validationRunId: 1,
  validationMonth: '2026-07-01',
  runNo: 1,
  runType: 'MONTHLY',
  status: 'COMPLETED',
  statusLabel: '계산완료',
  currentStep: 8,
  triggeredBy: 'design-preview',
}
const reconciliation = {
  reconciliationRunId: 41,
  validationRunId: 1,
  settlementMonth: '2026-07-01',
  paymentStage: 'GA_TO_FC',
  paymentStageLabel: 'GA → 설계사',
  status: 'COMPLETED',
  statusLabel: '계산완료',
  targetCount: 47,
  matchedCount: 18,
  exceptionCount: 29,
  matchRatePct: 38.2,
  differenceTotal: -5000,
}
const arbitrageItems: ArbitrageRow[] = [
  {
    arbitrageCheckId: 2, contractId: 11, contractNo: 'PREVIEW-2026-001',
    paymentStage: 'GA_TO_FC', paymentStageLabel: 'GA → 설계사',
    asOfDate: '2026-07-02', contractMonthNo: 2,
    paidCommissionAmount: 640000, plannedCommissionAmount: 850000,
    includedSurrenderValueAmount: 0, cumulativePaidPremium: 100000,
    netDifferenceAmount: 540000, projectedExcessAmount: 1390000,
    refundAdditionAppliedYn: false, surrenderValueSourceType: 'NOT_APPLICABLE', surrenderValueSourceTypeLabel: '적용 대상 아님',
    resultStatus: 'CANDIDATE', resultStatusLabel: '검토대상', candidateType: 'CURRENT_EXCESS',
    decisionReason: '현재 확정 수수료와 해약환급금이 누적 납입보험료를 초과합니다.',
  },
  {
    arbitrageCheckId: 1, contractId: 11, contractNo: 'PREVIEW-2026-001',
    paymentStage: 'GA_TO_FC', paymentStageLabel: 'GA → 설계사',
    asOfDate: '2026-07-01', contractMonthNo: 2,
    paidCommissionAmount: 100000, plannedCommissionAmount: 1390000,
    includedSurrenderValueAmount: 0, cumulativePaidPremium: 100000,
    netDifferenceAmount: 0, projectedExcessAmount: 1390000,
    refundAdditionAppliedYn: false, surrenderValueSourceType: 'NOT_APPLICABLE', surrenderValueSourceTypeLabel: '적용 대상 아님',
    resultStatus: 'CLEAR', resultStatusLabel: '이상없음', candidateType: 'PROJECTED_EXCESS',
    decisionReason: '현재 확정 수수료가 누적 납입보험료를 초과하지 않습니다.',
  },
]

export function designPreviewData(url: URL): unknown {
  const path = url.pathname
  if (path === '/api/v1/auth/me') return designPreviewUser
  const capDetail = /^\/api\/v1\/cap-checks\/(\d+)\/details$/.exec(path)
  if (capDetail) {
    const capCheck = capItems.find((row) => row.capCheckId === Number(capDetail[1]))
    if (capCheck)
      return {
        capCheck,
        calculationSnapshot: { premiumMultiplier: 12, warningUsagePct: 90 },
        warningStartUsagePct: '90.0',
        details: [
          {
            detailSeq: 1,
            commissionItemName: '예시 기본수수료',
            amount: capCheck.includedAmount ?? undefined,
            classificationSnapshot: 'INCLUDED',
            decisionReason: '미리보기 산입 금액',
          },
        ],
      } satisfies CapBasis
  }
  if (path === '/api/v1/clawbacks/candidates')
    return { ...page, content: clawbackCandidates, totalElements: clawbackCandidates.length, totalPages: 1 }
  if (path.startsWith('/api/v1/clawbacks/candidates/'))
    return {
      candidate: clawbackCandidates.find((row) => row.candidateId === Number(path.split('/').at(-1))),
      sourceRefs: [],
      paymentAttributions: [],
      scheduleLines: [],
      effects: [],
      policyCandidates: [],
    }
  if (path === '/api/v1/cap-checks')
    return {
      ...page,
      content: capItems,
      totalElements: capItems.length,
      totalPages: 1,
      summary: { normal: 13, warning: 0, violation: 3, reviewRequired: 0 },
      stageSummary: [
        {
          paymentStage: 'INSURER_TO_GA',
          paymentStageLabel: '보험회사 → GA',
          contractCount: 11,
          limitAmountTotal: 12336000,
          includedAmountTotal: 10868000,
          complianceDeductionAmountTotal: 0,
          usagePct: '88.0',
          violationCount: 3,
          warningCount: 0,
          reviewRequiredCount: 0,
          worstContractNo: 'PREVIEW-2026-001',
          worstUsagePct: '108.3',
        },
        {
          paymentStage: 'GA_TO_FC',
          paymentStageLabel: 'GA → 설계사',
          contractCount: 5,
          limitAmountTotal: 6000000,
          includedAmountTotal: 4475000,
          complianceDeductionAmountTotal: 0,
          usagePct: '74.5',
          violationCount: 0,
          warningCount: 0,
          reviewRequiredCount: 0,
          worstContractNo: 'PREVIEW-2026-002',
          worstUsagePct: '74.5',
        },
      ],
      agentSummary: [],
    }
  if (path === '/api/v1/arbitrage-checks') {
    const matches = arbitrageItems.filter((row) =>
      (!url.searchParams.get('month') || row.asOfDate?.startsWith(url.searchParams.get('month')!)) &&
      (!url.searchParams.get('contractNo') || row.contractNo?.includes(url.searchParams.get('contractNo')!)) &&
      (!url.searchParams.get('stage') || row.paymentStage === url.searchParams.get('stage')),
    )
    const content = matches.filter((row) => !url.searchParams.get('status') || row.resultStatus === url.searchParams.get('status'))
    return {
      summary: { clearCount: matches.filter((row) => row.resultStatus === 'CLEAR').length, candidateCount: matches.filter((row) => row.resultStatus === 'CANDIDATE').length, reviewRequiredCount: 0 },
      items: { ...page, content, totalElements: content.length, totalPages: content.length ? 1 : 0 },
    }
  }
  if (path === '/api/v1/exceptions/options')
    return { types: [], reasons: [], severities: [], assignees: [], validationMonths: ['2026-07-01'], statuses: [] }
  if (path === '/api/v1/exceptions')
    return {
      ...page,
      summary: [
        { type: 'RECON_MISMATCH', typeLabel: '대사 불일치', count: 29 },
        { type: 'ARBITRAGE_CANDIDATE', typeLabel: '차익거래 검토대상', count: 6 },
        { type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 3 },
        { type: 'DATA_QUALITY', typeLabel: '데이터 품질', count: 3 },
      ],
    }
  if (path === '/api/v1/validation-runs') return { ...page, content: [run], totalElements: 1, totalPages: 1 }
  if (path === '/api/v1/validation-runs/active-monthly') return { active: false }
  if (path === '/api/v1/validation-runs/1')
    return {
      header: run,
      targets: [],
      targetSummary: { selectedCount: 16, excludedCount: 6, reviewRequiredCount: 0 },
      capSummary: { checkedCount: 16, violationCount: 3, warningCount: 0, reviewRequiredCount: 0 },
      arbitrageSummary: { checkedCount: 11, candidateCount: 6, reviewRequiredCount: 0 },
      ledgerSummary: { journalCount: 70, imbalanceCount: 0 },
      reconciliationSummary: { resultCount: 47, mismatchCount: 29 },
      exceptionSummary: {
        detectedCount: 41,
        newCount: 41,
        recurringCount: 0,
        reopenedCount: 0,
        notDetectedCount: 0,
        openWorkItemCount: 41,
      },
    }
  if (path.endsWith('/finalize-checklist')) return { canFinalize: false, conditions: [] }
  if (path === '/api/v1/reconciliations/runs')
    return { ...page, content: [reconciliation], totalElements: 1, totalPages: 1 }
  if (path === '/api/v1/reconciliations/runs/41/results')
    return { ...page, summary: { resultCount: 47, matchedCount: 18, exceptionCount: 29, differenceTotal: -5000 } }
  if (path === '/api/v1/audit-logs/options') return { users: [], actions: [], entityTypes: [] }
  if (path === '/api/v1/base/commission-items' || path === '/api/v1/base/accounts') return []
  if (path === '/api/v1/journals') return { items: page }
  if (path.startsWith('/api/v1/base/') || ['/api/v1/contracts', '/api/v1/transactions', '/api/v1/schedules', '/api/v1/audit-logs'].includes(path)) return page
  return undefined
}
