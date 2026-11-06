/// <reference types="vite/client" />
import { readFileSync } from 'node:fs'
import { test as plainTest, type Browser, type Locator, type Page, type Request } from '@playwright/test'
import { matchPath } from 'react-router'
import { canOpen, screens } from '../src/app/screens'
import { test, expect, login, api, completeMonthly, sql } from './fixtures'
import screenHelp from '../src/features/assistant/guide/screen-help.json' with { type: 'json' }
import paymentGuides from '../src/features/assistant/payment-guides.v1.json' with { type: 'json' }
import workflowGuides from '../src/features/assistant/workflow-guides.v1.json' with { type: 'json' }
import workflowHelp from '../src/features/assistant/workflow-help.generated.json' with { type: 'json' }

// 실사용자 화면에 개발 용어나 코드가 보이면 실패한다 (#755, SRC-074, 화면정의서 공통규칙 10).
// 역할마다 갈 수 있는 모든 라우트를 돌고 목록은 첫 행의 상세까지 연다.
// 저장, 실행, 확정 버튼은 누르지 않는다. 데이터는 시험 준비에서만 쓴다.
// This read-only crawl records text/transfer artifacts and failure screenshots.
// Continuous video adds encoding work for hundreds of page scans without extra assertions.
test.use({ video: 'off' })

const month = '2026-07'
const accounts = [
  { role: 'SETTLEMENT', loginId: 'settle01' },
  { role: 'GA_ADMIN', loginId: 'gaadmin' },
  { role: 'SYSTEM_ADMIN', loginId: 'admin' },
  { role: 'COMPLIANCE', loginId: 'audit01' }, // 조회 전용 역할
  { role: 'AGENT', loginId: 'agent01' },
] as const

const forbidden = [
  { label: '오류 코드', pattern: /\bFGC-[A-Z]+-[A-Z0-9_]+\b/g },
  { label: '코드값', pattern: /\b[A-Z]{2,}(?:_[A-Z0-9]+)+\b/g },
  { label: '코드값', pattern: /\b[A-Z]{3,}\b/g },
  { label: '변수 이름', pattern: /\b[a-z]+[A-Z][A-Za-z0-9]*\b/g },
  { label: '변수 이름', pattern: /\b[a-z]+_[a-z0-9_]+\b/g },
  { label: '빈 값', pattern: /\b(?:null|undefined|NaN)\b|\[object Object\]/g },
  { label: '개발 용어', pattern: /\w*Exception\b|\bSQL\b|jdbc|Flyway|watermark|tasklet|\bchunk\b/gi },
  { label: '개발 용어', pattern: /체크포인트|스냅샷|멱등|트랜잭션|쿼리|워터마크|파라미터|페이로드|배치|시드/g },
  { label: '개발 용어', pattern: /\bID\b|\b[245]\d\d (?:수락|응답|오류)/g },
  { label: '스택 추적', pattern: /\bat [\w$.]+\([\w$.]+:\d+\)|\b(?:Type|Reference|Syntax)Error\b/g },
  { label: '내부 번호', pattern: /\b(?:FUN|IF-API|SRC|REG|TER|PER|DAR)-\d+/g },
  { label: '화면 ID', pattern: /\b[A-Z]{2,}-W\d\d\b/g },
  { label: '팀 내부 말', pattern: /룰셋|원수사|귀속행|상태 사건|계산판|발행판|예정판|스케줄행|매칭키|재검출|정정 묶음|오탐|프로젝트 가정|계약수동|원천유형|공통비 풀|확정전|사람이 수행|업무건|분모 계약|운영정책서|규제조문표|보정판|합성값|데모|시연/g },
  { label: '구역 번호', pattern: /(?<!조)[①-⑳]/g }, // 제3조① 같은 조문 표기는 통과
  { label: '표 설명 말', pattern: /화면 \d+행|\d+행\b/g },
  { label: '개발 용어', pattern: /서버 (?:저장|결과|확정|실행|처리|상태)|서버의|서버에서|서버가/g },
]

/** 사용자가 알아보는 대문자 단어. 두 글자(GA, FC, AI)는 코드값 검사에 걸리지 않는다. */
const businessWords = new Map([
  ['FGC', '제품명이자 계약번호, 조직코드의 앞머리'],
  ['CSV', '내보내기 파일 형식'],
  ['PDF', '환수 통지서 파일 형식'],
  ['BEST', '포트폴리오 막대 표시, 누구나 읽는 말'],
  ['FAQ', '금융당국 최종 FAQ를 가리키는 정책 근거 문구'],
])
/** 업무 식별자: 판매버전(화면정의서 :614). 정책코드는 입력칸에만 있고 화면 글자로 나오지 않는다. */
const businessCode = /\b20\d\d(?:-[A-Z0-9]+)+\b/g
/** 코드 열을 요구하는 화면. 기준정보는 조직코드 등을 보여 주는 화면이다(화면정의서 :612~:616). */
const codeScreens = new Set(['/base'])

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
  requests: number
  measuredRequests: number
  js: number
  css: number
  font: number
  image: number
  api: number
  largestApi: { url: string; bytes: number } | null
  consoleErrors: string[]
}

const pathnameOf = (path: string) => new URL(path, 'http://fgc.test').pathname
const routePattern = (pathname: string) =>
  screens.find((screen) => matchPath({ path: screen.path, end: true }, pathname))?.path ?? pathname

function scan(text: string, role: string, route: string, findings: Finding[]) {
  const shown = text.replace(businessCode, ' ')
  for (const { label, pattern } of forbidden) {
    for (const hit of shown.matchAll(pattern)) {
      const match = hit[0]
      if (label === '코드값' && (businessWords.has(match) || codeScreens.has(route))) continue
      if (findings.some((found) => found.role === role && found.route === route && found.match === match)) continue
      const at = hit.index ?? 0
      const context = shown.slice(Math.max(0, at - 30), at + match.length + 30).replace(/\s+/g, ' ')
      findings.push({ role, route, label, match, context })
    }
  }
}

// Chrome can omit terminal events for fetches interrupted by document navigation.
// Those requests no longer describe the visible document and must not delay its scan.
function pendingRequests<T>(isMainNavigation: (request: T) => boolean) {
  const pending = new Set<T>()
  return {
    pending,
    started(request: T) {
      if (isMainNavigation(request)) pending.clear()
      pending.add(request)
    },
    finished(request: T) { pending.delete(request) },
  }
}

plainTest('문서 이동 후 이전 요청은 기다리지 않고 새 문서·하위 프레임 요청은 기다린다', () => {
  const tracker = pendingRequests<{ main?: boolean }>((request) => request.main === true)
  const oldFetch = {}, navigation = { main: true }, currentFetch = {}, iframe = {}
  tracker.started(oldFetch)
  tracker.started(navigation)
  tracker.started(currentFetch)
  tracker.started(iframe)
  tracker.finished(oldFetch) // A late event from the previous document cannot clear new work.
  expect([...tracker.pending]).toEqual([navigation, currentFetch, iframe])
  tracker.finished(navigation)
  tracker.finished(iframe)
  expect([...tracker.pending]).toEqual([currentFetch])
  tracker.finished(currentFetch)
  expect(tracker.pending.size).toBe(0)
})

const inFlight = new WeakMap<Page, Set<Request>>()
function trackRequests(page: Page) {
  const requests = pendingRequests<Request>((request) => request.isNavigationRequest() && request.frame() === page.mainFrame())
  inFlight.set(page, requests.pending)
  page.on('request', requests.started)
  page.on('requestfinished', requests.finished)
  page.on('requestfailed', requests.finished)
}
/** 화면 안에서 탭이나 행을 바꾸면 networkidle이 다시 오지 않아 진행 중 요청을 직접 센다. */
async function settle(page: Page) {
  await page.waitForLoadState('load')
  for (let quiet = 0, waited = 0; quiet < 3 && waited < 100; waited++) {
    await page.waitForTimeout(100)
    quiet = inFlight.get(page)?.size ? 0 : quiet + 1
  }
}

/** 눈에 보이는 글자 전부: 접힌 영역을 펼치고 입력 안내와 말풍선 글도 함께 읽는다. */
const visibleText = (page: Page) =>
  page.evaluate(() => {
    for (const details of document.querySelectorAll('details')) details.open = true
    // 아이콘 글꼴 이름과 외부 기사 제목(보험저널)은 우리 문구가 아니다.
    const skipped = [...document.querySelectorAll<HTMLElement>('.material-symbols-rounded, .material-symbols-outlined, .cb-news-list')]
    for (const element of skipped) element.style.display = 'none'
    const text = document.body.innerText
    for (const element of skipped) element.style.display = ''
    const hints = [...document.querySelectorAll('[placeholder], [title]')].flatMap((element) => [
      element.getAttribute('placeholder') ?? '',
      element.getAttribute('title') ?? '',
    ])
    return `${text}\n${hints.join('\n')}`
  })

/** 글자를 읽는 시험이라 떠 있는 안내나 열린 창에 가려져도 누른다. */
const press = (control: Locator) => control.evaluate((element: HTMLElement) => element.click())

/** 첫 행의 상세를 연다. 첫 칸 버튼이나 행 클릭으로 선택하고, 행 안의 보기 버튼까지 누른다. */
async function openFirstRow(page: Page, check: () => Promise<void>) {
  const firstRow = () => page.locator('main table tbody tr:not(:has(td.table-empty))').first()
  if (!(await firstRow().count())) return
  const opener = firstRow().locator('button.table-row-link').first()
  if (await opener.count()) await press(opener)
  else
    await firstRow().evaluate((tr: HTMLTableRowElement) => {
      ;[...tr.cells].find((cell) => !cell.querySelector('a, button, input, select, summary'))?.click()
    })
  await settle(page)
  await check()
  const viewButton = firstRow().getByRole('button', { name: /보기|열기/ }).first()
  if (await viewButton.count()) {
    await press(viewButton)
    await settle(page)
    await check()
    await page.keyboard.press('Escape')
  }
}

async function walkRole(
  browser: Browser,
  baseURL: string,
  { role, loginId }: (typeof accounts)[number],
  states: { path: string; label: string }[],
  findings: Finding[],
  visits: Visit[],
) {
  const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  trackRequests(page)
  let current: Visit | null = null
  const sizes: Promise<unknown>[] = []
  const responseVisits = new WeakMap<Request, Visit>()
  page.on('console', (message) => {
    // 로그인 전 갱신 토큰 확인은 401이 정상이다.
    if (message.type() === 'error' && !(current?.route === '/login' && message.text().includes('status of 401')))
      current?.consoleErrors.push(message.text())
  })
  page.on('pageerror', (error) => current?.consoleErrors.push(error.message))
  page.on('response', (response) => {
    const visit = current
    if (!visit) return
    visit.requests += 1
    responseVisits.set(response.request(), visit)
  })
  // sizes() waits for completion: asking at response headers can wait forever
  // when navigation interrupts the body. Only completed requests are measurable.
  page.on('requestfinished', (request) => {
    const visit = responseVisits.get(request)
    if (!visit) return
    const type = request.resourceType()
    sizes.push(
      request.sizes()
        .then(({ responseBodySize }) => {
          visit.measuredRequests += 1
          const bytes = Math.max(0, responseBodySize)
          if (type === 'script') visit.js += bytes
          else if (type === 'stylesheet') visit.css += bytes
          else if (type === 'font') visit.font += bytes
          else if (type === 'image') visit.image += bytes
          else if (type === 'fetch' || type === 'xhr') {
            visit.api += bytes
            if (!visit.largestApi || bytes > visit.largestApi.bytes)
              visit.largestApi = { url: pathnameOf(request.url()), bytes }
          }
        })
        .catch(() => undefined),
    )
  })
  const begin = (route: string) => {
    console.log(`[screen-language] ${role} ${route}`)
    current = { role, route, requests: 0, measuredRequests: 0, js: 0, css: 0, font: 0, image: 0, api: 0, largestApi: null, consoleErrors: [] }
    return current
  }
  const finish = async () => {
    await Promise.all(sizes.splice(0))
    if (current) visits.push(current)
    current = null
  }

  begin('/login')
  const me = page.waitForResponse((response) => pathnameOf(response.url()) === '/api/v1/auth/me' && response.ok())
  await login(page, loginId, role === 'AGENT' ? `/agent-home?month=${month}` : `/?month=${month}`)
  const user = (await (await me).json()).data
  await settle(page)
  await finish()

  const allowed = (pathname: string) => {
    const screen = screens.find((candidate) => candidate.path === routePattern(pathname))
    return !!screen && canOpen(screen, user)
  }
  const visited = new Set<string>()
  const queue: { path: string; label?: string }[] = screens
    .filter((screen) => canOpen(screen, user) && !screen.path.includes(':'))
    .map((screen) => ({ path: screen.path }))
  // 첫 행으로는 안 보이는 상태는 처음 열 수 있는 역할이 한 번만 본다.
  for (const state of states.splice(0)) (allowed(pathnameOf(state.path)) ? queue : states).push(state)
  const enqueue = (path: string) => {
    const pattern = routePattern(pathnameOf(path))
    if (!allowed(pathnameOf(path)) || visited.has(pattern) || queue.some((item) => routePattern(pathnameOf(item.path)) === pattern)) return
    queue.push({ path })
  }
  const followLinks = async () => {
    const links = await page
      .locator('main a[href]:not([download]):not([target=_blank])')
      .evaluateAll((elements) => elements.map((element) => element.getAttribute('href') ?? ''))
    for (const href of links) if (href.startsWith('/')) enqueue(href)
    const here = pathnameOf(page.url())
    visited.add(routePattern(here))
    // 수정 화면은 링크가 아니라 버튼으로 열린다.
    if (routePattern(here) === '/contracts/:id') enqueue(`${here}/edit`)
  }

  while (queue.length) {
    const target = queue.shift()!
    visited.add(routePattern(pathnameOf(target.path)))
    const route = target.label ?? routePattern(pathnameOf(target.path))
    begin(route)
    const url = new URL(target.path, 'http://fgc.test')
    url.searchParams.set('month', month)
    await page.goto(`${url.pathname.slice(1)}${url.search}`)
    await settle(page)
    const check = async () => scan(await visibleText(page), role, route, findings)
    await check()
    // 화면 도움말의 첫 단계를 실제로 연다. 나머지 단계 문구는 안내 문구 시험이 본다.
    const help = page.getByRole('button', { name: '도움말', exact: true }).first()
    if (!target.label && (await help.count())) {
      await press(help)
      await settle(page)
      await check()
      await press(help)
    }
    const clickTabs = async () => {
      for (const tab of await page.locator('main [role=tab]').all()) {
        await press(tab)
        await settle(page)
        await check()
      }
    }
    await clickTabs()
    await followLinks()
    const listRoute = routePattern(pathnameOf(page.url()))
    await openFirstRow(page, check)
    await followLinks()
    if (routePattern(pathnameOf(page.url())) !== listRoute) await clickTabs()
    await finish()
  }

  // 떠 있는 안내도 같은 기준이다(SRC-074). 버튼이 없으면 시험이 실패해 빠뜨리지 않는다.
  const home = role === 'AGENT' ? '/agent-home' : '/'
  const overlays = role === 'AGENT' ? ['상품 추천 AI'] : ['전체 업무 가이드', /^업무 비서 AI 열기/]
  for (const name of overlays) {
    const { route } = begin(`${home} (${String(name).replace(/[/^]/g, '')})`)
    await page.goto(`${home.slice(1)}?month=${month}`)
    await settle(page)
    await press(page.getByRole('button', { name }).first())
    await settle(page)
    scan(await visibleText(page), role, route, findings)
    for (const tab of await page.locator('[role=dialog] [role=tab]').all()) {
      await press(tab)
      await settle(page)
      scan(await visibleText(page), role, route, findings)
    }
    await finish()
  }

  const missing = screens.filter((screen) => canOpen(screen, user) && !visited.has(screen.path)).map((screen) => screen.path)
  await context.close()
  return missing.map((path) => `${role} ${path}`)
}

const kb = (bytes: number) => (bytes / 1024).toFixed(1)
function transferTable(visits: Visit[]) {
  const rows = visits.map((visit) =>
    [
      visit.role,
      visit.route,
      visit.requests,
      visit.requests - visit.measuredRequests,
      kb(visit.js),
      kb(visit.css),
      kb(visit.font),
      kb(visit.image),
      kb(visit.api),
      visit.largestApi ? `${visit.largestApi.url} ${kb(visit.largestApi.bytes)}` : '-',
      visit.consoleErrors.length,
    ].join(' | '),
  )
  return [
    '실서버 시험은 Vite 개발 서버를 쓴다. JS, CSS는 번들 전 크기이고 같은 역할이 이미 받은 파일은 0이다.',
    '합격선이 아니라 기록이다(KB). 배포본 크기는 빌드 산출물로 따로 잰다. 본문 수신 중 이동·취소되거나 측정에 실패한 요청은 미측정 건수로 표시하며 0바이트로 판정하지 않는다.',
    '',
    '역할 | 라우트 | 요청 수 | 미측정 요청 수 | JS | CSS | 글꼴 | 이미지 | API | 가장 큰 API 응답 | 콘솔 오류',
    '--- | --- | --- | --- | --- | --- | --- | --- | --- | --- | ---',
    ...rows,
  ].join('\n')
}

plainTest('검사 규칙은 코드를 잡고 업무 표기는 통과시킨다', () => {
  const flagged = (text: string, route = '/contracts') => {
    const found: Finding[] = []
    scan(text, '시험', route, found)
    return found.map((item) => item.match)
  }
  for (const leak of ['FGC-VRUN-003', 'MANUAL_CONTRACT', 'FINALIZED', 'validationRunId', 'journal_no', 'undefined', '[object Object]', 'IllegalStateException', 'FUN-045', 'IF-API-49', 'REG-08', 'VRUN-W02', '체크포인트', '실행 ID', '202 수락'])
    expect(flagged(`실패: ${leak} 확인`).some((match) => leak.includes(match)), leak).toBe(true)
  for (const shown of ['FGC-FGL01-202607-0001', 'FGC-B0101', 'A-FC-001', 'JV-2026-07-0010', 'GA→설계사', 'CSV 내보내기', '2026-CURRENT-A', '요청번호 20261104-abcdef'])
    expect(flagged(`표시: ${shown}`), shown).toEqual([])
  expect(flagged('근거 REG-08, SRC-022', '/policies?policyVersionId=2')).toEqual(expect.arrayContaining(['REG-08', 'SRC-022']))
  expect(flagged('정책 CLAW-GA-VIRTUAL-2026').length).toBeGreaterThan(0)
  expect(flagged('부칙 제3조①')).toEqual([])
  expect(flagged('① 조회 조건')).toEqual(['①'])
  expect(flagged('BASE_COMMISSION', '/base')).toEqual([])
  expect(flagged('validationRunId', '/base')).toEqual(['validationRunId'])
})

/** 사용자에게 보이는 안내 문구: 제목, 단계 설명, 업무 설명 */
const guideTexts = (value: unknown, key = ''): string[] =>
  typeof value === 'string'
    ? ['title', 'instruction', 'description'].includes(key) ? [value] : []
    : Array.isArray(value)
      ? value.flatMap((item) => guideTexts(item, key))
      : value && typeof value === 'object'
        ? Object.entries(value).flatMap(([name, item]) => guideTexts(item, name))
        : []

plainTest('화면 도움말과 업무 안내의 모든 단계 문구에 개발 용어가 없다', () => {
  const found: Finding[] = []
  for (const [catalog, content] of Object.entries({ screenHelp, workflowGuides, paymentGuides, workflowHelp }))
    for (const text of guideTexts(content)) scan(text, catalog, catalog, found)
  expect(found.map((item) => `${item.route} | ${item.match} | ${item.context}`)).toEqual([])
})

test('실사용자 화면에 개발 용어와 코드가 보이지 않고 콘솔 오류가 없다', async ({ page, browser, baseURL }, testInfo) => {
  test.setTimeout(20 * 60_000)
  const findings: Finding[] = []
  const visits: Visit[] = []

  trackRequests(page)
  await page.goto('login')
  await settle(page)
  scan(await visibleText(page), '로그인 전', '/login', findings)

  await login(page)
  // 실패한 실행: 대상 선별 단계에 실제 DB 오류를 넣어 서버 실패 경로 전체를 지난다.
  sql(`CREATE OR REPLACE FUNCTION fgc.e2e_fail_target() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'e2e forced failure'; END $$ LANGUAGE plpgsql;
       CREATE TRIGGER e2e_fail_target BEFORE INSERT ON fgc.validation_target FOR EACH ROW EXECUTE FUNCTION fgc.e2e_fail_target();`)
  const failedRun = (await api(page, '/validation-runs', { validationMonth: month, runType: 'MONTHLY' })).validationRunId
  await api(page, `/validation-runs/${failedRun}/execute`, {})
  await expect
    .poll(async () => (await api(page, `/validation-runs/${failedRun}/progress`)).status, { timeout: 120_000 })
    .toBe('FAILED')
  sql('DROP TRIGGER e2e_fail_target ON fgc.validation_target; DROP FUNCTION fgc.e2e_fail_target();')
  // 사용자에게는 요청번호를 보이고 예외 원문은 그 요청번호로 서버 로그에서 찾는다.
  const progress = await api(page, `/validation-runs/${failedRun}/progress`)
  expect(progress.failureMessage).toBe(`2단계 처리 중 오류가 발생했습니다. 요청번호 ${progress.requestId}를 담당자에게 알려주세요.`)
  await expect.poll(() => readFileSync('e2e-artifacts/backend.log', 'utf8')).toContain(`requestId=${progress.requestId}`)
  expect(readFileSync('e2e-artifacts/backend.log', 'utf8')).toContain('e2e forced failure')

  await completeMonthly(page, month)
  // V64가 꺼 둔 계정이라 시험 뒤 되돌아가는 DB에서만 켠다.
  sql("UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'")

  const column = (query: string) => sql(query).split('\n').filter(Boolean)
  const state = (path: string) => ({ path, label: path })
  const states = [
    state(`/validation-runs/${failedRun}`),
    state(`/agent/statements?agentId=${sql("SELECT agent_id FROM fgc.agent WHERE agent_code='A-FC-001'")}`),
    ...column(
      `(SELECT commission_transaction_id FROM fgc.commission_transaction WHERE status='DRAFT' ORDER BY 1 LIMIT 1)
       UNION ALL (SELECT commission_transaction_id FROM fgc.commission_transaction WHERE status='CONFIRMED' ORDER BY 1 LIMIT 1)`,
    ).map((id) => state(`/transactions/new?id=${id}`)),
    ...column('SELECT DISTINCT exception_type FROM fgc.exception_case ORDER BY 1').map((type) => state(`/exceptions?type=${type}&status=`)),
    ...column('SELECT max(policy_version_id) FROM fgc.policy_version GROUP BY policy_type ORDER BY 1').map((id) => state(`/policies?policyVersionId=${id}`)),
    ...column('SELECT DISTINCT action_code FROM fgc.audit_log ORDER BY 1').map((action) => state(`/audit-logs?action=${action}`)),
  ]

  const missing: string[] = []
  try {
    for (const account of accounts) missing.push(...(await walkRole(browser, baseURL!, account, states, findings, visits)))
  } finally {
    for (const visit of visits.filter((item) => (item.largestApi?.bytes ?? 0) > 1024 * 1024))
      testInfo.annotations.push({ type: 'API 응답 1MB 초과', description: `${visit.route} ${visit.largestApi?.url} ${kb(visit.largestApi?.bytes ?? 0)}KB` })
    await testInfo.attach('화면 언어 일치 목록', { body: JSON.stringify(findings, null, 2), contentType: 'application/json' })
    await testInfo.attach('라우트별 전송량', { body: transferTable(visits), contentType: 'text/markdown' })
  }

  expect.soft(missing, '역할이 열 수 있는데 돌지 못한 라우트').toEqual([])
  expect.soft(states.map((item) => item.path), '어느 역할도 열지 못한 상태').toEqual([])
  expect
    .soft(visits.flatMap((visit) => visit.consoleErrors.map((text) => `${visit.role} | ${visit.route} | ${text}`)), '콘솔 오류')
    .toEqual([])
  expect(
    findings.map((found) => `${found.role} | ${found.route} | ${found.label} | ${found.match} | ${found.context}`),
    '화면에 보이는 개발 용어와 코드 (역할 | 라우트 | 종류 | 문자열 | 앞뒤 글)',
  ).toEqual([])
})
