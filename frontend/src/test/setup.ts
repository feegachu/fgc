import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

// 전체 스위트를 병렬로 돌리면 첫 렌더(모듈 변환 포함)가 기본 1초 대기를 넘길 수 있어 넉넉히 둔다.
configure({ asyncUtilTimeout: 5000 })

afterEach(() => {
  cleanup()
})
