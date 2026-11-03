import type { Locator, Page } from '@playwright/test'
import { test, expect, login, api, completeMonthly, sql } from './fixtures'
import { selectEntity } from '../e2e/selectEntity'
import catalog from '../src/features/assistant/workflow-guides.v1.json' with { type: 'json' }

// SRC-028: real catalog + real HTTP/DOM. A passed partial scenario is not a completed tutorial.
// This suite never clicks business save/execute/confirm actions; only test setup may write.
const month = '2026-07'
const guides = catalog.filter((guide) => guide.enabled)
type Guide = (typeof guides)[number]
type Step = Guide['steps'][number]
const partial: Record<string, { count: number; reason: string }> = {}
const feature = (page: Page, id: string) => page.locator(`[data-guide-feature="${id}"]`)
const tutorial = (page: Page) => page.getByRole('region', { name: '업무 튜토리얼', exact: true })

async function seedContract(page: Page) {
  await page.goto('login')
  const result = await api(page, '/contracts?contractNo=FGC-FGL01-202607-0001&page=1&size=20')
  expect(result.content.length, 'A real seeded contract is required; missing data must fail, not skip.').toBeGreaterThan(0)
  return api(page, `/contracts/${result.content[0].contractId}`)
}

interface Preparation { cap?: { capCheckId: number; contractNo: string }; fixtureNotes?: string[] }
async function prepare(page: Page, guide: Guide, account: string): Promise<Preparation | undefined> {
  if (account === 'agent01') {
    // V64 intentionally disables this account. Enable only this isolated, reset-after-test DB fixture.
    sql("UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'")
  }
  if (guide.workflowId === 'contract.register' || guide.workflowId === 'contract.edit' || guide.workflowId === 'contract.detail') {
    const original = await seedContract(page)
    const route = guide.route.replace(':id', String(original.contractId))
    await login(page, account, `${route}?month=${month}`)
    if (guide.workflowId === 'contract.register') {
      await page.locator('input[name=contractDate]').fill(original.contractDate)
      await page.locator('select[name=insurerId]').selectOption(String(original.insurerId))
      await selectEntity(page, page.locator('input[name=productOfferingId]'), String(original.productOfferingId))
      await selectEntity(page, page.locator('input[name=agentId]'), String(original.agentId))
    }
    return
  }
  if (guide.workflowId === 'schedule.detail') {
    const original = await seedContract(page)
    // The baseline contract has no operational GA_TO_FC schedule. Create the required real fixture explicitly.
    const headers = await api(page, `/contracts/${original.contractId}/schedules/regenerate?reason=E2E-catalog-guide-fixture`, {})
    expect(headers.length, 'Schedule preparation must create a real header before UI verification.').toBeGreaterThan(0)
    await login(page, account, `/schedules?month=${month}&contractNo=FGC-FGL01-202607-0001&stage=GA_TO_FC`)
    await page.getByRole('table').locator('tbody a[href^="/schedules/"]').first().click()
    await expect(page).toHaveURL(/\/schedules\/\d+(?:\?|$)/)
    return
  }
  if (guide.workflowId === 'clawback.review') {
    await page.goto('login')
    const contractNo = 'FGC-FGN01-202603-0001'
    const query = new URLSearchParams({ dateBasis: 'EVENT_DATE', dateFrom: `${month}-01`, dateTo: `${month}-31`, contractNo, page: '1', size: '20' })
    const rows = await api(page, `/clawbacks/candidates?${query}`)
    const candidate = rows.content.find((entry: { contractNo: string }) => entry.contractNo === contractNo)
    expect(candidate, 'Clawback tutorial requires the persisted July candidate with real source/payment evidence.').toBeTruthy()
    const detail = await api(page, `/clawbacks/candidates/${candidate.candidateId}`)
    expect(detail.sourceRefs.length).toBeGreaterThan(0)
    expect(detail.paymentAttributions.length).toBeGreaterThan(0)
    await login(page, account, `/clawbacks?month=${month}&${query}`)
    await page.getByRole('row', { name: `후보 ${candidate.candidateId} 상세 보기`, exact: true }).click()
    await expect(feature(page, 'clawback.evidence.source')).toBeVisible()
    return { fixtureNotes: [`UI targets only: ${detail.scheduleLines.length} schedule rows; no clawback calculation or confirmation is executed.`] }
  }
  if (guide.workflowId === 'cap.review') {
    await page.goto('login')
    await completeMonthly(page, month)
    const rows = await api(page, `/cap-checks?month=${month}&stage=INSURER_TO_GA&page=1&size=50`)
    const cap = rows.content.find((entry: { resultStatus: string; capCheckId: number; contractNo: string }) => entry.resultStatus !== 'NOT_APPLICABLE' && entry.capCheckId != null && entry.contractNo)
    expect(cap, 'Full cap tutorial requires a real calculation result, not only the NOT_APPLICABLE branch.').toBeTruthy()
    await login(page, account, `/cap-checks?month=${month}&stage=INSURER_TO_GA&contractNo=${encodeURIComponent(cap.contractNo)}`)
    return { cap: { capCheckId: cap.capCheckId, contractNo: cap.contractNo } }
  }
  if (guide.workflowId === 'validation.detail') {
    await page.goto('login')
    const id = await completeMonthly(page, month)
    await login(page, account, `/validation-runs/${id}?month=${month}`)
    return
  }
  if (guide.workflowId === 'policy.lifecycle') {
    await page.goto('login')
    const policies = await api(page, `/policies?asOf=${month}-01`)
    const policy = policies.find((entry: { policyType: string; status: string }) => entry.policyType === 'CURRENT_COMMISSION' && entry.status === 'ACTIVE')
    expect(policy, 'The positive lifecycle scenario requires a supported active commission policy.').toBeTruthy()
    await login(page, account, `/policies?month=${month}&asOf=${month}-01&policyVersionId=${policy.policyVersionId}`)
    return
  }
  await login(page, account, `${account === 'agent01' ? '/agent-home' : guide.route}?month=${month}`)
}

async function start(page: Page, guide: Guide, role: string) {
  if (role === 'AGENT') {
    await feature(page, 'agent.guide-menu.open').click()
    await page.getByRole('navigation', { name: '본인 업무 튜토리얼' }).getByRole('button', { name: guide.title, exact: true }).click()
    if (guide.workflowId === 'agent.contract.detail') {
      // The agent menu lives on AgentHome. The real SELECT_TARGET flow must select an owned row.
      await expect(tutorial(page).getByText('목록에서 안내받을 대상을 직접 선택한 뒤 현재 대상에서 다시 시작하세요.', { exact: true })).toBeVisible()
      await page.getByRole('table').locator('tbody a[href^="/contracts/"]').first().click()
      await expect(page).toHaveURL(/\/contracts\/\d+(?:\?|$)/)
      await tutorial(page).getByRole('button', { name: '현재 대상에서 다시 시작', exact: true }).click()
    }
  } else {
    await page.getByRole('button', { name: '업무 비서 AI 열기 · 피코', exact: true }).click()
    const pico = page.getByRole('dialog', { name: '피코 업무 비서' })
    await pico.getByRole('tab', { name: '업무 안내', exact: true }).click()
    // The menu also contains a description. Scope by its exact title element, not a fuzzy prefix.
    await pico.getByRole('button').filter({ has: page.getByText(guide.title, { exact: true }) }).click()
  }
}

async function activateTutorialControl(page: Page, name: string) {
  const button = tutorial(page).getByRole('button', { name, exact: true })
  const dialog = page.locator('[role="dialog"][aria-modal="true"]').last()
  if (await dialog.count()) {
    await expect(dialog.getByRole('region', { name: '업무 튜토리얼', exact: true })).toBeVisible()
    await button.focus()
    await expect(button).toBeFocused()
    await button.press('Enter')
  } else await button.click()
}

async function expectHighlight(page: Page, step: Step, target: Locator) {
  const card = tutorial(page)
  await expect(card.getByRole('heading', { name: step.title, exact: true })).toBeVisible()
  await expect(card.getByRole('button', { name: '확인했어요 · 다음 안내', exact: true })).toBeEnabled()
  await expect(card.getByRole('button', { name: '현재 대상에서 다시 시작', exact: true })).toHaveCount(0)
  await expect(target).toBeVisible()
  await expect(target).toBeInViewport()
  const spotlight = page.locator('.pico-guide-spotlight')
  await expect(spotlight).toBeVisible()
  // Also prove the overlay follows this target; merely finding a visible field would be a false pass.
  await expect.poll(async () => {
    const actual = await spotlight.boundingBox()
    const expected = await target.evaluate((element) => {
      const box = element.getBoundingClientRect()
      // The approved coach design uses an 8px halo clipped to the workspace below its header.
      const workspace = document.getElementById('main-content')?.getBoundingClientRect()
      const leftBoundary = Math.max(0, workspace?.left ?? 0)
      const rightBoundary = Math.min(document.documentElement.clientWidth, workspace?.right || document.documentElement.clientWidth)
      const headerBottom = Math.max(0, document.querySelector('.app-header')?.getBoundingClientRect().bottom ?? 0)
      const left = Math.max(leftBoundary, box.left - 8), top = Math.max(headerBottom + 8, box.top - 8)
      return { x: left, y: top, width: Math.max(0, Math.min(rightBoundary, box.right + 8) - left), height: Math.max(0, Math.min(window.innerHeight, box.bottom + 8) - top) }
    })
    return actual ? Math.max(...(['x', 'y', 'width', 'height'] as const).map((key) => Math.abs(actual[key] - expected[key]))) : Infinity
  }, { message: `${step.stepId} must highlight ${step.targetId}` }).toBeLessThan(3)
}

for (const guide of guides) {
  const role = guide.roles.includes('AGENT') ? 'AGENT' : guide.roles.includes('COMPLIANCE') ? 'COMPLIANCE' : 'SETTLEMENT'
  const account = role === 'AGENT' ? 'agent01' : role === 'COMPLIANCE' ? 'audit01' : 'settle01'
  const allowed = guide.steps.filter((step) => step.roles.includes(role))
  const limit = partial[guide.workflowId]?.count ?? allowed.length
  const planned = allowed.slice(0, limit)
  test(`튜토리얼 ${guide.workflowId}: ${role} ${planned.length}/${allowed.length}단계 ${partial[guide.workflowId] ? '부분 검증' : '완주 검증'}`, async ({ page }, info) => {
    const errors: string[] = [], writes: string[] = [], checked: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    const prepared = await prepare(page, guide, account)
    page.on('request', (request) => {
      const pathname = new URL(request.url()).pathname
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method()) && pathname.startsWith('/api/v1/') && !pathname.startsWith('/api/v1/auth/')) writes.push(`${request.method()} ${pathname}`)
    })
    try {
      await start(page, guide, role)
      for (const [index, step] of planned.entries()) {
        let target = feature(page, step.targetId)
        if (guide.workflowId === 'policy.review' && step.targetId.endsWith('.policy-version')) {
          await expect(tutorial(page).getByRole('heading', { name: step.title, exact: true })).toBeVisible()
          await expect(target.first()).toBeVisible()
          if (await target.count() > 1) {
            await expect(tutorial(page).getByText('같은 기능이 여러 행에 있습니다. 안내받을 행의 항목을 직접 선택한 뒤 이어가세요.', { exact: true })).toBeVisible()
            await target.first().click() // A preselected radio still needs a click to select the runtime's duplicate target.
            await tutorial(page).getByRole('button', { name: '돌아와 이어가기', exact: true }).click()
            target = target.first()
          }
        }
        await expectHighlight(page, step, target)
        if (step.completionRuleId === 'INPUT_VALID') {
          if (await target.getAttribute('role') === 'tab') {
            await target.click()
            await expect(target).toHaveAttribute('aria-selected', 'true')
          } else if (await target.getAttribute('type') === 'radio') {
            await target.check()
          } else {
            // Existing form data / explicit preparation, never bypassing native validity.
            await expect(target).toHaveValue(/\S/)
            await expect(target).toBeEnabled()
          }
        }
        if (guide.workflowId === 'policy.lifecycle' && step.stepId === 'policy.lifecycle.step.3') {
          await target.getByRole('button', { name: '새 버전 작성', exact: true }).click()
          const dialog = page.getByRole('dialog', { name: '정책 새 버전 작성', exact: true })
          await expect(dialog).toBeVisible()
          await expect(dialog.getByRole('textbox', { name: '작업 사유', exact: true })).toBeEnabled()
        }
        if (guide.workflowId === 'cap.review' && step.stepId === 'cap.review.step.2') {
          expect(prepared?.cap).toBeTruthy()
          const cap = prepared!.cap!
          const response = page.waitForResponse((entry) => new URL(entry.url()).pathname === `/api/v1/cap-checks/${cap.capCheckId}/details` && entry.request().method() === 'GET')
          await target.getByRole('button', { name: `${cap.contractNo} 계산근거 열기`, exact: true }).click()
          const loaded = await response
          expect(loaded.ok(), await loaded.text()).toBe(true)
          const basis = (await loaded.json()).data
          expect(basis.capCheck.capCheckId).toBe(cap.capCheckId)
          expect(basis.capCheck.resultStatus).not.toBe('NOT_APPLICABLE')
          await expect(page.getByRole('dialog', { name: '계산근거', exact: true })).toBeVisible()
        }
        checked.push(step.stepId)
        if (index + 1 < planned.length || limit === allowed.length) {
          await activateTutorialControl(page, '확인했어요 · 다음 안내')
        }
      }
      if (limit === allowed.length) {
        await expect(tutorial(page).getByRole('heading', { name: '안내를 마쳤어요', exact: true })).toBeVisible()
        await activateTutorialControl(page, '안내 닫기')
      } else {
        await tutorial(page).getByRole('button', { name: '업무 튜토리얼 종료', exact: true }).click()
      }
      if (guide.workflowId === 'policy.lifecycle') {
        await page.getByRole('dialog', { name: '정책 새 버전 작성', exact: true }).getByRole('button', { name: '취소', exact: true }).click()
      }
      if (guide.workflowId === 'cap.review') {
        await page.getByRole('dialog', { name: '계산근거', exact: true }).getByRole('button', { name: '닫기', exact: true }).click()
      }
      expect(writes, 'Guide acknowledgements and lookup navigation must not mutate business data.').toEqual([])
      expect(errors).toEqual([])
    } finally {
      await info.attach('workflow-coverage', {
        body: JSON.stringify({ workflowId: guide.workflowId, role, checkedSteps: checked, checkedMeaning: 'target and spotlight observed; full completion separately asserted by the test', unverifiedAllowedSteps: allowed.filter((step) => !checked.includes(step.stepId)).map((step) => step.stepId), excludedByRole: guide.steps.filter((step) => !step.roles.includes(role)).map((step) => step.stepId), limitation: partial[guide.workflowId]?.reason ?? null, fixtureNotes: prepared?.fixtureNotes ?? [], writes, pageErrors: errors }, null, 2),
        contentType: 'application/json',
      })
    }
  })
}


test('정책 튜토리얼: 실행할 상태 동작이 없는 정책에서는 사유와 함께 즉시 멈춘다', async ({ page }) => {
  await page.goto('login')
  const policies = await api(page, `/policies?asOf=${month}-01`)
  const policy = policies.find((entry: { policyCode: string }) => entry.policyCode === 'ARB-APPLICABILITY-2026')
  expect(policy, 'The negative scenario requires the real unsupported baseline policy.').toBeTruthy()
  await login(page, 'settle01', `/policies?month=${month}&asOf=${month}-01&policyVersionId=${policy.policyVersionId}`)
  const guide = guides.find((entry) => entry.workflowId === 'policy.lifecycle')!
  await start(page, guide, 'SETTLEMENT')
  const tab = feature(page, guide.steps[0].targetId)
  await expectHighlight(page, guide.steps[0], tab)
  await tab.click()
  await expect(tab).toHaveAttribute('aria-selected', 'true')
  await activateTutorialControl(page, '확인했어요 · 다음 안내')
  await expectHighlight(page, guide.steps[1], feature(page, guide.steps[1].targetId))
  await expect(feature(page, 'features.policies.policy-management.actions').locator('button')).toHaveCount(0)
  await activateTutorialControl(page, '확인했어요 · 다음 안내')
  await expect(tutorial(page).getByText('현재 선택한 정책과 계정에 실행 가능한 상태 동작이 없습니다. 정책의 상태와 권한을 확인하고 다른 정책을 선택한 뒤 다시 시작하세요.', { exact: true })).toBeVisible({ timeout: 2_000 })
  await expect(tutorial(page).getByRole('button', { name: '확인했어요 · 다음 안내', exact: true })).toHaveCount(0)
  await expect(page.locator('.pico-guide-spotlight')).toHaveCount(0)
  await expect(feature(page, 'features.policies.policy-management.textarea')).toHaveCount(0)
})
