import { test, expect, type Page } from '@playwright/test'

const entries = [1, 2, 3].map(id => ({ journalHeaderId: id, journalNo: `JRN-202611-00${id}`, journalDate: `2026-11-0${id}`,
  journalType: 'EXPECTED_FC_PAYOUT', status: 'POSTED', validationRunId: 7, description: `${id}회차 스케줄 생성`,
  debitTotal: id * 100000, creditTotal: id * 100000,
  lines: [{ lineNo: 1, accountCode: 'EXPECTED_FC_EXPENSE', accountName: '예상 지급비용', debitAmount: id * 100000, creditAmount: 0, memo: `${id}회차 스케줄 생성` },
    { lineNo: 2, accountCode: 'EXPECTED_PAYABLE', accountName: '예상 지급채무', debitAmount: 0, creditAmount: id * 100000, memo: `${id}회차 스케줄 생성` }] }))
const org = (id: number, parentId: number | null, name: string, type: string) => ({ organizationId: id, parentId, organizationName: name, organizationType: type })
const members = [1, 2].map(id => ({ agentId: id, agentCode: `FC-${id}`, agentName: `김설계${id}`, branchId: 4, branchName: '강동지사',
  organizationId: 5, organizationName: '강동1팀', rankCode: id === 1 ? 'TEAM_LEADER' : 'FC',
  organizationPath: [org(1, null, 'FGC금융서비스', 'GA'), org(2, 1, 'FGC 본사', 'HQ'), org(3, 2, '동부본부', 'DIVISION'), org(4, 3, '강동지사', 'BRANCH'), org(5, 4, '강동1팀', 'TEAM')],
  contractCount: 1, entryCount: 1, paymentAmount: id * 100000, heldAmount: 0, adjustmentAmount: 0, clawbackAmount: 0, missingAmountCount: 0 }))
const pageOf = <T,>(content: T[]) => ({ content, page: 1, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 })

async function install(page: Page) {
  const writes: string[] = []
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (request.method() !== 'GET' && !path.includes('/auth/')) writes.push(path)
    let data: unknown = pageOf([])
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'fixture-token', tokenType: 'Bearer', expiresIn: 3600 }
    else if (path.endsWith('/auth/me')) data = { loginId: 'restore', userName: '정산 담당자', roleCode: 'GA_ADMIN', canProcess: true, canReverseJournal: true, demoMonth: '2026-11' }
    else if (path.endsWith('/journals/explorer')) {
      const all = url.searchParams.get('all') === 'true', contractId = url.searchParams.get('contractId') === '2' ? 2 : 1
      const scoped = (all ? entries : entries.filter(entry => contractId === 1 ? entry.journalHeaderId <= 2 : entry.journalHeaderId === 3))
        .filter(entry => !url.searchParams.get('journalId') || entry.journalHeaderId === Number(url.searchParams.get('journalId')))
      const total = scoped.reduce((sum, entry) => sum + entry.debitTotal, 0)
      data = { contracts: pageOf([{ contractId: 1, contractNo: 'FGC-DEMO-1', journalCount: 2, debitTotal: 300000, creditTotal: 300000, agentName: '이보험', insurerName: '미래가상생명' },
        { contractId: 2, contractNo: 'FGC-DEMO-2', journalCount: 1, debitTotal: 300000, creditTotal: 300000, agentName: '김정산', insurerName: '미래가상생명' }]),
      journals: pageOf(scoped), selectedContractId: all ? null : contractId, totals: { journalCount: scoped.length, lineCount: scoped.length * 2, debitTotal: total, creditTotal: total } }
    } else if (/\/journals\/\d+$/.test(path)) {
      const entry = entries.find(entry => entry.journalHeaderId === Number(path.split('/').at(-1)))!
      data = { ...entry, journalTypeLabel: '예상 지급', balanced: true, contractId: 1, contractNo: 'FGC-DEMO-1' }
    } else if (path.endsWith('/journals/accounts')) data = []
    else if (path.endsWith('/statements/overview')) data = members
    else if (/\/agents\/\d+\/statements$/.test(path)) data = { items: pageOf([]), summary: { payment: { count: 1, amount: 100000 }, held: { count: 0, amount: 0 }, adjustment: { count: 0, amount: 0 }, clawback: { count: 0, amount: 0 } },
      basis: { month: '2026-11', range: { from: '2026-11-01', to: '2026-11-30' }, dateBasis: {}, warnings: [] } }
    else if (path.endsWith('/base/commission-items')) data = []
    else if (path.endsWith('/validation-runs/7/retries')) data = []
    else if (path.endsWith('/validation-runs/7/restartability')) data = { allowed: false, reason: '실행 전입니다.' }
    else if (path.endsWith('/validation-runs/7/progress')) data = { validationRunId: 7, status: 'CREATED', currentStep: 0, progressPct: 0 }
    else if (path.endsWith('/validation-runs/7/finalize-checklist')) data = { passed: false, conditions: [] }
    else if (path.endsWith('/validation-runs/7')) data = { header: { validationRunId: 7, validationMonth: '2026-11-01', runNo: 1, runType: 'MONTHLY', status: 'CREATED', currentStep: 0 }, targets: [] }
    await route.fulfill({ json: { data, error: null, requestId: 'restore-fixture' } })
  })
  return writes
}

test('검증원장은 합계·분개별 묶음 표·연결 근거를 표시하고 계약과 분개 범위를 전환한다', async ({ page }, info) => {
  const writes = await install(page)
  await page.setViewportSize({ width: 1600, height: 1050 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/journals?month=2026-11')
  const table = page.getByRole('table', { name: '분개별 차변·대변 내역' })
  await expect(table).toBeVisible()
  await expect(table.locator('thead')).toHaveCount(1)
  await expect(table.locator('tbody')).toHaveCount(2)
  await expect(table.locator('tbody td[rowspan="2"]')).toHaveCount(2)
  await expect(page.getByRole('region', { name: '분개 합계' })).toContainText('300,000')
  await table.getByRole('button', { name: 'JRN-202611-002' }).click()
  await expect(table.locator('tbody')).toHaveCount(2)
  await expect(page.getByRole('region', { name: '분개 상세' })).toContainText('연결 근거')
  await page.screenshot({ path: info.outputPath('ledger-contract-table.png'), fullPage: true })
  await page.getByRole('tree', { name: '계약별 분개 목록' }).getByRole('button', { name: /11-02/ }).click()
  await expect(table.locator('tbody')).toHaveCount(1)
  await page.getByRole('button', { name: '전체 내역', exact: true }).click()
  await expect(table.locator('tbody')).toHaveCount(2)
  await page.getByRole('tree', { name: '계약별 분개 목록' }).getByRole('button', { name: /FGC-DEMO-2/ }).click()
  await expect(table.locator('tbody')).toHaveCount(1)
  await expect(table).toContainText('JRN-202611-003')
  await page.getByRole('button', { name: '전체 분개 보기', exact: true }).click()
  await expect(table.locator('tbody')).toHaveCount(3)
  expect(writes).toEqual([])
  await page.screenshot({ path: info.outputPath('ledger-grouped-table.png'), fullPage: true })
})

test('명세 조직은 본사부터 시작하고 본사 클릭으로 하위 전체를 합산한다', async ({ page }, info) => {
  await install(page)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/agent/statements?month=2026-11')
  const tree = page.getByRole('tree', { name: '지점과 설계사 목록' })
  await expect(tree).toBeVisible()
  await expect(tree).not.toContainText('FGC금융서비스')
  await expect(tree).toContainText('FGC 본사')
  await tree.getByRole('button', { name: 'FGC 본사 2명', exact: true }).click()
  await expect(page.getByRole('region', { name: '지급' })).toContainText('300,000')
  await expect(tree).toContainText('팀장')
  await expect(tree).toContainText('설계사')
  await page.screenshot({ path: info.outputPath('headquarters-tree.png'), fullPage: true })
})

test('검증 전 대기 아이콘의 세 점이 원의 중앙에 위치한다', async ({ page }, info) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  await install(page)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/validation-runs/7?month=2026-11')
  const dots = page.locator('.vrun-pending-dots')
  await expect(dots).toHaveCount(8)
  const offsets = await dots.evaluateAll(elements => elements.map(element => {
    const circle = element.parentElement!.getBoundingClientRect(), icon = element.getBoundingClientRect()
    return { x: Math.abs(circle.x + circle.width / 2 - icon.x - icon.width / 2), y: Math.abs(circle.y + circle.height / 2 - icon.y - icon.height / 2) }
  }))
  for (const offset of offsets) { expect(offset.x).toBeLessThan(1); expect(offset.y).toBeLessThan(1) }
  await page.screenshot({ path: info.outputPath('validation-waiting.png'), fullPage: true })
  expect(errors).toEqual([])
  await expect(dots).toHaveCount(8)
})
