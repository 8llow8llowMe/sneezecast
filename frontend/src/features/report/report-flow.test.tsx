// @vitest-environment jsdom
import { useState } from 'react'

import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ReportFlow, resolveStep } from './report-flow'
import type { SubmittedReport } from './types'

// 테스트에는 Next 라우터가 없다. useSearchParams 는 지금 주소를 읽고, 주소가 바뀌면 다시 그려 흉내 낸다
vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(window.location.search),
}))

const WEEK = {
  regionName: '○○1동',
  weekRangeLabel: '11월 17일~23일',
  reportPeriodLabel: '11월 17일(월)~23일(일)',
}

const onNotReady = vi.fn()

function Harness({ initial = null }: { initial?: SubmittedReport | null }) {
  const [submitted, setSubmitted] = useState(initial)
  return (
    <>
      <ReportFlow
        week={WEEK}
        submitted={submitted}
        onSubmittedChange={setSubmitted}
        onNotReady={onNotReady}
      />
      <span data-testid="submitted">{submitted ? JSON.stringify(submitted.answer) : ''}</span>
    </>
  )
}

/** 주소를 바꾸는 동작 뒤에 다시 그린다 (Next 는 pushState 마다 다시 그린다) */
function setup(initial: SubmittedReport | null = null) {
  const user = userEvent.setup()
  const utils = render(<Harness initial={initial} />)
  const refresh = () => utils.rerender(<Harness initial={initial} />)
  return { user, refresh, ...utils }
}

function title() {
  return document.querySelector('dialog[open] h2')?.textContent
}

function submittedAnswer() {
  return screen.getByTestId('submitted').textContent
}

describe('ReportFlow', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/?report=start')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('쿼리가 없으면 닫혀 있다', () => {
    window.history.replaceState(null, '', '/')
    render(<Harness />)
    expect(document.querySelector('dialog[open]')).toBeNull()
  })

  it('"증상 없었어요" 는 바로 보내고 완료 화면에 되돌리기 알림을 띄운다 — 완료는 기록 없이 바꾼다', async () => {
    const { user, refresh } = setup()
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
    const { user, refresh } = setup()

    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    refresh()
    await user.click(screen.getByRole('button', { name: '되돌리기' }))
    refresh()

    expect(window.location.search).toBe('?report=start')
    expect(submittedAnswer()).toBe('')
    expect(screen.queryByText(/에 보고했어요/)).toBeNull()
  })

  it('증상 고르기 → 확인 → 보내기. 단계로 나아갈 때마다 기록을 쌓는다', async () => {
    const { user, refresh } = setup()

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

  it('"그 외 증상만" 을 고르면 다른 선택이 풀린다', async () => {
    const { user, refresh } = setup()
    await user.click(screen.getByRole('button', { name: '증상이 있었어요' }))
    refresh()

    await user.click(screen.getByRole('button', { name: '발열·기침·인후통' }))
    await user.click(screen.getByRole('button', { name: /그 외 증상만 있었어요/ }))

    expect(
      screen.getByRole('button', { name: '발열·기침·인후통' }).getAttribute('aria-pressed'),
    ).toBe('false')
    expect(
      screen.getByRole('button', { name: /그 외 증상만 있었어요/ }).getAttribute('aria-pressed'),
    ).toBe('true')
  })

  it('이미 보낸 주에는 수정 안내를 보이고, 고쳐 보내면 되돌리기 알림을 주지 않는다', async () => {
    const { user, refresh } = setup({ answer: { kind: 'none' }, reportedLabel: '11월 19일' })

    expect(
      screen.getByText('11월 19일에 보고했어요. 수정하면 집계에는 마지막 보고만 반영돼요.'),
    ).toBeDefined()

    await user.click(screen.getByRole('button', { name: '증상 없었어요' }))
    refresh()
    expect(title()).toBe('이번 주 보고를 받았어요')
    expect(screen.queryByRole('button', { name: '되돌리기' })).toBeNull()
  })

  it('증상을 보냈던 주를 고칠 때는 이전에 고른 증상이 채워져 있다', async () => {
    const { user, refresh } = setup({
      answer: { kind: 'symptom', symptoms: ['gastrointestinal'] },
      reportedLabel: '11월 19일',
    })

    await user.click(screen.getByRole('button', { name: '증상이 있었어요' }))
    refresh()
    expect(screen.getByRole('button', { name: '구토·설사' }).getAttribute('aria-pressed')).toBe(
      'true',
    )
  })

  it('완료 화면의 보고 수정하기는 시작 단계로, 변화 보기는 흐름을 닫는다', async () => {
    const go = vi.spyOn(window.history, 'go').mockImplementation(() => {})
    const { user, refresh } = setup({ answer: { kind: 'none' }, reportedLabel: '11월 19일' })
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
    const { user, refresh } = setup()
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

  it('아직 없는 화면(홈 화면 추가 안내 · 함께 채우기)은 onNotReady 로 알린다', async () => {
    const { user, refresh } = setup({ answer: { kind: 'none' }, reportedLabel: '11월 19일' })
    window.history.replaceState(null, '', '/?report=done')
    refresh()

    await user.click(screen.getByRole('button', { name: /다음 주 월요일에 알려드릴까요/ }))
    await user.click(screen.getByRole('button', { name: '우리 동네 자료 함께 채우기' }))
    expect(onNotReady).toHaveBeenCalledWith('홈 화면 추가 안내')
    expect(onNotReady).toHaveBeenCalledWith('함께 채우기')
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

  it('보낸 보고 없이 완료로 들어오면 시작으로 돌려보낸다', () => {
    expect(resolveStep('done', none)).toBe('start')
    expect(
      resolveStep('done', { ...none, submitted: { answer: { kind: 'none' }, reportedLabel: '' } }),
    ).toBe('done')
  })
})
