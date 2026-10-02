import { describe, expect, it } from 'vitest'

import { guardReportEntry, reportButtonLabel, reportEntryFor } from './report-gate'

describe('reportEntryFor', () => {
  it('비회원은 로그인 안내, 미동의 회원은 동의 시트, 동의한 회원은 보고 시작을 연다', () => {
    expect(reportEntryFor('guest')).toBe('login')
    expect(reportEntryFor('member-no-consent')).toBe('health-consent')
    expect(reportEntryFor('member')).toBe('start')
  })
})

describe('reportButtonLabel', () => {
  it('비회원은 로그인하고 보고하기다 — 보낸 보고가 있어도(덮어쓰기) 같다', () => {
    expect(reportButtonLabel('guest', false)).toBe('로그인하고 보고하기')
    expect(reportButtonLabel('guest', true)).toBe('로그인하고 보고하기')
  })

  it('회원이 이번 주 보고를 보내기 전이면 이번 주 건강 보고하기다', () => {
    expect(reportButtonLabel('member', false)).toBe('이번 주 건강 보고하기')
    expect(reportButtonLabel('member-no-consent', false)).toBe('이번 주 건강 보고하기')
  })

  it('동의한 회원이 보낸 뒤면 이번 주 보고 완료 · 수정하기다', () => {
    expect(reportButtonLabel('member', true)).toBe('이번 주 보고 완료 · 수정하기')
  })

  it('미동의 회원은 보낸 보고가 있어도(덮어쓰기) 완료로 보이지 않는다 — 누르면 동의 시트다', () => {
    expect(reportButtonLabel('member-no-consent', true)).toBe('이번 주 건강 보고하기')
  })
})

describe('guardReportEntry', () => {
  it.each([
    // 주소로 바로 들어온 보고 흐름은 회원 · 동의 상태에 맞는 시트로 바꾼다
    ['start', 'guest', 'login'],
    ['confirm', 'guest', 'login'],
    ['start', 'member-no-consent', 'health-consent'],
    ['done', 'member-no-consent', 'health-consent'],
    // 함께 채우기 공유 시트(#151)도 보고 흐름 단계라 같은 가드를 받는다
    ['share', 'guest', 'login'],
    ['share', 'member-no-consent', 'health-consent'],
    // 상태에 맞지 않는 시트
    ['health-consent', 'guest', 'login'],
    ['login', 'member-no-consent', 'health-consent'],
    ['login', 'member', 'start'],
    ['health-consent', 'member', 'start'],
  ] as const)('?report=%s · %s → %s', (value, auth, expected) => {
    expect(guardReportEntry(value, auth)).toBe(expected)
  })

  it.each([
    [null, 'guest'],
    ['login', 'guest'],
    ['health-consent', 'member-no-consent'],
    ['start', 'member'],
    ['symptom', 'member'],
    ['done', 'member'],
    ['share', 'member'],
    // 모르는 값은 아무 것도 열지 않으므로 그대로 둔다
    ['unknown', 'guest'],
  ] as const)('?report=%s · %s 는 그대로 둔다', (value, auth) => {
    expect(guardReportEntry(value, auth)).toBeNull()
  })
})
