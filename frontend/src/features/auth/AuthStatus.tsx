import { Button } from '../../components/Button'
import { authErrorText } from './errors'

export function AuthStatus({ error, retry }: { error?: unknown; retry?: () => void }) {
  if (!error) return <p className="shell-error" role="status">업무 화면을 준비하고 있습니다.</p>
  return (
    <div className="shell-error">
      <p role="alert">{authErrorText(error)}</p>
      <Button onClick={retry}>다시 시도</Button>
    </div>
  )
}
