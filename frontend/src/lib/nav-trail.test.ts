import { describe, expect, it } from 'vitest'

import { canGoBackTo, nextTrail, settleReplace } from './nav-trail'

describe('nextTrail', () => {
  it('처음 들어온 주소를 쌓고 같은 주소면 그대로 둔다', () => {
    const first = nextTrail([], '/start', false)
    expect(first).toEqual(['/start'])
    expect(nextTrail(first, '/start', false)).toEqual(['/start'])
  })

  it('앞으로 가면 쌓고 바로 앞 주소로 돌아오면 뺀다', () => {
    const forward = nextTrail(['/start', '/setup/region'], '/setup/adult', false)
    expect(forward).toEqual(['/start', '/setup/region', '/setup/adult'])
    expect(nextTrail(forward, '/setup/region', false)).toEqual(['/start', '/setup/region'])
  })

  it('기록을 바꾼 이동은 맨 끝을 바꾼다', () => {
    expect(nextTrail(['/setup/adult'], '/setup/region', true)).toEqual(['/setup/region'])
  })
})

describe('canGoBackTo', () => {
  it('바로 앞이 후보 중 하나일 때만 되돌릴 수 있다', () => {
    expect(canGoBackTo(['/start', '/setup/region'], ['/start'])).toBe(true)
    expect(canGoBackTo(['/login', '/setup/region'], ['/login', '/signup/account'])).toBe(true)
    expect(canGoBackTo(['/signup/account', '/setup/region'], ['/login', '/signup/account'])).toBe(
      true,
    )
    expect(canGoBackTo(['/setup/region'], ['/start'])).toBe(false)
    expect(canGoBackTo(['/setup/adult', '/setup/region'], ['/start'])).toBe(false)
  })

  it('후보를 생략하면 바로 앞이 앱 안 화면이기만 하면 되돌릴 수 있다', () => {
    expect(canGoBackTo(['/', '/official'])).toBe(true)
    expect(canGoBackTo(['/notice/11680640/2025-W47', '/official'])).toBe(true)
    expect(canGoBackTo(['/official'])).toBe(false)
    expect(canGoBackTo([])).toBe(false)
  })

  it('후보가 함수면 바로 앞 경로로 판단한다', () => {
    const notSetup = (previous: string) => !previous.startsWith('/setup/')
    expect(canGoBackTo(['/me', '/login'], notSetup)).toBe(true)
    expect(canGoBackTo(['/setup/adult', '/login'], notSetup)).toBe(false)
    expect(canGoBackTo(['/login'], () => true)).toBe(false)
  })
})

describe('settleReplace', () => {
  const pending = { from: '/', to: '/terms/reconsent' }

  it('바꿔 갈 경로에 닿으면 replace 로 보고 비운다', () => {
    expect(settleReplace(pending, '/terms/reconsent')).toEqual({ replaced: true, pending: null })
  })

  it('건 화면에 닿은 커밋(도착한 커밋에서 걸었다)이면 기다린다', () => {
    expect(settleReplace(pending, '/')).toEqual({ replaced: false, pending })
  })

  it('다른 곳에 닿으면(버려진 이동) 버린다. 걸어 둔 것이 없으면 replace 가 아니다', () => {
    expect(settleReplace(pending, '/official')).toEqual({ replaced: false, pending: null })
    expect(settleReplace(null, '/official')).toEqual({ replaced: false, pending: null })
  })
})
