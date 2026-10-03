// @vitest-environment jsdom
import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  getCurrentReportSnapshot,
  resetCurrentReportForTests,
} from '@/features/report/current-report'
import { useSubmittedReport } from '@/features/report/use-submitted-report'
import { resolveAccessToken } from '@/lib/api/access-token'
import { writeBrowserDataSource } from '@/lib/data-source'
import { hasSessionHint } from '@/lib/session/session-hint'
import {
  clearSession,
  getSessionSnapshot,
  resetSessionForTests,
  setSession,
} from '@/lib/session/session-store'
import {
  errorResponse,
  holdRequests,
  memberToken,
  myInfoBody,
  okResponse,
  resetApiSession,
  selectApiSource,
} from '@/test/api-session'

import { getMockSession, loginWithEmail, resetMockSession } from './auth-client'
import { getMemberInfoSnapshot, resetMemberInfoForTests } from './member-info'
import { SessionBootstrap } from './session-bootstrap'
import { useAuth } from './use-auth'
import { useMemberInfo } from './use-member-info'

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
}))

beforeEach(() => {
  resetSessionForTests()
})

afterEach(() => {
  resetMemberInfoForTests()
  resetCurrentReportForTests()
  resetMockSession()
  resetApiSession()
})

describe('SessionBootstrap', () => {
  it('목데이터 모드면 세션을 되살리지 않는다 (idle 그대로)', () => {
    render(<SessionBootstrap />)
    expect(getSessionSnapshot()).toEqual({ status: 'idle' })
  })

  it('실데이터 모드면 첫 커밋에서 바로 되살린다 (힌트가 없으면 요청 없이 비회원)', () => {
    selectApiSource()
    render(<SessionBootstrap />)
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
  })

  it('토글로 실데이터가 되면 그때 되살린다', () => {
    render(<SessionBootstrap />)
    expect(getSessionSnapshot()).toEqual({ status: 'idle' })

    act(() => writeBrowserDataSource('api'))
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
  })

  it('회원 정보 저장소를 세션에 잇는다 — 회원이 되면 읽기 시작하고 비회원이 되면 지운다', () => {
    holdRequests()
    render(<SessionBootstrap />)
    act(() => setSession(memberToken()))
    expect(getMemberInfoSnapshot()).toMatchObject({ info: { status: 'loading' } })
    act(() => clearSession('logout'))
    expect(getMemberInfoSnapshot()).toBeNull()
  })

  it('이번 주 보고 저장소를 세션에 잇는다 — 보고할 수 있는 회원이 되면 읽기 시작하고 비회원이 되면 지운다', () => {
    holdRequests()
    render(<SessionBootstrap />)
    act(() => setSession(memberToken({ reportWritable: false })))
    expect(getCurrentReportSnapshot()).toBeNull()
    act(() => setSession(memberToken()))
    expect(getCurrentReportSnapshot()).toMatchObject({ report: { status: 'loading' } })
    act(() => clearSession('logout'))
    expect(getCurrentReportSnapshot()).toBeNull()
  })

  it('API 계층에 공급자를 끼우고, 해제하면 슬롯을 비운다', async () => {
    const { unmount } = render(<SessionBootstrap />)
    setSession(memberToken({ accessToken: 'access-7' }))
    await expect(resolveAccessToken()).resolves.toBe('access-7')

    unmount()
    await expect(resolveAccessToken()).resolves.toBeNull()
  })
})

/* 뒤로 가기 캐시(bfcache) 복원 (#186) */

const INFO = 'GET /api/v1/members/me'
const REGION = 'GET /api/v1/members/me/region'
const CURRENT = 'GET /api/v1/reports/current'
const REISSUE = 'POST /api/v1/auth/token/reissue'

/** 이번 주 보고 응답(호흡기 증상) */
const SENT_SYMPTOM = {
  isoWeek: '2026-W40',
  districtCode: '11680640',
  symptomGroups: [{ code: 'RESPIRATORY' }],
  reportedAt: '2026-10-01T15:30:00Z',
  updatedAt: '2026-10-01T15:30:00Z',
}

/** 화면이 회원으로 그리는 것: 회원 상태 · 내 정보 별명 · 이번 주 보고 */
function MemberProbe() {
  const auth = useAuth()
  const info = useMemberInfo()
  const report = useSubmittedReport()
  const nickname = info?.info.status === 'ready' ? info.info.value.nickname : '-'
  return <p data-testid="probe">{`${auth}|${nickname}|${report ? report.answer.kind : '-'}`}</p>
}

const probe = () => screen.getByTestId('probe').textContent

/** 요청이 나가고(API 계층은 비동기다) 기다리던 응답의 then 이 돌 때까지 */
const flush = () => act(() => new Promise((resolve) => setTimeout(resolve, 0)))

const pageshow = (persisted: boolean) =>
  act(() => {
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted }))
  })

describe('SessionBootstrap — 뒤로 가기 캐시에서 되살아날 때(pageshow persisted)', () => {
  /** 실데이터 회원 화면을 띄운다: 세션 · 내 정보 · 이번 주 보고(증상)까지 받은 상태 */
  async function renderMember() {
    selectApiSource()
    const server = holdRequests()
    render(
      <>
        <SessionBootstrap />
        <MemberProbe />
      </>,
    )
    act(() => setSession(memberToken()))
    await flush()
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(null))
    server.reply(CURRENT, okResponse(SENT_SYMPTOM))
    await flush()
    expect(probe()).toBe('member|재채기탐정|symptom')
    server.fetchMock.mockClear()
    return server
  }

  it('회원이면 바로 가린다 — 다시 확인하는 동안 회원 상태 · 내 정보 · 증상이 보이지 않고 저장소도 비운다', async () => {
    const server = await renderMember()

    pageshow(true)

    expect(getSessionSnapshot()).toEqual({ status: 'restoring' })
    expect(probe()).toBe('guest|-|-')
    expect(getMemberInfoSnapshot()).toBeNull()
    expect(getCurrentReportSnapshot()).toBeNull()
    await flush()
    expect(server.requests()).toEqual([REISSUE])
  })

  it('다시 확인에 성공하면 회원으로 돌아오고 내 정보 · 이번 주 보고를 서버에서 다시 읽는다', async () => {
    const server = await renderMember()

    pageshow(true)
    await flush()
    server.reply(REISSUE, okResponse(memberToken({ accessToken: 'access-2' })))
    await flush()

    expect(getSessionSnapshot().status).toBe('member')
    expect(server.requests()).toEqual(expect.arrayContaining([REISSUE, INFO, REGION, CURRENT]))
    server.reply(INFO, okResponse(myInfoBody()))
    server.reply(REGION, okResponse(null))
    server.reply(CURRENT, okResponse(SENT_SYMPTOM))
    await flush()
    expect(probe()).toBe('member|재채기탐정|symptom')
  })

  it('다른 탭 · 기기에서 로그아웃했으면(재발급 AUTH_014) 비회원이 되고 앞 회원의 정보 · 증상이 다시 보이지 않는다', async () => {
    const server = await renderMember()

    pageshow(true)
    await flush()
    server.reply(REISSUE, errorResponse('AUTH_014', 401))
    await flush()

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(hasSessionHint()).toBe(false)
    expect(probe()).toBe('guest|-|-')
    expect(server.requests()).toEqual([REISSUE])
  })

  it('캐시가 아닌 보통 열기(persisted false)는 다시 확인하지 않는다', async () => {
    const server = await renderMember()

    pageshow(false)
    await flush()

    expect(getSessionSnapshot().status).toBe('member')
    expect(probe()).toBe('member|재채기탐정|symptom')
    expect(server.requests()).toEqual([])
  })

  it('비회원이면 요청하지 않는다', async () => {
    selectApiSource()
    const server = holdRequests()
    render(<SessionBootstrap />)
    expect(getSessionSnapshot()).toEqual({ status: 'guest' })

    pageshow(true)
    await flush()

    expect(getSessionSnapshot()).toEqual({ status: 'guest' })
    expect(server.requests()).toEqual([])
  })

  it('목데이터 모드는 그대로다 — 목 회원은 회원으로 남고 요청하지 않는다', async () => {
    const server = holdRequests()
    render(
      <>
        <SessionBootstrap />
        <MemberProbe />
      </>,
    )
    await act(() => loginWithEmail('me@example.com', 'pw-1234', 'mock'))
    const before = getMockSession()
    expect(before).not.toBe('guest')

    pageshow(true)
    await flush()

    expect(getMockSession()).toBe(before)
    expect(getSessionSnapshot()).toEqual({ status: 'idle' })
    expect(server.requests()).toEqual([])
  })

  it('해제하면 pageshow 를 더 듣지 않는다', async () => {
    selectApiSource()
    const server = holdRequests()
    const { unmount } = render(<SessionBootstrap />)
    act(() => setSession(memberToken()))
    unmount()
    await flush()
    server.fetchMock.mockClear()

    pageshow(true)

    expect(getSessionSnapshot().status).toBe('member')
    expect(server.requests()).toEqual([])
  })
})
