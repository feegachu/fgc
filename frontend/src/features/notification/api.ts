import { keepPreviousData, queryOptions } from '@tanstack/react-query'
import { apiClient } from '../../lib/api/client'
import type { components } from '../../lib/api/schema'

export type NotificationSetting = components['schemas']['NotificationSettingResponse']
export type NotificationSettingRequest = components['schemas']['NotificationSettingRequest']
export type ClosingNotification = components['schemas']['ClosingNotificationResponse']
export type ClosingNotificationPage = components['schemas']['PageResponseClosingNotificationResponse']

/** 다른 목록 화면과 같은 한 화면 20행. */
export const PAGE_SIZE = 20

/** IF-API-91 본인 마감 알림 수신 설정. */
export const notificationSettingQuery = () =>
  queryOptions({
    queryKey: ['notification-setting'],
    queryFn: async ({ signal }) =>
      (await apiClient.request<NotificationSetting>('/api/v1/notification-settings/me', { signal })).data,
    // 창을 닫으면 캐시를 버린다. 다시 열 때 옛 값으로 폼을 채우면, 그대로 저장할 때 다른 곳에서 바꾼 번호를 덮어쓴다.
    gcTime: 0,
    meta: { errorToast: false },
  })

/** IF-API-92. consent 가 false 면 서버가 번호를 지운다. */
export const saveNotificationSetting = async (body: NotificationSettingRequest) =>
  (await apiClient.request<NotificationSetting>('/api/v1/notification-settings/me', { method: 'PUT', body })).data

/** IF-API-93 마감 알림 발송 이력(최신순). */
export const notificationsQuery = (page: number) =>
  queryOptions({
    queryKey: ['notifications', 'list', page],
    queryFn: async ({ signal }) =>
      (
        await apiClient.request<ClosingNotificationPage>(`/api/v1/notifications?page=${page}&size=${PAGE_SIZE}`, {
          signal,
        })
      ).data,
    // 쪽을 넘길 때 표가 비었다가 다시 차지 않게 이전 결과를 유지한다.
    placeholderData: keepPreviousData,
    meta: { errorToast: false },
  })

/** IF-API-94. 실패했거나 건너뛴 알림을 대기로 되돌린다. */
export const resendNotification = (closingNotificationId: number) =>
  apiClient.request<null>(`/api/v1/notifications/${closingNotificationId}/resend`, { method: 'POST' })
