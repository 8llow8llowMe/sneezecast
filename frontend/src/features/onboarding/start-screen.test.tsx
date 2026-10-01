// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { StartScreen } from './start-screen'

const router = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('next/navigation', () => ({ useRouter: () => router }))

describe('StartScreen', () => {
  beforeEach(() => router.push.mockClear())

  it('제목은 하나이고 시안 문구 그대로다', () => {
    render(<StartScreen />)
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
      '요즘 우리 동네에뭐가 돌고 있을까요?',
    )
  })

  it('시작하기는 동네 선택, 둘러보기는 단계 없는 동네 선택으로 간다', async () => {
    const user = userEvent.setup()
    render(<StartScreen />)

    await user.click(screen.getByRole('button', { name: '시작하기' }))
    expect(router.push).toHaveBeenLastCalledWith('/setup/region')

    await user.click(screen.getByRole('button', { name: '보고 없이 둘러보기' }))
    expect(router.push).toHaveBeenLastCalledWith('/browse/region')
  })

  it('일러스트 패널은 장식이라 보조기술에서 숨긴다', () => {
    const { container } = render(<StartScreen />)
    const panel = container.querySelector('section[aria-hidden="true"]')
    expect(panel?.textContent).toContain('이번 주 우리 동네는')
    expect(panel?.querySelector('img')?.getAttribute('alt')).toBe('')
  })
})
