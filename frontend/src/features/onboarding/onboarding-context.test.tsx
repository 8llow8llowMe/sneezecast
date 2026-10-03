// @vitest-environment jsdom
import { useEffect } from 'react'

import { act, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { OnboardingProvider, useOnboarding } from './onboarding-context'

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/signup/account',
}))

/* 뒤로 가기 캐시(bfcache)에 들어갈 때 메모리의 가입 초안 · 재설정 토큰 · 연결 이메일을 비운다 (#186) */

function Probe() {
  const { signup, passwordReset, kakaoLinkEmail, membership, adultConfirmed } = useOnboarding()
  return (
    <pre data-testid="probe">
      {JSON.stringify({ signup, passwordReset, kakaoLinkEmail, membership, adultConfirmed })}
    </pre>
  )
}

const read = () =>
  JSON.parse(screen.getByTestId('probe').textContent ?? '{}') as Record<string, unknown>

function renderFilled() {
  render(
    <OnboardingProvider
      initialSignup={{
        method: 'email',
        email: 'me@example.com',
        codeSentAt: 1,
        verifiedAt: 2,
        password: 'Secret-PW-123',
        nickname: '재채기탐정',
      }}
      initialPasswordReset={{ email: 'me@example.com', codeSentAt: 3, resetToken: 'reset-token-1' }}
      initialKakaoLinkEmail="d***@example.com"
      initialAdultConfirmed
      initialMembership={{ accountCreated: true, loggedIn: false, regionSaved: false }}
    >
      <Probe />
    </OnboardingProvider>,
  )
}

describe('OnboardingProvider — 뒤로 가기 캐시', () => {
  it('얼기 직전(pagehide persisted)에 가입 초안(비밀번호 포함) · 재설정 토큰 · 연결 이메일 · 성인 확인을 비운다', () => {
    renderFilled()
    // act 없이 보내야 flushSync 가 그 자리에서 커밋하는지 볼 수 있다 — RTL 의 "not wrapped in act" 경고는 의도다
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }))
    consoleError.mockRestore()

    expect(read()).toEqual({
      signup: {
        method: null,
        email: '',
        codeSentAt: null,
        verifiedAt: null,
        password: '',
        nickname: '',
      },
      passwordReset: { email: '', codeSentAt: null, resetToken: null },
      kakaoLinkEmail: null,
      // 진행은 이미 만든 계정 · 세션에 딸린 값이라 두고, 세션은 세션 저장소가 다시 확인한다
      membership: { accountCreated: true, loggedIn: false, regionSaved: false },
      // 성인 확인은 사람마다 받는다 — 다음 사람에게 미리 체크된 채로 보이지 않게
      adultConfirmed: false,
    })
  })

  it('보통 떠나기(persisted false)는 그대로 둔다', () => {
    renderFilled()

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: false }))
    })

    expect(read()).toMatchObject({
      signup: { password: 'Secret-PW-123' },
      passwordReset: { resetToken: 'reset-token-1' },
      kakaoLinkEmail: 'd***@example.com',
      adultConfirmed: true,
    })
  })
})

describe('OnboardingProvider — 되살아날 때 화면을 다시 붙인다', () => {
  function MountCounter({ onMount }: { onMount: () => void }) {
    useEffect(onMount, [onMount])
    return null
  }

  it('되살아나면(pageshow persisted) 화면을 다시 붙여 단계 확인(값이 없으면 처음으로)을 다시 돌린다 — 얼기 직전에 보낸 이동은 버려진다', () => {
    const onMount = vi.fn()
    render(
      <OnboardingProvider>
        <MountCounter onMount={onMount} />
      </OnboardingProvider>,
    )
    expect(onMount).toHaveBeenCalledTimes(1)

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }))
    })
    expect(onMount).toHaveBeenCalledTimes(1)

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })
    expect(onMount).toHaveBeenCalledTimes(2)
  })
})
