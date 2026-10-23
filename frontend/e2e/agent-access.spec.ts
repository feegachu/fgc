import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

// UI 전용 fixture. 실제 JWT·소유권·PostgreSQL 검증은 AgentDataScopeIntegrationTest에서 수행한다.
async function agentFixture(page: Page) {
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
    if (path === '/api/v1/base/insurers') return fulfill({ content: [], totalPages: 0, totalElements: 0 })
    const contract = {
      contractId: 1, contractNo: 'FGC-FGL01-202607-0001', insurerName: '가상생명', productName: 'GOLDEN 상품',
      contractDate: '2026-07-10', monthlyEquivalentFirstPremium: 100000, contractStatus: 'ACTIVE',
      contractStatusLabel: '정상', agentIdName: 'A-FC-001 김정산', agentName: '김정산', dataOrigin: 'MANUAL',
    }
    if (path === '/api/v1/contracts') return fulfill({ content: [contract], totalPages: 1, totalElements: 1, page: 1, size: 20 })
    if (path === '/api/v1/contracts/1') return fulfill(contract)
    if (path === '/api/v1/contracts/1/status-events') return fulfill([])
    unexpected.push(path)
    return route.fulfill({ status: 403, json: { data: null, error: {
      code: 'FGC-AUTH-005', message: '본인 담당 자료만 조회·처리할 수 있습니다.',
    }, requestId: 'agent-ui-fixture' } })
  })
  return unexpected
}

test('AGENT 홈은 본인 계약으로 이동하고 전체 설계사·조직·관리자 API를 호출하지 않는다', async ({ page }) => {
  const unexpected = await agentFixture(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('./')
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
  await expect(page.getByRole('heading', { name: '403 · 권한 없음' })).toBeVisible()
  expect(unexpected).toEqual([])
})
