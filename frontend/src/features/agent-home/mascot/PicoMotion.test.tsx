import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PicoMotion } from './PicoMotion'

vi.mock('./PicoRive', () => ({ default: () => <canvas data-testid="rive" /> }))

beforeEach(() => {
  // 움직임을 허용하는 환경으로 맞춘다. 기본 시험 환경은 matchMedia 가 없어 항상 정지 그림이다.
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }))
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

it('WebAssembly 를 쓸 수 있으면 움직임을 그린다', async () => {
  render(<PicoMotion motion="still" size={92} />)
  expect(await screen.findByTestId('rive')).toBeInTheDocument()
})

it('보안 정책이 WebAssembly 를 막으면 움직임을 받지 않고 정지 그림을 보여 준다', async () => {
  vi.spyOn(WebAssembly, 'Module').mockImplementation(() => {
    throw new WebAssembly.CompileError('blocked by CSP')
  })
  const { container } = render(<PicoMotion motion="still" size={92} />)
  await new Promise((resolve) => setTimeout(resolve, 50))
  expect(container.querySelector('img')).not.toBeNull()
  expect(screen.queryByTestId('rive')).toBeNull()
})
