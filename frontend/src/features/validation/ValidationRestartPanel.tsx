import { useMutation, useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Button } from '../../components/Button'
import { Modal } from '../../components/Modal'
import { apiClient } from '../../lib/api/client'
import { ApiError } from '../../lib/api/errors'
import { errorText } from '../../lib/format'

export interface Restartability {
  allowed: boolean
  reason?: string | null
  expectedStateVersion: string
  lastFailedBatchExecutionId: number | null
  failedStep?: number | null
  completedCount?: number | null
  remainingCount?: number | null
  failedAt?: string | null
  checkpointAvailable: boolean
}
interface Retry {
  retryId: number
  requestedBy: number
  requestedAt: string
  startedAt?: string | null
  finishedAt?: string | null
  reason: string
  state: string
  errorCode?: string | null
  failedStep: number
  previousBatchExecutionId: number
  batchExecutionId?: number | null
}
const retryLabels: Record<string, string> = {
  ACCEPTED: '접수됨',
  RUNNING: '진행 중',
  LAUNCHING: '기동 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
}
const unstartedExecutionLabels: Record<string, string> = {
  ACCEPTED: '기동 대기',
  LAUNCHING: '기동 중',
  FAILED: '기동 안 됨',
}
export function ValidationRestartPanel({
  id,
  status,
  month = '',
  runNo,
  waiting,
  canProcess,
  onAccepted,
}: {
  id: string
  status: string
  month?: string
  runNo?: number
  waiting: boolean
  canProcess: boolean
  onAccepted: () => void
}) {
  const [open, setOpen] = useState(false)
  const [approval, setApproval] = useState<Restartability | null>(null)
  const [reason, setReason] = useState('')
  const [frozen, setFrozen] = useState(false)
  const key = useRef<string | null>(null)
  const sent = useRef<{ expectedBatchExecutionId: number; expectedStateVersion: string; reason: string } | null>(null)
  const state = useQuery({
    queryKey: ['validation-restart', id, status],
    queryFn: async () => (await apiClient.request<Restartability>(`/api/v1/validation-runs/${id}/restartability`)).data,
    refetchInterval: status === 'FAILED' || status === 'RUNNING' ? 2000 : false,
  })
  const history = useQuery({
    queryKey: ['validation-retries', id, status],
    queryFn: async () => (await apiClient.request<Retry[]>(`/api/v1/validation-runs/${id}/retries`)).data,
    // The run and retry completion are committed separately; keep polling an active retry.
    refetchInterval: (query) =>
      status === 'FAILED' || status === 'RUNNING' ||
      query.state.data?.some((retry) => ['ACCEPTED', 'LAUNCHING', 'RUNNING'].includes(retry.state))
        ? 2000
        : false,
  })
  const mutation = useMutation({
    mutationFn: async () => {
      if (!sent.current && (!approval?.allowed || approval.lastFailedBatchExecutionId == null))
        throw new Error('최신 재시작 가능 여부를 다시 확인하세요.')
      sent.current ??= {
        expectedBatchExecutionId: approval!.lastFailedBatchExecutionId!,
        expectedStateVersion: approval!.expectedStateVersion,
        reason: reason.trim(),
      }
      key.current ??= crypto.randomUUID()
      setFrozen(true)
      const result = await apiClient.request(`/api/v1/validation-runs/${id}/restart`, {
        method: 'POST',
        idempotencyKey: key.current,
        body: sent.current,
      })
      if (result.data == null) throw new Error('접수 결과를 확인하지 못했습니다. 같은 요청으로 다시 확인하세요.')
      return result.data
    },
    onSuccess: () => {
      setOpen(false)
      key.current = null
      sent.current = null
      setReason('')
      setFrozen(false)
      onAccepted()
      void state.refetch()
      void history.refetch()
    },
    onError: (error) => {
      if (error instanceof ApiError && (error.status === 409 || error.code === 'FGC-VRUN-008')) {
        key.current = null
        sent.current = null
        setFrozen(false)
        setOpen(false)
      }
      void state.refetch()
      void history.refetch()
    },
  })
  return (
    <section data-guide-feature="features.validation.validation-restart-panel.section-1" aria-label="실패 지점 재시작">
      {status === 'FAILED' && (
        <Button
          data-guide-feature="features.validation.validation-restart-panel.button-1"
          disabled={!canProcess || waiting || !state.data?.allowed || mutation.isPending}
          onClick={() => {
            setApproval(state.data ?? null)
            setOpen(true)
          }}
        >
          실패 지점부터 재시작
        </Button>
      )}
      {status === 'FAILED' && (
        <p role="status">
          {!canProcess
            ? '재시작은 정산담당자와 시스템관리자만 할 수 있습니다.'
            : state.isError
              ? '재시작 가능 여부 조회에 실패했습니다.'
              : state.data?.reason}
        </p>
      )}
      {mutation.isError && !open && (
        <p role="alert">
          {errorText(mutation.error, '최신 상태를 확인한 뒤 다시 시도하세요. 입력한 사유는 보존했습니다.')}
        </p>
      )}
      {history.isError && <p role="alert">재시도 이력을 불러오지 못했습니다.</p>}
      {!!history.data?.length && (
        <table data-guide-feature="features.validation.validation-restart-panel.table-1">
          <caption>재시도 이력</caption>
          <thead>
            <tr>
              <th>번호</th>
              <th>요청자</th>
              <th>요청 시각</th>
              <th>사유</th>
              <th>대상 단계</th>
              <th>이전 실행</th>
              <th>새 실행</th>
              <th>시작</th>
              <th>종료</th>
              <th>상태</th>
            </tr>
          </thead>
          <tbody>
            {history.data.map((r) => (
              <tr key={r.retryId}>
                <td>{r.retryId}</td>
                <td>{r.requestedBy}</td>
                <td>{new Date(r.requestedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}</td>
                <td>{r.reason}</td>
                <td>{r.failedStep}단계</td>
                <td>{r.previousBatchExecutionId}</td>
                <td>{r.batchExecutionId ?? unstartedExecutionLabels[r.state] ?? '자료 없음'}</td>
                <td>
                  {r.startedAt
                    ? new Date(r.startedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
                    : '자료 없음'}
                </td>
                <td>
                  {r.finishedAt
                    ? new Date(r.finishedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
                    : '자료 없음'}
                </td>
                <td>
                  {retryLabels[r.state] ?? '상태 확인 필요'}
                  {r.errorCode ? ' — 실행 실패' : ''}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {status === 'FAILED' && (
        <p>
          이어서 처리할 지점: {state.data ? (state.data.checkpointAvailable ? '있음' : '없음') : state.isError ? '확인 불가' : '확인 중'}
          {state.data?.completedCount != null && ` · 완료 대상: ${state.data.completedCount}`}
          {state.data?.remainingCount != null && ` · 남은 대상: ${state.data.remainingCount}`}
          {' · 실패 시각: '}
          {state.data?.failedAt
            ? new Date(state.data.failedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
            : '자료 없음'}
        </p>
      )}
      <Modal
        open={open}
        title="실패 지점 재시작 확인"
        onClose={() => {
          if (!mutation.isPending) setOpen(false)
        }}
        footer={
          <>
            <Button
              data-guide-feature="features.validation.validation-restart-panel.button-2"
              disabled={mutation.isPending}
              onClick={() => setOpen(false)}
            >
              취소
            </Button>
            <Button
              data-guide-feature="features.validation.validation-restart-panel.button-3"
              disabled={!canProcess || !reason.trim() || reason.length > 500 || mutation.isPending}
              onClick={() => mutation.mutate()}
            >
              재시작 요청
            </Button>
          </>
        }
      >
        <p>
          검증월 {month || '자료 없음'} / {runNo ?? '자료 없음'}회차 / 원래 검증 실행 {id}의 {approval?.failedStep}
          단계부터 이어 처리합니다. 요청 접수는 처리 완료가 아닙니다.
        </p>
        <label>
          재시작 사유
          <textarea
            data-guide-feature="features.validation.validation-restart-panel.textarea-1"
            aria-label="재시작 사유"
            maxLength={500}
            required
            value={reason}
            disabled={frozen || mutation.isPending}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        {mutation.isError && (
          <p role="alert">{errorText(mutation.error, '요청 결과를 확인하지 못했습니다. 입력을 보존했습니다.')}</p>
        )}
      </Modal>
    </section>
  )
}
