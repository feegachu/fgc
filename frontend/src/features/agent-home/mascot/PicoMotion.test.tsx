import { render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { PicoMotion } from './PicoMotion'

afterEach(() => vi.restoreAllMocks())

it('보안 정책이 WebAssembly 를 막으면 움직임을 받지 않고 정지 그림을 보여 준다', () => {
  vi.spyOn(WebAssembly, 'Module').mockImplementation(() => {
    throw new WebAssembly.CompileError('blocked by CSP')
  })
  const { container } = render(<PicoMotion motion="still" size={92} />)
  expect(container.querySelector('img')).not.toBeNull()
  expect(container.querySelector('canvas')).toBeNull()
})
