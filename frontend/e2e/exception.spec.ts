import { expect, test } from '@playwright/test'

// 네트워크를 모킹한 EXCP-W01 시나리오: 목록 조회 → 행 선택 → 조치 저장. 실서버 시나리오는 #418 범위다.
const envelope = (data: unknown) => ({ data, error: null, requestId: 'excp-fixture' })

test('예외 목록을 조회하고 한 건을 선택해 조치를 저장한다', async ({ page }) => {
  await page.route('**/api/v1/auth/refresh', (route) =>
    route.fulfill({ json: envelope({ accessToken: 'excp-fixture', tokenType: 'Bearer', expiresIn: 1800 }) }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: envelope({
        loginId: 'settle01',
        userName: '정산 담당자',
        roleCode: 'SETTLEMENT',
        canProcess: true,
        canViewAuditLog: false,
        canHandleException: true,
        demoMonth: '2026-07',
      }),
    }),
  )
  await page.route('**/api/v1/exceptions/options', (route) =>
    route.fulfill({
      json: envelope({
        types: [{ code: 'CAP_VIOLATION', label: '1,200% 위반' }],
        reasons: [],
        severities: [{ code: 'HIGH', label: '높음' }],
        assignees: [],
        validationMonths: [],
      }),
    }),
  )
  const searchUrls: string[] = []
  await page.route('**/api/v1/exceptions?**', (route) => {
    searchUrls.push(route.request().url())
    return route.fulfill({
      json: envelope({
        summary: [{ type: 'CAP_VIOLATION', typeLabel: '1,200% 위반', count: 1 }],
        content: [
          {
            exceptionCaseId: 11,
            type: 'CAP_VIOLATION',
            typeLabel: '1,200% 위반',
            severity: 'HIGH',
            severityLabel: '높음',
            status: 'NEW',
            statusLabel: '신규',
            title: '한도 초과 계약',
            contractNo: 'C-0001',
            detectionCount: 1,
            occurrences: [],
            actions: [],
          },
        ],
        page: 1,
        size: 20,
        totalElements: 1,
        totalPages: 1,
      }),
    })
  })
  await page.route('**/api/v1/exceptions/11/actions', (route) =>
    route.fulfill({
      json: envelope({
        actionSeq: 1,
        fromStatus: 'NEW',
        toStatus: 'IN_REVIEW',
        actionType: 'START_REVIEW',
        actionTypeLabel: '검토 시작',
        fromStatusLabel: '신규',
        toStatusLabel: '검토중',
        reason: '검토를 시작합니다',
        actionBy: 1,
        actionByLoginId: 'settle01',
        actionAt: '2026-07-11T01:00:00+09:00',
      }),
    }),
  )

  await page.goto('exceptions')
  await expect(page.getByRole('heading', { name: '예외함' })).toBeVisible()
  await expect(page.getByRole('link', { name: '1,200% 위반 1건' })).toBeVisible()
  expect(new URL(searchUrls[0]).searchParams.get('status')).toBe('OPEN')

  await page.getByRole('button', { name: '11' }).click()
  await page.getByLabel('조치').selectOption('START_REVIEW')
  const save = page.getByRole('button', { name: '처리 저장' })
  await expect(save).toBeDisabled()
  await page.getByLabel('처리 사유').fill('검토를 시작합니다')
  await save.click()

  await expect(page.getByText('1. 검토 시작')).toBeVisible()
  await expect(page.getByRole('table', { name: '예외 목록' }).getByText('검토중')).toBeVisible()
})
