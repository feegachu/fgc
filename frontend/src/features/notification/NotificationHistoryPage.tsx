import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { DataTable } from '../../components/DataTable'
import type { TableColumn } from '../../components/DataTable'
import { ListHeader } from '../../components/ListHeader'
import { ListPagination } from '../../components/ListPagination'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusTone } from '../../components/StatusBadge'
import { useSearchParamsState } from '../../hooks/useSearchParamsState'
import { dateTime, errorText, month } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { ScreenHelp } from '../assistant/guide/ScreenHelp'
import { PAGE_SIZE, notificationsQuery, resendNotification } from './api'
import type { ClosingNotification } from './api'
import './notification.css'

/*
 * FGC-UI-NOTI-W01 알림 발송 이력 (FUN-089, IF-API-93·94).
 * 마감 알림 문자가 누구에게 갔는지 보고, 실패했거나 건너뛴 알림을 다시 보낸다.
 */
const eventLabels: Record<string, string> = { FINALIZE_READY: '확정 대기', RUN_FINALIZED: '확정 통지' }
const statuses: Record<string, { label: string; tone: StatusTone }> = {
  PENDING: { label: '대기', tone: 'info' },
  SENT: { label: '보냄', tone: 'success' },
  FAILED: { label: '실패', tone: 'error' },
  SKIPPED: { label: '건너뜀', tone: 'warning' },
  NO_RECIPIENT: { label: '받는 사람 없음', tone: 'neutral' },
}
const reasonLabels: Record<string, string> = {
  NO_API_KEY: '문자 발송이 꺼져 있음',
  CONSENT_WITHDRAWN: '수신 해제함',
  TIMEOUT: '응답 없음',
  IO: '연결 실패',
  INTERNAL: '처리 중 오류',
}
const dash = (value: unknown) => (value == null || value === '' ? '-' : String(value))
const canResend = (row: ClosingNotification) => row.status === 'FAILED' || row.status === 'SKIPPED'

export default function NotificationHistoryPage() {
  const { params, update } = useSearchParamsState({ page: '' })
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '', 10) || 1)
  const notifications = useQuery({
    ...notificationsQuery(page),
    // 서버가 대기 알림을 10초 주기로 보낸다. 대기 행이 있는 동안만 같은 주기로 다시 읽어 결과를 보여 준다.
    refetchInterval: (query) => (query.state.data?.content?.some((row) => row.status === 'PENDING') ? 10_000 : false),
  })
  const client = useQueryClient()
  const resend = useMutation({
    mutationFn: (closingNotificationId: number) => resendNotification(closingNotificationId),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['notifications'] })
      toast('다시 보냅니다. 잠시 뒤 상태를 확인하세요.', 'success')
    },
  })
  const totalPages = notifications.data?.totalPages ?? 0
  const columns: TableColumn<ClosingNotification>[] = [
    // 열 너비 합(67.5rem)은 사이드바를 펼친 1440 화면에서 [다시 보내기]까지 한 번에 보이도록 맞춘 값이다.
    { key: 'createdAt', label: '만든 시각', width: '8.5rem', render: (row) => dateTime(row.createdAt) },
    { key: 'eventType', label: '알림', width: '5.5rem', render: (row) => eventLabels[row.eventType ?? ''] ?? dash(row.eventType) },
    { key: 'validationMonth', label: '정산월', width: '5.5rem', render: (row) => month(row.validationMonth) },
    { key: 'recipientName', label: '받는 사람', width: '6.5rem', render: (row) => dash(row.recipientName) },
    { key: 'recipientPhone', label: '번호', width: '7.5rem', render: (row) => dash(row.recipientPhone) },
    {
      key: 'status',
      label: '상태',
      width: '8rem',
      render: (row) => {
        const status = statuses[row.status ?? '']
        return (
          <StatusBadge tone={status?.tone} optionCount={Object.keys(statuses).length}>
            {status?.label ?? dash(row.status)}
          </StatusBadge>
        )
      },
    },
    { key: 'errorCode', label: '사유', width: '10rem', render: (row) => reasonLabels[row.errorCode ?? ''] ?? dash(row.errorCode) },
    { key: 'sentAt', label: '보낸 시각', width: '8.5rem', render: (row) => dateTime(row.sentAt) },
    {
      key: 'resend',
      label: '다시 보내기',
      width: '7.5rem',
      render: (row) =>
        canResend(row) && row.closingNotificationId != null ? (
          <Button
            data-guide-feature="features.notification.notification-history-page.button"
            variant="secondary"
            disabled={resend.isPending}
            onClick={() => resend.mutate(row.closingNotificationId!)}
          >
            다시 보내기
          </Button>
        ) : null,
    },
  ]
  return (
    <div className="notification-history-page">
      <header className="page-header">
        <ScreenHelp screenId="NOTI-W01" />
        <div className="page-header-copy">
          <h1 className="page-title">알림 발송 이력</h1>
        </div>
      </header>
      <section
        data-guide-feature="features.notification.notification-history-page.section"
        data-guide-target="notification.history"
        className="surface"
        aria-labelledby="notification-history-title"
      >
        <ListHeader total={notifications.data?.totalElements ?? 0} pageSize={PAGE_SIZE}>
          <h2 id="notification-history-title" className="surface-title">
            마감 알림 문자
          </h2>
        </ListHeader>
        {notifications.isError && (
          <p className="field-error" role="alert">
            {errorText(notifications.error)}
          </p>
        )}
        <DataTable
          className="notification-history-table"
          caption="마감 알림 문자 발송 이력"
          columns={columns}
          rows={notifications.data?.content ?? []}
          rowKey={(row) => String(row.closingNotificationId)}
          emptyMessage={notifications.isPending ? '불러오는 중입니다.' : undefined}
        />
        {totalPages > 1 && (
          <ListPagination
            label="알림 발송 이력 페이지 선택"
            page={page}
            totalPages={totalPages}
            onPageChange={(next) => update({ page: String(next) }, { resetPage: false })}
          />
        )}
      </section>
    </div>
  )
}
