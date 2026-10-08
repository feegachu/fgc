import { Button } from '../../components/Button'
import { Modal } from '../../components/Modal'

/**
 * 확정 확인·완료 모달. 브라우저 기본 확인 대화상자는 화면정의서 :1487 이 요구하는
 * "검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다" 고정 문구를 담을 수 없어 모달을 쓴다.
 * 완료 모달은 닫을 때까지 남겨 성공 피드백이 보이게 하고, 닫으면 최신 상태를 다시 불러온다.
 */
export function FinalizeDialogs({
  confirming,
  finalized,
  pending,
  validationMonth,
  runNo,
  onCancel,
  onConfirm,
  onClosed,
}: {
  confirming: boolean
  finalized: boolean
  pending: boolean
  validationMonth: string
  runNo?: number
  onCancel: () => void
  onConfirm: () => void
  onClosed: () => void
}) {
  return (
    <>
      <Modal
        open={confirming}
        title="이 검증 실행을 확정할까요?"
        onClose={() => !pending && onCancel()}
        closeOnBackdrop
        className="vrun-modal"
        footer={
          <>
            <Button variant="secondary" disabled={pending} onClick={onCancel}>
              취소
            </Button>
            <Button loading={pending} onClick={onConfirm}>
              확정
            </Button>
          </>
        }
      >
        <p className="vrun-modal-description">
          확정하면 이 실행의 검증 결과와 그때 쓰인 룰셋·계산근거가 잠깁니다. <strong>되돌릴 수 없습니다.</strong>
        </p>
        <div className="vrun-modal-points">
          <p>
            <strong>검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.</strong>
          </p>
          <p>
            수정 불가 — 확정된 검증 결과입니다. 결과를 바꾸려면 <strong>새 실행</strong>을 만드세요.
          </p>
          <p>확정 사실은 감사로그에 실행자·시각과 함께 남습니다.</p>
        </div>
        <dl className="vrun-kv">
          <div>
            <dt>검증월</dt>
            <dd className="tabular-nums">{validationMonth}</dd>
          </div>
          <div>
            <dt>회차</dt>
            <dd className="tabular-nums">{runNo}</dd>
          </div>
        </dl>
      </Modal>
      <Modal
        open={finalized}
        title="확정했습니다"
        onClose={onClosed}
        className="vrun-modal"
        footer={<Button onClick={onClosed}>확인</Button>}
      >
        <p className="vrun-modal-description">
          이 실행의 검증 결과와 계산 근거가 잠겼습니다. 확정 사실은 감사로그에 남았습니다.
        </p>
        <div className="vrun-modal-points">
          <p>
            <strong>검증 결과 잠금이며 실제 송금·회계 마감이 아닙니다.</strong>
          </p>
        </div>
      </Modal>
    </>
  )
}
