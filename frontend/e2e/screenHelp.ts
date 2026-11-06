import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { screenWalkthroughs } from '../src/features/assistant/guide/screen-walkthroughs'

export async function chooseDetailedHelp(page: Page) {
  const after = page.getByRole('radio', { name: '수정 후', exact: true })
  if (!await after.isChecked()) {
    await page.getByRole('radio', { name: '수정 전', exact: true }).focus()
    await page.keyboard.press('ArrowRight')
  }
  await expect(after).toBeChecked()
}

export async function readDetailedHelp(page: Page, trigger: Locator, minSteps: number) {
  await trigger.click()
  const card = page.locator('.screen-step-guide')
  const titles: string[] = []
  await expect(card).toBeVisible()
  for (let index = 0; index < 40; index++) {
    await expect(card.getByRole('heading')).toBeVisible()
    const title = await card.getByRole('heading').innerText()
    expect(titles).not.toContain(title)
    titles.push(title)
    if (await card.locator('.screen-step-summary').count()) {
      const lines = card.locator('.screen-step-summary > span')
      await expect(lines).toHaveCount(2)
      for (const line of await lines.all()) {
        expect(await line.evaluate(el => el.getBoundingClientRect().height <= parseFloat(getComputedStyle(el).lineHeight) + 1)).toBe(true)
      }
      await expect(card.locator('.screen-step-details, .screen-step-interaction, .screen-step-context')).toHaveCount(0)
    }
    await expect(card.getByRole('progressbar')).toHaveAttribute('value', String(index + 1))
    await expect(page.locator('.screen-step-spotlight')).toBeVisible()
    await expect.poll(async () => {
      const id = await card.getAttribute('data-help-target')
      const definition = Object.values(screenWalkthroughs).flat().find(item => item.targetId === id)
      if (!definition) return false
      const selector = definition.selector ?? `[${definition.attribute ?? 'data-guide-target'}="${id}"]`
      const owner = card.locator('xpath=ancestor::*[@role="dialog"][1]')
      const original = (await owner.count() ? owner : page.locator('#main-content')).locator(`${selector}:visible`).first()
      if (!await original.count()) return false
      const anchor = await original.evaluate((element, { start, includeLabel }) => {
        if (includeLabel) {
          const label = element.closest('.field')?.querySelector('.field-label')?.getBoundingClientRect()
          const rect = element.getBoundingClientRect()
          if (label) return { x: rect.x, y: Math.min(rect.y, label.y) }
        }
        const table = start ? (element.matches('table') ? element : [...element.querySelectorAll('table')].find(item => item.getClientRects().length > 0)) : null
        if (table) {
          const rect = table.getBoundingClientRect()
          const header = table.querySelector('thead')?.getBoundingClientRect()
          const viewport = table.closest('.data-table-viewport')?.getBoundingClientRect()
          return { x: Math.max(rect.x, viewport?.x ?? rect.x), y: header?.y ?? rect.y }
        }
        const label = element.getAttribute('aria-labelledby')?.split(' ')[0]
        const heading = label ? document.getElementById(label) : null
        const child = heading && element.contains(heading) ? heading
          : element.querySelector(':scope > .surface-header, :scope > header, :scope > h2, :scope > h3, thead')
        const target = (start && child ? child : element).getBoundingClientRect()
        return { x: target.x, y: target.y }
      }, { start: definition.anchor === 'start', includeLabel: definition.presentation?.includeLabel })
      const box = await page.locator('.screen-step-spotlight').boundingBox()
      const main = await page.locator('#main-content').boundingBox()
      const header = await page.locator('.app-header').boundingBox()
      return Boolean(box && Math.abs(box.x - Math.max(main!.x, anchor.x - 10)) < 2
        && Math.abs(box.y - Math.max(header!.y + header!.height + 8, anchor.y - 10)) < 2)
    }, { message: `${title}: spotlight follows the current top anchor` }).toBe(true)
    const box = await card.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
    const finish = card.getByRole('button', { name: '안내 마치기', exact: true })
    if (await finish.count()) {
      await finish.click()
      break
    }
    await card.getByRole('button', { name: '다음', exact: true }).click()
  }
  expect(titles.length).toBeGreaterThanOrEqual(minSteps)
  await expect(card).toHaveCount(0)
  await expect(trigger).toBeFocused()
  return titles
}
