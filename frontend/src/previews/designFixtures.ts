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
export function designPreviewData(url: URL): unknown {
  const path = url.pathname
  if (path === '/api/v1/auth/me') return designPreviewUser
  if (path === '/api/v1/cap-checks')
    return {
      ...page,
      summary: { normal: 13, warning: 0, violation: 3, reviewRequired: 0 },
      stageSummary: [],
      agentSummary: [],
    }
  if (path === '/api/v1/arbitrage-checks')
    return { summary: { clearCount: 5, candidateCount: 6, reviewRequiredCount: 0 }, items: page }
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
  if (path.startsWith('/api/v1/base/') || path === '/api/v1/contracts' || path === '/api/v1/transactions') return page
  return undefined
}
