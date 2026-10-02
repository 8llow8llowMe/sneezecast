// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { clearSession, getSessionSnapshot, setSession } from '@/lib/session/session-store'
import { clearSessionExpiring, onSessionExpired } from '@/lib/session-expiry'
import {
  errorResponse,
  holdRequests,
  memberToken,
  myInfoBody,
  okResponse,
  resetApiSession,
} from '@/test/api-session'

import {
  getMemberInfoSnapshot,
  reloadMemberRegion,
  resetMemberInfoForTests,
  retryMemberInfo,
  setMemberRegion,
  startMemberInfo,
} from './member-info'

const INFO = 'GET /api/v1/members/me'
const REGION = 'GET /api/v1/members/me/region'
const YEOKSAM1 = {
  code: '11680640',
  name: '역삼1동',
  sigungu: '서울특별시 강남구',
  abolished: false,
}

/** 요청이 나가고(API 계층은 비동기다) 기다리던 응답의 then 이 돌 때까지 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

let stop: () => void = () => {}

beforeEach(() => {
  resetMemberInfoForTests()
  stop = startMemberInfo()
})

afterEach(() => {
  stop()
  resetMemberInfoForTests()
  resetApiSession()
})

describe('회원 정보 저장소', () => {
  it('비회원이면 읽지 않고, 회원이 되면 내 정보 · 내 동네를 한 번씩 읽는다', async () => {
    const server = holdRequests()
    expect(getMemberInfoSnapshot()).toBeNull()
    expect(server.requests()).toEqual([])

    setSession(memberToken())
    expect(getMemberInfoSnapshot()).toEqual({
      memberId: '1843956734582784',
      info: { status: 'loading' },
      region: { status: 'loading' },
    })
    await flush()
    expect(server.requests()).toEqual([INFO, REGION])

    server.reply(INFO, okResponse(myInfoBody({ provider: 'KAKAO', hasPassword: false })))
    server.reply(REGION, okResponse(YEOKSAM1))
    await flush()
    expect(getMemberInfoSnapshot()).toEqual({
      memberId: '1843956734582784',
      info: {
        status: 'ready',
        value: {
          memberId: '1843956734582784',
          email: 'me@example.com',
          nickname: '재채기탐정',
          provider: 'kakao',
          hasPassword: false,
          pendingConsents: [],
        },
      },
      region: { status: 'ready', value: YEOKSAM1 },
    })
  })

  it('같은 회원이면 다시 읽지 않는다 — 재발급 · 재동의 항목 변경 · 다시 켜기 · 읽는 중 다시 시도', async () => {
    const server = holdRequests()
    setSession(memberToken())
    setSession(memberToken({ accessToken: 'access-2' }))
    setSession(memberToken({ pendingConsents: ['TERMS_OF_SERVICE'] }))
    stop()
    stop = startMemberInfo()
    retryMemberInfo()
    await flush()
    expect(server.requests()).toEqual([INFO, REGION])
  })

  it('내 동네를 아직 고르지 않았으면 ready 에 null 이다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(REGION, okResponse(null))
    await flush()
    expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: null })
  })

  it('두 요청은 따로 실패하고, 다시 시도하면 실패한 쪽만 다시 읽는다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    // 행정동 서비스 장애 — 내 동네만 503 이다
    server.reply(REGION, errorResponse('REGION_004', 503))
    await flush()
    expect(getMemberInfoSnapshot()).toMatchObject({
      info: { status: 'ready' },
      region: { status: 'failed' },
    })

    retryMemberInfo()
    expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'loading' })
    await flush()
    expect(server.requests()).toEqual([INFO, REGION, REGION])
    server.reply(REGION, okResponse(YEOKSAM1))
    await flush()
    expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: YEOKSAM1 })
  })

  it('내 정보가 일시 장애면 failed 이고 세션은 그대로다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(INFO, errorResponse('GATEWAY_004', 504))
    await flush()
    expect(getMemberInfoSnapshot()?.info).toEqual({ status: 'failed' })
    expect(getSessionSnapshot().status).toBe('member')
  })

  it('비회원이 되면 바로 지우고, 그 뒤에 온 응답은 버린다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    clearSession('logout')
    expect(getMemberInfoSnapshot()).toBeNull()

    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(YEOKSAM1))
    await flush()
    expect(getMemberInfoSnapshot()).toBeNull()
  })

  it('다른 회원이 되면 다시 읽고, 앞 회원의 늦은 응답은 버린다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    // 다른 탭에서 다른 회원으로 로그인했다
    setSession(memberToken({ memberId: '2' }))
    expect(getMemberInfoSnapshot()).toMatchObject({ memberId: '2', info: { status: 'loading' } })
    await flush()
    expect(server.requests()).toEqual([INFO, REGION, INFO, REGION])

    // 앞 회원의 응답이 먼저 온다
    server.reply(INFO, okResponse(myInfoBody({ email: 'first@example.com' })))
    server.reply(REGION, okResponse(YEOKSAM1))
    await flush()
    expect(getMemberInfoSnapshot()).toMatchObject({
      memberId: '2',
      info: { status: 'loading' },
      region: { status: 'loading' },
    })

    server.reply(INFO, okResponse(myInfoBody({ memberId: '2', email: 'second@example.com' })))
    await flush()
    expect(getMemberInfoSnapshot()?.info).toMatchObject({
      status: 'ready',
      value: { email: 'second@example.com' },
    })
  })

  it('회원 없음(MEMBER_004)이면 만료 알림 없이 세션을 비우고 저장소도 지운다', async () => {
    const server = holdRequests()
    const expired: unknown[] = []
    const unsubscribe = onSessionExpired(() => expired.push(true))
    setSession(memberToken())
    await flush()
    server.reply(INFO, errorResponse('MEMBER_004', 404))
    await flush()

    expect(getSessionSnapshot().status).toBe('guest')
    expect(getMemberInfoSnapshot()).toBeNull()
    expect(expired).toEqual([])
    unsubscribe()
  })

  it.each(['MEMBER_002', 'MEMBER_003'])(
    '탈퇴 · 정지(%s)면 재발급과 같게 만료로 세션을 끝내고 저장소도 지운다',
    async (code) => {
      const server = holdRequests()
      const expired: unknown[] = []
      const unsubscribe = onSessionExpired(() => expired.push(true))
      setSession(memberToken())
      await flush()
      server.reply(INFO, errorResponse(code, 403))
      await flush()

      expect(getSessionSnapshot().status).toBe('guest')
      expect(getMemberInfoSnapshot()).toBeNull()
      expect(expired).toEqual([true])
      unsubscribe()
      clearSessionExpiring()
    },
  )

  it('저장한 내 동네를 넣으면 그보다 먼저 보낸 조회의 늦은 응답은 버린다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    const saved = { ...YEOKSAM1 }
    setMemberRegion('1843956734582784', saved)
    expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: saved })

    // 로그인 직후 나간 조회가 동네 저장보다 늦게 "아직 고르지 않음" 으로 온다
    await flush()
    server.reply(REGION, okResponse(null))
    await flush()
    expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: saved })
  })

  it('저장을 보낸 회원이 지금 회원이 아니면 넣지 않는다', () => {
    holdRequests()
    setSession(memberToken())
    setMemberRegion('다른 회원', { ...YEOKSAM1 })
    expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'loading' })
  })

  describe('reloadMemberRegion — 보고가 동네로 거절됐을 때 다시 읽기 (#165)', () => {
    const ABOLISHED = { ...YEOKSAM1, abolished: true }

    async function readyMember(server: ReturnType<typeof holdRequests>) {
      setSession(memberToken())
      await flush()
      server.reply(INFO, okResponse(myInfoBody()))
      server.reply(REGION, okResponse(YEOKSAM1))
      await flush()
    }

    it('이미 읽은 내 동네를 다시 읽는다 — 읽는 동안 지금 값을 그대로 두고, 폐지로 바뀌면 넣는다', async () => {
      const server = holdRequests()
      await readyMember(server)

      reloadMemberRegion()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: YEOKSAM1 })
      await flush()
      expect(server.requests()).toEqual([INFO, REGION, REGION])

      server.reply(REGION, okResponse(ABOLISHED))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: ABOLISHED })
    })

    it('다시 읽지 못해도 지금 값을 둔다', async () => {
      const server = holdRequests()
      await readyMember(server)

      reloadMemberRegion()
      await flush()
      server.reply(REGION, errorResponse('REGION_004', 503))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: YEOKSAM1 })
    })

    it('여러 번 부르면 마지막 응답만 넣는다', async () => {
      const server = holdRequests()
      await readyMember(server)

      reloadMemberRegion()
      reloadMemberRegion()
      await flush()
      server.reply(REGION, okResponse(ABOLISHED))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: YEOKSAM1 })
      server.reply(REGION, okResponse(null))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: null })
    })

    it('처음 읽는 중이면 보내지 않고, 읽지 못한 상태면 loading 으로 다시 읽는다', async () => {
      const server = holdRequests()
      setSession(memberToken())
      reloadMemberRegion()
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])

      server.reply(REGION, errorResponse('REGION_004', 503))
      await flush()
      reloadMemberRegion()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'loading' })
      await flush()
      server.reply(REGION, okResponse(YEOKSAM1))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: YEOKSAM1 })
    })
  })
})
