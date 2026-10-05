import { QueryClient } from '@tanstack/react-query'

// 재시도·오류 처리 기본값은 공통 레이어(#402)에서 정한다.
export const queryClient = new QueryClient()
