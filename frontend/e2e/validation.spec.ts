import { expect, test } from '@playwright/test'

// 네트워크를 모킹한 VRUN 시나리오: 목록에서 실행 생성 → 상세에서 실행 → 폴링 완료 → 확정(GA_ADMIN).
// 실서버 시나리오는 #418 범위다.
const envelope = (data: unknown) => ({ data, error: null, requestId: 'vrun-fixture' })

const run = (over: Record<string, unknown> = {}) => ({
  validationRunId: 7,
  validationMonth: '2026-07-01',
  runNo: 1,
  runType: 'MONTHLY',
  runTypeLabel: '월간',
  status: 'CREATED',
  statusLabel: '생성됨',
  currentStep: 0,
  triggeredBy: 'ga01',
  ...over,
})
const detail = (header: ReturnType<typeof run>) => ({
  header,
  targets: [],
  targetSummary: { selectedCount: 0, excludedCount: 0, reviewRequiredCount: 0 },
  capSummary: { checkedCount: 0, violationCount: 0, warningCount: 0, reviewRequiredCount: 0 },
  arbitrageSummary: { checkedCount: 0, candidateCount: 0, reviewRequiredCount: 0 },
  ledgerSummary: { journalCount: 0, imbalanceCount: 0 },
  reconciliationSummary: { resultCount: 0, mismatchCount: 0 },
  exceptionSummary: {
    detectedCount: 0,
    newCount: 0,
    recurringCount: 0,
    reopenedCount: 0,
    notDetectedCount: 0,
    openWorkItemCount: 0,
  },
})

test('실행을 만들고 돌려 확정한다', async ({ page }) => {
  let status: 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FINALIZED' = 'CREATED'
  let polls = 0
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ json: envelope({ accessToken: 'vrun-fixture', tokenType: 'Bearer', expiresIn: 1800 }) }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: envelope({
        loginId: 'ga01',
        userName: 'GA 관리자',
        roleCode: 'SYSTEM_ADMIN',
        canProcess: true,
        canViewAuditLog: true,
        canFinalizeValidation: true,
        demoMonth: '2026-07',
      }),
    }),
  )
  const current = () =>
    run({
      status,
      statusLabel: { CREATED: '생성됨', RUNNING: '실행중', COMPLETED: '계산완료', FINALIZED: '확정(잠김)' }[status],
      currentStep: status === 'CREATED' ? 0 : status === 'FINALIZED' ? 10 : 8,
    })
  await page.route('**/api/v1/validation-runs/active-monthly**', (route) =>
    route.fulfill({ json: envelope({ exists: false }) }),
  )
  await page.route('**/api/v1/validation-runs?**', (route) =>
    route.fulfill({ json: envelope({ content: [current()], page: 1, size: 20, totalElements: 1, totalPages: 1 }) }),
  )
  await page.route('**/api/v1/validation-runs', (route) =>
    route.request().method() === 'POST'
      ? route.fulfill({ status: 201, json: envelope({ validationRunId: 7, runNo: 1, status: 'CREATED' }) })
      : route.fallback(),
  )
  await page.route('**/api/v1/validation-runs/7', (route) => route.fulfill({ json: envelope(detail(current())) }))
  await page.route('**/api/v1/validation-runs/7/execute', (route) => {
    status = 'RUNNING'
    return route.fulfill({
      status: 202,
      json: envelope({ validationRunId: 7, status: 'RUNNING', statusLabel: '실행중', currentStep: 0, totalSteps: 10 }),
    })
  })
  await page.route('**/api/v1/validation-runs/7/progress', (route) => {
    polls += 1
    if (polls >= 2) status = 'COMPLETED'
    return route.fulfill({
      json: envelope({
        status,
        statusLabel: status === 'COMPLETED' ? '계산완료' : '실행중',
        currentStep: status === 'COMPLETED' ? 8 : 3,
        totalSteps: 10,
        progressPct: 30,
      }),
    })
  })
  await page.route('**/api/v1/validation-runs/7/finalize-checklist', (route) =>
    route.fulfill({
      json: envelope({
        validationRunId: 7,
        passed: true,
        conditions: Array.from({ length: 6 }, (_, index) => ({
          no: index + 1,
          label: `조건 ${index + 1}`,
          passed: true,
          count: 0,
        })),
      }),
    }),
  )
  await page.route('**/api/v1/validation-runs/7/finalize', (route) => {
    status = 'FINALIZED'
    return route.fulfill({
      json: envelope({ status: 'FINALIZED', finalizedAt: '2026-07-11T10:00:00+09:00', finalizedBy: 'ga01' }),
    })
  })

  await page.goto('validation-runs?month=2026-07')
  await expect(page.getByRole('heading', { name: '월 통합검증 실행 목록' })).toBeVisible()
  await page.getByRole('button', { name: '실행 생성' }).click()
  await expect(page.getByRole('heading', { name: '월 통합검증 상세 · 확정' })).toBeVisible()

  await page.getByRole('button', { name: '실행', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '8/10 단계 (80%)' })).toBeVisible({ timeout: 10_000 })

  const finalize = page.getByRole('button', { name: '확정', exact: true })
  await expect(finalize).toBeEnabled({ timeout: 10_000 })
  await finalize.click()
  await page.getByRole('dialog').getByRole('button', { name: '확정', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '확정했습니다' })).toBeVisible()
})
