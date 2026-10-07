// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { EMPTY_SIGNUP, OnboardingProvider } from '@/features/onboarding/onboarding-context'

import { TermsScreen } from './terms-screen'

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
  usePathname: () => '/setup/terms',
  useSearchParams: () => new URLSearchParams(window.location.search),
}))

// 서비스 안내 시트 청크를 받지 못한 경우(지연 로드, #184 — 연결이 끊겼거나 배포 뒤 청크 이름이 바뀜). 받은 모듈은 남으므로 파일을 따로 둔다
vi.mock('@/features/me/info-sheet', () => {
  const error = new Error('Failed to load chunk static/chunks/info-sheet.js')
  error.name = 'ChunkLoadError'
  throw error
})

describe('가입 동의의 서비스 안내 시트를 받지 못하면 (#229)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    window.history.replaceState(null, '', '/')
  })

  it('가입 동의를 그대로 두고 알린 뒤 시트 쿼리만 닫는다', async () => {
    window.history.replaceState(null, '', '/setup/terms?info=privacy')
    const replaceState = vi.spyOn(window.history, 'replaceState')
    render(
      <OnboardingProvider
        initialDistrict={{ code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }}
        initialSignup={{ ...EMPTY_SIGNUP, method: 'kakao' }}
        initialAdultConfirmed
      >
        <TermsScreen />
      </OnboardingProvider>,
    )

    expect(
      await screen.findByText('화면을 불러오지 못했어요. 연결을 확인하고 새로고침해 주세요.'),
    ).toBeDefined()
    await waitFor(() =>
      expect(replaceState).toHaveBeenCalledWith({ sneezecastModalDepth: 0 }, '', '/setup/terms'),
    )
    expect(
      screen.getByRole('heading', { level: 1, name: '가입 약관에 동의해 주세요' }),
    ).toBeDefined()
    expect(document.querySelector('dialog[open]')).toBeNull()
  })
})
