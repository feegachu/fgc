import { test, expect, type Page } from '@playwright/test'

// The full Chromium build includes the PDF viewer; headless shell auto-downloads PDFs.
test.use({ channel: 'chromium' })

async function openDetail(page: Page) {
  await page.getByRole('button', { name: 'CLW-727-001 환수 상세 보기 후보 42' }).click()
  await expect(page.getByRole('dialog', { name: '환수 상세 CLW-727-001' })).toBeVisible()
}

test('전체 너비 표에서 계약번호로 상세를 열고 좁은 화면에서도 모달 안에서 읽는다', async ({ page }, info) => {
  await installApi(page)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await page.goto('/clawbacks?month=2026-07&contractNo=CLW-727')
  const table = page.getByRole('table', { name: '검색 조건에 해당하는 환수 검토 후보' })
  await expect(table).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('separator', { name: '환수 검토 후보 목록과 상세 너비' })).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('clawback-table.png'), fullPage: true })
  const trigger = table.getByRole('button', { name: /환수 상세 보기/ })
  await trigger.focus()
  await trigger.press('Enter')
  const dialog = page.getByRole('dialog', { name: '환수 상세 CLW-727-001' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('tab')).toHaveCount(5)
  await expect(dialog.getByRole('tab', { name: '계약 및 검토 정보' })).toHaveAttribute('aria-selected', 'true')
  await dialog.getByRole('tab', { name: '지급 스케줄' }).click()
  await expect(dialog.getByRole('tabpanel', { name: '지급 스케줄' })).toBeVisible()
  await expect(dialog.locator('.modal-footer').getByRole('button', { name: 'PDF 출력', exact: true })).toHaveCount(0)
  await expect(dialog.locator('.modal-footer').getByRole('button', { name: '환수액 계산', exact: true })).toBeVisible()
  for (const size of [{ width: 1440, height: 900 }, { width: 375, height: 740 }]) {
    await page.setViewportSize(size)
    await expect.poll(async () => {
      const box = await dialog.boundingBox()
      return !!box && box.x >= 0 && box.y >= 0 && box.x + box.width <= size.width && box.y + box.height <= size.height
    }).toBe(true)
    await expect(dialog.getByRole('button', { name: '닫기', exact: true })).toBeVisible()
    await page.screenshot({ path: info.outputPath(`clawback-modal-${size.width}.png`) })
  }
  await dialog.getByRole('button', { name: '닫기', exact: true }).click()
  await expect(trigger).toBeFocused()
  await expect(page).toHaveURL(/contractNo=CLW-727/)
})

// 브라우저 상호작용 검증용 API 응답. 실제 DB/PDF 금액·해시 검증은 CT-W02 통합 시험과 구분한다.
async function installApi(
  page: Page,
  { reader = false, stale = false, missingFile = false, loseResponse = false, gaAdmin = false } = {},
) {
  await page.route('**/clawbacks?*', async route => {
    const response = await route.fetch()
    await route.fulfill({ response, headers: { ...response.headers(), 'Content-Security-Policy': "frame-src 'self' blob:; object-src 'none'" } })
  })
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
        body: fixturePdf(),
        headers: { 'Content-Disposition': 'attachment; filename="CLN-7.pdf"' },
      })
    }
    await route.fulfill({ json: { data, error: null, requestId: 'claw727' } })
  })
  return { writes, documents }
}

test('계산 결과에서 기본 PDF 뷰어를 열고 같은 계산의 문서를 재사용한다', async ({ page }, info) => {
  const { writes, documents } = await installApi(page, { loseResponse: true })
  await page.goto('/clawbacks?month=2026-07&contractNo=CLW-727')
  await page.getByLabel('계약번호', { exact: true }).fill('아직 조회하지 않은 입력')
  await openDetail(page)
  const output = page.getByRole('button', { name: 'PDF 출력하기', exact: true })
  await expect(page.getByRole('region', { name: '환수 PDF 및 발행 이력' })).toHaveCount(0)
  let downloads = 0
  page.on('download', () => downloads++)
  await output.click()
  await expect(page.getByText('PDF를 준비하지 못했습니다. 같은 요청으로 다시 시도하세요.')).toBeVisible()
  await output.click()
  const viewer = page.getByRole('dialog', { name: '환수 문서뷰어' })
  await expect(viewer).toBeVisible()
  await expect(viewer.locator('iframe')).toHaveAttribute('src', /^blob:.*#toolbar=1/)
  expect(downloads).toBe(0)
  expect(writes[0]).toEqual(writes[1])
  expect(writes[0].body).toEqual({ calculationVersion: 2, expectedSourceVersion: 'claw-v1-ui727' })
  await expect(viewer.getByRole('link', { name: '다운로드' })).toHaveCount(0)
  await expect(viewer.getByText('CLN-7-v1.pdf', { exact: true })).toHaveCount(0)
  await expect.poll(() => page.frames().some(frame => frame.url().startsWith('chrome-extension:'))).toBe(true)
  const pdfFrame = page.frames().find(frame => frame.url().startsWith('chrome-extension:'))!
  await expect(pdfFrame.locator('viewer-toolbar')).toBeVisible()
  await expect(pdfFrame.locator('viewer-thumbnail')).toHaveCount(1)
  await page.screenshot({ path: info.outputPath('pdf-viewer.png') })
  await viewer.getByRole('button', { name: '창 닫기' }).click()
  await expect(output).toBeFocused()
  await output.click()
  await expect(viewer).toBeVisible()
  expect(writes).toHaveLength(2)
  expect(documents).toHaveLength(1)
  await viewer.getByRole('button', { name: '창 닫기' }).click()
  await expect(page.getByLabel('계약번호', { exact: true })).toHaveValue('아직 조회하지 않은 입력')
})

test('stale 409는 출력 요청을 잠그고 다시 조회 후 재시도한다', async ({ page }) => {
  await installApi(page, { stale: true })
  await page.goto('/clawbacks?month=2026-07')
  await openDetail(page)
  const output = page.getByRole('button', { name: 'PDF 출력하기', exact: true })
  await output.click()
  await expect(page.getByText('계산 근거가 바뀌었습니다. 다시 조회하고 계산해 주세요.')).toBeVisible()
  await expect(output).toBeDisabled()
  await page.getByRole('button', { name: '다시 조회', exact: true }).click()
  await expect(output).toBeEnabled()
})

test('COMPLIANCE는 기존 문서만 조회하고 없는 파일을 저장하거나 재발행하지 않는다', async ({ page }) => {
  const { writes } = await installApi(page, { reader: true, missingFile: true })
  await page.goto('/clawbacks?month=2026-07')
  await openDetail(page)
  let downloads = 0
  page.on('download', () => downloads++)
  await page.getByRole('button', { name: 'PDF 출력하기', exact: true }).click()
  await expect(page.getByText('PDF를 불러오지 못했습니다. 다시 눌러 재시도하세요.')).toBeVisible()
  expect(writes).toHaveLength(0)
  expect(downloads).toBe(0)
  await expect(page.getByRole('dialog', { name: '환수 문서뷰어' })).toHaveCount(0)
})

test('GA_ADMIN은 공통 canProcess가 false여도 IF73 권한으로 문서를 연다', async ({ page }) => {
  await installApi(page, { gaAdmin: true })
  await page.goto('/clawbacks?month=2026-07')
  await openDetail(page)
  await page.getByRole('button', { name: 'PDF 출력하기', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '환수 문서뷰어' })).toBeVisible()
})

function fixturePdf() {
  const stream = 'BT /F1 22 Tf 72 750 Td (Clawback statement - browser fixture) Tj ET'
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
  ]
  let pdf = '%PDF-1.4\n'
  const offsets = [0]
  objects.forEach((object, index) => { offsets.push(pdf.length); pdf += `${index + 1} 0 obj\n${object}\nendobj\n` })
  const xref = pdf.length
  pdf += `xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`
  return pdf
}
