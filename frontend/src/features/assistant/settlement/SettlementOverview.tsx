import { Button } from '../../../components/Button'
import { Modal } from '../../../components/Modal'
import mascot from '../assets/pico-guide.png'
import { STEPS } from './state'
import './settlement-overview.css'

// Reuse icons already bundled for the navigation and existing payment controls.
const overview = [
  { icon: 'description', title: '계약 생성', text: '계약일과 상품을 선택하고\n설계사·보험료를 입력해요.' },
  { icon: 'calendar_month', title: '예상 스케줄', text: '회차별 지급 대상과\n예상 수수료를 확인해요.' },
  { icon: 'payments', title: '지급 등록', text: '수수료와 계약별 귀속을\n입력하고 임시저장해요.' },
  { icon: 'fact_check', title: '사전검증', text: '귀속합계와 한도를 살펴보고\n수정할 항목을 확인해요.' },
  { icon: 'task_alt', title: '지급 확정', text: '검증을 통과한 지급 건의\n대상과 금액을 확인해 확정해요.' },
  { icon: 'fact_check', title: '월 통합검증', text: '해당 월의 계약과 지급을\n함께 검증해 결과를 확인해요.' },
  { icon: 'menu_book', title: '대사 확인', text: '예상·실제 지급을 비교하고\n차이가 나는 내역을 살펴봐요.' },
  { icon: 'inbox', title: '예외 처리', text: '문제의 원인을 확인하고\n필요한 정정과 재검증을 진행해요.' },
  { icon: 'verified', title: '최종 확정', text: '미해결 항목과 확정 조건을\n확인하고 월 검증을 마무리해요.' },
]

export function SettlementOverview({ open, busy, error, onClose, onStart }: {
  open: boolean; busy: boolean; error: string; onClose: () => void; onStart: () => void
}) {
  return <Modal open={open} title="전체 업무 가이드" className="settlement-overview" onClose={onClose}
    footer={<><span className="settlement-overview-footer-note">실제 화면에서 한 단계씩 따라가요.</span><Button variant="secondary" onClick={onClose}>닫기</Button><Button loading={busy} onClick={onStart}>계약 생성부터 시작<span className="material-symbols-rounded" aria-hidden="true">arrow_forward</span></Button></>}>
    <div className="settlement-overview-intro">
      <div><span className="settlement-overview-eyebrow">처음부터 끝까지, 하나의 흐름으로</span><h3>계약 한 건이 정산되기까지</h3><p>계약 생성부터 지급·검증·예외 처리, 최종 확정까지.<br />각 화면의 도움말을 따라 필요한 작업을 차례로 진행하세요.</p></div>
      <img src={mascot} width="100" height="100" alt="" />
    </div>
    <ol className="settlement-overview-steps" aria-label="전체 업무 9단계">
      {overview.map((step, index) => <li key={STEPS[index]}>
        <div className="settlement-overview-step-heading"><span className="material-symbols-rounded settlement-overview-icon" aria-hidden="true">{step.icon}</span><span className="settlement-overview-number">{String(index + 1).padStart(2, '0')}</span></div>
        <h4>{step.title}</h4><p>{step.text}</p>
      </li>)}
    </ol>
    <div className="settlement-overview-exceptions"><span className="material-symbols-rounded" aria-hidden="true">inbox</span><div><strong>중간에 문제가 생겨도, 처리 흐름을 함께 확인해요.</strong><p>예외 확인 → 원천 정정 → 재검증·재대사 → 해결 확인</p></div></div>
    <p className="settlement-overview-note">안내를 따라 입력하고, 저장과 확정은 화면에서 직접 진행합니다.</p>
    {error && <p role="alert" className="field-error">{error}</p>}
  </Modal>
}
