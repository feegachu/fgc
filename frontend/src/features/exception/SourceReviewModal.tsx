import { useState } from 'react'
import { useIsMutating } from '@tanstack/react-query'
import { Modal } from '../../components/Modal'
import { Button } from '../../components/Button'
import { useUnsavedWorkStore } from '../../stores/unsavedWork'
import { ReviewPanel } from './ReviewPanel'
import type { ExceptionCase } from './api'
export function SourceReviewModal({ item, onClose }: { item: ExceptionCase; onClose: () => void }) {
  const [discard, setDiscard] = useState(false)
  const busy = useIsMutating({ mutationKey: ['exception', 'write', item.exceptionCaseId] }) > 0
  const close = () => {
    if (busy) return
    if (Object.keys(useUnsavedWorkStore.getState().owners).some(key => key.startsWith(`exception:${item.exceptionCaseId}:`))) setDiscard(true)
    else onClose()
  }
  return <>
    <Modal open title="검토 및 원천 처리" onClose={close} closeOnBackdrop={false} closeOnEscape={!busy}
      footer={<Button data-guide-feature="features.exception.review.close" disabled={busy} onClick={close}>닫기</Button>}>
      <ReviewPanel item={item} onClosed={onClose} />
    </Modal>
    <Modal open={discard} title="작성 중인 검토 내용을 닫을까요?" onClose={() => setDiscard(false)} footer={<>
      <Button data-guide-feature="features.exception.review.continue" variant="secondary" onClick={() => setDiscard(false)}>계속 작성</Button>
      <Button data-guide-feature="features.exception.review.discard" onClick={onClose}>입력 버리고 닫기</Button>
    </>}><p>저장하지 않은 검토 메모와 문서번호가 있습니다.</p></Modal>
  </>
}
