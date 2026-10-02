// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { clientEnv } from '@/lib/env.client'

import { cancelReport, getSubmittedReport, submitReport } from './report-client'
import { ReportFlow, resolveStep } from './report-flow'
import type { ReportAnswer } from './types'

// 테스트에는 Next 라우터가 없다. useSearchParams 는 지금 주소를 읽고, 주소가 바뀌면 다시 그려 흉내 낸다
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
}))

const WEEK = {
  regionName: '○○1동',
  weekRangeLabel: '11월 17일~23일',
  reportPeriodLabel: '11월 17일(월)~23일(일)',
}

function Harness({
  regionCode = null,
  reportRegionCode = null,
}: {
  regionCode?: string | null
  reportRegionCode?: string | null
}) {
  return <ReportFlow week={WEEK} regionCode={regionCode} reportRegionCode={reportRegionCode} />
}

/** 11월 19일에 이번 주 보고를 이미 보낸 상태로 둔다 (목 report-client) */
async function sentOnNov19(answer: ReportAnswer) {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2025, 10, 19))
  await submitReport(answer, null, 'mock')
  vi.useRealTimers()
}

/** 주소를 바꾸는 동작 뒤에 다시 그린다 (Next 는 pushState 마다 다시 그린다) */
async function setup(
  initial: ReportAnswer | null = null,
  regionCode: string | null = null,
  reportRegionCode: string | null = null,
) {
  if (initial) await sentOnNov19(initial)
  const user = userEvent.setup()
  const tree = () => <Harness regionCode={regionCode} reportRegionCode={reportRegionCode} />
  const utils = render(tree())
  const refresh = () => utils.rerender(tree())
  return { user, refresh, ...utils }
}

const STEP_START_TITLE = '지난 7일 동안 건강은 어땠나요?'

function title() {
  return document.querySelector('dialog[open] h2')?.textContent
}

/** 목 report-client 가 들고 있는 이번 주 보고 */
function submittedAnswer() {
  const report = getSubmittedReport()
  return report ? JSON.stringify(report.answer) : ''
}

const INSTALL_ROW = /다음 주 월요일에 알려드릴까요/

const SHARE_BUTTON = '우리 동네 자료 함께 채우기'
const SHARE_TITLE = '이렇게 공유돼요'

/** 열린 공유 시트. 완료 대화상자 위에 하나 더 열린다 */
function shareSheet() {
  return [...document.querySelectorAll('dialog[open]')].find(
    (dialog) => dialog.querySelector('h2')?.textContent === SHARE_TITLE,
  ) as HTMLElement | undefined
}

/**
 * 공유 시트의 링크. 시안에 링크 칸이 없어 링크 복사로 클립보드에 쓴 값을 읽는다.
 * user-event 는 setup 때 navigator.clipboard 를 바꾸므로 그 뒤에 끼운다
 */
async function copiedLink(user: ReturnType<typeof userEvent.setup>) {
  const sheet = shareSheet()
  if (!sheet) return null
  const writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(window.navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
    writable: true,
  })
  await user.click(within(sheet).getByRole('button', { name: '링크 복사' }))
  return writeText.mock.calls[0]?.[0] as string | undefined
}

describe('ReportFlow', () => {
  beforeEach(async () => {
    window.history.replaceState(null, '', '/?report=start')
    await cancelReport('mock')
  })

  afterEach(() => {
    Reflect.deleteProperty(window.navigator, 'clipboard')
    vi.restoreAllMocks()
  })

  it('쿼리가 없으면 닫혀 있다', () => {
    window.history.replaceState(null, '', '/')
    render(<Harness />)
    expect(document.querySelector('dialog[open]')).toBeNull()
  })

  it('"증상 없었어요" 는 바로 보내고 완료 화면에 되돌리기 알림을 띄운다 — 완료는 기록 없이 바꾼다', async () => {
    const { user, refresh } = await setup()
    const pushState = vi.spyOn(window.history, 'pushState')

    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    refresh()

    expect(window.location.search).toBe('?report=done')
    expect(pushState).not.toHaveBeenCalled()
    expect(title()).toBe('이번 주 보고를 받았어요')
    expect(submittedAnswer()).toBe('{"kind":"none"}')
    expect(screen.getByRole('status').textContent).toContain('증상 없음으로 보냈어요')
  })

  it('되돌리기를 누르면 보고를 지우고 시작 단계로 돌아간다', async () => {
    const { user, refresh } = await setup()

    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    refresh()
    await user.click(screen.getByRole('button', { name: '되돌리기' }))
    refresh()

    expect(window.location.search).toBe('?report=start')
    expect(submittedAnswer()).toBe('')
    expect(screen.queryByText(/에 보고했어요/)).toBeNull()
  })

  it('증상 고르기 → 확인 → 보내기. 단계로 나아갈 때마다 기록을 쌓는다', async () => {
    const { user, refresh } = await setup()

    await user.click(screen.getByRole('button', { name: '증상이 있었어요' }))
    refresh()
    expect(window.location.search).toBe('?report=symptom')
    expect(title()).toBe('어떤 증상이었나요?')
    expect(screen.getByText('1 / 2')).toBeDefined()

    // 아무것도 고르지 않으면 다음으로 갈 수 없다
    const next = screen.getByRole('button', { name: '다음' })
    expect(next.hasAttribute('disabled')).toBe(true)

    await user.click(screen.getByRole('button', { name: '발열·기침·인후통' }))
    await user.click(screen.getByRole('button', { name: '구토·설사' }))
    expect(next.hasAttribute('disabled')).toBe(false)

    await user.click(next)
    refresh()
    expect(window.location.search).toBe('?report=confirm')
    expect(screen.getByText('2 / 2')).toBeDefined()
    expect(screen.getByText('증상 있음 · 발열·기침·인후통, 구토·설사')).toBeDefined()
    expect(screen.getByText('11월 17일~23일 · ○○1동')).toBeDefined()
    expect(screen.queryByText(/둘러보는 동네와 달라요/)).toBeNull()
    expect(
      screen.getByText('행정동 단위로만 집계돼요. 이름·주소·위치는 받지 않아요.'),
    ).toBeDefined()

    await user.click(screen.getByRole('button', { name: '보내기' }))
    refresh()
    expect(window.location.search).toBe('?report=done')
    expect(submittedAnswer()).toBe(
      '{"kind":"symptom","symptoms":["respiratory","gastrointestinal"]}',
    )
    // 증상을 보낸 경우에는 되돌리기 알림이 없다 (Report-done 시안)
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull()
  })

  it('이미 보낸 주에는 수정 안내를 보이고, 고쳐 보내면 되돌리기 알림을 주지 않는다', async () => {
    const { user, refresh } = await setup({ kind: 'none' })

    expect(
      screen.getByText('11월 19일에 보고했어요. 수정하면 집계에는 마지막 보고만 반영돼요.'),
    ).toBeDefined()

    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    refresh()
    expect(title()).toBe('이번 주 보고를 받았어요')
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull()
  })

  it('증상을 보냈던 주를 고칠 때는 이전에 고른 증상이 채워져 있다', async () => {
    const { user, refresh } = await setup({ kind: 'symptom', symptoms: ['gastrointestinal'] })

    await user.click(screen.getByRole('button', { name: '증상이 있었어요' }))
    refresh()
    expect(screen.getByRole('button', { name: '구토·설사' }).getAttribute('aria-pressed')).toBe(
      'true',
    )
  })

  it('완료 화면의 보고 수정하기는 시작 단계로, 변화 보기는 흐름을 닫는다', async () => {
    const go = vi.spyOn(window.history, 'go').mockImplementation(() => {})
    const { user, refresh } = await setup({ kind: 'none' })
    window.history.replaceState({ sneezecastModalDepth: 1 }, '', '/?report=done')
    refresh()

    await user.click(screen.getByRole('button', { name: '우리 동네 변화 보기' }))
    expect(go).toHaveBeenCalledWith(-1)

    await user.click(screen.getByRole('button', { name: '보고 수정하기' }))
    refresh()
    expect(window.location.search).toBe('?report=start')
    expect(screen.getByText(/11월 19일에 보고했어요/)).toBeDefined()
  })

  it('고르다가 닫으면 선택을 버린다 — 다시 열었을 때 지난 선택이 섞여 보내지지 않는다', async () => {
    vi.spyOn(window.history, 'go').mockImplementation(() => {
      window.history.replaceState(null, '', '/')
    })
    const { user, refresh } = await setup()
    await user.click(screen.getByRole('button', { name: '증상이 있었어요' }))
    refresh()
    await user.click(screen.getByRole('button', { name: '발열·기침·인후통' }))
    await user.click(screen.getByRole('button', { name: '닫기' }))
    refresh()

    window.history.replaceState(null, '', '/?report=symptom')
    refresh()
    expect(
      screen.getByRole('button', { name: '발열·기침·인후통' }).getAttribute('aria-pressed'),
    ).toBe('false')
  })

  it('다른 동네를 둘러보는 중이면 시작 단계에 보고 동네를 알리고, 확인 단계의 동네 앞에 "보고 동네" 를 붙인다', async () => {
    const user = userEvent.setup()
    const tree = () => <ReportFlow week={WEEK} regionCode="11440660" reportingElsewhere />
    const { rerender } = render(tree())
    expect(
      screen.getByText('보고는 내 동네 ○○1동 기준이에요. 둘러보는 동네와 달라요.'),
    ).toBeDefined()

    await user.click(screen.getByRole('button', { name: '증상이 있었어요' }))
    rerender(tree())
    await user.click(screen.getByRole('button', { name: '구토·설사' }))
    await user.click(screen.getByRole('button', { name: '다음' }))
    rerender(tree())
    expect(screen.getByText('11월 17일~23일 · 보고 동네 ○○1동')).toBeDefined()
  })

  it('함께 채우기는 기록을 쌓아 완료 화면 위에 공유 시트를 연다 — 링크는 보고 동네다', async () => {
    const { user, refresh } = await setup({ kind: 'none' }, '11110515', '11440660')
    window.history.replaceState({ sneezecastModalDepth: 1 }, '', '/?report=done&region=11110515')
    refresh()
    const pushState = vi.spyOn(window.history, 'pushState')

    await user.click(screen.getByRole('button', { name: SHARE_BUTTON }))
    refresh()

    expect(pushState).toHaveBeenCalledOnce()
    expect(window.location.search).toBe('?report=share&region=11110515')
    // 아래 완료 대화상자는 그대로다
    expect(title()).toBe('이번 주 보고를 받았어요')
    expect(shareSheet()).toBeDefined()
    expect(await copiedLink(user)).toBe(`${clientEnv.siteUrl}/?region=11440660`)
  })

  it('보고 동네를 모르면 둘러보기 동네, 그것도 없으면 동네 없는 홈 링크다', async () => {
    window.history.replaceState(null, '', '/?report=share')
    const { user, unmount } = await setup({ kind: 'none' }, '11110515')
    expect(await copiedLink(user)).toBe(`${clientEnv.siteUrl}/?region=11110515`)

    unmount()
    render(<Harness />)
    expect(await copiedLink(user)).toBe(`${clientEnv.siteUrl}/`)
  })

  it('공유 시트의 닫기는 뒤로 가기로 완료 화면에 돌아간다', async () => {
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {
      window.history.replaceState({ sneezecastModalDepth: 1 }, '', '/?report=done')
    })
    const { user, refresh } = await setup({ kind: 'none' })
    window.history.replaceState({ sneezecastModalDepth: 2 }, '', '/?report=share')
    refresh()

    const sheet = shareSheet()
    if (!sheet) throw new Error('공유 시트가 열려 있지 않다')
    await user.click(within(sheet).getByRole('button', { name: '닫기' }))
    refresh()

    expect(back).toHaveBeenCalledOnce()
    expect(window.location.search).toBe('?report=done')
    expect(shareSheet()).toBeUndefined()
    expect(title()).toBe('이번 주 보고를 받았어요')
  })

  it('주소로 바로 연 공유 시트(쌓은 기록 없음)의 닫기는 사이트를 떠나지 않고 완료로 바꾼다', async () => {
    const back = vi.spyOn(window.history, 'back')
    const { user, refresh } = await setup({ kind: 'none' })
    window.history.replaceState(null, '', '/?report=share')
    refresh()

    const sheet = shareSheet()
    if (!sheet) throw new Error('공유 시트가 열려 있지 않다')
    await user.click(within(sheet).getByRole('button', { name: '닫기' }))
    refresh()

    expect(back).not.toHaveBeenCalled()
    expect(window.location.search).toBe('?report=done')
    expect(shareSheet()).toBeUndefined()
  })

  it('보낸 보고 없이 주소로 공유 시트를 열면 시작 단계로 풀린다', () => {
    window.history.replaceState(null, '', '/?report=share')
    render(<Harness reportRegionCode="11440660" />)
    expect(title()).toBe(STEP_START_TITLE)
    expect(shareSheet()).toBeUndefined()
  })

  it('홈 화면 앱이어야 알림을 받는 기기는 완료 화면에서 설치 안내로 잇는다 — 동네 · 덮어쓰기를 남긴다', async () => {
    window.history.replaceState(
      null,
      '',
      '/?report=done&region=11440660&mock-auth=member&mock-push=needs-install',
    )
    const { user } = await setup({ kind: 'none' }, '11440660')

    const link = screen.getByRole('link', { name: INSTALL_ROW })
    expect(link.textContent).toContain('홈 화면에 추가하면 알림을 받을 수 있어요')
    // 보고 단계(report)는 넘기지 않는다. 닫으면 router.back() 으로 이 완료 주소에 돌아온다
    expect(link.getAttribute('href')).toBe(
      '/install?region=11440660&mock-auth=member&mock-push=needs-install',
    )

    // jsdom 은 링크 이동을 하지 않는다. 누름 처리만 확인한다
    link.addEventListener('click', (event) => event.preventDefault())
    await user.click(link)
  })

  it.each(['supported', 'unsupported'])(
    '푸시가 %s 이면 완료 화면에 설치 안내 행을 그리지 않는다',
    async (push) => {
      window.history.replaceState(null, '', `/?report=done&mock-push=${push}`)
      await setup({ kind: 'none' })
      expect(title()).toBe('이번 주 보고를 받았어요')
      expect(screen.queryByText(INSTALL_ROW)).toBeNull()
      expect(screen.queryByRole('link')).toBeNull()
    },
  )

  it('보낸 보고는 화면 밖(report-client)에 있어, 흐름을 새로 그려도 완료 단계가 이어진다', async () => {
    const { user, refresh, unmount } = await setup()
    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    refresh()
    expect(title()).toBe('이번 주 보고를 받았어요')

    // 홈을 떠났다 돌아온 것처럼 새로 그린다
    unmount()
    render(<Harness />)
    expect(window.location.search).toBe('?report=done')
    expect(title()).toBe('이번 주 보고를 받았어요')
  })
})

describe('resolveStep', () => {
  const none = { hasSelection: false, submitted: null }

  it('모르는 값이거나 없으면 닫힌 상태다', () => {
    expect(resolveStep(null, none)).toBeNull()
    expect(resolveStep('finish', none)).toBeNull()
  })

  it('고른 증상 없이 확인으로 들어오면 증상 고르기로 돌려보낸다', () => {
    expect(resolveStep('confirm', none)).toBe('symptom')
    expect(resolveStep('confirm', { ...none, hasSelection: true })).toBe('confirm')
  })

  it.each(['done', 'share'])('보낸 보고 없이 %s 로 들어오면 시작으로 돌려보낸다', (value) => {
    expect(resolveStep(value, none)).toBe('start')
    expect(
      resolveStep(value, { ...none, submitted: { answer: { kind: 'none' }, reportedLabel: '' } }),
    ).toBe(value)
  })
})
