import type { Precheck } from './form-api'
import { gateStates } from './form-model'

/** Local input progress never substitutes for a saved payment or a server cap check. */
export function ContractPaymentGates({
  rows,
  inputsReady,
  amountsReady,
  savedRows,
  checks,
  confirmedRows,
  uncertain,
}: {
  rows: number
  inputsReady: boolean
  amountsReady: boolean
  savedRows: number
  checks: (Precheck | undefined)[]
  confirmedRows: number
  uncertain: boolean
}) {
  const present = rows > 0
  const results = checks.map((result) => gateStates(result ?? null))
  const serverState = (index: number) =>
    uncertain
      ? 'todo'
      : results.some((result) => result[index].state === 'fail')
        ? 'fail'
        : present && results.every((result) => result[index].state === 'pass')
          ? 'pass'
          : 'todo'
  const local = (index: number, ready: boolean) =>
    serverState(index) === 'fail' ? 'fail' : ready && !uncertain ? 'pass' : 'todo'
  const complete = present && confirmedRows === rows && !uncertain
  const ready = present && savedRows === rows && checks.every((result) => result?.confirmable) && !uncertain
  const steps = [
    {
      label: '작성중으로 저장',
      state: local(0, present && savedRows === rows),
      text: `${savedRows}/${rows}행 저장`,
      detail: '임시저장하면 체크됩니다.',
    },
    {
      label: '귀속행 입력',
      state: local(1, inputsReady),
      text: inputsReady ? '입력 완료' : '입력 대기',
      detail: '설계사·수수료·날짜·금액을 확인합니다.',
    },
    {
      label: '귀속합계와 지급액 일치',
      state: local(2, amountsReady),
      text: amountsReady ? '일치' : '입력 대기',
      detail: '입력한 귀속금액이 각 지급 건의 금액으로 연결됩니다.',
    },
    {
      label: '검토필요 귀속 해소',
      state: serverState(3),
      text: '사전검증 대기',
      detail: '사전검증 결과로 확인합니다.',
    },
    {
      label: '1,200% 사전검증',
      state: serverState(4),
      text: '사전검증 대기',
      detail: '모든 귀속행의 한도 결과를 확인합니다.',
    },
    {
      label: '지급 확정',
      state: complete ? 'pass' : serverState(5) === 'fail' ? 'fail' : 'todo',
      text: complete ? '확정 완료' : ready ? '확정 대기' : '검증 대기',
      detail: `${confirmedRows}/${rows}행 확정 완료`,
    },
  ]
  return (
    <section
      className="surface transaction-gate-panel"
      data-guide-target="payment.precheck.result"
      aria-labelledby="contract-payment-gates-title"
    >
      <header className="surface-header transaction-surface-header">
        <h2 className="surface-title" id="contract-payment-gates-title">
          ③ 확정 전 6단계 점검
        </h2>
        <span className="transaction-panel-meta">입력 상태 자동 반영</span>
      </header>
      <div className="surface-body transaction-gate-list" aria-live="polite">
        {steps.map((step, index) => (
          <div key={step.label} className={`transaction-gate-step transaction-gate-${step.state}`}>
            <span className="material-symbols-rounded transaction-gate-icon" aria-hidden="true">
              {step.state === 'pass' ? 'check' : step.state === 'fail' ? 'close' : 'more_horiz'}
            </span>
            <div>
              <div className="transaction-gate-label">
                {index + 1}. {step.label} ·{' '}
                {step.state === 'fail'
                  ? '확인 필요'
                  : index === 3 || index === 4
                    ? step.state === 'pass'
                      ? '통과'
                      : step.text
                    : step.text}
              </div>
              <div className="transaction-gate-detail">
                {step.state === 'fail' ? '귀속행을 눌러 검증 결과를 확인하세요.' : step.detail}
              </div>
            </div>
          </div>
        ))}
        {uncertain && (
          <p role="status" className="transaction-gate-detail">
            처리 결과를 다시 확인해 주세요.
          </p>
        )}
      </div>
    </section>
  )
}
