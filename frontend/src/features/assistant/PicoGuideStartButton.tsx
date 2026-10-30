import { useEffect, useId, useRef, useState } from 'react'
import { findGuide } from './catalog'
import { useGuideRuntime } from './guide-runtime'
import { startPicoGuide } from './pico-guide-api'
import { isEntityId } from './guards'

export function PicoGuideStartButton({
  workflowId,
  contextRevision,
  canProcess,
  contextValid = true,
}: {
  workflowId: string
  contextRevision: string
  canProcess: boolean
  contextValid?: boolean
}) {
  const observation = useGuideRuntime((state) => state.observation)
  const controller = useRef<AbortController | null>(null)
  const lock = useRef(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const statusId = useId()
  useEffect(
    () => () => {
      controller.current?.abort()
      controller.current = null
      lock.current = false
      setBusy(false)
    },
    [observation, contextRevision, workflowId, canProcess, contextValid],
  )
  const definition = findGuide(workflowId, '1.0')
  if (!definition) return null
  const unavailable =
    !canProcess && definition.capability === 'PROCESS'
      ? '현재 계정은 조회 안내만 사용할 수 있습니다. 지급 처리 안내는 정산 담당자에게 요청하세요.'
      : !contextValid
        ? '질문 또는 지급 대상·입력이 변경됐습니다. 현재 화면에서 다시 질문해주세요.'
        : !observation || !isEntityId(observation.paymentId)
          ? '저장된 지급 초안을 선택해야 화면 안내를 시작할 수 있습니다.'
          : observation.busy
            ? '지급 요청을 처리 중입니다. 결과를 확인한 뒤 안내를 시작하세요.'
            : !observation.ready
              ? '지급 화면의 기준정보를 확인 중이거나 불러오지 못했습니다. 화면의 조회 상태를 먼저 확인하세요.'
              : !observation.saved
                ? '현재 입력이 저장본과 다릅니다. 기존 수정 저장 버튼으로 저장한 뒤 다시 질문해주세요.'
                : ''
  const message = unavailable || notice
  async function start() {
    if (lock.current || unavailable || !observation?.paymentId || !definition) return
    const captured = observation
    const abort = new AbortController()
    controller.current = abort
    lock.current = true
    setBusy(true)
    setNotice('')
    try {
      await startPicoGuide(
        { workflowId, guideVersion: '1.0', paymentId: captured.paymentId!, clientContextRevision: contextRevision },
        abort.signal,
      )
      if (abort.signal.aborted || useGuideRuntime.getState().observation !== captured) return
      if (!useGuideRuntime.getState().start(definition.workflowId)) throw new Error('GUIDE_NOT_READY')
    } catch {
      if (!abort.signal.aborted)
        setNotice('지급 상태나 시작 허가를 다시 확인해야 합니다. 현재 화면에서 안내 시작을 다시 선택해주세요.')
    } finally {
      if (controller.current === abort) {
        lock.current = false
        setBusy(false)
      }
    }
  }
  return (
    <div>
      <button
        type="button"
        className="pico-primary"
        disabled={busy || Boolean(unavailable)}
        aria-describedby={message ? statusId : undefined}
        onClick={() => void start()}
      >
        {busy ? '현재 지급 확인 중…' : `이 업무 안내 시작 · ${definition.title}`}
      </button>
      {message && (
        <p className="pico-notice" role="status" id={statusId}>
          {message}
        </p>
      )}
    </div>
  )
}
