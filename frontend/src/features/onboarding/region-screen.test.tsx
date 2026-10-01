// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type * as regionClient from '@/features/region/region-client'
import { searchDistricts } from '@/features/region/region-client'
import type { District } from '@/features/region/types'

import {
  EMPTY_SIGNUP,
  type Membership,
  NO_MEMBERSHIP,
  OnboardingProvider,
  type SignupDraft,
  useOnboarding,
} from './onboarding-context'
import { RegionScreen } from './region-screen'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
// 지금 주소. 앱 안 이동은 이 값을 바꾸고 다시 그려 흉내 낸다
const location = vi.hoisted(() => ({ pathname: '/setup/region' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => location.pathname,
}))

// 실제 목 검색을 쓰되 실패 · 늦은 응답을 흉내 낼 수 있게 감싼다
vi.mock('@/features/region/region-client', async (importOriginal) => {
  const actual = await importOriginal<typeof regionClient>()
  return { ...actual, searchDistricts: vi.fn(actual.searchDistricts) }
})

function setup({
  browse = false,
  initial = null,
}: { browse?: boolean; initial?: District | null } = {}) {
  const user = userEvent.setup()
  render(
    <OnboardingProvider initialDistrict={initial}>
      <RegionScreen browse={browse} />
    </OnboardingProvider>,
  )
  const input = screen.getByRole('searchbox', { name: '행정동 이름' })
  const next = screen.getByRole<HTMLButtonElement>('button', {
    name: browse ? '이 동네 보기' : '다음',
  })
  return { user, input, next }
}

describe('RegionScreen', () => {
  beforeEach(() => {
    router.push.mockClear()
    router.replace.mockClear()
    router.back.mockClear()
    location.pathname = '/setup/region'
    // vi.fn(구현) 의 mockReset 은 처음 준 구현(실제 목 검색)으로 되돌린다
    vi.mocked(searchDistricts).mockReset()
  })

  it('검색어에 맞는 행정동을 시군구와 함께 라디오로 보인다', async () => {
    const { user, input } = setup()
    await user.type(input, '신사')

    const options = await screen.findAllByRole('radio')
    expect(options.map((option) => option.closest('label')?.textContent)).toEqual([
      '신사동서울특별시 강남구',
      '신사동서울특별시 관악구',
    ])
    expect(screen.getByRole('status').textContent).toBe('동네 2곳을 찾았어요')
  })

  it('빈 검색어면 결과도 안내도 없다', () => {
    setup()
    expect(screen.queryAllByRole('radio')).toHaveLength(0)
    expect(screen.getByRole('status').textContent).toBe('')
  })

  it('맞는 동이 없으면 찾는 동네가 없다고 알린다', async () => {
    const { user, input } = setup()
    await user.type(input, '없는동이름')

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toBe(
        '‘없는동이름’과 맞는 행정동이 없어요동 이름을 다시 확인해 주세요.예: 망원1동, 역삼2동',
      ),
    )
    expect(screen.queryAllByRole('radio')).toHaveLength(0)
  })

  it('검색이 실패하면 다시 검색하라고 알린다', async () => {
    vi.mocked(searchDistricts).mockRejectedValueOnce(new Error('network'))
    const { user, input } = setup()
    await user.type(input, '역삼')

    await waitFor(() =>
      expect(screen.getByRole('status').textContent).toContain('동네를 불러오지 못했어요'),
    )
  })

  it('늦게 온 이전 검색어의 응답은 무시한다', async () => {
    let resolveSlow: (value: District[]) => void = () => {}
    vi.mocked(searchDistricts).mockImplementationOnce(
      () => new Promise<District[]>((resolve) => (resolveSlow = resolve)),
    )
    const { user, input } = setup()

    await user.type(input, '신사')
    await waitFor(() => expect(searchDistricts).toHaveBeenCalledWith('신사'))

    await user.clear(input)
    await user.type(input, '역삼')
    await screen.findByRole('radio', { name: /역삼1동/ })

    // 늦은 응답이 상태를 바꿨다면 다시 그려질 때까지 기다린 뒤 확인한다
    await act(async () => {
      resolveSlow([{ code: '11680510', name: '신사동', sigungu: '서울특별시 강남구' }])
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(screen.queryByRole('radio', { name: /신사동/ })).toBeNull()
  })

  it('동네를 고르기 전에는 다음이 꺼져 있고 고르면 켜진 뒤 성인 확인으로 간다', async () => {
    const { user, input, next } = setup()
    expect(screen.getByText('1 / 4')).toBeDefined()
    expect(next.disabled).toBe(true)

    await user.type(input, '역삼')
    const option = await screen.findByRole<HTMLInputElement>('radio', { name: /역삼2동/ })
    await user.click(option)

    expect(option.checked).toBe(true)
    expect(next.disabled).toBe(false)
    await user.click(next)
    expect(router.push).toHaveBeenCalledWith('/setup/adult')
  })

  it('고른 동네가 있으면 그 이름으로 검색해 선택된 채로 보인다', async () => {
    setup({ initial: { code: '11680650', name: '역삼2동', sigungu: '서울특별시 강남구' } })

    const option = await screen.findByRole<HTMLInputElement>('radio', { name: /역삼2동/ })
    expect(option.checked).toBe(true)
  })

  it('둘러보기 모드는 단계 표시 없이 "이 동네 보기" 로 고른 동네 홈에 간다', async () => {
    const { user, input, next } = setup({ browse: true })
    expect(screen.queryByText(/\/ 4/)).toBeNull()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('어느 동네를 볼까요?')

    await user.type(input, '서교')
    await user.click(await screen.findByRole('radio', { name: /서교동/ }))
    await user.click(next)

    expect(router.push).toHaveBeenCalledWith('/?region=11440660')
  })

  it('고른 뒤 검색어를 고치면 선택이 지워져 다음이 꺼진다', async () => {
    const { user, input, next } = setup()
    await user.type(input, '역삼')
    await user.click(await screen.findByRole('radio', { name: /역삼1동/ }))
    expect(next.disabled).toBe(false)

    await user.type(input, '1')
    expect(next.disabled).toBe(true)
    const option = await screen.findByRole<HTMLInputElement>('radio', { name: /역삼1동/ })
    expect(option.checked).toBe(false)
  })

  it('주소로 바로 들어왔으면 뒤로는 기록을 쌓지 않고 로그인으로 바꿔 간다', async () => {
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/login')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.push).not.toHaveBeenCalled()
  })

  it('둘러보기는 주소로 바로 들어왔으면 시작 화면으로 바꿔 간다', async () => {
    location.pathname = '/browse/region'
    const { user } = setup({ browse: true })
    await user.click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.replace).toHaveBeenCalledWith('/start')
  })

  /** 앞 화면(`from`)에서 앱 안 이동으로 지금 화면(`to`)에 온 상태로 그린다 */
  function renderAfterNavigation(from: string, to: string, browse = false) {
    location.pathname = from
    // 같은 요소 객체를 다시 넘기면 React 가 다시 그리지 않아 매번 새로 만든다
    const tree = () => (
      <OnboardingProvider>
        <RegionScreen browse={browse} />
      </OnboardingProvider>
    )
    const { rerender } = render(tree())
    location.pathname = to
    rerender(tree())
  }

  it('로그인에서 앱 안 이동으로 왔으면 뒤로는 기록을 되돌린다', async () => {
    renderAfterNavigation('/login', '/setup/region')
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('이메일 가입(비밀번호 · 닉네임)에서 왔어도 뒤로는 기록을 되돌린다', async () => {
    renderAfterNavigation('/signup/account', '/setup/region')
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('둘러보기는 시작 화면에서 왔을 때 기록을 되돌린다', async () => {
    renderAfterNavigation('/start', '/browse/region', true)
    await userEvent.setup().click(screen.getByRole('button', { name: '뒤로' }))
    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })
})

describe('RegionScreen 카카오에서 돌아옴 (?from=kakao)', () => {
  const IN_PROGRESS: Membership = { accountCreated: true, loggedIn: true, regionSaved: false }
  const EMAIL_DRAFT: SignupDraft = {
    ...EMPTY_SIGNUP,
    method: 'email',
    email: 'dong@example.com',
    verifiedAt: 1,
    password: 'dongne2026',
  }

  function Probe() {
    const { signup, membership } = useOnboarding()
    return (
      <span hidden data-testid="state">
        {JSON.stringify({ signup, membership })}
      </span>
    )
  }
  const state = () =>
    JSON.parse(screen.getByTestId('state').textContent ?? '{}') as {
      signup: SignupDraft
      membership: Membership
    }

  function renderRegion({
    draft = EMPTY_SIGNUP,
    membership = NO_MEMBERSHIP,
    browse = false,
    fromKakao = true,
  }: {
    draft?: SignupDraft
    membership?: Membership
    browse?: boolean
    fromKakao?: boolean
  } = {}) {
    render(
      <OnboardingProvider initialSignup={draft} initialMembership={membership}>
        <RegionScreen browse={browse} fromKakao={fromKakao} />
        <Probe />
      </OnboardingProvider>,
    )
  }

  beforeEach(() => {
    location.pathname = '/setup/region'
  })

  it('가입 종류가 없으면(홈의 로그인 안내 시트에서 시작) 카카오로 둔다', () => {
    renderRegion()
    expect(state().signup).toEqual({ ...EMPTY_SIGNUP, method: 'kakao' })
  })

  it('이미 카카오면(S13-1 에서 시작 · 뒤로 가기로 다시 옴) 진행 중인 가입을 지우지 않는다', () => {
    const draft: SignupDraft = { ...EMPTY_SIGNUP, method: 'kakao' }
    renderRegion({ draft, membership: IN_PROGRESS })
    expect(state()).toEqual({ signup: draft, membership: IN_PROGRESS })
  })

  it('다른 가입 종류였다면 새 가입 시도라 초안과 마무리 진행을 비우고 카카오로 둔다', () => {
    renderRegion({ draft: EMAIL_DRAFT, membership: IN_PROGRESS })
    expect(state()).toEqual({
      signup: { ...EMPTY_SIGNUP, method: 'kakao' },
      membership: NO_MEMBERSHIP,
    })
  })

  it('카카오 표시가 없으면 가입 초안을 건드리지 않는다', () => {
    renderRegion({ draft: EMAIL_DRAFT, membership: IN_PROGRESS, fromKakao: false })
    expect(state()).toEqual({ signup: EMAIL_DRAFT, membership: IN_PROGRESS })
  })

  it('둘러보기에서는 ?from=kakao 가 있어도 무시한다', () => {
    renderRegion({ browse: true })
    expect(state().signup).toEqual(EMPTY_SIGNUP)
  })
})
