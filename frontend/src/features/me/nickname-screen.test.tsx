// @vitest-environment jsdom
import type { ReactNode } from 'react'

import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from '@/features/auth/auth-client'
import {
  getMockProfile,
  loginWithEmail,
  resetMockSession,
  updateNickname,
} from '@/features/auth/auth-client'
import {
  getMemberInfoSnapshot,
  resetMemberInfoForTests,
  startMemberInfo,
} from '@/features/auth/member-info'
import { getSessionSnapshot, resetSessionForTests, setSession } from '@/lib/session/session-store'
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
import { NicknameScreen } from './nickname-screen'

// 테스트에는 Next 라우터가 없다. 주소 · 경로는 이 값으로 흉내 낸다
let search = ''
let pathname = '/me/nickname'
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
  usePathname: () => pathname,
  useRouter: () => router,
}))

vi.mock('@/features/auth/auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, updateNickname: vi.fn(actual.updateNickname) }
})

// 앱에서는 루트 레이아웃의 NavTrailProvider(앱 안 이동 기록)가 내 정보 레이아웃을 감싼다
function withTrail(children: ReactNode) {
  return (
    <NavTrailProvider>
      <MeTrailProvider>{children}</MeTrailProvider>
    </NavTrailProvider>
  )
}

const nicknameScreen = (regionCode?: string) =>
  withTrail(<NicknameScreen regionName="○○동" {...(regionCode ? { regionCode } : {})} />)

function renderNickname(regionCode?: string) {
  return render(nicknameScreen(regionCode))
}

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

const RULE_ERROR = '닉네임은 2~10자로 써 주세요.'
const saveButton = () => screen.getByRole('button', { name: '이 닉네임으로 바꾸기' })
const field = () => screen.getByLabelText<HTMLInputElement>('닉네임')

async function retype(user: ReturnType<typeof userEvent.setup>, value: string) {
  await user.clear(field())
  if (value !== '') await user.type(field(), value)
}

beforeEach(async () => {
  search = ''
  pathname = '/me/nickname'
  resetMockSession()
  vi.clearAllMocks()
  await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('NicknameScreen 닉네임 바꾸기', () => {
  it('머리줄 알림(종)은 동네 · 덮어쓰기를 남긴 채 알림 설정으로 간다', async () => {
    search =
      'region=11440660&mock-auth=member&mock-provider=email&mock-push=supported&confirm=unknown'
    renderNickname('11440660')

    const [bell] = await screen.findAllByRole('button', { name: '알림 설정' })
    await userEvent.setup().click(bell as HTMLElement)
    expect(router.push).toHaveBeenCalledWith(
      '/me/notifications?region=11440660&mock-auth=member&mock-provider=email&mock-push=supported',
    )
  })

  it('제목 · 지금 닉네임을 채운 칸 · 가입과 같은 도움말 · 글자 수를 보이고, 바꾸기 전에는 버튼이 꺼져 있다', () => {
    renderNickname()

    expect(screen.getAllByRole('heading', { level: 1, name: '닉네임 바꾸기' })).toHaveLength(2)
    expect(field().value).toBe('동네지기')
    expect(field().getAttribute('autocomplete')).toBe('nickname')
    expect(screen.getByText('내 정보에서만 보여요. 다른 사람에게는 보이지 않아요.')).toBeDefined()
    expect(screen.getByText('4/10')).toBeDefined()
    expect(saveButton().getAttribute('aria-disabled')).toBe('true')
  })

  it('비었거나 공백만 있거나 앞뒤 공백만 다르면(지금과 같음) 버튼이 꺼진 채 보내지 않는다', async () => {
    renderNickname()
    const user = userEvent.setup()

    for (const value of ['', '   ', ' 동네지기 ']) {
      await retype(user, value)
      expect(saveButton().getAttribute('aria-disabled')).toBe('true')
      await user.click(saveButton())
    }
    expect(updateNickname).not.toHaveBeenCalled()
  })

  it('주소로 바로 들어와 바꾸면 목 프로필을 바꾸고 내 정보로 기록을 바꿔 간다 (동네 · 덮어쓰기를 남긴다) — Enter 로도 보낸다', async () => {
    search = 'region=11440660&mock-provider=email'
    renderNickname('11440660')
    const user = userEvent.setup()

    await retype(user, ' 재채기탐정 ')
    expect(saveButton().getAttribute('aria-disabled')).toBeNull()
    await user.type(field(), '{Enter}')

    expect(updateNickname).toHaveBeenCalledWith(' 재채기탐정 ', 'mock')
    expect(getMockProfile()?.nickname).toBe('재채기탐정')
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660&mock-provider=email')
    expect(router.push).not.toHaveBeenCalled()
  })

  it('규칙에 맞지 않으면(1자 · 11자) 버튼을 누를 때 알리고 보내지 않는다 — 고치면 지운다', async () => {
    renderNickname()
    const user = userEvent.setup()

    await retype(user, '가')
    expect(screen.queryByText(RULE_ERROR)).toBeNull()
    await user.click(saveButton())
    expect(screen.getByText(RULE_ERROR)).toBeDefined()
    expect(field().getAttribute('aria-invalid')).toBe('true')
    expect(saveButton().getAttribute('aria-disabled')).toBe('true')

    await retype(user, '열한글자를넘는닉네임이다')
    expect(screen.getByText('12/10')).toBeDefined()
    expect(screen.getByText(RULE_ERROR)).toBeDefined()
    expect(updateNickname).not.toHaveBeenCalled()

    await retype(user, '재채기탐정')
    expect(screen.queryByText(RULE_ERROR)).toBeNull()
    expect(saveButton().getAttribute('aria-disabled')).toBeNull()
  })

  it('보내지 못하면(nickname-fail@example.com) 빨강 상자로 알리고 다시 누를 수 있다 — 칸을 고치면 지운다', async () => {
    await loginWithEmail('nickname-fail@example.com', 'dongne2026', 'mock')
    renderNickname()
    const user = userEvent.setup()

    await retype(user, '재채기탐정')
    await user.click(saveButton())
    expect((await screen.findByRole('alert')).textContent).toBe(
      '닉네임을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(router.replace).not.toHaveBeenCalled()
    expect(getMockProfile()?.nickname).toBe('동네지기')
    expect(saveButton().getAttribute('aria-disabled')).toBeNull()

    await user.click(saveButton())
    expect(updateNickname).toHaveBeenCalledTimes(2)
    await user.type(field(), '1')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('서버가 규칙 위반으로 거절하면(invalid) 칸 아래 규칙 문구를 보이고 고칠 때까지 버튼을 끈다', async () => {
    vi.mocked(updateNickname).mockResolvedValueOnce({ status: 'invalid' })
    renderNickname()
    const user = userEvent.setup()

    await retype(user, '재채기탐정')
    await user.click(saveButton())
    expect(screen.getByText(RULE_ERROR)).toBeDefined()
    expect(saveButton().getAttribute('aria-disabled')).toBe('true')
    expect(router.replace).not.toHaveBeenCalled()

    await user.type(field(), '1')
    expect(screen.queryByText(RULE_ERROR)).toBeNull()
    expect(saveButton().getAttribute('aria-disabled')).toBeNull()
  })

  it('보내는 중에는 칸 · 버튼 · 뒤로를 꺼 두 번 보내지 않는다', async () => {
    const pending = deferred<authClient.UpdateNicknameResult>()
    vi.mocked(updateNickname).mockReturnValueOnce(pending.promise)
    renderNickname()
    const user = userEvent.setup()

    await retype(user, '재채기탐정')
    await user.click(saveButton())
    expect(field().hasAttribute('readonly')).toBe(true)
    expect(saveButton().getAttribute('aria-disabled')).toBe('true')
    const back = screen.getByRole('button', { name: '뒤로' })
    expect(back.getAttribute('aria-disabled')).toBe('true')
    expect(
      screen.getByRole('button', { name: '내 정보로 돌아가기' }).getAttribute('aria-disabled'),
    ).toBe('true')

    await user.click(saveButton())
    await user.click(back)
    expect(updateNickname).toHaveBeenCalledTimes(1)
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).not.toHaveBeenCalled()

    await act(async () => {
      pending.resolve({ status: 'ok' })
      await pending.promise
    })
    expect(router.replace).toHaveBeenCalledWith('/me')
  })

  it('응답 전에 화면을 떠나면 늦은 응답으로 이동하지 않는다', async () => {
    const pending = deferred<authClient.UpdateNicknameResult>()
    vi.mocked(updateNickname).mockReturnValueOnce(pending.promise)
    const { unmount } = renderNickname()
    const user = userEvent.setup()
    await retype(user, '재채기탐정')
    await user.click(saveButton())
    unmount()

    await act(async () => {
      pending.resolve({ status: 'ok' })
      await pending.promise
    })
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('NicknameScreen 가드 · 내 정보로 돌아가기', () => {
  it('비회원이 주소로 들어오면 로그인(돌아올 곳 /me/nickname)으로 기록을 바꿔 간다', () => {
    resetMockSession()
    const { container } = renderNickname()
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme%2Fnickname')
  })

  it('주소로 바로 들어왔으면 뒤로가 내 정보로 기록을 바꿔 간다', async () => {
    search = 'region=11440660&mock-auth=member'
    renderNickname('11440660')
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/me?region=11440660&mock-auth=member')
  })

  it('내 정보에서 왔으면 바꾼 뒤 기록을 되돌리고, 내 정보가 바뀐 닉네임과 알림을 한 번 보인다', async () => {
    const user = userEvent.setup()
    const meScreen = () => withTrail(<MeScreen regionName="○○동" />)
    pathname = '/me'
    const { rerender } = render(meScreen())
    pathname = '/me/nickname'
    rerender(nicknameScreen())

    await retype(user, '재채기탐정')
    await user.click(saveButton())
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()

    // 기록을 되돌려 내 정보가 다시 그려진다
    pathname = '/me'
    rerender(meScreen())
    expect(screen.getByText('닉네임을 바꿨어요').closest('[role="status"]')).not.toBeNull()
    expect(screen.getByRole('link', { name: /^닉네임/ }).textContent).toBe('닉네임재채기탐정')

    // 알림은 비워졌다 — 계정 화면에 갔다 돌아와도 다시 뜨지 않는다
    pathname = '/me/devices'
    rerender(withTrail(<div />))
    pathname = '/me'
    rerender(meScreen())
    expect(screen.queryByText('닉네임을 바꿨어요')).toBeNull()
  })
})

describe('NicknameScreen 실데이터 (#192)', () => {
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

  /** 요청이 나가고 응답의 then 이 돌 때까지 */
  const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))
  const storedNickname = () => {
    const info = getMemberInfoSnapshot()?.info
    return info?.status === 'ready' ? info.value.nickname : null
  }

  it('프로필을 읽는 동안은 그리지 않고, 읽지 못하면 오류 화면 · 다시 시도로 받으면 서버 닉네임을 채운다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderNickname()
    await flush()
    // 목 프로필(동네지기)을 쓰지 않는다
    expect(screen.queryByLabelText('닉네임')).toBeNull()

    act(() => server.reply('GET /api/v1/members/me', errorResponse('GATEWAY_003', 503)))
    await flush()
    expect(screen.getByRole('heading', { name: '정보를 불러오지 못했어요' })).toBeDefined()

    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    await flush()
    act(() => server.reply('GET /api/v1/members/me', okResponse(myInfoBody())))
    await flush()
    expect(field().value).toBe('재채기탐정')
  })

  it('PATCH 로 보내고, 응답을 회원 정보 저장소에 넣은 뒤 내 정보로 간다', async () => {
    const server = holdRequests()
    act(() => setSession(memberToken()))
    renderNickname()
    await flush()
    act(() => server.reply('GET /api/v1/members/me', okResponse(myInfoBody())))
    await flush()

    const user = userEvent.setup()
    await retype(user, '동네탐정')
    await user.click(saveButton())
    await flush()
    expect(updateNickname).toHaveBeenCalledWith('동네탐정', 'api')
    expect(server.requests()).toContain('PATCH /api/v1/members/me')

    act(() =>
      server.reply('PATCH /api/v1/members/me', okResponse(myInfoBody({ nickname: '동네탐정' }))),
    )
    await flush()
    expect(storedNickname()).toBe('동네탐정')
    expect(router.replace).toHaveBeenCalledWith('/me')
    // GET /me 를 다시 보내지 않는다
    expect(server.requests().filter((key) => key === 'GET /api/v1/members/me')).toHaveLength(1)
  })

  /** 회원 정보를 받은 화면에서 새 닉네임을 보내 PATCH 가 붙잡힐 때까지 */
  async function sendNickname(server: ReturnType<typeof holdRequests>) {
    act(() => setSession(memberToken()))
    renderNickname()
    await flush()
    act(() => server.reply('GET /api/v1/members/me', okResponse(myInfoBody())))
    await flush()
    const user = userEvent.setup()
    await retype(user, '동네탐정')
    await user.click(saveButton())
    await flush()
  }

  it('일시 장애(503)면 빨강 상자로 알리고 닉네임은 그대로 · 내 정보를 다시 읽지 않는다', async () => {
    const server = holdRequests()
    await sendNickname(server)

    act(() => server.reply('PATCH /api/v1/members/me', errorResponse('GATEWAY_003', 503)))
    await flush()
    expect(screen.getByRole('alert').textContent).toBe(
      '닉네임을 바꾸지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(storedNickname()).toBe('재채기탐정')
    expect(server.requests().filter((key) => key === 'GET /api/v1/members/me')).toHaveLength(1)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('회원 없음(MEMBER_004)이면 내 정보를 다시 읽고, 그것도 회원 없음이면 세션을 비워 로그인(돌아올 곳 /me/nickname)으로 간다', async () => {
    const server = holdRequests()
    await sendNickname(server)

    act(() => server.reply('PATCH /api/v1/members/me', errorResponse('MEMBER_004', 404)))
    await flush()
    expect(screen.getByRole('alert').textContent).toContain('닉네임을 바꾸지 못했어요.')
    // 회원 정보 저장소가 내 정보를 다시 읽는다 — 세션 판단은 저장소가 한다
    expect(server.requests().filter((key) => key === 'GET /api/v1/members/me')).toHaveLength(2)
    expect(router.replace).not.toHaveBeenCalled()

    act(() => server.reply('GET /api/v1/members/me', errorResponse('MEMBER_004', 404)))
    await flush()
    expect(getSessionSnapshot().status).toBe('guest')
    expect(router.replace).toHaveBeenCalledWith('/login?next=%2Fme%2Fnickname')
  })

  it('서버가 길이 규칙으로 거절하면(MEMBER_102) 칸 아래 규칙 문구를 보이고 닉네임은 그대로다', async () => {
    const server = holdRequests()
    await sendNickname(server)
    act(() => server.reply('PATCH /api/v1/members/me', errorResponse('MEMBER_102', 400)))
    await flush()

    expect(screen.getByText(RULE_ERROR)).toBeDefined()
    expect(storedNickname()).toBe('재채기탐정')
    expect(router.replace).not.toHaveBeenCalled()
  })
})
