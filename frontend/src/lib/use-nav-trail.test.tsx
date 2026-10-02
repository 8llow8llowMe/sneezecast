// @vitest-environment jsdom
import { type ReactNode, StrictMode, useEffect } from 'react'

import { act, render } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { NavTrailProvider, useNavTrail } from './use-nav-trail'

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }))
// 지금 주소. 앱 안 이동은 이 값을 바꾸고 다시 그려 흉내 낸다
const location = vi.hoisted(() => ({ pathname: '/' }))
vi.mock('next/navigation', () => ({
  useRouter: () => router,
  usePathname: () => location.pathname,
}))

let navTrail: ReturnType<typeof useNavTrail> | null = null
function Probe() {
  navTrail = useNavTrail()
  return null
}

function trail() {
  if (!navTrail) throw new Error('Probe 를 그리지 않았다')
  return navTrail
}

/**
 * 어떤 경로에 도착한 커밋의 effect 에서 replace 하는 화면(회원 가드 · 값이 없어 앞 단계로 돌려보내는 단계 화면)을 흉내 낸다.
 * `at` 경로에서만 그린다
 */
function ReplaceOnArrival({ at, to }: { at: string; to: string }) {
  const { replace } = useNavTrail()
  const here = location.pathname === at
  useEffect(() => {
    if (here) replace(to)
  }, [here, replace, to])
  return null
}

/** 주소를 차례로 지나며 그린다. 첫 주소가 이 문서를 처음 연 주소다. `extra` 가 돌려준 요소를 Provider 안에 함께 그린다 */
function visit(
  paths: readonly string[],
  { strict = false, extra }: { strict?: boolean; extra?: () => ReactNode } = {},
) {
  // 같은 요소 객체를 다시 넘기면 React 가 다시 그리지 않아 매번 새로 만든다
  const tree = () => {
    const ui = (
      <NavTrailProvider>
        <Probe />
        {extra?.()}
      </NavTrailProvider>
    )
    return strict ? <StrictMode>{ui}</StrictMode> : ui
  }
  const [first, ...rest] = paths
  location.pathname = first ?? '/'
  const result = render(tree())
  const go = (path: string) => {
    location.pathname = path
    result.rerender(tree())
  }
  rest.forEach(go)
  return { ...result, go }
}

beforeEach(() => {
  navTrail = null
  vi.clearAllMocks()
})

describe('useNavTrail', () => {
  it('주소로 바로 들어왔으면 fallback 으로 기록을 바꿔 간다', () => {
    visit(['/official'])
    trail().goBack('/?region=1111051500')
    expect(router.replace).toHaveBeenCalledWith('/?region=1111051500')
    expect(router.back).not.toHaveBeenCalled()
  })

  it('앱 안 이동으로 왔으면 기록을 되돌린다. 후보를 생략하면 어느 화면에서 왔든 같다', () => {
    visit(['/notice/11680640/2025-W47', '/official'])
    trail().goBack('/')
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('후보를 주면 바로 앞이 후보일 때만 되돌린다', () => {
    visit(['/me', '/me/devices'])
    trail().goBack('/me?region=1', ['/me'])
    expect(router.back).toHaveBeenCalledOnce()

    vi.clearAllMocks()
    visit(['/', '/me/devices'])
    trail().goBack('/me?region=1', ['/me'])
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/me?region=1')
  })

  it('replace 로 간 이동은 기록의 맨 끝을 바꾼다 — 바꾼 화면은 앞 기록이 되지 않는다', () => {
    const { go } = visit(['/setup/adult'])
    act(() => trail().replace('/setup/region'))
    expect(router.replace).toHaveBeenCalledWith('/setup/region')
    go('/setup/region')

    vi.clearAllMocks()
    trail().goBack('/start', ['/start', '/setup/adult'])
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/start')
  })

  it('바로 앞 주소로 돌아오면(휴대폰 뒤로) 기록을 뺀다', () => {
    visit(['/', '/install', '/'])
    trail().goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('주소로 연 화면 → 홈 → 같은 화면 → 휴대폰 뒤로 두 번이면 앞 기록이 없어 replace 한다 (사이트 밖으로 나가지 않는다)', () => {
    const notice = '/notice/11680640/2025-W47'
    visit([notice, '/', notice, '/', notice])
    trail().goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('새로고침 · 새 탭(Provider 를 새로 그림)이면 앞 기록 없이 시작한다', () => {
    const { unmount } = visit(['/me', '/install'])
    unmount()

    visit(['/install'])
    trail().goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('StrictMode 의 두 번 그리기 · effect 에도 앱 안 기록을 잃거나 두 번 쌓지 않는다', () => {
    visit(['/', '/official'], { strict: true })
    trail().goBack('/')
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()

    vi.clearAllMocks()
    visit(['/official'], { strict: true })
    trail().goBack('/')
    expect(router.back).not.toHaveBeenCalled()
  })

  it('Provider 밖이면 앞 기록을 모르는 것으로 보고 늘 replace 한다', () => {
    render(<Probe />)
    trail().goBack('/', ['/'])
    trail().replace('/login')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenNthCalledWith(1, '/')
    expect(router.replace).toHaveBeenNthCalledWith(2, '/login')
  })
})

/**
 * 기록을 바꾸는 이동이 기록과 어긋나면(유령 항목) 바로 연 첫 화면의 뒤로가 사이트 밖으로 나간다. 후보를 생략하는 화면
 * (공식 정보 · 동네 안내 · 설치 안내)이 특히 그렇다. 아래는 그 회귀를 막는다.
 */
describe('useNavTrail — replace 와 기록이 어긋나지 않는다', () => {
  it('회원 가드가 홈에 도착한 커밋에서 replace 해도 바로 연 공식 정보로 휴대폰 뒤로 돌아오면 사이트 밖으로 나가지 않는다', () => {
    // /official 바로 열기 → 탭바 홈 → 가드가 /terms/reconsent 로 replace → 휴대폰 뒤로 → 머리줄 뒤로
    const { go } = visit(['/official', '/'], {
      extra: () => <ReplaceOnArrival at="/" to="/terms/reconsent" />,
    })
    expect(router.replace).toHaveBeenCalledWith('/terms/reconsent')
    go('/terms/reconsent')
    go('/official')

    vi.clearAllMocks()
    trail().goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('홈에서 로그인 만료로 /login 에 replace 한 뒤 휴대폰 뒤로 돌아온 공식 정보도 replace 한다', () => {
    const { go } = visit(['/official', '/'])
    act(() => trail().replace('/login?reason=expired'))
    go('/login')
    go('/official')

    vi.clearAllMocks()
    trail().goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('주소로 연 안내 → 로그인 → 이메일 로그인 성공(홈으로 replace) → 휴대폰 뒤로 두 번이면 안내의 뒤로가 replace 한다', () => {
    const notice = '/notice/11680640/2025-W47'
    const { go } = visit([notice, '/login', '/login/email'])
    act(() => trail().replace('/'))
    go('/')
    go('/login')
    go(notice)

    vi.clearAllMocks()
    trail().goBack('/')
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/')
  })

  it('바로 연 단계 화면이 마운트 effect 에서 앞 단계로 replace 해도 유령 항목을 남기지 않는다', () => {
    // 바로 연 /setup/adult 가 고른 동네가 없어 /setup/region 으로 돌려보낸다. 브라우저 기록은 [/setup/region] 하나다
    const { go } = visit(['/setup/adult'], {
      extra: () => <ReplaceOnArrival at="/setup/adult" to="/setup/region" />,
    })
    go('/setup/region')

    vi.clearAllMocks()
    trail().goBack('/start', ['/start', '/setup/adult'])
    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith('/start')
  })

  it('같은 경로로의 replace(쿼리만 바뀜)는 걸어 두지 않아 나중 이동을 replace 로 잘못 세지 않는다', () => {
    const { go } = visit(['/start', '/login'])
    act(() => trail().replace('/login?reason=expired'))
    go('/login/email')
    go('/login')

    vi.clearAllMocks()
    trail().goBack('/start', ['/start'])
    expect(router.back).toHaveBeenCalledOnce()
    expect(router.replace).not.toHaveBeenCalled()
  })

  it('Next 가 버린 replace 는 다른 곳에 닿으면 버린다 — 나중에 그 경로로 링크를 따라가면 쌓는다', () => {
    const { go } = visit(['/'])
    act(() => trail().replace('/login'))
    // replace 가 버려지고 다른 화면으로 갔다
    go('/official')
    go('/login')

    vi.clearAllMocks()
    trail().goBack('/start', ['/official'])
    expect(router.back).toHaveBeenCalledOnce()
  })
})
