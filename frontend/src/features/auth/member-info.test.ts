// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { submitReport } from '@/features/report/report-client'
import {
  clearSession,
  getSessionSnapshot,
  setSession,
  startSession,
} from '@/lib/session/session-store'
import { SESSION_CHANNEL_NAME } from '@/lib/session/session-sync'
import { clearSessionExpiring, onSessionExpired } from '@/lib/session-expiry'
import {
  errorResponse,
  holdRequests,
  memberToken,
  myInfoBody,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { saveRegion } from './auth-client'
import {
  getMemberInfoSnapshot,
  REGION_REFRESH_INTERVAL_MS,
  reloadMemberInfo,
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
  vi.useRealTimers()
  Reflect.deleteProperty(document, 'visibilityState')
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

  describe('reloadMemberInfo — 비밀번호 변경이 비밀번호 없는 계정으로 거절됐을 때 다시 읽기 (#166)', () => {
    async function readyMember(server: ReturnType<typeof holdRequests>) {
      setSession(memberToken())
      await flush()
      server.reply(INFO, okResponse(myInfoBody()))
      server.reply(REGION, okResponse(YEOKSAM1))
      await flush()
    }

    const hasPassword = () => {
      const info = getMemberInfoSnapshot()?.info
      return info?.status === 'ready' ? info.value.hasPassword : null
    }

    it('이미 읽은 내 정보를 다시 읽는다 — 읽는 동안 지금 값을 두고, 받으면 바꾼다(내 동네는 그대로)', async () => {
      const server = holdRequests()
      await readyMember(server)

      reloadMemberInfo()
      expect(hasPassword()).toBe(true)
      await flush()
      expect(server.requests()).toEqual([INFO, REGION, INFO])

      server.reply(INFO, okResponse(myInfoBody({ provider: 'KAKAO', hasPassword: false })))
      await flush()
      expect(hasPassword()).toBe(false)
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'ready', value: YEOKSAM1 })
    })

    it('다시 읽지 못해도 지금 값을 두고, 회원 없음(MEMBER_004)이면 처음 읽을 때처럼 세션을 비운다', async () => {
      const server = holdRequests()
      await readyMember(server)

      reloadMemberInfo()
      await flush()
      server.reply(INFO, errorResponse('MEMBER_009', 503))
      await flush()
      expect(hasPassword()).toBe(true)

      reloadMemberInfo()
      await flush()
      server.reply(INFO, errorResponse('MEMBER_004', 404))
      await flush()
      expect(getSessionSnapshot().status).toBe('guest')
      expect(getMemberInfoSnapshot()).toBeNull()
    })

    it('처음 읽는 중이면 보내지 않고, 읽지 못한 상태면 loading 으로 다시 읽는다', async () => {
      const server = holdRequests()
      setSession(memberToken())
      reloadMemberInfo()
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])

      server.reply(INFO, errorResponse('MEMBER_009', 503))
      await flush()
      reloadMemberInfo()
      expect(getMemberInfoSnapshot()?.info).toEqual({ status: 'loading' })
      await flush()
      server.reply(INFO, okResponse(myInfoBody()))
      await flush()
      expect(hasPassword()).toBe(true)
    })
  })
})

describe('다른 곳에서 바뀐 내 동네 다시 읽기 (#190)', () => {
  const MEMBER_ID = '1843956734582784'
  const YEOKSAM2 = {
    code: '11680650',
    name: '역삼2동',
    sigungu: '서울특별시 강남구',
    abolished: false,
  }
  const SAVE = 'PUT /api/v1/members/me/region'

  async function readyMember(server: ReturnType<typeof holdRequests>) {
    setSession(memberToken())
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(YEOKSAM1))
    await flush()
  }

  const regionValue = () => {
    const region = getMemberInfoSnapshot()?.region
    return region?.status === 'ready' ? region.value : undefined
  }

  describe('다른 탭의 저장 알림', () => {
    type FakeChannel = {
      name: string
      closed: boolean
      onmessage: ((event: MessageEvent) => void) | null
      postMessage: (data: unknown) => void
      close: () => void
    }

    let stopSession: () => void = () => {}
    /** 같은 브라우저의 다른 탭이 연 통로. 이 탭이 보낸 알림을 `received` 에 모은다 */
    let otherTab: FakeChannel
    let received: unknown[] = []

    beforeEach(() => {
      // 같은 이름의 통로끼리 잇는 가짜 BroadcastChannel. 보낸 통로 자신은 받지 않는다(브라우저와 같다)
      const open: FakeChannel[] = []
      function createChannel(name: string): FakeChannel {
        const channel: FakeChannel = {
          name,
          closed: false,
          onmessage: null,
          postMessage(data) {
            for (const other of open) {
              if (other !== channel && other.name === name && !other.closed) {
                other.onmessage?.(new MessageEvent('message', { data: structuredClone(data) }))
              }
            }
          },
          close() {
            channel.closed = true
          },
        }
        open.push(channel)
        return channel
      }
      vi.stubGlobal(
        'BroadcastChannel',
        vi.fn(function (this: unknown, name: string) {
          return createChannel(name)
        }),
      )
      stopSession = startSession()
      otherTab = createChannel(SESSION_CHANNEL_NAME)
      received = []
      otherTab.onmessage = (event) => received.push(event.data)
    })

    afterEach(() => {
      stopSession()
    })

    it('같은 회원의 알림이면 낡은 값을 두지 않고 loading 으로 다시 읽고, 그 뒤 보고 본문의 districtCode 는 새 동네다', async () => {
      const server = holdRequests()
      await readyMember(server)

      otherTab.postMessage({ type: 'region-changed', memberId: MEMBER_ID })
      // 낡았음이 확정이다 — 읽는 동안 보고 흐름이 옛 동네로 보내지 않게 loading 이다(report-flow 가 막는다)
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'loading' })
      await flush()
      expect(server.requests()).toEqual([INFO, REGION, REGION])

      server.reply(REGION, okResponse(YEOKSAM2))
      await flush()
      expect(regionValue()).toEqual(YEOKSAM2)

      // 보고 흐름은 저장소의 내 동네 코드를 보고 동네로 보낸다(report-flow · useMemberRegion)
      void submitReport({ kind: 'none' }, regionValue()?.code ?? null, 'api')
      await flush()
      expect(server.requests()).toContain('PUT /api/v1/reports/current')
      const calls = server.fetchMock.mock.calls as [string, RequestInit | undefined][]
      const put = calls.find(
        ([url, init]) =>
          init?.method === 'PUT' && new URL(url).pathname === '/api/v1/reports/current',
      )
      const body = put?.[1]?.body
      expect(typeof body).toBe('string')
      expect(JSON.parse(body as string)).toMatchObject({ districtCode: '11680650' })
    })

    it('알림 뒤 다시 읽지 못하면 failed 이고(옛 값으로 돌아가지 않는다), 다시 시도로 다시 읽는다', async () => {
      const server = holdRequests()
      await readyMember(server)

      otherTab.postMessage({ type: 'region-changed', memberId: MEMBER_ID })
      await flush()
      server.reply(REGION, errorResponse('REGION_004', 503))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'failed' })

      retryMemberInfo()
      await flush()
      server.reply(REGION, okResponse(YEOKSAM2))
      await flush()
      expect(regionValue()).toEqual(YEOKSAM2)
    })

    it('읽지 못한 상태에서 알림을 받아도 loading 으로 다시 읽는다', async () => {
      const server = holdRequests()
      setSession(memberToken())
      await flush()
      server.reply(INFO, okResponse(myInfoBody()))
      server.reply(REGION, errorResponse('REGION_004', 503))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'failed' })

      otherTab.postMessage({ type: 'region-changed', memberId: MEMBER_ID })
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'loading' })
      await flush()
      server.reply(REGION, okResponse(YEOKSAM2))
      await flush()
      expect(regionValue()).toEqual(YEOKSAM2)
    })

    it.each([
      ['다른 회원의 알림', { type: 'region-changed', memberId: '다른 회원' }],
      ['회원 구분이 없는 알림', { type: 'region-changed' }],
      ['모르는 종류의 알림', { type: 'region-updated', memberId: MEMBER_ID }],
    ])('무시한다 — %s', async (_, data) => {
      const server = holdRequests()
      await readyMember(server)

      otherTab.postMessage(data)
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])
      expect(regionValue()).toEqual(YEOKSAM1)
    })

    it('비회원이면 알림을 무시한다', async () => {
      const server = holdRequests()
      otherTab.postMessage({ type: 'region-changed', memberId: MEMBER_ID })
      await flush()
      expect(server.requests()).toEqual([])
      expect(getMemberInfoSnapshot()).toBeNull()
    })

    it('처음 읽는 중에 알림이 오면 새로 보내고 앞 조회의 응답은 버린다 — 그 조회가 저장보다 먼저 나갔을 수 있다', async () => {
      const server = holdRequests()
      setSession(memberToken())
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])

      otherTab.postMessage({ type: 'region-changed', memberId: MEMBER_ID })
      await flush()
      expect(server.requests()).toEqual([INFO, REGION, REGION])

      server.reply(REGION, okResponse(null))
      await flush()
      expect(getMemberInfoSnapshot()?.region).toEqual({ status: 'loading' })
      server.reply(REGION, okResponse(YEOKSAM2))
      await flush()
      expect(regionValue()).toEqual(YEOKSAM2)
    })

    it('내 동네를 저장한 탭은 값 없이 다른 탭에 알리기만 하고 자신은 다시 읽지 않는다', async () => {
      const server = holdRequests()
      await readyMember(server)
      received = []

      const saving = saveRegion({ code: YEOKSAM2.code, name: YEOKSAM2.name }, 'api')
      await flush()
      server.reply(SAVE, okResponse(YEOKSAM2))
      await expect(saving).resolves.toEqual({ status: 'ok' })
      await flush()

      expect(received).toEqual([{ type: 'region-changed', memberId: MEMBER_ID }])
      expect(server.requests()).toEqual([INFO, REGION, SAVE])
      expect(regionValue()).toEqual(YEOKSAM2)
    })

    it('저장이 실패하면 알리지 않는다', async () => {
      const server = holdRequests()
      await readyMember(server)
      received = []

      const saving = saveRegion({ code: YEOKSAM2.code, name: YEOKSAM2.name }, 'api')
      await flush()
      server.reply(SAVE, errorResponse('REGION_004', 503))
      await expect(saving).rejects.toThrow()
      expect(received).toEqual([])
    })
  })

  describe('화면이 다시 보일 때', () => {
    const T0 = new Date('2026-10-04T05:00:00Z')

    function setVisibility(state: DocumentVisibilityState) {
      Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
    }

    const after = (ms: number) => vi.setSystemTime(new Date(T0.getTime() + ms))

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(T0)
    })

    it('마지막으로 읽은 지 60초 안이면 요청하지 않고, 지나면 지금 값을 둔 채 다시 읽는다', async () => {
      selectApiSource()
      const server = holdRequests()
      await readyMember(server)

      after(REGION_REFRESH_INTERVAL_MS - 1)
      window.dispatchEvent(new Event('focus'))
      setVisibility('visible')
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])

      after(REGION_REFRESH_INTERVAL_MS)
      setVisibility('hidden')
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])

      setVisibility('visible')
      // 보이기 · 포커스가 함께 와도 한 번이다(방금 읽기 시작했다)
      window.dispatchEvent(new Event('focus'))
      expect(regionValue()).toEqual(YEOKSAM1)
      await flush()
      expect(server.requests()).toEqual([INFO, REGION, REGION])

      server.reply(REGION, okResponse(YEOKSAM2))
      await flush()
      expect(regionValue()).toEqual(YEOKSAM2)
    })

    it('다시 읽지 못해도 지금 값을 두고, 다음 60초 동안은 다시 보내지 않는다', async () => {
      selectApiSource()
      const server = holdRequests()
      await readyMember(server)

      after(REGION_REFRESH_INTERVAL_MS)
      window.dispatchEvent(new Event('focus'))
      await flush()
      server.reply(REGION, errorResponse('REGION_004', 503))
      await flush()
      expect(regionValue()).toEqual(YEOKSAM1)

      after(REGION_REFRESH_INTERVAL_MS * 2 - 1)
      window.dispatchEvent(new Event('focus'))
      await flush()
      expect(server.requests()).toEqual([INFO, REGION, REGION])
    })

    it('저장한 응답을 넣은 때부터 잰다', async () => {
      selectApiSource()
      const server = holdRequests()
      await readyMember(server)

      after(50_000)
      setMemberRegion(MEMBER_ID, { ...YEOKSAM2 })
      after(50_000 + REGION_REFRESH_INTERVAL_MS - 1)
      window.dispatchEvent(new Event('focus'))
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])
    })

    it('목데이터 모드 · 비회원 · 처음 읽는 중이면 아무 일도 없다', async () => {
      const server = holdRequests()
      await readyMember(server)

      // 목데이터 모드(출처 쿠키 없음 — 테스트 기본값은 목)에 남은 실데이터 세션
      after(REGION_REFRESH_INTERVAL_MS)
      window.dispatchEvent(new Event('focus'))
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])

      selectApiSource()
      clearSession('logout')
      window.dispatchEvent(new Event('focus'))
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])

      setSession(memberToken())
      after(REGION_REFRESH_INTERVAL_MS * 3)
      window.dispatchEvent(new Event('focus'))
      await flush()
      expect(server.requests()).toEqual([INFO, REGION, INFO, REGION])
    })

    it('그만 받으면(startMemberInfo 의 정리) 리스너도 뗀다', async () => {
      selectApiSource()
      const server = holdRequests()
      await readyMember(server)

      stop()
      after(REGION_REFRESH_INTERVAL_MS)
      window.dispatchEvent(new Event('focus'))
      setVisibility('visible')
      await flush()
      expect(server.requests()).toEqual([INFO, REGION])
    })
  })
})
