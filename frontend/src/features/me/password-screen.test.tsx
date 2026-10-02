// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  changePassword,
  getMockProfile,
  loginWithEmail,
  resetMockSession,
  signup,
} from '@/features/auth/auth-client'
import { consentFor } from '@/features/auth/legal'
import { resetMemberInfoForTests, startMemberInfo } from '@/features/auth/member-info'
import { resetSessionForTests, setSession } from '@/lib/session/session-store'
import { NavTrailProvider } from '@/lib/use-nav-trail'
import {
  errorResponse,
  holdRequests,
  memberToken,
  myInfoBody,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { MeScreen } from './me-screen'
import { MeTrailProvider } from './me-trail'
import { PasswordScreen } from './password-screen'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/me/password'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return {
    ...actual,
    changePassword: vi.fn(actual.changePassword),
  }
})

// 앱에서는 루트 레이아웃의 NavTrailProvider(앱 안 이동 기록)가 내 정보 레이아웃을 감싼다
function withTrail(children: ReactNode) {
  return (
    <NavTrailProvider>
      <MeTrailProvider>{children}</MeTrailProvider>
    </NavTrailProvider>
  )
}

function renderPassword(props: { regionCode?: string } = {}) {
  return render(withTrail(<PasswordScreen regionName="○○동" {...props} />))
}

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: () => void = () => {}
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = () => fail(new Error('mock failure'))
  })
  return { promise, resolve, reject }
}

const submitButton = (name = '비밀번호 바꾸기') => screen.getByRole('button', { name })

async function fillChange(
  user: ReturnType<typeof userEvent.setup>,
  {
    current = 'dongne2026',
    next = 'newpass2026',
    confirm = next,
  }: { current?: string; next?: string; confirm?: string } = {},
) {
  await user.type(screen.getByLabelText('현재 비밀번호'), current)
  await user.type(screen.getByLabelText('새 비밀번호'), next)
  await user.type(screen.getByLabelText('새 비밀번호 확인'), confirm)
}

beforeEach(async () => {
  search = ''
  pathname = '/me/password'
  resetMockSession()
  vi.clearAllMocks()
  await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('PasswordScreen 이메일 회원 — 비밀번호 변경', () => {
  it('현재 · 새 · 확인 세 칸과 규칙 도움말, 비밀번호를 잊었어요를 보인다', () => {
    renderPassword()

    expect(screen.getAllByRole('heading', { level: 1, name: '비밀번호 변경' })).toHaveLength(2)
    expect(screen.getByLabelText('현재 비밀번호').getAttribute('autocomplete')).toBe(
      'current-password',
    )
    expect(screen.getByText('8~20자 · 영문과 숫자 포함 · 띄어쓰기 없이')).toBeDefined()
    expect(screen.getByLabelText('새 비밀번호 확인')).toBeDefined()
    // 빈 칸이 있으면 꺼져 있다
    expect(submitButton().getAttribute('aria-disabled')).toBe('true')
  })

  it('주소로 바로 들어와 바꾸면 칸을 비우고 내 정보로 기록을 바꿔 간다 (동네 · 덮어쓰기는 남기고 알림은 주소에 넣지 않는다)', async () => {
    search = 'region=11440660&mock-provider=email'
    renderPassword({ regionCode: '11440660' })
    const user = userEvent.setup()

    await fillChange(user)
    await user.click(submitButton())
    expect(changePassword).toHaveBeenCalledWith('dongne2026', 'newpass2026', 'mock')
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660&mock-provider=email')
    expect(router.push).not.toHaveBeenCalled()
    for (const label of ['현재 비밀번호', '새 비밀번호', '새 비밀번호 확인']) {
      expect(screen.getByLabelText<HTMLInputElement>(label).value).toBe('')
    }
  })

  it('비밀번호는 주소 · 목 세션 · 프로필 어디에도 남지 않는다', async () => {
    renderPassword()
    const user = userEvent.setup()
    await fillChange(user, { current: 'secret2026', next: 'hidden2026' })
    await user.click(submitButton())

    const destination = vi.mocked(router.replace).mock.calls[0]?.[0] ?? ''
    expect(destination).not.toMatch(/secret2026|hidden2026/)
    expect(JSON.stringify(getMockProfile())).not.toMatch(/secret2026|hidden2026/)
    expect(window.location.href).not.toMatch(/secret2026|hidden2026/)
  })

  it('현재 비밀번호가 맞지 않으면(wrong) 그 칸 아래 알리고 그 칸으로 포커스를 옮긴다 — 고치면 지운다', async () => {
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user, { current: 'wrong' })
    await user.click(submitButton())
    const current = screen.getByLabelText('현재 비밀번호')
    expect(current.getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByText('현재 비밀번호가 맞지 않아요.')).toBeDefined()
    expect(document.activeElement).toBe(current)
    expect(router.replace).not.toHaveBeenCalled()

    await user.type(current, '1')
    expect(screen.queryByText('현재 비밀번호가 맞지 않아요.')).toBeNull()
  })

  it('규칙에 맞지 않으면(rule) 버튼을 누를 때 알리고 보내지 않는다', async () => {
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user, { next: 'short1' })
    await user.click(submitButton())
    expect(screen.getByText('영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.')).toBeDefined()
    expect(screen.getByLabelText('새 비밀번호').getAttribute('aria-invalid')).toBe('true')
    expect(changePassword).not.toHaveBeenCalled()
    // 오류가 있는 동안 버튼이 꺼진다
    expect(submitButton().getAttribute('aria-disabled')).toBe('true')
  })

  it('확인이 다르면(mismatch) 알리고, 맞게 고치면 바로 지운다', async () => {
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user, { next: 'newpass2026', confirm: 'newpass2027' })
    await user.click(submitButton())
    expect(screen.getByText('비밀번호가 서로 달라요.')).toBeDefined()
    expect(changePassword).not.toHaveBeenCalled()

    const confirm = screen.getByLabelText('새 비밀번호 확인')
    await user.clear(confirm)
    await user.type(confirm, 'newpass2026')
    expect(screen.queryByText('비밀번호가 서로 달라요.')).toBeNull()
    expect(submitButton().getAttribute('aria-disabled')).toBeNull()
  })

  it('새 비밀번호가 현재와 같아도 막지 않는다 (백엔드 계약에 없음)', async () => {
    renderPassword()
    const user = userEvent.setup()
    await fillChange(user, { current: 'dongne2026', next: 'dongne2026' })
    await user.click(submitButton())
    expect(changePassword).toHaveBeenCalledWith('dongne2026', 'dongne2026', 'mock')
  })

  it('보내지 못하면(password-fail@example.com) 빨강 상자로 알리고 다시 누를 수 있다', async () => {
    await loginWithEmail('password-fail@example.com', 'dongne2026', 'mock')
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user)
    await user.click(submitButton())
    expect(screen.getByRole('alert').textContent).toContain(
      '비밀번호를 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(submitButton().getAttribute('aria-disabled')).toBeNull()
    await user.click(submitButton())
    expect(changePassword).toHaveBeenCalledTimes(2)
  })

  it('현재 비밀번호 확인이 잠기면(locked) 회색 상자로 알리고, 칸을 고치면 지운다', async () => {
    vi.mocked(changePassword).mockResolvedValueOnce({ status: 'locked' })
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user)
    await user.click(submitButton())
    expect(screen.getByRole('alert').textContent).toBe(
      '비밀번호 확인 시도가 많아 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('현재 비밀번호'), '1')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('서버가 새 비밀번호를 규칙 위반으로 거절하면(invalid-password) 그 칸 아래 규칙 오류를 보이고 고칠 때까지 버튼을 끈다', async () => {
    vi.mocked(changePassword).mockResolvedValueOnce({ status: 'invalid-password' })
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user)
    await user.click(submitButton())
    expect(screen.getByLabelText('새 비밀번호').getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByText('영문과 숫자를 함께 8~20자로, 띄어쓰기 없이 써 주세요.')).toBeDefined()
    expect(submitButton().getAttribute('aria-disabled')).toBe('true')

    await user.type(screen.getByLabelText('새 비밀번호'), '1')
    expect(screen.getByLabelText('새 비밀번호').getAttribute('aria-invalid')).toBeNull()
  })

  it('비밀번호 없는 계정(no-password)이면 빨강 상자로 알린다', async () => {
    vi.mocked(changePassword).mockResolvedValueOnce({ status: 'no-password' })
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user)
    await user.click(submitButton())
    expect(screen.getByRole('alert').textContent).toContain('비밀번호를 바꾸지 못했어요.')
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('보내는 중에는 칸 · 버튼 · 뒤로를 꺼 두 번 보내지 않는다', async () => {
    const pending = deferred<authClient.ChangePasswordResult>()
    vi.mocked(changePassword).mockReturnValueOnce(pending.promise)
    renderPassword()
    const user = userEvent.setup()

    await fillChange(user)
    await user.click(submitButton())
    expect(submitButton().getAttribute('aria-disabled')).toBe('true')
    expect(screen.getByLabelText('새 비밀번호').hasAttribute('readonly')).toBe(true)
    const back = screen.getByRole('button', { name: '뒤로' })
    expect(back.getAttribute('aria-disabled')).toBe('true')
    expect(
      screen.getByRole('button', { name: '내 정보로 돌아가기' }).getAttribute('aria-disabled'),
    ).toBe('true')
    expect(
      screen.getByRole('button', { name: '비밀번호를 잊었어요' }).getAttribute('aria-disabled'),
    ).toBe('true')

    await user.click(submitButton())
    await user.click(back)
    expect(changePassword).toHaveBeenCalledTimes(1)
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).not.toHaveBeenCalled()

    await act(async () => {
      pending.resolve({ status: 'ok' })
      await pending.promise.catch(() => {})
    })
    expect(router.replace).toHaveBeenCalledWith('/me')
  })

  it('응답 전에 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    const pending = deferred<authClient.ChangePasswordResult>()
    vi.mocked(changePassword).mockReturnValueOnce(pending.promise)
    const { unmount } = renderPassword()
    const user = userEvent.setup()
    await fillChange(user)
    await user.click(submitButton())
    unmount()

    await act(async () => {
      pending.resolve({ status: 'ok' })
      await pending.promise.catch(() => {})
    })
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('비밀번호를 잊었어요는 비밀번호 재설정으로 간다', async () => {
    renderPassword()
    await userEvent.setup().click(screen.getByRole('button', { name: '비밀번호를 잊었어요' }))
    expect(router.push).toHaveBeenCalledWith('/password/reset')
  })
})

describe('PasswordScreen 비밀번호가 없는 회원(카카오로만 로그인) — 화면 없음 (#166)', () => {
  beforeEach(async () => {
    resetMockSession()
    await signup({ kind: 'kakao', consents: [consentFor('TERMS_OF_SERVICE')] }, 'mock')
  })

  it('주소로 들어오면 그리지 않고 내 정보로 기록을 바꿔 간다 — 설정 화면이 없다', () => {
    search = 'region=11440660'
    const { container } = renderPassword({ regionCode: '11440660' })
    expect(container.textContent).toBe('')
    expect(screen.queryByLabelText('현재 비밀번호')).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660')
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('?mock-provider=kakao 덮어쓰기(예시 카카오 프로필)도 같다', () => {
    resetMockSession()
    search = 'mock-auth=member&mock-provider=kakao'
    const { container } = renderPassword()
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/me?mock-auth=member&mock-provider=kakao')
  })
})

describe('PasswordScreen 회원 가드 · 뒤로 가기', () => {
  it('비회원이 주소로 들어오면 로그인으로 기록을 바꿔 간다', () => {
    resetMockSession()
    const { container } = renderPassword()
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/login')
  })

  it('주소로 바로 들어왔으면 내 정보로 기록을 바꿔 가고, 앱 안에서 왔으면 기록을 되돌린다', async () => {
    search = 'region=11440660'
    const { unmount } = renderPassword({ regionCode: '11440660' })
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660')
    unmount()

    pathname = '/me'
    const { rerender } = render(withTrail(<div />))
    pathname = '/me/password'
    rerender(withTrail(<PasswordScreen regionName="○○동" />))
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledTimes(1)
  })
})

describe('PasswordScreen → 내 정보 (알림 · 기록)', () => {
  const meScreen = () => withTrail(<MeScreen regionName="○○동" />)
  const passwordScreen = () => withTrail(<PasswordScreen regionName="○○동" />)

  async function change(user: ReturnType<typeof userEvent.setup>) {
    await fillChange(user)
    await user.click(submitButton())
  }

  it('내 정보에서 왔으면 성공 뒤 기록을 되돌리고(/me 가 두 번 남지 않음), 내 정보가 알림을 한 번 띄운다', async () => {
    const user = userEvent.setup()
    pathname = '/me'
    const { rerender } = render(meScreen())
    pathname = '/me/password'
    rerender(passwordScreen())

    await change(user)
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()

    // 기록을 되돌려 내 정보가 다시 그려진다
    pathname = '/me'
    rerender(meScreen())
    expect(screen.getAllByText('비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요')).toHaveLength(
      1,
    )

    // 알림은 비워졌다 — 계정 화면에 갔다 돌아와도 다시 뜨지 않는다
    pathname = '/me/devices'
    rerender(withTrail(<div />))
    pathname = '/me'
    rerender(meScreen())
    expect(screen.queryByText('비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요')).toBeNull()
  })

  it('주소로 바로 들어왔으면 성공 뒤 내 정보로 기록을 바꿔 가고 거기서 알림을 띄운다', async () => {
    const user = userEvent.setup()
    const { rerender } = render(passwordScreen())

    await change(user)
    expect(router.replace).toHaveBeenCalledWith('/me')
    expect(router.back).not.toHaveBeenCalled()

    pathname = '/me'
    rerender(meScreen())
    expect(
      screen
        .getByText('비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요')
        .closest('[role="status"]'),
    ).not.toBeNull()
  })
})

describe('PasswordScreen 실데이터 (#164)', () => {
  let stopMemberInfo: () => void = () => {}
  beforeEach(() => {
    resetSessionForTests()
    resetMemberInfoForTests()
    stopMemberInfo = startMemberInfo()
    selectApiSource()
  })
  afterEach(() => {
    stopMemberInfo()
    resetMemberInfoForTests()
    resetApiSession()
  })

  const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))

  it('프로필을 읽는 동안은 그리지 않고, 읽지 못하면 오류 화면 · 다시 시도로 받으면 폼을 그린다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderPassword()
    await flush()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('heading', { name: '정보를 불러오지 못했어요' })).toBeNull()

    act(() => server.reply('GET /api/v1/members/me', errorResponse('GATEWAY_003', 503)))
    await flush()
    expect(screen.getByRole('heading', { name: '정보를 불러오지 못했어요' })).toBeDefined()

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    act(() => server.reply('GET /api/v1/members/me', okResponse(myInfoBody())))
    await flush()
    expect(
      screen.getAllByRole('heading', { level: 1, name: '비밀번호 변경' }).length,
    ).toBeGreaterThan(0)
  })

  it('비밀번호가 없는 회원(hasPassword false)이면 그리지 않고 내 정보로 돌려보낸다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderPassword()
    await flush()
    act(() =>
      server.reply(
        'GET /api/v1/members/me',
        okResponse(myInfoBody({ provider: 'KAKAO', hasPassword: false })),
      ),
    )
    await flush()
    expect(screen.queryByLabelText('현재 비밀번호')).toBeNull()
    expect(router.replace).toHaveBeenCalledWith('/me')
  })

  it('출처 api 로 바꾸고, 서버가 비밀번호 없는 계정(MEMBER_007)이라 하면 내 정보를 다시 읽어 내 정보로 되돌아간다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    // 내 정보에서 왔다
    pathname = '/me'
    const { rerender } = render(withTrail(<div />))
    pathname = '/me/password'
    rerender(withTrail(<PasswordScreen regionName="○○동" />))
    await flush()
    act(() => server.reply('GET /api/v1/members/me', okResponse(myInfoBody())))
    await flush()

    const user = userEvent.setup()
    await fillChange(user)
    await user.click(submitButton())
    await flush()
    expect(changePassword).toHaveBeenCalledWith('dongne2026', 'newpass2026', 'api')

    act(() => server.reply('POST /api/v1/members/me/password', errorResponse('MEMBER_007', 409)))
    await flush()
    expect(screen.getByRole('alert').textContent).toContain('비밀번호를 바꾸지 못했어요.')
    // 다시 읽는 동안에는 화면을 그대로 둔다
    expect(server.requests().filter((key) => key === 'GET /api/v1/members/me')).toHaveLength(2)
    expect(router.replace).not.toHaveBeenCalled()

    act(() =>
      server.reply(
        'GET /api/v1/members/me',
        okResponse(myInfoBody({ provider: 'KAKAO', hasPassword: false })),
      ),
    )
    await flush()
    expect(screen.queryByLabelText('현재 비밀번호')).toBeNull()
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })
})
