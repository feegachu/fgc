import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const contract = {
  contractId: 1,
  contractNo: 'CONT-UI-001',
  contractDate: '2026-07-01',
  contractStatus: 'ACTIVE',
  insurerId: 1,
  insurerName: '시연 보험사',
  productOfferingId: 7,
  productName: '시연 상품',
  offeringVersion: 'v1',
  agentId: 3,
  agentName: '시연 설계사',
  organizationId: 4,
  organizationName: '계약 당시 조직',
  paymentCycleCode: 'MONTHLY',
  premiumPerCycleAmount: 100000,
  firstPremiumAmount: 100000,
  monthlyEquivalentFirstPremium: 100000,
  paymentTermMonths: 120,
  updatedAt: '2026-07-01T00:00:00.123456Z',
}
async function fixture(page: Page, canProcess = true) {
  const envelope = (data: unknown) => ({ data, error: null, requestId: 'contract-browser' })
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path.endsWith('/auth/refresh'))
      return route.fulfill({ json: envelope({ accessToken: 'contract-fixture', expiresIn: 1800 }) })
    if (path.endsWith('/auth/me'))
      return route.fulfill({
        json: envelope({
          loginId: 'tester',
          userName: '시연 담당자',
          roleCode: canProcess ? 'SETTLEMENT' : 'COMPLIANCE',
          canProcess,
          demoMonth: '2026-07',
        }),
      })
    if (path.endsWith('/contracts/999999'))
      return route.fulfill({
        status: 400,
        json: {
          data: null,
          error: { code: 'FGC-COMMON-002', message: '존재하지 않는 보험계약입니다.' },
          requestId: 'missing-contract',
        },
      })
    if (path.endsWith('/contracts/1')) {
      if (route.request().method() === 'PUT')
        return route.fulfill({
          status: 409,
          json: {
            data: null,
            error: {
              code: 'FGC-CONT-003',
              message:
                '다른 요청에서 계약을 수정했습니다. 현재 입력은 유지됩니다. 변경 내용을 확인한 뒤 ‘최신 계약 다시 불러오기’를 눌러 다시 수정하세요.',
            },
            requestId: 'conflict-contract',
          },
        })
      return route.fulfill({ json: envelope(contract) })
    }
    if (path.endsWith('/contracts'))
      return route.fulfill({
        json: envelope({ content: [contract], page: 1, size: 20, totalPages: 1, totalElements: 1 }),
      })
    if (path.endsWith('/schedules')) return route.fulfill({ json: envelope({ headers: [], lines: [] }) })
    if (path.endsWith('/base/insurers'))
      return route.fulfill({
        json: envelope({ content: [{ insurerId: 1, insurerName: '시연 보험사', activeYn: true }] }),
      })
    if (path.endsWith('/base/products'))
      return route.fulfill({
        json: envelope({
          content: [
            {
              productOfferingId: 7,
              productName: '시연 상품',
              offeringVersion: 'v1',
              paymentTermMonths: 120,
              standardDeduction80Yn: false,
            },
          ],
        }),
      })
    if (path.endsWith('/base/agents'))
      return route.fulfill({
        json: envelope({
          content: [
            {
              agentId: 3,
              agentName: '시연 설계사',
              agentCode: 'FC3',
              agentStatus: 'ACTIVE',
              rankCode: 'FC',
              activeYn: true,
              organizationId: 4,
              organizationName: '계약 당시 조직',
            },
          ],
        }),
      })
    return route.fulfill({ json: envelope([]) })
  })
}

test('계약 직접 진입·새로고침·두 스케줄·409 입력 보존·수정 취소를 검증한다', async ({ page }) => {
  await fixture(page)
  await page.goto('contracts/1?tab=schedules')
  await expect(page.getByRole('tab', { name: '예상 스케줄' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('table', { name: '원수사→GA 예상 스케줄' })).toBeVisible()
  await expect(page.getByRole('table', { name: 'GA→설계사 예상 스케줄' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('table', { name: 'GA→설계사 예상 스케줄' })).toBeVisible()
  await page.getByRole('button', { name: '수정', exact: true }).click()
  const save = page.getByRole('button', { name: '수정하고 스케줄 재생성' })
  await expect(save).toBeEnabled()
  await page.getByPlaceholder('계약번호 입력').fill('CONT-UI-EDIT')
  await save.click()
  await expect(page.getByRole('alert')).toContainText('다른 요청에서 계약을 수정했습니다.')
  await expect(page.getByText('다른 요청에서 계약을 수정했습니다.', { exact: false })).toHaveCount(1)
  await expect(page.getByPlaceholder('계약번호 입력')).toHaveValue('CONT-UI-EDIT')
  await expect(save).toBeDisabled()
  await page.getByRole('link', { name: '취소', exact: true }).click()
  await expect(page.getByRole('heading', { name: '보험계약 목록' })).toBeVisible()
})

test('없는 계약의 기존 오류와 COMPLIANCE의 폼 직접 진입 권한을 검증한다', async ({ page }) => {
  await fixture(page, false)
  await page.goto('contracts/999999')
  await expect(page.getByRole('alert')).toContainText('존재하지 않는 보험계약입니다.')
  await page.goto('contracts/1/edit')
  await expect(page.getByRole('heading', { name: '403 · 권한 없음' })).toBeVisible()
  await expect(page.getByRole('button', { name: /저장하고 예상 스케줄/ })).toHaveCount(0)
})
