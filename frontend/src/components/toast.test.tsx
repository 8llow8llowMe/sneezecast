// @vitest-environment jsdom
import { act, render, renderHook, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ToastRegion, useToast } from './toast'

describe('ToastRegion', () => {
  it('알림이 없어도 live region 은 그려 둔다', () => {
    render(<ToastRegion toast={null} />)
    const region = screen.getByRole('status')

    expect(region.getAttribute('aria-live')).toBe('polite')
    expect(region.textContent).toBe('')
  })

  it('알림 문구와 동작 버튼을 보이고, 누르면 동작과 onAction 을 부른다', async () => {
    const undo = vi.fn()
    const onAction = vi.fn()
    render(
      <ToastRegion
        toast={{ message: '증상 없음으로 보냈어요', action: { label: '되돌리기', onClick: undo } }}
        onAction={onAction}
      />,
    )

    expect(screen.getByRole('status').textContent).toContain('증상 없음으로 보냈어요')
    await userEvent.setup().click(screen.getByRole('button', { name: '되돌리기' }))
    expect(undo).toHaveBeenCalledTimes(1)
    expect(onAction).toHaveBeenCalledTimes(1)
  })

  it('동작이 띄운 다음 알림은 onAction(닫기) 뒤에도 남는다', async () => {
    function Harness() {
      const { toast, show, dismiss } = useToast()
      return (
        <>
          <button
            type="button"
            onClick={() =>
              show({
                message: '증상 없음으로 보냈어요',
                action: {
                  label: '되돌리기',
                  onClick: () => show({ message: '보고를 되돌렸어요' }),
                },
              })
            }
          >
            보내기
          </button>
          <ToastRegion toast={toast} onAction={dismiss} />
        </>
      )
    }
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: '보내기' }))
    await user.click(screen.getByRole('button', { name: '되돌리기' }))
    expect(screen.getByRole('status').textContent).toBe('보고를 되돌렸어요')
  })

  it('동작이 없으면 버튼이 없다', () => {
    render(<ToastRegion toast={{ message: '저장했어요' }} />)
    expect(screen.queryByRole('button')).toBeNull()
  })
})

describe('useToast', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('show 한 알림은 정해진 시간 뒤 사라진다', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useToast(5000))

    act(() => {
      result.current.show({ message: '보냈어요' })
    })
    expect(result.current.toast?.message).toBe('보냈어요')

    act(() => {
      vi.advanceTimersByTime(4999)
    })
    expect(result.current.toast).not.toBeNull()

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(result.current.toast).toBeNull()
  })

  it('새 알림은 이전 알림을 바꾸고 시간을 다시 잰다', () => {
    vi.useFakeTimers()
    const { result } = renderHook(() => useToast(5000))

    act(() => {
      result.current.show({ message: '첫 번째' })
    })
    act(() => {
      vi.advanceTimersByTime(3000)
    })
    act(() => {
      result.current.show({ message: '두 번째' })
    })
    act(() => {
      vi.advanceTimersByTime(3000)
    })

    expect(result.current.toast?.message).toBe('두 번째')
  })

  it('dismiss 는 바로 닫는다', () => {
    const { result } = renderHook(() => useToast())

    act(() => {
      result.current.show({ message: '보냈어요' })
    })
    act(() => {
      result.current.dismiss()
    })
    expect(result.current.toast).toBeNull()
  })
})
