import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const envelope = (data: unknown) => ({ data, error: null, requestId: 'dashboard-fixture' })
const unavailable = {
  status: 500,
  json: { data: null, error: { code: 'FGC-COMMON-500', message: '처리 중 오류가 발생했습니다.' }, requestId: 'dashboard-fixture' },
}
const agentRow = (index: number) => ({
  agentId: index,
  agentName: `설계사${index}`,
  agentCode: `D12-A-${String(index).padStart(4, '0')}`,
  organizationName: '가상지점 01 1팀',
  agentStatusLabel: index === 600 ? '해촉' : '활동',
})
const home = {
  asOf: '2026-11-04',
  agents: { registered: 560, active: 540, terminated: 40 },
  branches: Array.from({ length: 12 }, (_, index) => ({
    organizationId: index + 1,
    name: `가상지점 ${String(index + 1).padStart(2, '0')}`,
    agentCount: 46,
    activeContractCount: 1000,
    attentionCount: index === 0 ? 10 : 0,
  })),
  attentionTotal: 10,
  attentionAgents: Array.from({ length: 10 }, (_, index) => ({
    agentId: index + 1,
    name: `설계사${index + 1}`,
    code: `D12-A-${String(index + 1).padStart(4, '0')}`,
    branch: '가상지점 01',
    status: index === 9 ? 'TERMINATED' : 'ACTIVE',
    statusLabel: index === 9 ? '해촉' : '활동',
    contractCount: 20 - index,
    reasons: [
      { code: 'OPEN_EXCEPTION', label: '미처리 예외', count: 2 },
      { code: 'NEAR_LIMIT', label: '한도 근접', count: 1 },
      { code: 'RECENT_STATUS_CHANGE', label: '최근 상태 변화', count: 1 },
    ],
  })),
  basis: '유효 계약은 기준일 상태가 정상, 미납, 부활인 계약입니다.',
}

async function session(page: Page, overrides: { home?: boolean; counts?: boolean } = {}) {
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path.endsWith('/auth/refresh'))
      return route.fulfill({ json: envelope({ accessToken: 'dashboard-fixture', tokenType: 'Bearer', expiresIn: 1800 }) })
    if (path.endsWith('/auth/me'))
      return route.fulfill({
        json: envelope({ loginId: 'settle01', userName: '정산 담당자', roleCode: 'SETTLEMENT', canProcess: true, demoMonth: '2026-07' }),
      })
    if (path.endsWith('/dashboard/summary'))
      return route.fulfill({
        json: envelope({
          month: '2026-07-01',
          capViolation: 3,
          capWarning: 2,
          arbitrageCandidate: 6,
          reconMismatch: 29,
          journalImbalance: 0,
          openException: 41,
          recentExceptions: [],
          recentRuns: [],
        }),
      })
    if (path.endsWith('/exceptions/counts'))
      return route.fulfill(overrides.counts === false ? unavailable : { json: envelope({ total: 41, bySeverity: [], byType: [] }) })
    if (path.endsWith('/home/summary'))
      return route.fulfill(overrides.home === false ? unavailable : { json: envelope(home) })
    if (path.endsWith('/base/agents')) {
      const keyword = url.searchParams.get('keyword')
      if (keyword === '없는사람')
        return route.fulfill({ json: envelope({ content: [], page: 1, size: 10, totalElements: 0, totalPages: 0 }) })
      const content = keyword ? [agentRow(600)] : Array.from({ length: 10 }, (_, index) => agentRow(index + 1))
      return route.fulfill({
        json: envelope({ content, page: 1, size: 10, totalElements: keyword ? 1 : 600, totalPages: keyword ? 1 : 60 }),
      })
    }
    return route.fulfill({ json: envelope({ content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }) })
  })
}

test('FUN-080 현황 영역은 기존 배치 아래에 더해지고 준비 중이나 자료 영역을 만들지 않는다', async ({ page }) => {
  await session(page)
  await page.goto('?month=2026-07')
  await expect(page.getByRole('region', { name: '주요 검증 지표' }).getByRole('link')).toHaveCount(6)
  await expect(page.getByRole('heading', { name: '최근 예외' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '검증 항목별 건수 비중' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '최근 통합검증 실행' })).toBeVisible()
  const region = page.getByRole('region', { name: 'GA·설계사 현황' })
  await expect(region.getByRole('region', { name: '등록 설계사' })).toContainText('560')
  await expect(region.getByRole('table', { name: '확인할 설계사 상위 10명' }).getByRole('row')).toHaveCount(11)
  await expect(page.getByText('기준 정산월 2026년 7월')).toBeVisible()
  await expect(region.getByLabel('현황 기준일')).toBeVisible()
  await expect(page.getByText(/자료마당|공지|문의|준비 중/)).toHaveCount(0)
  // 첫 화면의 검증 지표가 현황보다 위에 남는다.
  const kpiBox = await page.getByRole('region', { name: '주요 검증 지표' }).boundingBox()
  const overviewBox = await region.boundingBox()
  expect(kpiBox!.y).toBeLessThan(overviewBox!.y)
})

test('현황 오류는 그 영역에만 재시도를 보이고 0을 지어내지 않는다', async ({ page }) => {
  await session(page, { home: false, counts: false })
  await page.goto('?month=2026-07')
  const region = page.getByRole('region', { name: 'GA·설계사 현황' })
  await expect(region.getByRole('alert')).toContainText('처리 중 오류가 발생했습니다.')
  await expect(region.getByRole('button', { name: '다시 시도' })).toBeVisible()
  await expect(region.getByRole('region', { name: '등록 설계사' })).toHaveCount(0)
  const kpis = page.getByRole('region', { name: '주요 검증 지표' })
  await expect(kpis.getByRole('link', { name: '1,200% 위반 3건' })).toBeVisible()
  await expect(kpis.getByRole('button', { name: '미처리 예외 다시 불러오기' })).toBeVisible()
  await expect(kpis.getByRole('link', { name: /미처리 예외/ })).toHaveCount(0)
})

test('설계사 찾기는 키보드로 끝까지 쓸 수 있고 고른 설계사의 계약 목록을 연다', async ({ page }) => {
  await session(page)
  await page.goto('?month=2026-07')
  const search = page.getByRole('region', { name: '설계사 찾기' })
  await expect(search.getByRole('table')).toHaveCount(0)
  await search.getByLabel('이름 또는 코드').fill('없는사람')
  await search.getByLabel('이름 또는 코드').press('Enter')
  await expect(search.getByText('조건에 맞는 설계사가 없습니다')).toBeVisible()
  await search.getByLabel('이름 또는 코드').fill('D12-A-0600')
  await search.getByLabel('이름 또는 코드').press('Enter')
  const link = search.getByRole('link', { name: '설계사600 (D12-A-0600) 계약 목록 열기' })
  await link.focus()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/contracts\?agentId=600&contractDateTo=\d{4}-\d{2}-\d{2}&month=2026-07/)
})

test('모바일 폭에서 페이지가 가로로 넘치지 않는다', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await session(page)
  await page.goto('?month=2026-07')
  const region = page.getByRole('region', { name: 'GA·설계사 현황' })
  await expect(region.getByRole('table', { name: '확인할 설계사 상위 10명' })).toBeVisible()
  await region.getByLabel('지점', { exact: true }).selectOption({ label: '가상지점 01' })
  await region.getByRole('button', { name: '조회' }).click()
  await expect(region.getByRole('table', { name: '설계사 검색 결과' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(0)
})
