// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from './auth-client'
import { startKakaoLogin } from './auth-client'
import { LoginSheet } from './login-sheet'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, startKakaoLogin: vi.fn(actual.startKakaoLogin) }
})

/** 응답을 테스트가 정할 때까지 붙잡아 둔다 */
function holdKakao() {
  let resolve: (value: { redirectTo: string }) => void = () => {}
  let reject: (reason: Error) => void = () => {}
  vi.mocked(startKakaoLogin).mockImplementationOnce(
    () =>
      new Promise((res, rej) => {
        resolve = res
        reject = rej
      }),
  )
  return { resolve: (to: string) => resolve({ redirectTo: to }), reject: () => reject(new Error()) }
}

const kakao = () => screen.getByRole('button', { name: '카카오로 계속하기' })

describe('LoginSheet', () => {
  beforeEach(() => {
    router.push.mockClear()
    vi.mocked(startKakaoLogin).mockClear()
  })

  it('보고는 회원만 할 수 있다는 이유와 두 가지 시작 방법을 보인다', () => {
    render(<LoginSheet open onClose={() => {}} />)

    expect(screen.getByRole('dialog', { name: '보고는 회원만 할 수 있어요' })).toBeDefined()
    expect(
      screen.getByText('한 사람이 한 주에 한 번만 보고하도록 계정으로 확인해요.'),
    ).toBeDefined()
    expect(screen.getByRole('button', { name: '이메일로 시작하기' })).toBeDefined()
    expect(screen.getByText('동네 현황은 로그인 없이도 계속 볼 수 있어요.')).toBeDefined()
  })

  it('카카오로 계속하기는 카카오 로그인을 한 번만 시작하고 돌려준 주소로 간다', async () => {
    const user = userEvent.setup()
    const hold = holdKakao()
    render(<LoginSheet open onClose={() => {}} />)

    await user.click(kakao())
    await user.click(kakao())
    expect(startKakaoLogin).toHaveBeenCalledTimes(1)
    expect((kakao() as HTMLButtonElement).disabled).toBe(true)

    hold.resolve('/setup/region?from=kakao')
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/setup/region?from=kakao'))
  })

  it('카카오 로그인을 시작하지 못하면 알리고 다시 누를 수 있다', async () => {
    const user = userEvent.setup()
    const hold = holdKakao()
    render(<LoginSheet open onClose={() => {}} />)

    await user.click(kakao())
    hold.reject()

    expect((await screen.findByRole('alert')).textContent).toContain(
      '카카오 로그인을 시작하지 못했어요',
    )
    expect((kakao() as HTMLButtonElement).disabled).toBe(false)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('응답 전에 시트가 닫히면 늦게 온 주소로 가지 않는다', async () => {
    const user = userEvent.setup()
    const hold = holdKakao()
    const { rerender } = render(<LoginSheet open onClose={() => {}} />)

    await user.click(kakao())
    rerender(<LoginSheet open={false} onClose={() => {}} />)
    hold.resolve('/setup/region')

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(router.push).not.toHaveBeenCalled()
  })

  it('이메일로 시작하기는 보고하려던 이메일 로그인으로 간다 (가입하기 링크가 그 화면에 있다)', async () => {
    render(<LoginSheet open onClose={() => {}} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '이메일로 시작하기' }))
    expect(router.push).toHaveBeenCalledWith('/login/email?intent=report')
  })

  it('둘러보기 동네가 있으면 이메일 로그인에 함께 넘겨 로그인 뒤 같은 동네로 돌아온다', async () => {
    render(<LoginSheet open onClose={() => {}} regionCode="11680640" />)

    await userEvent.setup().click(screen.getByRole('button', { name: '이메일로 시작하기' }))
    expect(router.push).toHaveBeenCalledWith('/login/email?region=11680640&intent=report')
  })

  it('닫기를 누르면 onClose 를 부른다', async () => {
    const onClose = vi.fn()
    render(<LoginSheet open onClose={onClose} />)

    await userEvent.setup().click(screen.getByRole('button', { name: '닫기' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
