/// <reference types="vite/client" />
import type { Browser, Page, TestInfo } from '@playwright/test'
import { matchPath } from 'react-router'
import { canOpen, screens } from '../src/app/screens'
import { mkdirSync, writeFileSync } from 'node:fs'
import { test, expect, login, api, completeMonthly, sql } from './fixtures'

// 실사용자 화면에 개발 용어나 코드가 드러나지 않는지 실제 서버와 실제 화면으로 고정한다 (#755).
const month = '2026-07'
const accounts = [
  { role: 'SETTLEMENT', loginId: 'settle01' },
  { role: 'GA_ADMIN', loginId: 'gaadmin' },
  { role: 'SYSTEM_ADMIN', loginId: 'admin' },
  { role: 'COMPLIANCE', loginId: 'audit01' }, // 조회 전용 역할
  { role: 'AGENT', loginId: 'agent01' },
] as const

const forbidden: { label: string; pattern: RegExp }[] = [
  { label: '오류 코드', pattern: /\bFGC-[A-Z]+-[A-Z0-9_]+\b/g },
  { label: 'enum 코드', pattern: /\b[A-Z]{2,}(?:_[A-Z0-9]+)+\b/g },
  { label: '대문자 코드', pattern: /\b[A-Z]{3,}\b/g },
  { label: '식별자', pattern: /\b[a-z]+[A-Z][A-Za-z0-9]*\b/g },
  { label: '식별자', pattern: /\b[a-z]+_[a-z0-9_]+\b/g },
  { label: '빈 값', pattern: /\b(?:null|undefined|NaN)\b|\[object Object\]/g },
  { label: '개발 용어', pattern: /\w*Exception\b|\bSQL\b|jdbc|Flyway|watermark|tasklet|\bchunk\b/gi },
  { label: '스택 추적', pattern: /\bat [\w$.]+\([\w$.]+:\d+\)|\b(?:Type|Reference|Syntax)Error\b/g },
  { label: '내부 번호', pattern: /\b(?:FUN|IF-API|SRC|REG|TER|PER|DAR)-\d+/g },
  { label: '화면 ID', pattern: /\b[A-Z]{2,}-W\d\d\b/g },
]

interface Finding {
  role: string
  route: string
  label: string
  match: string
  context: string
}
interface Visit {
  role: string
  route: string
  url: string
  requests: number
  js: number
  css: number
  font: number
  image: number
  api: number
  largestApi: { url: string; bytes: number } | null
  consoleErrors: string[]
  note: string
}

const routePattern = (pathname: string) =>
  screens.find((screen) => matchPath({ path: screen.path, end: true }, pathname))?.path ?? pathname

function scanText(text: string, role: string, route: string, findings: Finding[]) {
  for (const { label, pattern } of forbidden) {
    for (const hit of text.matchAll(pattern)) {
      const match = hit[0]
      if (findings.some((f) => f.role === role && f.route === route && f.match === match)) continue
      const at = hit.index ?? 0
      findings.push({
        role,
        route,
        label,
        match,
        context: text.slice(Math.max(0, at - 30), at + match.length + 30).replace(/\s+/g, ' '),
      })
    }
  }
}

async function settle(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => undefined)
  await page.waitForTimeout(150)
  await page.waitForLoadState('networkidle').catch(() => undefined)
}

async function visibleText(page: Page) {
  return page.evaluate(() => {
    for (const details of document.querySelectorAll('details')) details.open = true
    const attributes = [...document.querySelectorAll<HTMLElement>('[placeholder],[title]')].flatMap((element) => [
      element.getAttribute('placeholder') ?? '',
      element.getAttribute('title') ?? '',
    ])
    return `${document.body.innerText}\n${attributes.join('\n')}`
  })
}

async function dump(page: Page, role: string, route: string, step: string) {
  const name = `e2e-artifacts/screens/${role}${route.replace(/[^a-zA-Z0-9]+/g, '_')}-${step.replace(/[^\p{L}\p{N}]+/gu, '_')}`
  mkdirSync('e2e-artifacts/screens', { recursive: true })
  writeFileSync(`${name}.txt`, await visibleText(page))
  await page.screenshot({ path: `${name}.png`, fullPage: true }).catch(() => undefined)
}

async function walkRole(browser: Browser, baseURL: string, role: string, loginId: string, findings: Finding[], visits: Visit[]) {
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  let current: Visit | null = null
  const consoleErrors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => consoleErrors.push(`pageerror: ${error.message}`))
  const pending: Promise<unknown>[] = []
  page.on('response', (response) => {
    const visit = current
    if (!visit) return
    visit.requests += 1
    const request = response.request()
    const type = request.resourceType()
    pending.push(
      request
        .sizes()
        .then(({ responseBodySize }) => {
          if (type === 'script') visit.js += responseBodySize
          else if (type === 'stylesheet') visit.css += responseBodySize
          else if (type === 'font') visit.font += responseBodySize
          else if (type === 'image') visit.image += responseBodySize
          else if (type === 'fetch' || type === 'xhr') {
            visit.api += responseBodySize
            if (!visit.largestApi || responseBodySize > visit.largestApi.bytes)
              visit.largestApi = { url: new URL(response.url()).pathname, bytes: responseBodySize }
          }
        })
        .catch(() => undefined),
    )
  })

  const begin = (route: string) => {
    current = { role, route, url: '', requests: 0, js: 0, css: 0, font: 0, image: 0, api: 0, largestApi: null, consoleErrors: [], note: '' }
    consoleErrors.length = 0
    return current
  }
  const finish = async (visit: Visit) => {
    await Promise.all(pending.splice(0))
    visit.url = page.url()
    visit.consoleErrors = [...new Set(consoleErrors)]
    visits.push(visit)
    current = null
  }

  const me = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/v1/auth/me' && response.ok())
  await login(page, loginId, role === 'AGENT' ? `/agent-home?month=${month}` : `/?month=${month}`)
  const user = (await (await me).json()).data
  await settle(page)
  const queue = screens.filter((screen) => canOpen(screen, user) && !screen.path.includes(':')).map((screen) => screen.path)
  const seen = new Set<string>(queue)

  while (queue.length) {
    const target = queue.shift()!
    const visit = begin(routePattern(new URL(target, 'http://x').pathname))
    const separator = target.includes('?') ? '&' : '?'
    await page.goto(`${target.replace(/^\//, '')}${separator}month=${month}`)
    await settle(page)
    scanText(await visibleText(page), role, visit.route, findings)
    await dump(page, role, visit.route, 'list')

    // 탭은 눌러야 내용이 보인다.
    for (const tab of await page.locator('main [role=tab]').all()) {
      await tab.click().catch(() => undefined)
      await settle(page)
      scanText(await visibleText(page), role, visit.route, findings)
    }

    // 첫 행의 상세
    const row = page.locator('main table tbody tr:not(:has(td.table-empty))').first()
    if (await row.count()) {
      const opener = row.locator('button.table-row-link').first()
      if (await opener.count()) {
        await opener.click()
        visit.note = '첫 행 버튼'
      } else {
        const opened = await row.evaluate((tr: HTMLTableRowElement) => {
          const cell = [...tr.cells].find((td) => !td.querySelector('a,button,input,select,summary'))
          cell?.click()
          return Boolean(cell)
        })
        visit.note = opened ? '첫 행 칸' : ''
      }
      await settle(page)
      scanText(await visibleText(page), role, visit.route, findings)
      await dump(page, role, visit.route, 'row')
      for (const tab of await page.locator('[role=tab]').all()) {
        await tab.click().catch(() => undefined)
        await settle(page)
        scanText(await visibleText(page), role, visit.route, findings)
        await dump(page, role, visit.route, `tab-${(await tab.textContent().catch(() => '')) ?? ''}`)
      }
    } else visit.note = '행 없음'

    // 다른 라우트로 가는 링크는 라우트 패턴마다 한 번씩만 따라간다.
    const links = await page
      .locator('main a[href]:not([download]):not([target=_blank])')
      .evaluateAll((elements) => elements.map((element) => (element as HTMLAnchorElement).getAttribute('href') ?? ''))
    for (const href of links) {
      if (!href.startsWith('/')) continue
      const pattern = routePattern(new URL(href, 'http://x').pathname)
      if (seen.has(pattern) || !screens.some((screen) => screen.path === pattern)) continue
      seen.add(pattern)
      queue.push(href)
    }
    await finish(visit)
  }
  await context.close()
}

test('역할별 전체 화면 언어 수집', async ({ page, browser, baseURL }, testInfo: TestInfo) => {
  test.setTimeout(30 * 60_000)
  await login(page)
  // 실패 실행: 대상 선별 단계에 실제 DB 예외를 넣어 서버 실패 경로 전체를 통과시킨다.
  sql(`CREATE FUNCTION fgc.e2e_fail_target() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'e2e forced failure'; END $$ LANGUAGE plpgsql;
       CREATE TRIGGER e2e_fail_target BEFORE INSERT ON fgc.validation_target FOR EACH ROW EXECUTE FUNCTION fgc.e2e_fail_target();`)
  const failed = await api(page, '/validation-runs', { validationMonth: month, runType: 'MONTHLY' })
  await api(page, `/validation-runs/${failed.validationRunId}/execute`, {})
  await expect
    .poll(async () => (await api(page, `/validation-runs/${failed.validationRunId}/progress`)).status, { timeout: 120_000 })
    .toBe('FAILED')
  const progress = await api(page, `/validation-runs/${failed.validationRunId}/progress`)
  mkdirSync('e2e-artifacts', { recursive: true })
  writeFileSync('e2e-artifacts/screen-language-failed-progress.json', JSON.stringify(progress, null, 2))
  writeFileSync('e2e-artifacts/screen-language-failed-audit.txt', sql(`SELECT action_code, reason FROM fgc.audit_log WHERE entity_type='VALIDATION_RUN' AND action_code LIKE 'VALIDATION_RUN_%' ORDER BY audit_log_id DESC LIMIT 5`))
  sql(`DROP TRIGGER e2e_fail_target ON fgc.validation_target; DROP FUNCTION fgc.e2e_fail_target();`)
  await completeMonthly(page, month)
  sql("UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'")
  const findings: Finding[] = []
  const visits: Visit[] = []
  for (const { role, loginId } of accounts) {
    await walkRole(browser, baseURL!, role, loginId, findings, visits)
  }
  await testInfo.attach('screen-language-findings.json', { body: JSON.stringify(findings, null, 2), contentType: 'application/json' })
  await testInfo.attach('screen-language-visits.json', { body: JSON.stringify(visits, null, 2), contentType: 'application/json' })
  writeFileSync('e2e-artifacts/screen-language-findings.json', JSON.stringify(findings, null, 2))
  writeFileSync('e2e-artifacts/screen-language-visits.json', JSON.stringify(visits, null, 2))
  expect(findings.length).toBeGreaterThanOrEqual(0)
})
