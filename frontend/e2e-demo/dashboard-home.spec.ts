import { expect, login, sql, test } from './fixtures'

/** 시드에 미처리 예외가 없을 수 있어 건수를 직접 넣는다. fixtures 가 시험마다 DB 를 되돌린다. */
function insertOpenExceptions(count: number) {
  sql(`INSERT INTO fgc.exception_case(exception_key, exception_type, severity, status, source_entity_type, source_entity_id, title, contract_id)
       SELECT 'E2E-HOME:' || n, 'DATA_QUALITY', 'WARNING', 'NEW', 'E2E', n::text, '홈 시험 예외 ' || n,
              (SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1)
         FROM generate_series(1, ${count}) n`)
}

test('FUN-080 home: 미처리 숫자를 누르면 같은 건수의 예외함이 열린다', async ({ page }) => {
  insertOpenExceptions(3)
  const expected = Number(sql("SELECT count(*) FROM fgc.exception_case WHERE status IN ('NEW','IN_REVIEW')"))
  expect(expected).toBeGreaterThanOrEqual(3)

  await login(page, 'settle01', '/?month=2026-07')
  const kpi = page.getByRole('region', { name: '주요 검증 지표' }).getByRole('link', { name: /^미처리 예외 / })
  await expect(kpi).toHaveAccessibleName(`미처리 예외 ${expected.toLocaleString('ko-KR')}건`)
  await kpi.click()

  await expect(page).toHaveURL(/\/exceptions\?.*status=OPEN/)
  await expect(page.locator('.exception-page .list-header-total').first()).toHaveText(`총 ${expected.toLocaleString('ko-KR')}건`)
})

test('FUN-080 home: 월별 수수료와 여섯 달 막대는 DB의 확정 계약 귀속 금액과 같다', async ({ page }) => {
  const amounts: { month: string; ga: number; agent: number }[] = JSON.parse(sql(`
    SELECT json_agg(row_to_json(t)) FROM (
      SELECT to_char(m.month, 'YYYY-MM') AS month,
             coalesce(sum(ta.attributed_amount) FILTER (WHERE ct.payment_stage = 'INSURER_TO_GA'), 0) AS ga,
             coalesce(sum(ta.attributed_amount) FILTER (WHERE ct.payment_stage = 'GA_TO_FC'), 0) AS agent
        FROM generate_series(DATE '2026-02-01', DATE '2026-07-01', INTERVAL '1 month') m(month)
        LEFT JOIN fgc.commission_transaction ct ON ct.settlement_month = m.month
          AND ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'
        LEFT JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = ct.commission_transaction_id
          AND ta.attribution_scope = 'CONTRACT'
       GROUP BY m.month ORDER BY m.month
    ) t`))
  expect(amounts).toHaveLength(6)
  const current = amounts[5]
  expect(current.ga).toBeGreaterThan(0)
  expect(current.agent).toBeGreaterThan(0)
  const money = (value: number) => `${value.toLocaleString('ko-KR')}원`

  await login(page, 'settle01', '/?month=2026-07')
  const region = page.getByRole('region', { name: '월별 수수료' })
  await expect(region.locator('.commission-total').filter({ hasText: 'GA 수령액' }).locator('strong')).toHaveText(money(current.ga))
  await expect(region.locator('.commission-total').filter({ hasText: '설계사 지급액' }).locator('strong')).toHaveText(money(current.agent))
  for (const row of amounts) {
    const [year, month] = row.month.split('-')
    await expect(region.getByRole('button', {
      name: `${year}년 ${Number(month)}월, GA 수령액 ${money(row.ga)}, 설계사 지급액 ${money(row.agent)}`,
      exact: true,
    })).toBeVisible()
  }
})

test('FUN-080 home: 지점 인원과 상세는 하위 팀을 포함하며 계약 상세로 이동한다', async ({ page }) => {
  const agents: { agent_code: string; agent_name: string }[] = JSON.parse(sql(`
    WITH RECURSIVE branch AS (
      SELECT organization_id FROM fgc.organization WHERE organization_code = 'FGC-B0101'
      UNION ALL
      SELECT o.organization_id FROM fgc.organization o JOIN branch b ON o.parent_id = b.organization_id
    )
    SELECT json_agg(row_to_json(t)) FROM (
      SELECT a.agent_code, a.agent_name FROM fgc.agent a JOIN branch b USING (organization_id)
       WHERE a.appointment_date <= DATE '2026-07-31'
         AND (a.termination_date IS NULL OR a.termination_date >= DATE '2026-07-31')
       ORDER BY a.agent_id DESC
    ) t`))
  expect(agents.length).toBeGreaterThan(0)
  expect(agents.map(agent => agent.agent_code)).toContain('A-FC-001')

  await login(page, 'settle01', '/?month=2026-07')
  const region = page.getByRole('region', { name: '지점별 현황' })
  await region.getByRole('button', { name: '설계사', exact: true }).click()
  await expect(region.getByText('2026-07-31', { exact: true })).toBeVisible()
  const branch = region.getByRole('button', { name: '강동지사 설계사 상세 보기' })
  await expect(branch.locator('strong')).toHaveText(`${agents.length}명`)
  await branch.click()
  const dialog = page.getByRole('dialog', { name: '강동지사 · 설계사 상세' })
  const rows = dialog.getByRole('table', { name: '강동지사 설계사 상세 내역' }).locator('tbody tr')
  await expect(rows).toHaveCount(Math.min(agents.length, 10))
  for (const [index, agent] of agents.slice(0, 10).entries()) {
    await expect(rows.nth(index)).toContainText(agent.agent_code)
    await expect(rows.nth(index)).toContainText(agent.agent_name)
  }
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)

  await region.getByRole('button', { name: '계약', exact: true }).click()
  await region.getByRole('button', { name: '강동지사 계약 상세 보기' }).click()
  const contractDialog = page.getByRole('dialog', { name: '강동지사 · 계약 상세' })
  const contractLink = contractDialog.getByRole('link').first()
  await expect(contractLink).toHaveAttribute('href', /^\/contracts\/\d+\?month=2026-07$/)
  const contractNo = await contractLink.innerText()
  const href = await contractLink.getAttribute('href')
  await contractLink.click()
  await expect(page).toHaveURL(new URL(href!, page.url()).href)
  await expect(page.getByRole('heading', { name: '보험계약 상세', exact: true })).toBeVisible()
  await expect(page.locator('.page-content')).toContainText(contractNo)
})
