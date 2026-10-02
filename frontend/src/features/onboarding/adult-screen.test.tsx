// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { District } from '@/features/region/types'
import { NavTrailProvider } from '@/lib/use-nav-trail'

import { AdultScreen } from './adult-screen'
import { OnboardingProvider } from './onboarding-context'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
const location = vi.hoisted(() => ({ pathname: '/setup/adult' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => location.pathname,
}))

const DISTRICT: District = { code: '11680640', name: '역삼1동', sigungu: '서울특별시 강남구' }

function renderAdult(initial: District | null = DISTRICT) {
  return render(
    <NavTrailProvider>
      <OnboardingProvider initialDistrict={initial}>
        <AdultScreen />
      </OnboardingProvider>
    </NavTrailProvider>,
  )
}

describe('AdultScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    router.back.mockClear()
    location.pathname = '/setup/adult'
  })

  it('확인 체크 전에는 다음이 꺼져 있고 체크하면 켜져 동의 단계로 간다', async () => {
    const user = userEvent.setup()
    renderAdult()
    expect(screen.getByText('2 / 4')).toBeDefined()
    const next = screen.getByRole<HTMLButtonElement>('button', { name: '다음' })
    expect(next.disabled).toBe(true)

    await user.click(screen.getByRole('checkbox', { name: '성인 본인의 건강 상태만 보고할게요' }))
    expect(next.disabled).toBe(false)

    await user.click(next)
    expect(router.push).toHaveBeenCalledWith('/setup/terms')
  })

  it('체크를 다시 풀면 다음이 꺼진다', async () => {
    const user = userEvent.setup()
    renderAdult()
    const box = screen.getByRole('checkbox', { name: '성인 본인의 건강 상태만 보고할게요' })

    await user.click(box)
    await user.click(box)
    expect(screen.getByRole<HTMLButtonElement>('button', { name: '다음' }).disabled).toBe(true)
  })

  it('고른 동네가 없으면 그리지 않고 동네 선택으로 돌려보낸다', () => {
    const { container } = renderAdult(null)
    expect(container.textContent).toBe('')
    expect(router.replace).toHaveBeenCalledWith('/setup/region')
  })

  it('동네 선택에서 앱 안 이동으로 왔으면 뒤로는 기록을 되돌린다', async () => {
    location.pathname = '/setup/region'
    // 같은 요소 객체를 다시 넘기면 React 가 다시 그리지 않아 매번 새로 만든다
    const tree = () => (
      <NavTrailProvider>
        <OnboardingProvider initialDistrict={DISTRICT}>
          <AdultScreen />
        </OnboardingProvider>
      </NavTrailProvider>
    )
    const { rerender } = render(tree())
    location.pathname = '/setup/adult'
    rerender(tree())

    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('주소로 바로 들어왔으면 뒤로는 동네 선택으로 바꿔 간다', async () => {
    renderAdult()
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/setup/region')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })
})
