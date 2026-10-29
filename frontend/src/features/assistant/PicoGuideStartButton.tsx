import { useEffect, useRef, useState } from 'react'
import { findGuide } from './catalog'
import { useGuideRuntime } from './guide-runtime'
import { startPicoGuide } from './pico-guide-api'
import { isEntityId } from './guards'

export function PicoGuideStartButton({
  workflowId,
  contextRevision,
  canProcess,
}: {
  workflowId: string
  contextRevision: string
  canProcess: boolean
}) {
  const observation = useGuideRuntime((state) => state.observation)
  const controller = useRef<AbortController | null>(null)
  const lock = useRef(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  useEffect(
    () => () => {
      controller.current?.abort()
      controller.current = null
      lock.current = false
      setBusy(false)
    },
    [observation, contextRevision, workflowId, canProcess],
  )
  const definition = findGuide(workflowId, '1.0')
  if (
    !definition ||
    !observation?.ready ||
    !observation.saved ||
    observation.busy ||
    !isEntityId(observation.paymentId) ||
    (!canProcess && definition.capability === 'PROCESS')
  )
    return null
  async function start() {
    if (lock.current || !observation?.paymentId || !definition) return
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
      <button type="button" className="pico-primary" disabled={busy} onClick={() => void start()}>
        {busy ? '현재 지급 확인 중…' : `이 업무 안내 시작 · ${definition.title}`}
      </button>
      {notice && (
        <p className="pico-notice" role="status">
          {notice}
        </p>
      )}
    </div>
  )
}
