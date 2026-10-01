import { describe, expect, it } from 'vitest'

import { canGoBackTo, nextTrail } from './onboarding-trail'

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
  it('바로 앞이 그 주소일 때만 되돌릴 수 있다', () => {
    expect(canGoBackTo(['/start', '/setup/region'], '/start')).toBe(true)
    expect(canGoBackTo(['/setup/region'], '/start')).toBe(false)
    expect(canGoBackTo(['/setup/adult', '/setup/region'], '/start')).toBe(false)
  })
})
