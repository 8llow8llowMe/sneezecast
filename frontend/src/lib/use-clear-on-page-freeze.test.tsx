// @vitest-environment jsdom
import { useState } from 'react'

import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { useClearOnPageFreeze } from './use-clear-on-page-freeze'

function SecretField({ onClear }: { onClear?: () => void }) {
  const [value, setValue] = useState('')
  useClearOnPageFreeze(() => {
    setValue('')
    onClear?.()
  })
  return (
    <input aria-label="비밀번호" value={value} onChange={(event) => setValue(event.target.value)} />
  )
}

/**
 * act 없이 보낸다 — flushSync 가 이벤트 처리 안에서 DOM 까지 커밋하는지 보려는 것이다.
 * RTL 이 켜 둔 act 환경에서 act 밖 업데이트는 "not wrapped in act" 경고를 내므로, 그 경고만 이 호출 동안 막는다(의도한 경고)
 */
function dispatch(type: 'pagehide' | 'pageshow', persisted: boolean) {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
  try {
    window.dispatchEvent(new PageTransitionEvent(type, { persisted }))
  } finally {
    consoleError.mockRestore()
  }
}

describe('useClearOnPageFreeze', () => {
  it('뒤로 가기 캐시에 들어가기 직전(pagehide persisted)에 비우고, 이벤트 처리 안에서 DOM 까지 바로 비운다', async () => {
    const user = userEvent.setup()
    render(<SecretField />)
    const field = screen.getByRole<HTMLInputElement>('textbox', { name: '비밀번호' })
    await user.type(field, 'Secret-PW-123')

    // act 로 감싸지 않는다 — 얼기 전에 커밋돼야 하므로 flushSync 가 이벤트 안에서 DOM 을 바꿔야 한다
    dispatch('pagehide', true)

    expect(field.value).toBe('')
  })

  it('되살아날 때(pageshow persisted)도 한 번 더 비운다', async () => {
    const user = userEvent.setup()
    const onClear = vi.fn()
    render(<SecretField onClear={onClear} />)
    const field = screen.getByRole<HTMLInputElement>('textbox', { name: '비밀번호' })
    await user.type(field, 'Secret-PW-123')

    dispatch('pageshow', true)

    expect(field.value).toBe('')
    expect(onClear).toHaveBeenCalledTimes(1)
  })

  it('캐시에 들지 않는 보통 떠나기 · 열기(persisted false)는 그대로 둔다', async () => {
    const user = userEvent.setup()
    const onClear = vi.fn()
    render(<SecretField onClear={onClear} />)
    const field = screen.getByRole<HTMLInputElement>('textbox', { name: '비밀번호' })
    await user.type(field, 'Secret-PW-123')

    act(() => {
      dispatch('pagehide', false)
      dispatch('pageshow', false)
    })

    expect(field.value).toBe('Secret-PW-123')
    expect(onClear).not.toHaveBeenCalled()
  })

  it('마지막으로 그린 비우기 함수를 부르고, 해제하면 더 듣지 않는다', () => {
    const first = vi.fn()
    const second = vi.fn()
    const { rerender, unmount } = render(<SecretField onClear={first} />)
    rerender(<SecretField onClear={second} />)

    dispatch('pagehide', true)
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledTimes(1)

    unmount()
    dispatch('pagehide', true)
    expect(second).toHaveBeenCalledTimes(1)
  })
})
