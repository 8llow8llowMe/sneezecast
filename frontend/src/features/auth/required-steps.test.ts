import { beforeEach, describe, expect, it } from 'vitest'

import {
  ME_DEVICES_PATH,
  ME_NICKNAME_PATH,
  ME_PASSWORD_PATH,
  ME_PATH,
  ME_REGION_PATH,
  ME_REPORTS_PATH,
} from '@/features/me/me-paths'
import type { SessionSnapshot } from '@/lib/session/session-store'

import {
  agreeTermsReconsent,
  EXAMPLE_PROFILES,
  loginWithEmail,
  type MockProfile,
  resetMockSession,
} from './auth-client'
import { consentFor } from './legal'
import type { MyRegion } from './member-client'
import type { MemberInfoSnapshot } from './member-info'
import {
  carriedParams,
  memberRequirements,
  NEXT_PATHS,
  parseMockRequired,
  reportIntentFrom,
  requiredStepHref,
  safeNextPath,
  sessionRequirements,
  stepTarget,
  targetAfter,
} from './required-steps'

const profile = (patch: Partial<MockProfile>): MockProfile => ({
  ...EXAMPLE_PROFILES.email,
  ...patch,
})
const OLD = { code: '11680640', name: '역삼1동' }

describe('parseMockRequired', () => {
  it.each([
    ['terms', ['terms']],
    ['region', ['region']],
    // 적은 순서와 무관하게 재동의가 먼저다
    ['region,terms', ['terms', 'region']],
    [' terms , region ', ['terms', 'region']],
    ['terms,unknown', ['terms']],
    ['unknown', null],
    ['', null],
    [null, null],
  ] as const)('%s → %j', (value, expected) => {
    expect(parseMockRequired(value)).toEqual(expected)
  })
})

describe('memberRequirements', () => {
  it('비회원은 조건이 있어도 거칠 화면이 없다', () => {
    const conditions = profile({ termsReconsentRequired: true, regionAbolished: true, region: OLD })
    expect(memberRequirements('guest', conditions, ['terms', 'region'])).toEqual({
      steps: [],
      abolishedRegion: null,
      settled: true,
    })
  })

  it('회원은 프로필의 조건대로, 재동의 → 동네 순서로 거친다', () => {
    expect(
      memberRequirements(
        'member',
        profile({ termsReconsentRequired: true, regionAbolished: true, region: OLD }),
        null,
      ),
    ).toEqual({ steps: ['terms', 'region'], abolishedRegion: OLD, settled: true })
    expect(
      memberRequirements(
        'member-no-consent',
        profile({ regionAbolished: true, region: OLD }),
        null,
      ),
    ).toEqual({ steps: ['region'], abolishedRegion: OLD, settled: true })
    expect(memberRequirements('member', profile({}), null)).toEqual({
      steps: [],
      abolishedRegion: null,
      settled: true,
    })
  })

  it('덮어쓰기가 있으면 프로필 조건 대신 그 값이고, 옛 동네가 없으면 시안 예시다', () => {
    const conditions = profile({ termsReconsentRequired: true })
    expect(memberRequirements('member', conditions, ['region'])).toEqual({
      steps: ['region'],
      abolishedRegion: { code: '99990110', name: '○○1동' },
      settled: true,
    })
    // 덮어쓰기만 있어 세션 프로필이 없어도 같다
    expect(memberRequirements('member', null, ['terms'])).toEqual({
      steps: ['terms'],
      abolishedRegion: null,
      settled: true,
    })
  })

  it('프로필에 폐지된 동네가 있으면 덮어쓰기에도 그 이름을 쓴다', () => {
    expect(
      memberRequirements('member', profile({ regionAbolished: true, region: OLD }), ['region'])
        .abolishedRegion,
    ).toEqual(OLD)
  })
})

describe('sessionRequirements (실데이터)', () => {
  const session = (pendingConsents: string[] = []): SessionSnapshot => ({
    status: 'member',
    summary: { memberId: 'm1', role: 'USER', pendingConsents, reportWritable: true },
  })
  const info = (region: MemberInfoSnapshot['region'], memberId = 'm1'): MemberInfoSnapshot => ({
    memberId,
    info: { status: 'loading' },
    region,
  })
  const myRegion = (patch: Partial<MyRegion>): MyRegion => ({
    code: '11680640',
    name: '역삼1동',
    sigungu: '서울특별시 강남구',
    abolished: false,
    ...patch,
  })

  it('비회원(복원 중 포함)은 조건이 없고 정해진 것이다', () => {
    expect(sessionRequirements({ status: 'restoring' }, null)).toEqual({
      steps: [],
      abolishedRegion: null,
      settled: true,
    })
  })

  it('내 동네를 읽는 중이면 동네 조건을 판단하지 않는다 — 재동의는 세션으로 바로 정한다', () => {
    expect(sessionRequirements(session(['TERMS_OF_SERVICE']), info({ status: 'loading' }))).toEqual(
      { steps: ['terms'], abolishedRegion: null, settled: false },
    )
    // 저장소가 아직 앞 회원 것이면 읽는 중과 같다
    expect(
      sessionRequirements(
        session(),
        info({ status: 'ready', value: myRegion({ abolished: true }) }, 'm0'),
      ),
    ).toEqual({ steps: [], abolishedRegion: null, settled: false })
    expect(sessionRequirements(session(), null).settled).toBe(false)
  })

  it('내 동네가 폐지됐으면 재동의 뒤에 동네 다시 고르기다 (이름을 모르면 null)', () => {
    expect(
      sessionRequirements(
        session(['TERMS_OF_SERVICE']),
        info({ status: 'ready', value: myRegion({ abolished: true }) }),
      ),
    ).toEqual({
      steps: ['terms', 'region'],
      abolishedRegion: { code: '11680640', name: '역삼1동' },
      settled: true,
    })
    expect(
      sessionRequirements(
        session(),
        info({ status: 'ready', value: myRegion({ name: null, sigungu: null, abolished: true }) }),
      ).abolishedRegion,
    ).toEqual({ code: '11680640', name: null })
  })

  it('내 동네가 현행이거나 아직 고르지 않았거나 읽지 못했으면 다시 고르게 하지 않는다', () => {
    const none = { steps: [], abolishedRegion: null, settled: true }
    expect(sessionRequirements(session(), info({ status: 'ready', value: myRegion({}) }))).toEqual(
      none,
    )
    expect(sessionRequirements(session(), info({ status: 'ready', value: null }))).toEqual(none)
    expect(sessionRequirements(session(), info({ status: 'failed' }))).toEqual(none)
  })
})

describe('safeNextPath (오픈 리다이렉트 방지)', () => {
  it('허용 목록은 홈 · 내 정보 화면이고 me-paths 와 같다', () => {
    expect(NEXT_PATHS).toEqual([
      '/',
      ME_PATH,
      ME_DEVICES_PATH,
      ME_PASSWORD_PATH,
      ME_REGION_PATH,
      ME_NICKNAME_PATH,
      ME_REPORTS_PATH,
    ])
  })

  it.each(['/', '/me', '/me/devices', '/me/password', '/me/region', '/me/nickname', '/me/reports'])(
    '%s 는 그대로다',
    (path) => {
      expect(safeNextPath(path)).toBe(path)
    },
  )

  it.each([
    ['다른 오리진(//)', '//evil.example'],
    ['다른 오리진(https)', 'https://evil.example/me'],
    ['자바스크립트', 'javascript:alert(1)'],
    ['목록 밖 경로', '/setup/terms'],
    ['쿼리가 붙음', '/me?confirm=withdraw'],
    ['조각이 붙음', '/me#x'],
    ['경로 우회', '/me/../login'],
    ['역슬래시', '/\\evil.example'],
    ['역슬래시 하나', '/\\evil'],
    ['대문자', '/ME'],
    ['끝 빗금', '/me/'],
    ['앞뒤 공백', ' /me '],
    ['인코딩된 다른 오리진(그대로 온 값)', '%2F%2Fevil'],
    ['인코딩된 다른 오리진(디코드한 값)', decodeURIComponent('%2F%2Fevil')],
    ['빈 값', ''],
    ['없음', null],
  ])('%s 이면 홈이다', (_, value) => {
    expect(safeNextPath(value)).toBe('/')
  })
})

describe('carriedParams', () => {
  it('동네 · 목 덮어쓰기만 남기고 열린 시트 · 그 밖 쿼리는 버린다', () => {
    const params = carriedParams(
      new URLSearchParams(
        'region=11440660&mock-auth=member&mock-provider=kakao&mock-required=terms&report=start&mock=high&next=/me',
      ),
    )
    expect(params.toString()).toBe(
      'region=11440660&mock-auth=member&mock-provider=kakao&mock-required=terms',
    )
  })

  it('마친 조건은 덮어쓰기에서 빼고, 다 빠지면 쿼리를 지운다', () => {
    const both = new URLSearchParams('mock-required=terms,region&mock-auth=member')
    expect(carriedParams(both, 'terms').get('mock-required')).toBe('region')
    const one = new URLSearchParams('mock-required=region&mock-auth=member')
    expect(carriedParams(one, 'region').toString()).toBe('mock-auth=member')
  })

  it('아는 값이 없는 덮어쓰기는 버린다', () => {
    expect(carriedParams(new URLSearchParams('mock-required=nope')).toString()).toBe('')
  })
})

describe('requiredStepHref · stepTarget', () => {
  const params = new URLSearchParams('region=11440660&mock-auth=member')

  it('재동의는 /terms/reconsent, 동네는 /setup/region?reselect=1 이고 돌아갈 곳을 붙인다', () => {
    expect(requiredStepHref('terms', '/me/devices', params)).toBe(
      '/terms/reconsent?next=%2Fme%2Fdevices&region=11440660&mock-auth=member',
    )
    expect(requiredStepHref('region', '/me', params)).toBe(
      '/setup/region?reselect=1&next=%2Fme&region=11440660&mock-auth=member',
    )
  })

  it('돌아갈 곳이 홈이면 next 를 붙이지 않는다', () => {
    expect(requiredStepHref('terms', '/', new URLSearchParams())).toBe('/terms/reconsent')
  })

  it('남은 화면이 없으면 돌아갈 곳에 남길 쿼리를 붙인다', () => {
    expect(stepTarget([], '/me', params)).toBe('/me?region=11440660&mock-auth=member')
    expect(stepTarget([], '/', new URLSearchParams())).toBe('/')
    expect(stepTarget(['terms', 'region'], '/', new URLSearchParams())).toBe('/terms/reconsent')
  })
})

describe('보고하려던 로그인을 조건 화면 내내 잇는다 (#140)', () => {
  beforeEach(() => resetMockSession())

  it('reportIntentFrom: intent=report 이고 돌아갈 곳이 홈일 때만이다', () => {
    expect(reportIntentFrom(new URLSearchParams('intent=report'), '/')).toBe(true)
    expect(reportIntentFrom(new URLSearchParams('intent=report'), '/me')).toBe(false)
    expect(reportIntentFrom(new URLSearchParams('intent=share'), '/')).toBe(false)
    expect(reportIntentFrom(new URLSearchParams(), '/')).toBe(false)
  })

  it('조건 화면 주소에 intent=report 를 붙인다 — 홈으로 돌아갈 때만', () => {
    const params = new URLSearchParams('region=11680640')
    expect(requiredStepHref('terms', '/', params, true)).toBe(
      '/terms/reconsent?region=11680640&intent=report',
    )
    expect(requiredStepHref('region', '/me', params, true)).toBe(
      '/setup/region?reselect=1&next=%2Fme&region=11680640',
    )
  })

  it('남은 조건이 없는 stepTarget 은 보고 진입을 붙이지 않는다 (마친 것이 아니라 닿은 것이라)', () => {
    expect(stepTarget([], '/', new URLSearchParams('region=11680640'), true)).toBe(
      '/?region=11680640',
    )
    expect(stepTarget(['region'], '/', new URLSearchParams(), true)).toBe(
      '/setup/region?reselect=1&intent=report',
    )
  })

  it('재동의를 마쳤는데 동네가 남았으면 동네 다시 고르기에 intent 를 잇는다', () => {
    const search = new URLSearchParams(
      'region=11680640&mock-auth=member&mock-required=terms,region&intent=report',
    )
    expect(targetAfter('terms', 'member', search, 'mock')).toBe(
      '/setup/region?reselect=1&region=11680640&mock-auth=member&mock-required=region&intent=report',
    )
  })

  it('남은 조건이 없으면 같은 동네 홈의 보고 진입(report=start)으로 간다', () => {
    const search = new URLSearchParams('region=11680640&mock-required=region&intent=report')
    expect(targetAfter('region', 'member', search, 'mock')).toBe('/?region=11680640&report=start')
    expect(targetAfter('terms', 'member', new URLSearchParams('intent=report'), 'mock')).toBe(
      '/?report=start',
    )
  })

  it('돌아갈 곳이 홈이 아니거나 intent 를 모르면 보고 진입을 붙이지 않는다', () => {
    expect(
      targetAfter('terms', 'member', new URLSearchParams('next=/me&intent=report'), 'mock'),
    ).toBe('/me')
    expect(targetAfter('terms', 'member', new URLSearchParams('intent=REPORT'), 'mock')).toBe('/')
  })

  it('목록 밖 next 는 홈이 되어 보고 진입으로 간다 (next 규칙은 바꾸지 않는다)', () => {
    expect(
      targetAfter(
        'terms',
        'member',
        new URLSearchParams('next=//evil.example&intent=report'),
        'mock',
      ),
    ).toBe('/?report=start')
  })
})

describe('targetAfter', () => {
  beforeEach(() => resetMockSession())

  it('재동의를 마쳤는데 덮어쓰기에 동네가 남았으면 동네 다시 고르기로 이어 간다', () => {
    const search = new URLSearchParams('next=/me&mock-auth=member&mock-required=terms,region')
    expect(targetAfter('terms', 'member', search, 'mock')).toBe(
      '/setup/region?reselect=1&next=%2Fme&mock-auth=member&mock-required=region',
    )
  })

  it('마친 뒤 남은 조건이 없으면 next 로, 목록 밖 next 는 홈으로 간다', () => {
    expect(
      targetAfter('region', 'member', new URLSearchParams('next=/me&mock-required=region'), 'mock'),
    ).toBe('/me')
    expect(targetAfter('terms', 'member', new URLSearchParams('next=//evil.example'), 'mock')).toBe(
      '/',
    )
  })

  it('목 프로필의 결과를 읽는다 — 재동의를 마친 프로필은 다시 재동의로 보내지 않는다', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    await agreeTermsReconsent(consentFor('TERMS_OF_SERVICE'))
    expect(targetAfter('terms', 'member-no-consent', new URLSearchParams('next=/me'), 'mock')).toBe(
      '/me',
    )
  })

  it('마친 조건은 프로필에 아직 남아 있어도 다시 넣지 않는다(되돌이 방지)', async () => {
    await loginWithEmail('reconsent@example.com', 'dongne2026', 'mock')
    expect(targetAfter('terms', 'member-no-consent', new URLSearchParams(), 'mock')).toBe('/')
  })
})
