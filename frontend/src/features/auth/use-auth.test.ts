// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { resetSessionForTests, restoreSession, setSession } from '@/lib/session/session-store'
import {
  errorResponse,
  holdReissue,
  holdRequests,
  memberToken,
  myInfoBody,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { EXAMPLE_PROFILES, loginWithEmail, resetMockSession } from './auth-client'
import { resetMemberInfoForTests, retryMemberInfo, startMemberInfo } from './member-info'
import { authStateOf, useAuth, useAuthSettled } from './use-auth'
import { useMemberRequirements } from './use-member-requirements'
import { useMockProfile, useMockProfileStatus } from './use-mock-auth'

// 테스트에는 Next 라우터가 없다. 주소 쿼리는 이 값으로 흉내 낸다
let search = ''
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(search),
}))

let stopMemberInfo: () => void = () => {}

beforeEach(() => {
  search = ''
  resetMockSession()
  resetSessionForTests()
  resetMemberInfoForTests()
  stopMemberInfo = startMemberInfo()
})

afterEach(() => {
  stopMemberInfo()
  resetMemberInfoForTests()
  resetApiSession()
})

const INFO = 'GET /api/v1/members/me'
const REGION = 'GET /api/v1/members/me/region'

/** 요청이 나가고 응답의 then 이 돌 때까지 */
const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))

describe('authStateOf', () => {
  it('회원이 아니면(복원 전 · 복원 중 포함) 비회원이다', () => {
    expect(authStateOf({ status: 'idle' })).toBe('guest')
    expect(authStateOf({ status: 'restoring' })).toBe('guest')
    expect(authStateOf({ status: 'guest' })).toBe('guest')
  })

  it('보고를 쓸 수 있으면 member, 아니면 member-no-consent 다', () => {
    const summary = { memberId: '1', role: 'USER', pendingConsents: [] } as const
    expect(authStateOf({ status: 'member', summary: { ...summary, reportWritable: true } })).toBe(
      'member',
    )
    expect(authStateOf({ status: 'member', summary: { ...summary, reportWritable: false } })).toBe(
      'member-no-consent',
    )
  })
})

describe('useAuth', () => {
  it('목데이터 모드는 ?mock-auth= 덮어쓰기와 목 세션을 따른다', async () => {
    search = 'mock-auth=member'
    const { result, rerender } = renderHook(() => useAuth())
    expect(result.current).toBe('member')

    search = ''
    await act(() => loginWithEmail('dong@example.com', 'dongne2026', 'mock'))
    rerender()
    expect(result.current).toBe('member-no-consent')
  })

  it('실데이터 모드는 ?mock-auth= 와 목 세션을 듣지 않고 세션 저장소를 따른다', async () => {
    selectApiSource()
    search = 'mock-auth=member'
    await loginWithEmail('dong@example.com', 'dongne2026', 'mock')
    const { result } = renderHook(() => useAuth())
    expect(result.current).toBe('guest')

    act(() => setSession(memberToken({ reportWritable: false })))
    expect(result.current).toBe('member-no-consent')
  })
})

describe('useAuthSettled', () => {
  it('목데이터 모드는 하이드레이션을 마치면 정해진 것이다', () => {
    const { result } = renderHook(() => useAuthSettled())
    expect(result.current).toBe(true)
  })

  it('실데이터 모드는 복원 전(idle) · 복원 중(restoring)에는 정해지지 않았다', async () => {
    selectApiSource()
    const server = holdReissue()
    const { result } = renderHook(() => useAuthSettled())
    expect(result.current).toBe(false)

    let restoring: Promise<void> = Promise.resolve()
    act(() => {
      restoring = restoreSession()
    })
    expect(result.current).toBe(false)

    // 재로그인으로 끝나면 비회원으로 정해진다
    await act(async () => {
      server.fail('AUTH_014', 401)
      await restoring
    })
    expect(result.current).toBe(true)
  })
})

describe('useMockProfile (실데이터)', () => {
  const profileHook = () =>
    renderHook(() => ({ profile: useMockProfile(), status: useMockProfileStatus() }))

  it('읽는 동안은 예시 프로필 없이 null · loading 이고, 받으면 내 정보를 옮긴다 (?mock-provider= 는 듣지 않는다)', async () => {
    selectApiSource()
    search = 'mock-provider=email'
    const server = holdRequests()
    const { result } = profileHook()
    expect(result.current).toEqual({ profile: null, status: 'ready' })

    act(() => setSession(memberToken({ pendingConsents: ['TERMS_OF_SERVICE'] })))
    expect(result.current).toEqual({ profile: null, status: 'loading' })

    await flush()
    act(() => {
      server.reply(
        INFO,
        okResponse(
          myInfoBody({
            email: 'kakao@example.com',
            nickname: '재채기탐정',
            provider: 'KAKAO',
            hasPassword: false,
            pendingConsents: ['TERMS_OF_SERVICE'],
          }),
        ),
      )
      server.reply(
        REGION,
        okResponse({
          code: '11680640',
          name: '역삼1동',
          sigungu: '서울특별시 강남구',
          abolished: true,
        }),
      )
    })
    await flush()
    expect(result.current).toEqual({
      profile: {
        provider: 'kakao',
        email: 'kakao@example.com',
        nickname: '재채기탐정',
        hasPassword: false,
        region: { code: '11680640', name: '역삼1동' },
        regionAbolished: true,
        termsReconsentRequired: true,
      },
      status: 'ready',
    })
    expect(result.current.profile).not.toEqual(EXAMPLE_PROFILES.email)
  })

  it('읽지 못하면 null · failed 이고, 다시 시도해 받으면 프로필이다', async () => {
    selectApiSource()
    const server = holdRequests()
    const { result } = profileHook()
    act(() => setSession(memberToken()))
    await flush()
    act(() => server.reply(INFO, errorResponse('GATEWAY_003', 503)))
    await flush()
    expect(result.current).toEqual({ profile: null, status: 'failed' })

    act(() => retryMemberInfo())
    expect(result.current.status).toBe('loading')
    await flush()
    act(() => server.reply(INFO, okResponse(myInfoBody())))
    await flush()
    expect(result.current.status).toBe('ready')
    expect(result.current.profile?.email).toBe('me@example.com')
  })

  it('목 세션 프로필이 있어도 실데이터에서는 쓰지 않는다', async () => {
    await loginWithEmail('mock@example.com', 'dongne2026', 'mock')
    selectApiSource()
    holdRequests()
    const { result } = profileHook()
    act(() => setSession(memberToken()))
    expect(result.current).toEqual({ profile: null, status: 'loading' })
  })
})

describe('실데이터 모드의 QA 덮어쓰기', () => {
  it('?mock-required= 를 듣지 않고 재동의할 항목(pendingConsents)으로 거칠 화면을 정한다', async () => {
    selectApiSource()
    search = 'mock-required=region'
    const server = holdRequests()
    act(() => setSession(memberToken()))
    const { result } = renderHook(() => useMemberRequirements())
    expect(result.current.steps).toEqual([])

    act(() => setSession(memberToken({ pendingConsents: ['TERMS_OF_SERVICE'] })))
    // 내 동네를 읽기 전에는 동네 조건을 판단하지 않는다
    expect(result.current).toEqual({ steps: ['terms'], abolishedRegion: null, settled: false })

    await flush()
    act(() =>
      server.reply(
        REGION,
        okResponse({ code: '11680640', name: null, sigungu: null, abolished: true }),
      ),
    )
    await flush()
    expect(result.current).toEqual({
      steps: ['terms', 'region'],
      abolishedRegion: { code: '11680640', name: null },
      settled: true,
    })
  })

  it('목데이터 모드는 ?mock-required= 를 그대로 듣는다', () => {
    search = 'mock-auth=member&mock-required=region'
    const { result } = renderHook(() => useMemberRequirements())
    expect(result.current.steps).toEqual(['region'])
  })
})
