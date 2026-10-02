// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { type Membership, OnboardingProvider } from '@/features/onboarding/onboarding-context'
import { setSession } from '@/lib/session/session-store'
import { memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import type * as authClient from './auth-client'
import { agreeHealthConsent } from './auth-client'
import { HealthConsentScreen } from './health-consent-screen'
import {
  clearLoginReturn,
  LOGIN_RETURN_STORAGE_KEY,
  peekLoginReturn,
  saveLoginReturn,
} from './login-return-store'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => '/setup/health-consent',
}))

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, agreeHealthConsent: vi.fn(actual.agreeHealthConsent) }
})

const DONE: Membership = { accountCreated: true, loggedIn: true, regionSaved: true }

function setup(membership: Membership = DONE) {
  const user = userEvent.setup()
  const utils = render(
    <OnboardingProvider initialMembership={membership}>
      <HealthConsentScreen />
    </OnboardingProvider>,
  )
  return { user, ...utils }
}

const agree = () => screen.getByRole('button', { name: '동의하고 시작하기' })
const check = () =>
  screen.getByRole<HTMLInputElement>('checkbox', {
    name: /건강·증상 정보\(민감정보\) 처리에 동의해요/,
  })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

describe('HealthConsentScreen', () => {
  beforeEach(() => {
    router.replace.mockClear()
    router.push.mockClear()
    vi.mocked(agreeHealthConsent).mockReset()
    clearLoginReturn()
  })

  it('4 / 4 단계 · 법정 고지 표(보관 52주)를 보이고 뒤로 버튼이 없다', () => {
    setup()
    expect(screen.getByText('4 / 4')).toBeDefined()
    expect(screen.getByText('보관 기간').nextElementSibling?.textContent).toContain(
      '52주 뒤 지워지고',
    )
    expect(screen.getByText('모으지 않는 것').nextElementSibling?.textContent).toBe(
      '이름, 연락처, 정확한 주소, GPS 위치, 자유 입력',
    )
    expect(screen.queryByRole('button', { name: '뒤로' })).toBeNull()
  })

  it('체크해야 동의가 켜지고, 동의하면 민감정보 동의를 보내고 홈으로 기록을 바꿔 간다', async () => {
    const { user } = setup()
    expect(isOff(agree())).toBe(true)
    await user.click(agree())
    expect(agreeHealthConsent).not.toHaveBeenCalled()

    await user.click(check())
    expect(isOff(agree())).toBe(false)
    await user.click(agree())

    await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/'))
    expect(agreeHealthConsent).toHaveBeenCalledWith({
      type: 'SENSITIVE_HEALTH_INFO',
      documentVersion: '2026-10-01',
    })
    expect(router.push).not.toHaveBeenCalled()
  })

  it('나중에 할게요는 동의 없이 홈으로 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '나중에 할게요' }))
    expect(router.replace).toHaveBeenCalledWith('/')
    expect(agreeHealthConsent).not.toHaveBeenCalled()
  })

  it('보내는 동안 두 번 눌러도 한 번만 보낸다', async () => {
    let resolve: () => void = () => {}
    vi.mocked(agreeHealthConsent).mockImplementationOnce(
      () => new Promise<void>((done) => (resolve = done)),
    )
    const { user } = setup()
    await user.click(check())
    await user.click(agree())
    await user.click(agree())
    expect(agreeHealthConsent).toHaveBeenCalledTimes(1)
    await act(async () => {
      resolve()
      await Promise.resolve()
    })
  })

  it('보내지 못하면 alert 로 알린다', async () => {
    vi.mocked(agreeHealthConsent).mockRejectedValueOnce(new Error('network'))
    const { user } = setup()
    await user.click(check())
    await user.click(agree())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '동의를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('보내는 동안 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    let resolve: () => void = () => {}
    vi.mocked(agreeHealthConsent).mockImplementationOnce(
      () => new Promise<void>((done) => (resolve = done)),
    )
    const { user, unmount } = setup()
    await user.click(check())
    await user.click(agree())
    unmount()
    await act(async () => {
      resolve()
      await Promise.resolve()
    })
    expect(router.replace).not.toHaveBeenCalled()
  })

  describe('로그인 뒤 돌아갈 곳 (#140)', () => {
    const REPORT = { next: '/', region: '11680640', intent: 'report' as const }

    it('보고하려던 가입이면 동의 뒤 같은 동네 홈의 보고 진입으로 가고 둔 값을 지운다', async () => {
      saveLoginReturn(REPORT)
      const { user } = setup()
      await user.click(check())
      await user.click(agree())
      await waitFor(() =>
        expect(router.replace).toHaveBeenCalledWith('/?region=11680640&report=start'),
      )
      expect(peekLoginReturn()).toEqual({ next: '/', region: null, intent: null })
    })

    it('나중에 할게요는 보고 진입을 붙이지 않는다 — 미룬 동의 시트를 홈이 다시 열지 않게', async () => {
      saveLoginReturn(REPORT)
      const { user } = setup()
      await user.click(screen.getByRole('button', { name: '나중에 할게요' }))
      expect(router.replace).toHaveBeenCalledWith('/?region=11680640')
    })

    it('내 정보에서 온 가입이면 내 정보로 간다', async () => {
      saveLoginReturn({ next: '/me', region: null, intent: null })
      const { user } = setup()
      await user.click(screen.getByRole('button', { name: '나중에 할게요' }))
      expect(router.replace).toHaveBeenCalledWith('/me')
    })

    it('카카오 왕복 뒤처럼 저장소에만 남은 값도 읽는다', async () => {
      window.sessionStorage.setItem(
        LOGIN_RETURN_STORAGE_KEY,
        JSON.stringify({
          v: 1,
          next: '/me/devices',
          region: null,
          intent: null,
          savedAt: Date.now(),
        }),
      )
      const { user } = setup()
      await user.click(screen.getByRole('button', { name: '나중에 할게요' }))
      expect(router.replace).toHaveBeenCalledWith('/me/devices')
      expect(window.sessionStorage.getItem(LOGIN_RETURN_STORAGE_KEY)).toBeNull()
    })

    describe('실데이터 — 동의가 세션 요약에 반영됐을 때만 보고 진입을 붙인다', () => {
      afterEach(() => resetApiSession())

      it('요약이 아직 미동의면(#168 전 — 동의 보내기가 목) 보고 진입 없이 같은 동네 홈으로 간다', async () => {
        selectApiSource()
        act(() => setSession(memberToken({ reportWritable: false })))
        saveLoginReturn(REPORT)
        const { user } = setup()
        await user.click(check())
        await user.click(agree())
        await waitFor(() => expect(router.replace).toHaveBeenCalledWith('/?region=11680640'))
      })

      it('요약이 동의로 바뀌었으면(#168 에서 동의 뒤 세션 요약을 다시 받음) 보고 진입을 붙인다', async () => {
        selectApiSource()
        act(() => setSession(memberToken({ reportWritable: false })))
        vi.mocked(agreeHealthConsent).mockImplementationOnce(() => {
          setSession(memberToken({ reportWritable: true }))
          return Promise.resolve()
        })
        saveLoginReturn(REPORT)
        const { user } = setup()
        await user.click(check())
        await user.click(agree())
        await waitFor(() =>
          expect(router.replace).toHaveBeenCalledWith('/?region=11680640&report=start'),
        )
      })
    })

    it('오염된 값이면 홈으로 간다', async () => {
      window.sessionStorage.setItem(
        LOGIN_RETURN_STORAGE_KEY,
        JSON.stringify({
          v: 1,
          next: 'https://evil.example',
          region: 'x',
          intent: null,
          savedAt: Date.now(),
        }),
      )
      const { user } = setup()
      await user.click(screen.getByRole('button', { name: '나중에 할게요' }))
      expect(router.replace).toHaveBeenCalledWith('/')
    })
  })

  it('가입을 마치지 않았으면 가입 동의로 돌려보낸다', () => {
    const { container } = setup({ accountCreated: false, loggedIn: false, regionSaved: false })
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/setup/terms')
  })
})
