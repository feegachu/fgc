import { test, expect, login, api, responseFor, successful, sql } from './fixtures'

// 화면의 IF-API-19/16 표시와 호환 IF-API-89 근거 보존을 확인한다. 이 서버는 후속 worker를 꺼 두므로 처리 결과만 SQL로 남기고,
// worker 동작 자체는 ContractStatusHistoryIntegrationTest가 확인한다.
test('CONT-W03 상태 변경 뒤 이력과 후속 상태만 표시하고 영향 근거 API와 AGENT 범위를 보존한다', async ({
  page,
}, testInfo) => {
  test.setTimeout(240_000)
  const contract = sql(`SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'`)
  await login(page, 'settle01', `/contracts/${contract}/edit`)
  await page.getByLabel('변경 상태').selectOption('UNPAID')
  await page.getByLabel('효력일시').fill('2026-08-01T00:00')
  await page.getByLabel('사유 구분').selectOption('MANUAL_STATUS_CHANGE')
  await page.getByLabel(/^사유\s*\*?$/).fill('보험회사 미납 통보 반영')
  await page.getByRole('button', { name: '수정 저장' }).click()
  const saved = responseFor(page, `/contracts/${contract}`, 'PUT')
  await page.getByRole('dialog', { name: '상태 변경 저장 확인' }).getByRole('button', { name: '확인하고 저장' }).click()
  const response = await successful(saved)
  expect(response.followUp).toEqual({ status: 'PENDING', eventId: response.statusEventId })
  await expect(page.getByRole('heading', { name: '보험계약 상세' })).toBeVisible()

  const table = page.locator('.contract-history-timeline')
  const first = table.locator('.contract-history-event').nth(0)
  const second = table.locator('.contract-history-event').nth(1)
  // 시드 사건은 후속 작업이 없고 처리자가 원천에 없다. 수기 사건은 대기, 실제 처리자, 자유 사유를 보인다.
  await expect(first).toContainText('대상 없음')
  await expect(first).toContainText('신규 계약')
  await expect(second).toContainText('미납')
  await expect(second).toContainText('처리 대기')
  await expect(second).toContainText('보험회사 미납 통보 반영')
  await page.screenshot({ path: testInfo.outputPath('real-status-history-pending.png'), fullPage: true })

  await expect(table.getByRole('button', { name: /영향 보기|상세 보기/ })).toHaveCount(0)
  const event = Number(response.statusEventId)
  const effectsPath = `/contracts/${contract}/status-events/${event}/effects?page=1&size=20`
  expect((await api(page, effectsPath)).totalElements).toBe(0)

  // The worker is disabled in this fixture; persist its result and observe the normal history poll.
  sql(`UPDATE fgc.contract_event_job SET state = 'SUCCEEDED', attempt_count = 2, last_attempt_at = clock_timestamp()
        WHERE contract_status_event_id = ${event};
       INSERT INTO fgc.contract_status_event_processing(contract_status_event_id, processing_job, processing_status, failure_reason)
       VALUES (${event}, 'PaymentHoldJob', 'FAILED', 'IllegalStateException: 내부 메시지'),
              (${event}, 'PaymentHoldJob', 'SUCCEEDED', NULL);
       INSERT INTO fgc.contract_status_event_effect(contract_status_event_id, processing_job, entity_type, entity_id,
              previous_state, next_state, amount, policy_version_id, reason_code, reason)
       VALUES (${event}, 'PaymentHoldJob', 'PAYMENT', 9001, 'DRAFT', 'HELD', 5000, NULL, 'STATUS_UNPAID', '귀속일 시점 미납이라 보류합니다.'),
              (${event}, 'PaymentHoldJob', 'PAYMENT', 9002, 'CONFIRMED', 'EXCLUDED', 0, NULL, 'ALREADY_CONFIRMED', '이미 확정된 지급 건이라 보류하지 않습니다(이력 보존).'),
              (${event}, 'PaymentHoldJob', 'SCHEDULE_LINE', 9003, 'PLANNED', 'HOLD', NULL, NULL, 'STATUS_UNPAID', '미납 회차를 보류합니다.'),
              (${event}, 'PaymentHoldJob', 'SCHEDULE_LINE', 9004, 'PLANNED', 'CANCELLED', 1000, NULL, 'STATUS_INACTIVE', '지급 예정을 중단합니다.');`)
  await expect(second).toContainText('재시도 후 성공, 2/5회 시도', { timeout: 20_000 })
  await expect(table).not.toContainText('IllegalStateException')
  await expect(table).not.toContainText('내부 메시지')
  const effects = await api(page, effectsPath)
  expect(effects.totalElements).toBe(4)
  expect(effects.content).toEqual(expect.arrayContaining([
    expect.objectContaining({ entityId: 9001, nextState: 'HELD', amount: 5000, reasonCode: 'STATUS_UNPAID' }),
    expect.objectContaining({ entityId: 9002, nextState: 'EXCLUDED', amount: 0, reasonCode: 'ALREADY_CONFIRMED' }),
    expect.objectContaining({ entityId: 9003, nextState: 'HOLD', amount: null }),
    expect.objectContaining({ entityId: 9004, nextState: 'CANCELLED', amount: 1000 }),
  ]))
  await expect(page.getByRole('dialog', { name: /변경 .* 영향/ })).toHaveCount(0)
  await page.locator('.contract-history-panel').screenshot({ path: testInfo.outputPath('real-status-history-done.png') })

  // 시드에서 이 계약은 agent01의 본인 계약이다. V64가 꺼 둔 계정이라 시험 뒤 되돌아가는 DB에서만 켠다.
  sql(
    "UPDATE fgc.app_user SET account_status='ACTIVE', password_hash=(SELECT password_hash FROM fgc.app_user WHERE login_id='settle01') WHERE login_id='agent01'",
  )
  const other = sql(
    `SELECT min(c.contract_id) FROM fgc.insurance_contract c JOIN fgc.agent a USING (agent_id) WHERE a.agent_code <> 'A-FC-001'`,
  )
  // FUN-085: 같은 사건의 영향이어도 AGENT에게는 본인 지급 건과 본인 회차만 보인다. 위 9001~9004는 본인 것이 아니다.
  const ownPayment = sql(
    `SELECT min(ct.commission_transaction_id) FROM fgc.commission_transaction ct
       JOIN fgc.agent a ON a.agent_id = ct.recipient_agent_id WHERE a.agent_code = 'A-FC-001'`,
  )
  sql(`INSERT INTO fgc.contract_status_event_effect(contract_status_event_id, processing_job, entity_type, entity_id,
              previous_state, next_state, amount, policy_version_id, reason_code, reason)
       VALUES (${event}, 'PaymentHoldJob', 'PAYMENT', ${ownPayment}, 'DRAFT', 'HELD', 5000, NULL, 'STATUS_UNPAID', '귀속일 시점 미납이라 보류합니다.')`)
  const ownEffects = await api(page, effectsPath, undefined, 'agent01')
  expect(ownEffects.totalElements).toBe(1)
  expect(ownEffects.content).toEqual([expect.objectContaining({ entityId: Number(ownPayment), nextState: 'HELD' })])
  await login(page, 'agent01', `/contracts/${contract}`)
  await expect(page.locator('.contract-history-event').nth(1)).toContainText('미납')
  await expect(page.getByRole('button', { name: /영향 보기|상세 보기/ })).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: /변경 .* 영향/ })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('real-status-history-agent.png') })
  await page.goto(`contracts/${other}`)
  await expect(page.getByRole('alert')).toContainText('본인 데이터만')
  await expect(page.locator('.contract-history-timeline')).toHaveCount(0)
})
