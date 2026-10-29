import { waitFor } from '@testing-library/react'
import type userEvent from '@testing-library/user-event'
/** 같은 업무 시험을 native 코드 선택과 CMN-C02 엔티티 선택에 함께 사용한다. */
export async function selectOption(user: ReturnType<typeof userEvent.setup>, control: HTMLElement, id: string) {
  if (control instanceof HTMLSelectElement) return user.selectOptions(control, id)
  await user.click(control)
  const option = await waitFor(() => {
    const result = document.querySelector<HTMLElement>(`[role="option"][data-value="${id}"]`)
    if (!result) throw new Error(`선택지 ${id}를 찾을 수 없습니다.`)
    return result
  })
  await user.click(option)
}
