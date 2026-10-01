// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { loginWithEmail, resetMockSession } from '@/features/auth/auth-client'

import { MeRequiredStepsGate, useRequiredStepsGate } from './member-gate'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/me'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

function HomeGate() {
  useRequiredStepsGate('/')
  return <p>홈</p>
}

beforeEach(() => {
  search = ''
  pathname = '/me'
  resetMockSession()
  router.replace.mockClear()
})

describe('useRequiredStepsGate', () => {
  it('비회원은 조건 덮어쓰기가 있어도 보내지 않는다', () => {
    search = 'mock-required=terms,region'
    render(<HomeGate />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('조건이 없는 회원은 보내지 않는다', async () => {
    await loginWithEmail('dong@example.com', 'dongne2026')
    render(<HomeGate />)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('약관이 개정된 회원은 재동의로 보낸다 (홈이면 next 없음)', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026')
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent')
  })

  it('동네가 폐지된 회원은 동네 다시 고르기로 보낸다', async () => {
    await loginWithEmail('reselect@example.com', 'dongne2026')
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledWith('/setup/region?reselect=1')
  })

  it('조건이 둘이면 재동의가 먼저다', () => {
    search = 'mock-auth=member&mock-required=region,terms'
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith(
      '/terms/reconsent?mock-auth=member&mock-required=terms%2Cregion',
    )
  })

  it('동네 · 목 덮어쓰기만 남기고 열린 시트 쿼리는 버린다', () => {
    search = 'region=11440660&mock-auth=member-no-consent&mock-required=region&report=start'
    render(<HomeGate />)
    expect(router.replace).toHaveBeenCalledWith(
      '/setup/region?reselect=1&region=11440660&mock-auth=member-no-consent&mock-required=region',
    )
  })

  it('하이드레이션 첫 그림(비회원)으로는 판단하지 않고, 하이드레이션 뒤 회원 상태로 보낸다', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026')
    const container = document.createElement('div')
    document.body.append(container)
    // 서버 그림은 목 세션을 모른다(비회원)
    container.innerHTML = renderToString(<HomeGate />)
    expect(router.replace).not.toHaveBeenCalled()

    render(<HomeGate />, { container, hydrate: true })
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent')
  })
})

describe('MeRequiredStepsGate', () => {
  it.each(['/me', '/me/devices', '/me/password'])(
    '%s 에서 보내면 그 경로로 돌아오게 next 를 붙인다',
    (path) => {
      pathname = path
      search = 'mock-auth=member&mock-required=terms'
      render(<MeRequiredStepsGate />)
      expect(router.replace).toHaveBeenCalledWith(
        `/terms/reconsent?next=${encodeURIComponent(path)}&mock-auth=member&mock-required=terms`,
      )
    },
  )

  it('허용 목록 밖 경로에서는 홈으로 돌아오게 한다', () => {
    pathname = '/me/unknown'
    search = 'mock-auth=member&mock-required=region'
    render(<MeRequiredStepsGate />)
    expect(router.replace).toHaveBeenCalledWith(
      '/setup/region?reselect=1&mock-auth=member&mock-required=region',
    )
  })
})
