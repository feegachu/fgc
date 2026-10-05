import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it } from 'vitest'
import { routes } from './router'

function renderAt(path: string) {
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: [path] })} />)
}

describe('routes', () => {
  it('첫 화면을 보여 준다', () => {
    renderAt('/')
    expect(screen.getByRole('heading', { name: 'FGC' })).toBeInTheDocument()
  })

  it('없는 경로는 안내 화면을 보여 준다', () => {
    renderAt('/no-such-page')
    expect(screen.getByRole('heading', { name: '페이지를 찾을 수 없습니다' })).toBeInTheDocument()
  })
})
