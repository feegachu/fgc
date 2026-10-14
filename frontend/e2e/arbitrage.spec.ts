import { expect, test } from '@playwright/test'
for (const roleCode of ['SETTLEMENT', 'COMPLIANCE', 'GA_ADMIN', 'SYSTEM_ADMIN']) {
  test(`${roleCode}: arbitrage URL, timeline, manual recheck and permission`, async ({ page }) => {
    const canProcess = ['SETTLEMENT', 'SYSTEM_ADMIN'].includes(roleCode)
    const writes: unknown[] = []
    let checked = false
    let finishRecheck: () => void = () => {}
    const pendingRecheck = new Promise<void>((resolve) => {
      finishRecheck = resolve
    })
    const row = {
      arbitrageCheckId: 1,
      contractId: 10,
      contractNo: 'ARB-001',
      paymentStage: 'GA_TO_FC',
      paymentStageLabel: 'GA→설계사',
      asOfDate: '2026-07-31',
      contractMonthNo: 7,
      cumulativePaidPremium: 800000,
      paidCommissionAmount: 890000,
      plannedCommissionAmount: 0,
      includedSurrenderValueAmount: 120000,
      netDifferenceAmount: 210000,
      refundAdditionAppliedYn: true,
      surrenderValueSourceTypeLabel: '실제 해약환급금',
      resultStatus: 'CANDIDATE',
      resultStatusLabel: '검토대상',
      decisionReason: '서버 판정 근거',
    }
    await page.route('**/api/v1/**', async (route) => {
      const url = new URL(route.request().url())
      let data: unknown
      if (url.pathname.endsWith('/refresh'))
        data = { accessToken: 'arbitrage-e2e', tokenType: 'Bearer', expiresIn: 1800 }
      else if (url.pathname.endsWith('/me'))
        data = {
          loginId: roleCode,
          userName: '테스트 사용자',
          roleCode,
          canProcess,
          canViewAuditLog: ['COMPLIANCE', 'SYSTEM_ADMIN'].includes(roleCode),
          demoMonth: '2026-07',
        }
      else if (url.pathname.endsWith('/insurers'))
        data = {
          content: [{ insurerId: 1, insurerCode: 'INS1', insurerName: '가상생명', activeYn: true }],
          page: 1,
          totalPages: 1,
        }
      else if (route.request().method() === 'POST') {
        writes.push(route.request().postDataJSON())
        await pendingRecheck
        checked = true
        data = { arbitrageCheckId: 2, resultStatus: 'CLEAR', validationRunId: 3 }
      } else
        data = {
          summary: { clearCount: checked ? 1 : 0, candidateCount: checked ? 0 : 1, reviewRequiredCount: 0 },
          items: {
            content: [
              {
                ...row,
                decisionReason: checked ? '재검증 반영' : '서버 판정 근거',
                resultStatus: checked ? 'CLEAR' : 'CANDIDATE',
                resultStatusLabel: checked ? '이상없음' : '검토대상',
              },
            ],
            page: Number(url.searchParams.get('page') ?? 1),
            totalPages: url.searchParams.get('size') === '100' ? 1 : 3,
            totalElements: 41,
          },
        }
      return route.fulfill({ json: { data, error: null, requestId: '412-browser' } })
    })
    await page.goto('arbitrage-checks?month=2026-07&status=CANDIDATE&page=2&contractNo=ARB')
    await expect(page.getByLabel('판정', { exact: true })).toHaveValue('CANDIDATE')
    await expect(page.getByLabel('계약번호')).toHaveValue('ARB')
    await expect(page.getByRole('img', { name: '기준일별 초과액 추이' })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('button', { name: '2', exact: true })).toHaveAttribute('aria-current', 'page')
    await page.getByRole('button', { name: /이상없음.*초과액/ }).click()
    await expect(page).toHaveURL(/status=CLEAR/)
    await expect(page).not.toHaveURL(/page=/)
    const recheck = page.getByRole('button', { name: '이 계약만 다시 검증' })
    if (canProcess) {
      await expect(recheck).toBeEnabled()
      await recheck.click()
      await expect(page.getByLabel('검증 기준일')).toBeFocused()
      await page.getByRole('button', { name: '재검증 실행', exact: true }).click()
      await expect(page.getByText('재검증 사유를 입력하세요.')).toBeVisible()
      await page.getByLabel('재검증 사유').fill('자료 변경 확인')
      await page.getByRole('button', { name: '재검증 실행', exact: true }).click()
      await expect(page.getByRole('button', { name: '취소', exact: true })).toBeDisabled()
      const dialog = page.getByRole('dialog')
      await expect.poll(() => dialog.evaluate((el) => el.contains(document.activeElement))).toBe(true)
      await page.keyboard.press('Escape')
      await expect(dialog).toBeVisible()
      await page.keyboard.press('Tab')
      await expect(page.getByRole('button', { name: '모달 닫기' })).toBeFocused()
      finishRecheck()
      await expect(page.getByRole('dialog')).toHaveCount(0)
      await expect(recheck).toBeFocused()
      await expect(page.getByText('차익거래 재검증을 완료했습니다.')).toBeVisible()
      await expect(page.getByText('재검증 반영')).toBeVisible()
      expect(writes).toEqual([{ asOfDate: '2026-07-31', reason: '자료 변경 확인' }])
    } else {
      await expect(recheck).toBeDisabled()
      await expect(page.getByText(/재검증은 처리 권한/)).toBeVisible()
      expect(writes).toEqual([])
    }
    await page.setViewportSize({ width: 390, height: 844 })
    await expect.poll(() => page.locator('.arb-page').evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true)
    await expect(page.getByLabel('계약번호')).toBeVisible()
  })
}
