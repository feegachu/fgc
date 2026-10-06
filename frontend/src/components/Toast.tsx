import { useEffect } from 'react'
import { createPortal } from 'react-dom'
import { subscribeApiErrors } from '../lib/api/errorNotifications'
import { useToastStore } from '../stores/toasts'
import type { ToastMessage } from '../stores/toasts'
const icons = { info: 'info', success: 'check_circle', warning: 'warning', error: 'error', loading: 'progress_activity' }
function Toast({ item }: { item: ToastMessage }) {
  const close = useToastStore((state) => state.close)
  useEffect(() => { if (item.duration <= 0) return; const timer = setTimeout(() => close(item.id), item.duration); return () => clearTimeout(timer) }, [item.id, item.duration, close])
  const [title, ...details] = item.message.split(' — ')
  return <div className={`toast toast-${item.tone}`} role={item.tone === 'error' ? 'alert' : 'status'} aria-atomic="true"><span className="toast-icon" aria-hidden="true"><span className="material-symbols-rounded">{icons[item.tone]}</span></span><div className="toast-content"><p className="toast-message">{title}</p>{details.length > 0 && <p className="toast-message-detail">{details.join(' — ')}</p>}</div><button type="button" className="icon-button toast-close" aria-label="알림 닫기" onClick={() => close(item.id)}><span className="material-symbols-rounded" aria-hidden="true">close</span></button></div>
}
export function ToastRegion() {
  const messages = useToastStore((state) => state.messages)
  useEffect(() => subscribeApiErrors((message) => { useToastStore.getState().show(message, 'error') }), [])
  return createPortal(<div className="toast-region" role="region" aria-label="알림" aria-live="polite">{messages.map((item) => <Toast key={item.id} item={item} />)}</div>, document.body)
}
