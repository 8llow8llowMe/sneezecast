// @vitest-environment jsdom
import { renderToString } from 'react-dom/server'

import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { APP_NAME } from '@/lib/app-info'

import { SHARE_TEXT } from './share-link'
import { ShareSheet } from './share-sheet'

const LINK = 'https://www.sneezecast.com/?region=11440660'
const FALLBACK = '복사하지 못했어요. 링크를 길게 눌러 복사해 주세요.'

/** 브라우저 API 를 테스트마다 바꿔 끼운다. jsdom 은 navigator.share · clipboard 가 없다 */
function stubNavigator(name: 'share' | 'clipboard', value: unknown) {
  Object.defineProperty(window.navigator, name, { value, configurable: true, writable: true })
}

/**
 * user-event 는 setup 때 navigator.clipboard 를 자기 흉내로 바꾼다. 클립보드는 그 뒤에 끼운다(없으면 undefined — 보안 연결이 아닌 브라우저)
 */
function setupUser(clipboard?: unknown) {
  const user = userEvent.setup()
  stubNavigator('clipboard', clipboard)
  return user
}

function linkField() {
  return screen.getByRole<HTMLInputElement>('textbox', { name: '공유 링크' })
}

describe('ShareSheet', () => {
  afterEach(() => {
    // 끼운 값을 지워 jsdom 의 원래 상태(없음)로 돌린다
    Reflect.deleteProperty(window.navigator, 'share')
    Reflect.deleteProperty(window.navigator, 'clipboard')
    vi.restoreAllMocks()
  })

  it('시안의 제목 · 미리보기 카드를 보이고, 평소에는 링크 칸이 없다', () => {
    render(<ShareSheet open onClose={() => {}} link={LINK} />)
    expect(document.querySelector('dialog[open] h2')?.textContent).toBe('이렇게 공유돼요')
    expect(screen.getByText(APP_NAME)).toBeDefined()
    expect(screen.getByText(/우리 동네 건강을\s*같이 살펴요/)).toBeDefined()
    expect(
      screen.getByText('일주일에 한 번 10초면 돼요. 이름·주소·위치는 받지 않아요.'),
    ).toBeDefined()
    expect(screen.getByText('내 보고 내용은 공유되지 않아요')).toBeDefined()
    expect(screen.queryByRole('textbox', { name: '공유 링크' })).toBeNull()
  })

  it('링크 복사는 클립보드에 링크를 쓰고 알림을 띄운다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    const user = setupUser({ writeText })
    render(<ShareSheet open onClose={() => {}} link={LINK} />)

    await user.click(screen.getByRole('button', { name: '링크 복사' }))
    expect(writeText).toHaveBeenCalledWith(LINK)
    expect(screen.getByRole('status').textContent).toBe('링크를 복사했어요')
    expect(screen.queryByText(FALLBACK)).toBeNull()
  })

  it('클립보드에 쓰지 못하면 그때 링크 칸을 보여 골라 두고 직접 복사하라고 안내한다', async () => {
    const user = setupUser({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    render(<ShareSheet open onClose={() => {}} link={LINK} />)

    await user.click(screen.getByRole('button', { name: '링크 복사' }))
    const field = linkField()
    expect(field.value).toBe(LINK)
    expect(field.readOnly).toBe(true)
    expect(document.activeElement).toBe(field)
    expect([field.selectionStart, field.selectionEnd]).toEqual([0, LINK.length])
    // 안내는 칸의 설명이라 포커스를 받을 때 함께 읽힌다
    expect(field.getAttribute('aria-describedby')).toBeTruthy()
    expect(screen.getByText(FALLBACK)).toBeDefined()
    expect(screen.getByRole('status').textContent).toBe('')
  })

  it('클립보드가 없는 브라우저(보안 연결이 아님)도 같은 대체 안내다. 다시 눌러도 칸을 다시 골라 둔다', async () => {
    const user = setupUser()
    render(<ShareSheet open onClose={() => {}} link={LINK} />)

    await user.click(screen.getByRole('button', { name: '링크 복사' }))
    expect(screen.getByText(FALLBACK)).toBeDefined()

    linkField().blur()
    await user.click(screen.getByRole('button', { name: '링크 복사' }))
    expect(document.activeElement).toBe(linkField())
  })

  it('공유 기능이 없는 브라우저는 메신저로 보내기가 없고 링크 복사가 주 버튼이다', () => {
    render(<ShareSheet open onClose={() => {}} link={LINK} />)
    expect(screen.queryByRole('button', { name: '메신저로 보내기' })).toBeNull()
    expect(screen.getByRole('button', { name: '링크 복사' }).classList).toContain('bg-brand')
  })

  it('공유 기능이 있으면 메신저로 보내기가 주 버튼 · 링크 복사가 보조이고, 앱 이름 · 문구 · 링크만 넘긴다', async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    stubNavigator('share', share)
    const user = userEvent.setup()
    render(<ShareSheet open onClose={() => {}} link={LINK} />)

    expect(screen.getByRole('button', { name: '링크 복사' }).classList).toContain('bg-section')
    expect(screen.getByRole('button', { name: '메신저로 보내기' }).classList).toContain('bg-brand')
    await user.click(screen.getByRole('button', { name: '메신저로 보내기' }))
    expect(share).toHaveBeenCalledWith({ title: APP_NAME, text: SHARE_TEXT, url: LINK })
    expect(screen.getByRole('status').textContent).toBe('')
  })

  it('공유 창을 취소하면 아무것도 알리지 않는다', async () => {
    stubNavigator('share', vi.fn().mockRejectedValue(new DOMException('취소', 'AbortError')))
    const user = userEvent.setup()
    render(<ShareSheet open onClose={() => {}} link={LINK} />)

    await user.click(screen.getByRole('button', { name: '메신저로 보내기' }))
    expect(screen.getByRole('status').textContent).toBe('')
  })

  it('그 밖의 공유 실패는 링크 복사로 안내한다', async () => {
    stubNavigator('share', vi.fn().mockRejectedValue(new DOMException('막힘', 'NotAllowedError')))
    const user = userEvent.setup()
    render(<ShareSheet open onClose={() => {}} link={LINK} />)

    await user.click(screen.getByRole('button', { name: '메신저로 보내기' }))
    expect(screen.getByRole('status').textContent).toBe(
      '메신저로 보내지 못했어요. 링크 복사를 눌러 주세요.',
    )
  })

  it('서버 · 하이드레이션 첫 그림에는 공유 버튼이 없고, 하이드레이션 뒤에 보인다', async () => {
    stubNavigator('share', vi.fn())
    const ui = <ShareSheet open onClose={() => {}} link={LINK} />
    const container = document.createElement('div')
    container.innerHTML = renderToString(ui)
    document.body.append(container)
    expect(container.textContent).toContain('링크 복사')
    expect(container.textContent).not.toContain('메신저로 보내기')

    render(ui, { container, hydrate: true })
    await act(async () => {})
    expect(screen.getByRole('button', { name: '메신저로 보내기' })).toBeDefined()
  })

  it('닫으면 링크 칸 · 대체 안내를 지우고 onClose 를 부른다', async () => {
    const onClose = vi.fn()
    const user = setupUser()
    const { rerender } = render(<ShareSheet open onClose={onClose} link={LINK} />)
    await user.click(screen.getByRole('button', { name: '링크 복사' }))
    expect(screen.getByText(FALLBACK)).toBeDefined()

    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(onClose).toHaveBeenCalledOnce()
    rerender(<ShareSheet open onClose={onClose} link={LINK} />)
    expect(screen.queryByText(FALLBACK)).toBeNull()
    expect(screen.queryByRole('textbox', { name: '공유 링크' })).toBeNull()
  })
})
