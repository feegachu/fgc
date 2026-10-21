import { JOURNAL_ACCOUNT } from './labels'

const UNKNOWN_ACCOUNT = '계정명 확인 필요'

/**
 * 계정과목은 한글 계정명만 보여 준다. 내부 계정코드는 조회·정정 요청 값으로만 쓰고 화면에 병기하지 않는다.
 * 서버가 준 accountName(공백만 있으면 누락)을 우선하고, 알려진 코드는 JOURNAL_ACCOUNT 로 보완한다.
 */
export function accountDisplayName(code?: string | null, name?: string | null): string {
  const trimmed = name?.trim()
  if (trimmed) return trimmed
  if (!code) return '-'
  return (JOURNAL_ACCOUNT as Record<string, string>)[code] ?? UNKNOWN_ACCOUNT
}
