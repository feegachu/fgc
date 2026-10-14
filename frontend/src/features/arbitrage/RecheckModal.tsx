import { useState, useRef } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { QueryState } from '../reference/ReferenceUI'
import { toast } from '../../stores/toasts'
import { recheckContract } from './api'
import type { ArbitrageRow } from './api'
import { present } from './utils'
export function RecheckModal({
  row,
  onClose,
  onSuccess,
}: {
  row: ArbitrageRow
  onClose: () => void
  onSuccess: () => void
}) {
  const [asOfDate, setDate] = useState(row.asOfDate ?? '')
  const [reason, setReason] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const initialFocus = useRef<HTMLInputElement>(null)
  const client = useQueryClient()
  const mutation = useMutation({
    mutationFn: () => recheckContract(row.contractId!, { asOfDate, reason: reason.trim() }),
    meta: { errorToast: false },
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['arbitrage'] })
      onSuccess()
      onClose()
      toast('차익거래 재검증을 완료했습니다.', 'success')
    },
  })
  function submit() {
    setSubmitted(true)
    if (asOfDate && reason.trim() && reason.trim().length <= 200 && !mutation.isPending) mutation.mutate()
  }
  return (
    <Modal
      open
      title="차익거래 수동 재검증"
      className="arb-recheck-modal"
      initialFocusRef={initialFocus}
      closeOnBackdrop={!mutation.isPending}
      closeOnEscape={!mutation.isPending}
      onClose={() => {
        if (!mutation.isPending) onClose()
      }}
      footer={
        <>
          <Button variant="secondary" disabled={mutation.isPending} onClick={onClose}>
            취소
          </Button>
          <Button loading={mutation.isPending} onClick={submit}>
            재검증 실행
          </Button>
        </>
      }
    >
      <div className="arb-recheck-body">
        <p className="arb-recheck-contract">
          {row.contractNo} · {present(row.paymentStageLabel)}
        </p>
        <Field label="검증 기준일" error={submitted && !asOfDate ? '검증 기준일을 입력하세요.' : undefined}>
          <input
            ref={initialFocus}
            type="date"
            value={asOfDate}
            disabled={mutation.isPending}
            onChange={(e) => setDate(e.target.value)}
          />
        </Field>
        <Field label="재검증 사유" error={submitted && !reason.trim() ? '재검증 사유를 입력하세요.' : undefined}>
          <textarea
            className="textarea-control"
            maxLength={200}
            rows={4}
            placeholder="예) 지급·환수 자료 변경 확인"
            value={reason}
            disabled={mutation.isPending}
            onChange={(e) => setReason(e.target.value)}
          />
        </Field>
        <QueryState error={mutation.error} />
      </div>
    </Modal>
  )
}
