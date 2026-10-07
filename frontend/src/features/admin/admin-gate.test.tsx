// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetMockSession } from '@/features/auth/auth-client'
import { restoreSession, setSession } from '@/lib/session/session-store'
import { memberToken, resetApiSession, selectApiSource } from '@/test/api-session'

import { AdminGate } from './admin-gate'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => '/admin/review',
  useRouter: () => router,
}))

function renderGate() {
  return render(
    <AdminGate>
      <p>운영자 본문</p>
    </AdminGate>,
  )
}

const FORBIDDEN = '운영자만 볼 수 있는 화면이에요'

beforeEach(() => {
  search = ''
  resetMockSession()
  router.replace.mockClear()
})

afterEach(() => {
  resetApiSession()
})

describe('AdminGate 운영자 화면 가드 (목데이터)', () => {
  it('비회원은 로그인으로 보내고(돌아올 곳 /admin/review) 아무것도 그리지 않는다', () => {
    search = 'mock-role=operator'
    renderGate()
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fadmin%2Freview')
    expect(screen.queryByText('운영자 본문')).toBeNull()
    expect(screen.queryByText(FORBIDDEN)).toBeNull()
  })

  it('일반 회원(역할 덮어쓰기 없음 · user)은 보내지 않고 권한 없음을 알린다', () => {
    for (const role of ['', '&mock-role=user', '&mock-role=nope']) {
      search = `mock-auth=member${role}`
      const view = renderGate()
      expect(screen.getByRole('heading', { level: 1, name: FORBIDDEN })).toBeDefined()
      expect(screen.getByRole('link', { name: '홈으로 가기' }).getAttribute('href')).toBe('/')
      expect(screen.queryByText('운영자 본문')).toBeNull()
      view.unmount()
    }
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('서버 · 하이드레이션 첫 그림은 운영자 덮어쓰기가 있어도 본문 · 권한 없음을 그리지 않고, 하이드레이션 뒤에 그린다', () => {
    search = 'mock-auth=member&mock-role=operator'
    const container = document.createElement('div')
    document.body.append(container)
    container.innerHTML = renderToString(
      <AdminGate>
        <p>운영자 본문</p>
      </AdminGate>,
    )
    expect(container.textContent).toBe('')

    render(
      <AdminGate>
        <p>운영자 본문</p>
      </AdminGate>,
      { container, hydrate: true },
    )
    expect(screen.getByText('운영자 본문')).toBeDefined()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('운영자 · 관리자는 본문을 그린다 (동의하지 않은 회원이어도)', () => {
    for (const query of [
      'mock-auth=member&mock-role=operator',
      'mock-auth=member&mock-role=admin',
      'mock-auth=member-no-consent&mock-role=operator',
    ]) {
      search = query
      const view = renderGate()
      expect(screen.getByText('운영자 본문')).toBeDefined()
      view.unmount()
    }
  })
})

describe('AdminGate 운영자 화면 가드 (실데이터)', () => {
  beforeEach(() => {
    selectApiSource()
  })

  it('역할은 세션의 role 이고, 주소의 ?mock-role= 은 듣지 않는다', () => {
    setSession(memberToken({ role: 'USER' }))
    search = 'mock-auth=member&mock-role=operator'
    renderGate()
    expect(screen.getByText(FORBIDDEN)).toBeDefined()
    expect(screen.queryByText('운영자 본문')).toBeNull()
  })

  it('세션이 운영자면 본문을 그린다', () => {
    setSession(memberToken({ role: 'OPERATOR' }))
    renderGate()
    expect(screen.getByText('운영자 본문')).toBeDefined()
  })

  it('비회원은 ?mock-auth= · ?mock-role= 이 있어도 로그인으로 보낸다', async () => {
    await restoreSession() // 힌트가 없어 요청 없이 비회원으로 정해진다
    search = 'mock-auth=member&mock-role=admin'
    renderGate()
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fadmin%2Freview')
    expect(screen.queryByText('운영자 본문')).toBeNull()
  })
})
