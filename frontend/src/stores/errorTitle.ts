import { create } from 'zustand'
// 오류 화면이 보이는 동안의 문서 제목. AppShell은 경로로 정한 제목보다 이 값을 먼저 쓴다(#467).
// 자식 effect가 먼저 실행되므로 오류 화면이 document.title만 바꾸면 AppShell이 다시 덮어쓴다.
export const useErrorTitleStore = create<{ title: string | null }>(() => ({ title: null }))
