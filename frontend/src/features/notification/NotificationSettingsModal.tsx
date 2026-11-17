import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '../../components/Button'
import { Field } from '../../components/Field'
import { Modal } from '../../components/Modal'
import { errorText } from '../../lib/format'
import { toast } from '../../stores/toasts'
import { notificationSettingQuery, saveNotificationSetting } from './api'
import type { NotificationSetting } from './api'
import './notification.css'

/** 서버(NotificationSettingService)와 같은 규칙. 하이픈과 공백은 빼고 본다. */
const MOBILE = /^01[016789]\d{7,8}$/

/*
 * FUN-089 본인 마감 알림 수신 설정(IF-API-91·92). 번호는 본인만 넣고 지운다.
 * 받는 알림은 역할마다 하나라 문장 하나로만 알려 준다.
 */
export function NotificationSettingsModal({ agent, onClose }: { agent: boolean; onClose: () => void }) {
  const setting = useQuery(notificationSettingQuery())
  if (setting.data) return <SettingForm agent={agent} setting={setting.data} onClose={onClose} />
  return (
    <Modal open title="알림 설정" onClose={onClose}>
      <p role={setting.isError ? 'alert' : undefined} className={setting.isError ? 'field-error' : undefined}>
        {setting.isError ? errorText(setting.error) : '불러오는 중입니다.'}
      </p>
    </Modal>
  )
}

function SettingForm({ agent, setting, onClose }: { agent: boolean; setting: NotificationSetting; onClose: () => void }) {
  const [mobilePhone, setMobilePhone] = useState(setting.mobilePhone ?? '')
  const [consent, setConsent] = useState(Boolean(setting.mobilePhone))
  const [submitted, setSubmitted] = useState(false)
  const client = useQueryClient()
  // 검증한 값을 그대로 보낸다. 서버는 줄바꿈 없는 공백 같은 문자를 빼지 못해, 입력값을 보내면 화면 검증을 통과하고도 거절된다.
  const digits = mobilePhone.replace(/[\s-]/g, '')
  const invalid = consent && !MOBILE.test(digits)
  const mutation = useMutation({
    mutationFn: () => saveNotificationSetting(consent ? { mobilePhone: digits, consent } : { consent }),
    meta: { errorToast: false },
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: ['notification-setting'] })
      toast('알림 설정을 저장했습니다.', 'success')
      onClose()
    },
  })
  function submit() {
    setSubmitted(true)
    if (!invalid && !mutation.isPending) mutation.mutate()
  }
  return (
    <Modal
      open
      title="알림 설정"
      closeOnBackdrop={!mutation.isPending}
      closeOnEscape={!mutation.isPending}
      onClose={() => {
        if (!mutation.isPending) onClose()
      }}
      footer={
        <>
          <Button
            data-guide-feature="features.notification.notification-settings-modal.button"
            variant="secondary"
            disabled={mutation.isPending}
            onClick={onClose}
          >
            취소
          </Button>
          <Button
            data-guide-feature="features.notification.notification-settings-modal.button-2"
            loading={mutation.isPending}
            onClick={submit}
          >
            저장
          </Button>
        </>
      }
    >
      <div className="notification-settings-body">
        <p>{agent ? '수수료 명세가 확정되면 문자로 알려 드립니다.' : '월 통합검증을 확정할 차례가 되면 문자로 알려 드립니다.'}</p>
        <Field
          label="휴대폰 번호"
          error={submitted && invalid ? '휴대폰 번호를 확인하세요. 예: 010-0000-0000' : undefined}
        >
          <input
            data-guide-feature="features.notification.notification-settings-modal.input"
            className="field-control"
            type="tel"
            inputMode="numeric"
            autoComplete="tel"
            placeholder="010-0000-0000"
            value={mobilePhone}
            disabled={mutation.isPending}
            onChange={(event) => setMobilePhone(event.target.value)}
          />
        </Field>
        <label className="checkbox-label">
          <input
            data-guide-feature="features.notification.notification-settings-modal.input-2"
            type="checkbox"
            checked={consent}
            disabled={mutation.isPending}
            onChange={(event) => setConsent(event.target.checked)}
          />
          마감 알림 문자를 받습니다
        </label>
        {!setting.smsConfigured && <p className="field-helper">지금은 문자 발송이 꺼져 있어 알림 이력만 남습니다.</p>}
        {mutation.isError && (
          <p className="field-error" role="alert">
            {errorText(mutation.error)}
          </p>
        )}
      </div>
    </Modal>
  )
}
