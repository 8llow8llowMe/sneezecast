// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type * as authClient from './auth-client'
import { agreeHealthConsent } from './auth-client'
import { HealthConsentSheet } from './health-consent-sheet'

vi.mock('./auth-client', async (importOriginal) => {
  const actual = await importOriginal<typeof authClient>()
  return { ...actual, agreeHealthConsent: vi.fn(actual.agreeHealthConsent) }
})

/** 응답을 테스트가 정할 때까지 붙잡아 둔다 */
function holdAgree() {
  let resolve: () => void = () => {}
  let reject: () => void = () => {}
  vi.mocked(agreeHealthConsent).mockImplementationOnce(
    () =>
      new Promise<void>((res, rej) => {
        resolve = res
        reject = () => rej(new Error())
      }),
  )
  return { resolve: () => resolve(), reject: () => reject() }
}

function setup({ open = true } = {}) {
  const onClose = vi.fn()
  const onAgreed = vi.fn()
  const user = userEvent.setup()
  const utils = render(<HealthConsentSheet open={open} onClose={onClose} onAgreed={onAgreed} />)
  return { user, onClose, onAgreed, ...utils }
}

const agree = () => screen.getByRole('button', { name: '동의하고 보고하기' })
const later = () => screen.getByRole('button', { name: '나중에 할게요' })
const check = () =>
  screen.getByRole<HTMLInputElement>('checkbox', {
    name: /건강·증상 정보\(민감정보\) 처리에 동의해요/,
  })
const isOff = (button: HTMLElement) => button.getAttribute('aria-disabled') === 'true'

describe('HealthConsentSheet', () => {
  beforeEach(() => {
    vi.mocked(agreeHealthConsent).mockClear()
  })

  it('S02-4 와 같은 고지 표를 시트 폭(항목 92 · 글자 13)으로 보인다', () => {
    setup()
    expect(screen.getByRole('dialog', { name: '증상 보고에 동의해 주세요' })).toBeDefined()
    expect(screen.getByText('보고하려면 건강 정보 처리에 따로 동의해야 해요.')).toBeDefined()
    const term = screen.getByText('보관 기간')
    expect(term.nextElementSibling?.textContent).toContain('52주 뒤 지워지고')
    expect(term.classList).toContain('w-23')
    expect(term.classList).toContain('text-sub')
  })

  it('체크 전에는 동의가 꺼져 눌러도 보내지 않는다 (unchecked)', async () => {
    const { user, onAgreed } = setup()
    expect(isOff(agree())).toBe(true)

    await user.click(agree())
    expect(agreeHealthConsent).not.toHaveBeenCalled()
    expect(onAgreed).not.toHaveBeenCalled()
  })

  it('체크하면 켜지고(checked), 동의하면 민감정보 동의를 한 번 보내고 onAgreed 를 부른다', async () => {
    const { user, onAgreed } = setup()
    const hold = holdAgree()

    await user.click(check())
    expect(isOff(agree())).toBe(false)
    await user.click(agree())
    await user.click(agree())

    // 보내는 중에는 두 버튼 모두 꺼진다 (포커스는 그대로)
    expect(isOff(agree())).toBe(true)
    expect(isOff(later())).toBe(true)
    expect(agreeHealthConsent).toHaveBeenCalledTimes(1)
    expect(agreeHealthConsent).toHaveBeenCalledWith({
      type: 'SENSITIVE_HEALTH_INFO',
      documentVersion: '2026-10-01',
    })

    hold.resolve()
    await waitFor(() => expect(onAgreed).toHaveBeenCalledTimes(1))
  })

  it('보내지 못하면 빨강 상자로 알리고 다시 누를 수 있다', async () => {
    const { user, onAgreed } = setup()
    const hold = holdAgree()

    await user.click(check())
    await user.click(agree())
    hold.reject()

    expect((await screen.findByRole('alert')).textContent).toBe(
      '동의를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.',
    )
    expect(isOff(agree())).toBe(false)
    expect(onAgreed).not.toHaveBeenCalled()

    await user.click(agree())
    await waitFor(() => expect(onAgreed).toHaveBeenCalledTimes(1))
    expect(agreeHealthConsent).toHaveBeenCalledTimes(2)
  })

  it('나중에 할게요는 동의 없이 닫는다. 보내는 중에는 닫지 않는다', async () => {
    const { user, onClose } = setup()
    await user.click(later())
    expect(onClose).toHaveBeenCalledTimes(1)

    holdAgree()
    await user.click(check())
    await user.click(agree())
    await user.click(later())
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('응답 전에 시트가 닫히면 늦게 온 응답으로 onAgreed 를 부르지 않는다', async () => {
    const { user, onAgreed, onClose, rerender } = setup()
    const hold = holdAgree()

    await user.click(check())
    await user.click(agree())
    rerender(<HealthConsentSheet open={false} onClose={onClose} onAgreed={onAgreed} />)
    hold.resolve()

    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(onAgreed).not.toHaveBeenCalled()
  })

  it('다시 열면 체크 · 안내를 처음부터 시작한다', async () => {
    const { user, onAgreed, onClose, rerender } = setup()
    await user.click(check())

    rerender(<HealthConsentSheet open={false} onClose={onClose} onAgreed={onAgreed} />)
    rerender(<HealthConsentSheet open onClose={onClose} onAgreed={onAgreed} />)
    expect(check().checked).toBe(false)
  })

  it('보기는 약관 본문을 준비하고 있다고 알린다', async () => {
    const { user } = setup()
    await user.click(
      screen.getByRole('button', { name: '건강·증상 정보(민감정보) 처리에 동의해요 보기' }),
    )
    expect(screen.getByRole('status').textContent).toBe('약관 본문을 준비하고 있어요')
  })
})
