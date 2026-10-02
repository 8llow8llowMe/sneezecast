// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { kstIsoWeek } from '@/lib/iso-week'
import { clearSession, getSessionSnapshot, setSession } from '@/lib/session/session-store'
import {
  errorResponse,
  holdRequests,
  memberToken,
  okResponse,
  resetApiSession,
} from '@/test/api-session'

import {
  getCurrentReportSnapshot,
  resetCurrentReportForTests,
  retryCurrentReport,
  setCurrentReport,
  startCurrentReport,
} from './current-report'
import type { SubmittedReport } from './types'

const CURRENT = 'GET /api/v1/reports/current'
const MEMBER_ID = '1843956734582784'
const OTHER_ID = '1843956734582999'

function reportBody(symptomGroups: { code: string }[] = []): Record<string, unknown> {
  return {
    isoWeek: '2026-W40',
    districtCode: '11680640',
    symptomGroups,
    reportedAt: '2026-10-01T15:30:00Z',
    updatedAt: '2026-10-01T15:30:00Z',
  }
}

/** 요청이 나가고(API 계층은 비동기다) 기다리던 응답의 then 이 돌 때까지 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

let stop: () => void = () => {}

beforeEach(() => {
  resetCurrentReportForTests()
  stop = startCurrentReport()
})

afterEach(() => {
  stop()
  resetCurrentReportForTests()
  resetApiSession()
  vi.useRealTimers()
  Reflect.deleteProperty(document, 'visibilityState')
})

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('이번 주 보고 저장소', () => {
  it('비회원이면 읽지 않고, 보고할 수 있는 회원이 되면 한 번 읽는다 — 미보고면 ready 에 null', async () => {
    const server = holdRequests()
    expect(getCurrentReportSnapshot()).toBeNull()

    setSession(memberToken())
    expect(getCurrentReportSnapshot()).toEqual({
      memberId: MEMBER_ID,
      report: { status: 'loading' },
      week: null,
    })
    await flush()
    expect(server.requests()).toEqual([CURRENT])

    server.reply(CURRENT, okResponse(null))
    await flush()
    expect(getCurrentReportSnapshot()).toEqual({
      memberId: MEMBER_ID,
      report: { status: 'ready', value: null },
      // 미보고면 응답에 주가 없어 요청을 보낸 때의 KST 주다
      week: kstIsoWeek(new Date()),
    })
  })

  it('보낸 보고를 읽으면 화면 모델이다 — 보고한 날은 KST 날짜', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(CURRENT, okResponse(reportBody([{ code: 'RESPIRATORY' }])))
    await flush()
    expect(getCurrentReportSnapshot()?.report).toEqual({
      status: 'ready',
      value: { answer: { kind: 'symptom', symptoms: ['respiratory'] }, reportedLabel: '10월 2일' },
    })
  })

  it('보고할 수 없는 회원(reportWritable false)이면 요청 없이 비워 두고, 보고할 수 있게 되면 그때 읽는다', async () => {
    const server = holdRequests()
    setSession(memberToken({ reportWritable: false }))
    await flush()
    expect(server.requests()).toEqual([])
    expect(getCurrentReportSnapshot()).toBeNull()

    setSession(memberToken({ reportWritable: true, accessToken: 'access-2' }))
    await flush()
    expect(server.requests()).toEqual([CURRENT])
  })

  it('보고할 수 없게 되면 바로 지우고, 기다리던 응답은 버린다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()

    setSession(memberToken({ reportWritable: false }))
    expect(getCurrentReportSnapshot()).toBeNull()

    server.reply(CURRENT, okResponse(reportBody()))
    await flush()
    expect(getCurrentReportSnapshot()).toBeNull()
  })

  it('같은 회원이면 다시 읽지 않는다 — 재발급 · 재동의 항목 변경 · 다시 켜기 · 읽는 중 다시 시도', async () => {
    const server = holdRequests()
    setSession(memberToken())
    setSession(memberToken({ accessToken: 'access-2' }))
    setSession(memberToken({ pendingConsents: ['TERMS_OF_SERVICE'], reportWritable: true }))
    stop()
    stop = startCurrentReport()
    retryCurrentReport()
    await flush()
    expect(server.requests()).toEqual([CURRENT])
  })

  it('다른 회원이 되면 다시 읽고, 앞 회원의 늦은 응답은 버린다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()

    setSession(memberToken({ memberId: OTHER_ID, accessToken: 'access-2' }))
    expect(getCurrentReportSnapshot()).toEqual({
      memberId: OTHER_ID,
      report: { status: 'loading' },
      week: null,
    })
    await flush()
    expect(server.requests()).toEqual([CURRENT, CURRENT])

    // 앞 회원 요청의 응답이 먼저 온다 — 버린다
    server.reply(CURRENT, okResponse(reportBody()))
    await flush()
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'loading' })

    server.reply(CURRENT, okResponse(null))
    await flush()
    expect(getCurrentReportSnapshot()).toEqual({
      memberId: OTHER_ID,
      report: { status: 'ready', value: null },
      // 미보고면 응답에 주가 없어 요청을 보낸 때의 KST 주다
      week: kstIsoWeek(new Date()),
    })
  })

  it('로그아웃하면 바로 지우고, 늦은 응답은 버린다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()

    clearSession('logout')
    expect(getCurrentReportSnapshot()).toBeNull()

    server.reply(CURRENT, okResponse(reportBody()))
    await flush()
    expect(getCurrentReportSnapshot()).toBeNull()
  })

  it('읽지 못하면 failed 이고, 다시 시도하면 그때만 다시 읽는다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(CURRENT, errorResponse('GATEWAY_003', 503))
    await flush()
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'failed' })

    retryCurrentReport()
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'loading' })
    await flush()
    server.reply(CURRENT, okResponse(null))
    await flush()
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'ready', value: null })

    // 이미 읽었으면 다시 보내지 않는다
    retryCurrentReport()
    await flush()
    expect(server.requests()).toEqual([CURRENT, CURRENT])
  })

  it('응답에 모르는 증상군이 있으면 버리지 않고 failed 다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(CURRENT, okResponse(reportBody([{ code: 'RESPIRATORY' }, { code: 'SKIN' }])))
    await flush()
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'failed' })
  })

  it('보내기 · 되돌리기 결과는 바로 넣고, 먼저 보낸 조회의 늦은 응답은 덮어쓰지 않는다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    const sent: SubmittedReport = { answer: { kind: 'none' }, reportedLabel: '10월 2일' }

    setCurrentReport(MEMBER_ID, sent)
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'ready', value: sent })

    server.reply(CURRENT, okResponse(null))
    await flush()
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'ready', value: sent })

    setCurrentReport(MEMBER_ID, null)
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'ready', value: null })
  })

  it('보낸 회원이 지금 저장소의 회원이 아니면 넣지 않는다', () => {
    holdRequests()
    setSession(memberToken())
    setCurrentReport(OTHER_ID, { answer: { kind: 'none' }, reportedLabel: '10월 2일' })
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'loading' })
  })

  it('읽기가 보고 권한 없음(SECURITY_006)이면 세션 요약을 다시 맞추고, 보고할 수 없다고 바뀌면 지운다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(CURRENT, errorResponse('SECURITY_006', 403))
    await flush()
    expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'failed' })
    expect(server.requests()).toEqual([CURRENT, 'POST /api/v1/auth/token/reissue'])

    server.reply(
      'POST /api/v1/auth/token/reissue',
      okResponse(memberToken({ accessToken: 'access-2', reportWritable: false })),
    )
    await flush()
    expect(getSessionSnapshot()).toMatchObject({ summary: { reportWritable: false } })
    expect(getCurrentReportSnapshot()).toBeNull()
  })

  it('일시 장애로 읽지 못하면 세션 요약을 다시 맞추지 않는다', async () => {
    const server = holdRequests()
    setSession(memberToken())
    await flush()
    server.reply(CURRENT, errorResponse('GATEWAY_004', 504))
    await flush()
    expect(server.requests()).toEqual([CURRENT])
  })

  describe('주가 바뀌면 다시 읽기 (월요일 00:00 KST)', () => {
    it('다시 보일 때(visible · focus) 같은 주면 요청하지 않고, 주가 바뀌었으면 loading 으로 다시 읽는다', async () => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-10-04T14:00:00Z')) // 일요일 23:00 KST
      const server = holdRequests()
      setSession(memberToken())
      await flush()
      server.reply(CURRENT, okResponse(reportBody()))
      await flush()
      expect(getCurrentReportSnapshot()).toMatchObject({
        report: { status: 'ready' },
        week: '2026-W40',
      })

      window.dispatchEvent(new Event('focus'))
      setVisibility('visible')
      expect(server.requests()).toEqual([CURRENT])

      vi.setSystemTime(new Date('2026-10-04T15:00:01Z')) // 월요일 00:00:01 KST
      // 숨겨질 때는 읽지 않는다
      setVisibility('hidden')
      expect(server.requests()).toEqual([CURRENT])

      setVisibility('visible')
      expect(getCurrentReportSnapshot()?.report).toEqual({ status: 'loading' })
      await flush()
      expect(server.requests()).toEqual([CURRENT, CURRENT])
      // 읽는 중에는 다시 보내지 않는다(single-flight)
      window.dispatchEvent(new Event('focus'))
      await flush()
      expect(server.requests()).toEqual([CURRENT, CURRENT])

      server.reply(CURRENT, okResponse(null))
      await flush()
      expect(getCurrentReportSnapshot()).toMatchObject({
        report: { status: 'ready', value: null },
        week: '2026-W41',
      })
    })

    it('focus 로도 주가 바뀐 것을 알아채고, 그만 받으면(stop) 리스너를 뗀다', async () => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-10-04T14:00:00Z'))
      const server = holdRequests()
      setSession(memberToken())
      await flush()
      server.reply(CURRENT, okResponse(reportBody()))
      await flush()

      vi.setSystemTime(new Date('2026-10-05T01:00:00Z'))
      window.dispatchEvent(new Event('focus'))
      await flush()
      expect(server.requests()).toEqual([CURRENT, CURRENT])
      server.reply(CURRENT, okResponse(null))
      await flush()

      stop()
      stop = () => {}
      vi.setSystemTime(new Date('2026-10-12T01:00:00Z'))
      window.dispatchEvent(new Event('focus'))
      setVisibility('visible')
      await flush()
      expect(server.requests()).toEqual([CURRENT, CURRENT])
    })

    it('보내기 결과는 응답의 주로 둔다', () => {
      holdRequests()
      setSession(memberToken())
      setCurrentReport(
        MEMBER_ID,
        { answer: { kind: 'none' }, reportedLabel: '10월 5일' },
        '2026-W41',
      )
      expect(getCurrentReportSnapshot()?.week).toBe('2026-W41')
    })
  })
})
