import { expect } from '@playwright/test'
import type { Locator, Page } from '@playwright/test'
import { screenWalkthroughs } from '../src/features/assistant/guide/screen-walkthroughs'

export async function chooseDetailedHelp(page: Page) {
  await expect(page.getByRole('button', { name: '도움말', exact: true })).toBeVisible()
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
      if (await lines.count()) await expect(lines).toHaveCount(2)
      for (const line of await lines.all()) {
        await expect(line).toBeVisible()
        expect((await line.innerText()).length).toBeLessThanOrEqual(160)
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
      const original = (await owner.count() ? owner : page.locator('body')).locator(`${selector}:visible`).first()
      if (!await original.count()) return false
      const anchor = await original.boundingBox()
      const box = await page.locator('.screen-step-spotlight').boundingBox()
      return Boolean(box && anchor && box.width > 0 && box.height > 0
        && box.x < anchor.x + anchor.width && box.x + box.width > anchor.x
        && box.y < anchor.y + anchor.height && box.y + box.height > anchor.y)
    }, { message: `${title}: spotlight follows the current top anchor` }).toBe(true)
    const box = await card.boundingBox()
    expect(box!.x).toBeGreaterThanOrEqual(0)
    expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width)
    const finish = card.getByRole('button', { name: '안내 마치기', exact: true })
    if (await finish.count()) {
      await finish.click()
      break
    }
    const next = card.getByRole('button', { name: /^(다음|실행 버튼을 눌러주세요|강조된 메뉴를 눌러주세요)$/ })
    if (await next.isDisabled()) {
      await expect(card.locator('.disabled-button-hint')).toHaveCount(1)
      await card.getByRole('button', { name: '종료', exact: true }).click()
      break
    }
    await next.click()
  }
  expect(titles.length).toBeGreaterThanOrEqual(minSteps)
  await expect(card).toHaveCount(0)
  await expect(trigger).toBeFocused()
  return titles
}
