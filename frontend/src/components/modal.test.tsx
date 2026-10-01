// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { Modal, type ModalProps } from './modal'

function renderModal(props: Partial<ModalProps> = {}) {
  const onClose = vi.fn()
  const utils = render(
    <Modal open onClose={onClose} title="지난 7일 동안 건강은 어땠나요?" {...props}>
      <p>11월 17일(월)~23일(일)</p>
      <button type="button">증상 없었어요</button>
    </Modal>,
  )
  // 닫힌 dialog 는 보조기술 트리에서 빠지므로 role 대신 태그로 찾는다
  const dialog = utils.container.querySelector('dialog')
  if (!dialog) throw new Error('dialog 가 없다')
  return { ...utils, dialog, onClose }
}

describe('Modal', () => {
  it('open 이면 열리고, 제목이 대화상자 이름이 된다', () => {
    const { dialog } = renderModal()

    expect(dialog.open).toBe(true)
    expect(screen.getByRole('dialog', { name: '지난 7일 동안 건강은 어땠나요?' })).toBe(dialog)
  })

  it('open 이 false 로 바뀌면 닫힌다', () => {
    const { dialog, rerender, onClose } = renderModal()

    rerender(
      <Modal open={false} onClose={onClose} title="제목">
        <p>내용</p>
      </Modal>,
    )
    expect(dialog.open).toBe(false)
  })

  it('닫기 버튼 · Esc(cancel) · 바깥 누르기는 onClose 를 부른다', async () => {
    const user = userEvent.setup()
    const { dialog, onClose } = renderModal()

    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(onClose).toHaveBeenCalledTimes(1)

    // Esc 를 누르면 브라우저가 cancel 이벤트를 보낸다. 브라우저가 바로 닫지 않게 막고 부모에게 맡긴다
    const cancel = new Event('cancel', { cancelable: true })
    dialog.dispatchEvent(cancel)
    expect(cancel.defaultPrevented).toBe(true)
    expect(onClose).toHaveBeenCalledTimes(2)

    // 바깥(::backdrop) 누르기는 dialog 자신이 클릭 대상이 된다
    fireEvent.click(dialog)
    expect(onClose).toHaveBeenCalledTimes(3)
  })

  it('안쪽 내용을 누르면 닫지 않는다', async () => {
    const { onClose } = renderModal()

    await userEvent.setup().click(screen.getByRole('button', { name: '증상 없었어요' }))
    await userEvent.setup().click(screen.getByText('11월 17일(월)~23일(일)'))
    expect(onClose).not.toHaveBeenCalled()
  })

  it('onBack 이 있으면 이전 단계 버튼과 단계 표시를 보인다', async () => {
    const onBack = vi.fn()
    renderModal({ onBack, step: '1 / 2' })

    expect(screen.getByText('1 / 2')).toBeDefined()
    await userEvent.setup().click(screen.getByRole('button', { name: '이전 단계' }))
    expect(onBack).toHaveBeenCalledTimes(1)
  })

  it('onBack 이 없으면 이전 단계 버튼이 없다', () => {
    renderModal()
    expect(screen.queryByRole('button', { name: '이전 단계' })).toBeNull()
  })

  it('compactSheet 면 머리줄을 모바일에서만 숨기고 대화상자에서는 보인다', () => {
    renderModal({ compactSheet: true })
    const header = screen.getByRole('button', { name: '닫기' }).parentElement

    expect(header?.classList).toContain('hidden')
    expect(header?.classList).toContain('tablet:flex')
  })

  it('기본은 머리줄이 모든 폭에서 보인다', () => {
    renderModal()
    const header = screen.getByRole('button', { name: '닫기' }).parentElement

    expect(header?.classList).toContain('flex')
    expect(header?.classList).not.toContain('hidden')
  })

  it('모바일은 시트, 태블릿부터 가운데 대화상자 폭을 쓴다', () => {
    const { classList } = renderModal().dialog

    expect(classList).toContain('rounded-t-sheet')
    expect(classList).toContain('mt-auto')
    expect(classList).toContain('tablet:w-dialog-tablet')
    expect(classList).toContain('desktop:w-dialog-desktop')
  })
})
