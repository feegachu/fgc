import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

// UI 전용 fixture. 실제 JWT·소유권·PostgreSQL 검증은 AgentDataScopeIntegrationTest에서 수행한다.
async function agentFixture(page: Page, commission = 300000) {
  const unexpected: string[] = []
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const fulfill = (data: unknown) => route.fulfill({ json: { data, error: null, requestId: 'agent-ui-fixture' } })
    if (path === '/api/v1/auth/refresh' || path === '/api/v1/auth/login')
      return fulfill({ accessToken: 'agent-ui-fixture', tokenType: 'Bearer', expiresIn: 1800 })
    if (path === '/api/v1/auth/me') return fulfill({
      loginId: 'agent01', userName: '김정산', roleCode: 'AGENT', agentId: 1, isAgent: true,
      canUseAgentWorkspace: true, canProcess: false, canViewAuditLog: false,
      canHandleException: false, canReverseJournal: false, canFinalizeValidation: false, demoMonth: '2026-07',
    })
    if (path === '/api/v1/agent/me/home') return fulfill({
      asOf: '2026-07-31', period: '12', range: { from: '2025-08-01', to: '2026-07-31' }, filters: { insurerType: 'ALL' },
      priorityContracts: [], priorityContractTotal: 0, dataStatus: 'AVAILABLE',
      myCommission: { month: '2026-07', expected: { amount: commission, count: 1 }, held: { amount: 0, count: 0 },
        clawback: { amount: 0, count: 0 }, dataStatus: 'AVAILABLE' },
      portfolio: { contractCount: 1,
        insurers: [{ insurerId: 1, name: '가상생명', contractCount: 1, monthlyEquivalentFirstPremiumSum: 100000 }],
        monthly: [{ month: '2026-06', newContractCount: 0, monthlyEquivalentFirstPremiumSum: 0 },
          { month: '2026-07', newContractCount: 1, monthlyEquivalentFirstPremiumSum: 100000 }],
        categories: [{ code: 'HEALTH_PROTECTION', label: '건강보장', count: 1 }],
        statuses: [{ code: 'ACTIVE', label: '정상', count: 1 }] },
      basis: { asOf: '2026-07-31', warnings: [] },
    })
    if (path === '/api/v1/base/insurers') return fulfill({ content: [], totalPages: 0, totalElements: 0 })
    if (path === '/api/v1/base/products') return fulfill({ content: [], totalPages: 0, totalElements: 0 })
    const contract = {
      contractId: 1, contractNo: 'FGC-FGL01-202607-0001', insurerName: '가상생명', productName: 'GOLDEN 상품',
      contractDate: '2026-07-10', monthlyEquivalentFirstPremium: 100000, contractStatus: 'ACTIVE',
      contractStatusLabel: '정상', agentIdName: 'A-FC-001 김정산', agentName: '김정산', dataOrigin: 'MANUAL',
    }
    if (path === '/api/v1/contracts') return fulfill({ content: [contract], totalPages: 1, totalElements: 1, page: 1, size: 20 })
    if (path === '/api/v1/transactions') return fulfill({
      content: [{ amount: commission, paymentStage: 'GA_TO_FC', cashflowType: 'PAYMENT' }],
      totalPages: 1, totalElements: 1, page: 1, size: 100,
    })
    if (path === '/api/v1/contracts/1') return fulfill(contract)
    if (path === '/api/v1/contracts/1/status-events') return fulfill([])
    unexpected.push(path)
    return route.fulfill({ status: 403, json: { data: null, error: {
      code: 'FGC-AUTH-005', message: '본인 데이터만 조회하거나 허용된 확인 기록을 저장할 수 있습니다.',
    }, requestId: 'agent-ui-fixture' } })
  })
  return unexpected
}

test('AGENT 첫 화면은 포트폴리오이고 본인 계약 목록은 전체 설계사·조직·관리자 API를 호출하지 않는다', async ({ page }) => {
  const unexpected = await agentFixture(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('./')
  await expect(page.getByRole('heading', { name: '포트폴리오' })).toBeVisible()
  await expect(page.getByRole('region', { name: '이번 달 현황' })).toBeVisible()
  await expect(page.getByRole('region', { name: '설계사 판매 관리 그래프' })).toBeVisible()
  await page.screenshot({ path: 'e2e-artifacts/714-agent-home.png', fullPage: true })
  await page.goto('contracts')
  await expect(page.getByRole('heading', { name: '보험계약 목록' })).toBeVisible()
  await expect(page.getByText('FGC-FGL01-202607-0001', { exact: true })).toBeVisible()
  await expect(page.locator('.sidebar-user-role')).toHaveText('설계사')
  await expect(page.locator('.sidebar-nav-summary')).toHaveCount(1)
  await expect(page.getByRole('link', { name: '업무 대시보드', exact: true })).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: '모집 설계사' })).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: '조직', exact: true })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '신규 등록' })).toHaveCount(0)
  expect(unexpected).toEqual([])
  await page.screenshot({ path: 'e2e-artifacts/634-agent-contracts.png', fullPage: true })
})

for (const { width, expand } of [{ width: 1280, expand: false }, { width: 1200, expand: true }]) {
  test(`${width}px${expand ? '에서 사이드바를 펼쳐도' : ' 화면에서도'} 7자리 수수료와 단위가 카드 안 한 줄에 보인다`, async ({ page }) => {
  await agentFixture(page, 1_910_000)
  await page.setViewportSize({ width, height: 720 })
  await page.goto('./')
  // 1280px 미만은 사이드바가 기본으로 접히고, 사용자가 펼치면 지표 칸이 더 좁아진다.
  if (expand) await page.getByRole('button', { name: '사이드바 펼치기' }).click()
  const card = page.getByRole('link', { name: '수령 예정 수수료' })
  await expect(card.locator('.kpi-value')).toHaveText('1,910,000')
  await page.evaluate(() => document.fonts.ready)
  // 펼친 직후에는 셸 그리드 열 전환이 진행 중이라 카드 폭이 최종값보다 넓다.
  await page.locator('.app-shell-layout').evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)))
  const box = (await card.boundingBox())!
  const value = (await card.locator('.kpi-value').boundingBox())!
  const unit = (await card.locator('.kpi-unit').boundingBox())!
  // 줄바꿈되면 단위가 값 아래 줄 왼쪽으로 내려간다.
  expect(unit.x).toBeGreaterThanOrEqual(value.x + value.width)
  expect(unit.y).toBeLessThan(value.y + value.height)
  expect(unit.x + unit.width).toBeLessThanOrEqual(box.x + box.width)
  })
}

test('계약 상세의 저장된 내부 탭은 기본정보로 제한하고 내부 API를 호출하지 않는다', async ({ page }) => {
  const unexpected = await agentFixture(page)
  await page.goto('contracts/1?tab=ledger')
  await expect(page.getByRole('heading', { name: '계약 기본정보' })).toBeVisible()
  await expect(page.getByRole('tab')).toHaveCount(1)
  expect(unexpected).toEqual([])
})

test('내부 화면 직접 URL은 403 화면으로 표시하고 업무 API를 호출하지 않는다', async ({ page }) => {
  const unexpected = await agentFixture(page)
  await page.goto('journals')
  await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
  expect(unexpected).toEqual([])
})
