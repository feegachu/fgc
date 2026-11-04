import { test, expect, type Page } from '@playwright/test'

async function expandDetail(page: Page) {
  await page.setViewportSize({ width: 1600, height: 1000 })
  const divider = page.getByRole('separator', { name: '환수 검토 후보 목록과 상세 너비' })
  for (let step = 0; step < 3; step++) await divider.press('Shift+ArrowLeft')
  await page
    .getByRole('region', { name: '환수 PDF·발행 이력' })
    .getByRole('table')
    .evaluate((table) => {
      if (table.parentElement) table.parentElement.scrollLeft = 0
    })
}

// 브라우저 상호작용 검증용 API 응답. 실제 DB/PDF 금액·해시 검증은 CT-W02 통합 시험과 구분한다.
async function installApi(
  page: Page,
  { reader = false, stale = false, missingFile = false, loseResponse = false, gaAdmin = false } = {},
) {
  const writes: { key?: string; body: unknown }[] = []
  const documents: Record<string, unknown>[] = []
  const attempts = new Map<string, Record<string, unknown>>()
  let lost = false
  let conflicts = stale
  const candidate = {
    candidateId: 42,
    contractId: 10,
    contractNo: 'CLW-727-001',
    insurerName: '가상생명',
    productName: '가상 종신보험',
    agentName: '김설계',
    triggerType: 'STATUS_EVENT',
    sourceId: 7,
    eventDate: '2026-07-15',
    dateBasisLabel: '효력일',
    contractDate: '2026-01-01',
    sourceVersion: 'claw-v1-ui727',
    calculationStatus: 'CALCULATED',
    reviewReasons: [],
    paidAmount: 1200000,
    unpaidAmount: 0,
    clawbackCaseId: 7,
    calculatedAmount: 600000,
    allowedActions: ['CALCULATE'],
  }
  const edition = (version: number) => ({
    documentNo: 'CLN-7',
    version,
    calculationVersion: 2,
    sha256: 'a'.repeat(64),
    createdAt: '2026-07-31T00:00:00Z',
    createdBy: 5,
    downloadPath: `/api/v1/documents/CLN-7.pdf?version=${version}`,
  })
  if (reader) documents.push(edition(1))
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    let data: unknown = {}
    if (path.endsWith('/auth/refresh')) data = { accessToken: 'clawback-fixture', expiresIn: 1800 }
    else if (path.endsWith('/auth/me'))
      data = {
        loginId: 'ui727',
        userName: '화면 검증 사용자',
        roleCode: reader ? 'COMPLIANCE' : gaAdmin ? 'GA_ADMIN' : 'SETTLEMENT',
        canProcess: !reader && !gaAdmin,
        canViewAuditLog: reader,
        demoMonth: '2026-07',
      }
    else if (path.endsWith('/base/insurers')) data = { content: [] }
    else if (path.endsWith('/candidates')) data = { content: [candidate], page: 1, totalPages: 1, totalElements: 1 }
    else if (path.endsWith('/candidates/42'))
      data = {
        candidate,
        sourceRefs: [],
        paymentAttributions: [],
        scheduleLines: [],
        effects: [],
        policyCandidates: [],
        elapsedMonths: 6,
        latestCalculation: {
          clawbackCaseId: 7,
          candidateId: 42,
          calculationVersion: 2,
          sourceVersion: candidate.sourceVersion,
          amount: 600000,
          calculatedAt: '2026-07-31T00:00:00Z',
        },
      }
    else if (path.endsWith('/notice-documents')) {
      if (request.method() === 'POST') {
        const key = request.headers()['idempotency-key']
        writes.push({ key, body: request.postDataJSON() })
        if (conflicts) {
          conflicts = false
          return route.fulfill({
            status: 409,
            json: {
              data: null,
              error: { code: 'FGC-IMPL-STATE_CHANGED', message: '원천이 변경되었습니다.' },
              requestId: 'stale727',
            },
          })
        }
        if (!attempts.has(key)) {
          const doc = edition(documents.length + 1)
          attempts.set(key, doc)
          documents.unshift(doc)
        }
        data = attempts.get(key)
        if (loseResponse && !lost) {
          lost = true
          return route.abort('failed')
        }
      } else data = { content: documents, page: 1, totalPages: 1, totalElements: documents.length }
    } else if (path.endsWith('.pdf')) {
      if (missingFile)
        return route.fulfill({
          status: 404,
          json: { data: null, error: { code: 'FGC-COMMON-004', message: '파일 없음' }, requestId: 'missing727' },
        })
      return route.fulfill({
        contentType: 'application/pdf',
        body: '%PDF-1.7\nfixture edition ' + url.searchParams.get('version'),
        headers: { 'Content-Disposition': 'attachment; filename="CLN-7.pdf"' },
      })
    }
    await route.fulfill({ json: { data, error: null, requestId: 'claw727' } })
  })
  return { writes, documents }
}

test('PDF 발행 응답 유실 재시도·새 키 재발행·정확한 과거판 다운로드', async ({ page }, info) => {
  const { writes, documents } = await installApi(page, { loseResponse: true })
  await page.goto('/clawbacks?month=2026-07&contractNo=CLW-727')
  const panel = page.getByRole('region', { name: '환수 PDF·발행 이력' })
  await expect(panel.getByRole('button', { name: 'PDF 생성', exact: true })).toBeEnabled()
  await page.getByLabel('계약번호', { exact: true }).fill('아직 조회하지 않은 입력')
  await panel.getByRole('button', { name: 'PDF 생성', exact: true }).click()
  await panel.getByRole('button', { name: '발행 재시도' }).click()
  await expect(panel.getByText(/PDF 발행 완료/)).toContainText('발행판 v1')
  expect(writes[0]).toEqual(writes[1])
  expect(writes[0].body).toEqual({ calculationVersion: 2, expectedSourceVersion: 'claw-v1-ui727' })
  await panel.getByRole('button', { name: '재발행', exact: true }).click()
  await expect(panel.getByText(/PDF 발행 완료/)).toContainText('발행판 v2')
  expect(writes[2].key).not.toBe(writes[1].key)
  expect(documents).toHaveLength(2)
  await expect(page.getByLabel('계약번호', { exact: true })).toHaveValue('아직 조회하지 않은 입력')
  const downloaded = page.waitForEvent('download')
  const response = page.waitForRequest('**/documents/CLN-7.pdf?version=1')
  await panel.getByRole('button', { name: '발행판 v1 다운로드' }).click()
  await response
  expect((await downloaded).suggestedFilename()).toBe('CLN-7-v1.pdf')
  await expandDetail(page)
  await panel.scrollIntoViewIfNeeded()
  await panel.screenshot({ path: info.outputPath('pdf-history.png') })
})

test('stale 409는 재발행을 잠그고 다시 조회해도 검색 입력을 보존한다', async ({ page }, info) => {
  await installApi(page, { stale: true })
  await page.goto('/clawbacks?month=2026-07')
  const panel = page.getByRole('region', { name: '환수 PDF·발행 이력' })
  await page.getByLabel('계약번호', { exact: true }).fill('입력 유지')
  await panel.getByRole('button', { name: 'PDF 생성', exact: true }).click()
  await expect(panel.getByText('현재 계산판·원천과 발행 요청이 다릅니다. 다시 조회해 주세요.')).toBeVisible()
  await expect(panel.getByRole('button', { name: '발행 재시도' })).toBeDisabled()
  await page.setViewportSize({ width: 1600, height: 1000 })
  await panel.screenshot({ path: info.outputPath('pdf-stale409.png') })
  await panel.getByRole('button', { name: '다시 조회' }).click()
  await expect(page.getByLabel('계약번호', { exact: true })).toHaveValue('입력 유지')
  await expect(panel.getByRole('button', { name: '발행 재시도' })).toBeEnabled()
})

test('COMPLIANCE 읽기 전용·없는 PDF 파일의 JSON을 저장하지 않는다', async ({ page }, info) => {
  await installApi(page, { reader: true, missingFile: true })
  await page.goto('/clawbacks?month=2026-07')
  const panel = page.getByRole('region', { name: '환수 PDF·발행 이력' })
  await expect(panel.getByRole('table', { name: '환수 PDF 발행 이력' })).toBeVisible()
  await expect(panel.getByRole('button', { name: /PDF 생성|재발행/ })).toHaveCount(0)
  let downloads = 0
  page.on('download', () => downloads++)
  await panel.getByRole('button', { name: '발행판 v1 다운로드' }).click()
  await expect(panel.getByText('요청한 발행판의 파일이 없습니다. 발행 이력을 다시 조회해 주세요.')).toBeVisible()
  expect(downloads).toBe(0)
  await expandDetail(page)
  await panel.screenshot({ path: info.outputPath('pdf-file-missing.png') })
})

test('GA_ADMIN은 공통 canProcess가 false여도 IF73 권한으로 발행한다', async ({ page }) => {
  await installApi(page, { gaAdmin: true })
  await page.goto('/clawbacks?month=2026-07')
  const panel = page.getByRole('region', { name: '환수 PDF·발행 이력' })
  await panel.getByRole('button', { name: 'PDF 생성', exact: true }).click()
  await expect(panel.getByText(/PDF 발행 완료/)).toContainText('발행판 v1')
})
