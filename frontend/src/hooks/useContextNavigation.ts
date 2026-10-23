import { useNavigate } from 'react-router'

/** 상세로 떠나기 전에 목록의 선택 행을 현재 history 항목에도 기록한다. */
export function useContextNavigation() {
  const navigate = useNavigate()
  return async (to: string) => {
    const back = new URL(to, 'http://workspace.local').searchParams.get('returnTo')
    if (back && /^\/(contracts|transactions|schedules)(?:[/?]|$)/.test(back)) {
      await navigate(back, { replace: true })
    }
    await navigate(to)
  }
}
