// 실제 자료로 표를 그렸을 때 읽어야 할 열과 값이 가려지지 않는지 잰다. 글자 내용은 vitest와 screen-language.spec.ts가 본다.
import type { Page } from '@playwright/test'
import { test, expect, login, completeMonthly, sql } from './fixtures'

const month = '2026-07'
// 1440폭 화면에서 세로 스크롤바(17px)를 뺀 실제 본문 폭. 시험 브라우저는 스크롤바를 그리지 않는다.
const viewport = { width: 1423, height: 1000 }

/** 머리글이 표 보기 영역 밖으로 나간 픽셀 수. 0이면 가로 스크롤 없이 보인다. */
const hiddenPixels = (page: Page, labels: string[]) =>
  page.evaluate((names) => {
    const table = document.querySelector<HTMLTableElement>('table.data-table')!
    const edge = table.closest('.data-table-viewport')!.getBoundingClientRect().right
    const headers = [...table.tHead!.rows[0].cells]
    return Object.fromEntries(
      names.map((name) => {
        const header = headers.find((cell) => cell.textContent?.startsWith(name))!
        return [name, Math.max(0, Math.round(header.getBoundingClientRect().right - edge))]
      }),
    )
  }, labels)

/** 끝까지 내렸을 때 떠 있는 업무 비서에 가려지는 본문 글자. */
async function coveredAtScrollEnd(page: Page) {
  await expect(page.locator('.pico-launcher')).toBeVisible() // 지연 로딩이라 새 화면마다 기다린다.
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  return page.evaluate(() => {
    const launcher = document.querySelector('.pico-launcher')!
    const boxes = [launcher, ...launcher.querySelectorAll('*')].map((element) => element.getBoundingClientRect())
    const walker = document.createTreeWalker(document.querySelector('.page-content')!, NodeFilter.SHOW_TEXT)
    const range = document.createRange()
    const covered: string[] = []
    while (walker.nextNode()) {
      const text = walker.currentNode.textContent?.trim()
      if (!text) continue
      range.selectNodeContents(walker.currentNode)
      const hit = [...range.getClientRects()].some((line) =>
        boxes.some(
          (box) =>
            box.width > 0 && line.right > box.left && line.left < box.right && line.bottom > box.top && line.top < box.bottom,
        ),
      )
      if (hit) covered.push(text)
    }
    return covered
  })
}

/** 제목이 왼쪽 끝에서, 도움말 버튼이 오른쪽 끝에서 떨어진 픽셀. 머리가 아직 없으면 null. */
const headerLayout = (page: Page, selector: string, titleSelector: string, helpSelector = selector) =>
  page.evaluate(
    ([headerSelector, title, helpContainerSelector]) => {
      const header = document.querySelector(headerSelector)
      const heading = header?.querySelector(title)
      const helpContainer = document.querySelector(helpContainerSelector)
      const help = [...(helpContainer?.querySelectorAll('button') ?? [])].find((button) => button.textContent?.trim() === '도움말')
      if (!header || !heading || !helpContainer || !help) return null
      const box = header.getBoundingClientRect()
      return {
        titleFromLeft: Math.round(heading.getBoundingClientRect().left - box.left - parseFloat(getComputedStyle(header).paddingLeft)),
        helpFromRight: Math.round(helpContainer.getBoundingClientRect().right - parseFloat(getComputedStyle(helpContainer).paddingRight) - help.getBoundingClientRect().right),
      }
    },
    [selector, titleSelector, helpSelector],
  )

test('쪽 제목은 왼쪽 끝에, 도움말은 오른쪽 끝에 있고 격자를 가득 채우지 않는다', async ({ page }) => {
  await page.setViewportSize(viewport)
  await login(page)
  const run = await completeMonthly(page, month)
  const contract = sql('SELECT min(contract_id) FROM fgc.insurance_contract')
  const schedule = sql('SELECT min(schedule_header_id) FROM fgc.schedule_header')
  // completeMonthly의 준비 요청도 admin으로 로그인해 앞선 세션을 밀어내므로 그 뒤에 들어간다.
  await login(page, 'admin', `/?month=${month}`)
  const atEdges = { titleFromLeft: 0, helpFromRight: 0 }

  for (const route of [
    './',
    'contracts/new',
    `contracts/${contract}`,
    'contracts',
    'transactions',
    'agent/statements',
    `schedules/${schedule}`,
    'schedules',
    'cap-checks',
    'arbitrage-checks',
    'acquisition-cost-checks',
    'journals',
    'reconciliations',
    'clawbacks',
    `validation-runs/${run}`,
    'audit-logs',
  ]) {
    await page.goto(`${route}?month=${month}`)
    if (route === 'acquisition-cost-checks') {
      // 사용자에게 숨긴 화면은 일반 업무 머리글 대신 접근 차단 화면을 표시한다.
      await expect(page.getByRole('heading', { name: '권한 없음' })).toBeVisible()
      continue
    }
    await expect.poll(() => headerLayout(page, '.page-header', '.page-title'), { message: route }).toEqual(atEdges)
  }

  // 관리자 기준정보·정책 화면은 기준일과 도움말이 탭과 같은 줄에 있다 (#907).
  for (const [route, dateSelector] of [
    ['base', '.base-as-of-summary'],
    ['policies', '.policy-date-field input'],
  ]) {
    await page.goto(`${route}?month=${month}`)
    await expect.poll(() => headerLayout(page, '.page-header', '.page-title', '.reference-tabs-toolbar'), { message: route }).toEqual(atEdges)
    await expect.poll(() => page.evaluate((selector) => {
      const toolbar = document.querySelector('.reference-tabs-toolbar')
      const tabs = toolbar?.querySelector('[role="tablist"]')
      const help = toolbar?.querySelector('.screen-help-trigger')
      const date = toolbar?.querySelector(selector)
      if (!tabs || !help || !date) return null
      const center = (element: Element) => {
        const rect = element.getBoundingClientRect()
        return rect.top + rect.height / 2
      }
      return Math.max(Math.abs(center(tabs) - center(help)), Math.abs(center(date) - center(help)))
    }, dateSelector), { message: `${route}: 탭, 기준일 및 도움말 세로 정렬` }).toBeLessThanOrEqual(1)
  }

  // VRUN-W01은 도움말이 쪽 머리가 아니라 새 실행 만들기 머리에 있다.
  await page.goto(`validation-runs?month=${month}`)
  await expect.poll(() => headerLayout(page, '.page-header', '.page-title')).toEqual(atEdges)

  // 예외 상세의 도움말은 모달 헤더 오른쪽에 있다.
  await page.goto(`exceptions?month=${month}`)
  await expect.poll(() => headerLayout(page, '.page-header', '.page-title'), { message: 'exceptions' }).toEqual(atEdges)
  await page.locator('.exception-row').first().click()
  const detail = page.getByRole('dialog', { name: /^예외 상세/ })
  const detailHelp = detail.locator('.exception-detail-help').getByRole('button', { name: '도움말', exact: true })
  await expect(detailHelp).toBeVisible()
  expect((await detailHelp.boundingBox())!.width).toBeLessThan(160)

})

test('1440폭에서 읽어야 할 열이 가로 스크롤 뒤로 가지 않고 업무 비서가 끝의 값을 가리지 않는다', async ({ page }) => {
  await page.setViewportSize(viewport)
  await login(page)
  const run = await completeMonthly(page, month)

  // VRUN-W01: 상세로 가는 길인 열기와 실패 사유
  await page.goto(`validation-runs?month=${month}`)
  await expect(page.getByRole('link', { name: /차 실행 열기$/ }).first()).toBeVisible()
  await expect.poll(() => hiddenPixels(page, ['상세', '실패 사유'])).toEqual({ 상세: 0, '실패 사유': 0 })

  // EXCP-W01: 오른쪽 패널을 연 기본 분할에서 내용, 상태, 담당자
  await page.goto(`exceptions?month=${month}`)
  await page.locator('.exception-row').first().click()
  await expect(page.getByRole('button', { name: '처리 저장' })).toBeVisible()
  await expect.poll(() => hiddenPixels(page, ['내용', '상태', '담당자'])).toEqual({ 내용: 0, 상태: 0, 담당자: 0 })
  const content = page.getByRole('columnheader', { name: /^내용/ })
  expect((await content.boundingBox())!.width, '내용 열이 한 줄에 몇 글자는 읽히는 폭').toBeGreaterThan(160)

  await page.getByRole('dialog', { name: /예외 상세/ }).getByRole('button', { name: '닫기', exact: true }).first().click()
  // 끝까지 내리면 표의 마지막 행까지 업무 비서에 가려지지 않는다.
  await expect.poll(() => coveredAtScrollEnd(page), { message: 'EXCP-W01' }).toEqual([])
  await page.goto(`reconciliations?month=${month}&historyView=table`)
  await page.getByRole('button', { name: '표 보기', exact: true }).click()
  await expect(page.getByRole('table', { name: '검색조건에 해당하는 대사 실행 이력' }).locator('tbody tr').first()).toBeVisible()
  await expect.poll(() => coveredAtScrollEnd(page), { message: 'RECO-W01' }).toEqual([])
  await page.goto(`validation-runs/${run}?month=${month}`)
  await expect(page.getByRole('button', { name: '확정' })).toBeVisible()
  await expect.poll(() => coveredAtScrollEnd(page), { message: 'VRUN-W02' }).toEqual([])
})
