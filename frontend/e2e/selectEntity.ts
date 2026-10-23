import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'

export async function selectEntity(page: Page, control: Locator, id: string) {
  await expect(control).toBeEnabled()
  await control.click()
  const option = page.locator(`[role="option"][data-value="${id}"]`)
  while (!(await option.count())) {
    await expect(page.locator('.search-select-popup')).not.toContainText('검색 중입니다.')
    if (await option.count()) break
    const more = page.getByRole('button', { name: '더 불러오기' })
    await expect(more).toBeVisible()
    await more.click()
  }
  await option.click()
}
