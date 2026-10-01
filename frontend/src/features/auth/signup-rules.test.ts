import { describe, expect, it } from 'vitest'

import {
  confirmProblem,
  isEmailFormat,
  nicknameLength,
  nicknameProblem,
  passwordProblem,
} from './signup-rules'

describe('isEmailFormat', () => {
  it.each([
    ['dong@example.com', true],
    [' dong@example.co.kr ', true],
    ['dong@example', false],
    ['dong@.com', false],
    ['dong example@x.com', false],
    ['', false],
  ])('%s → %s', (value, expected) => {
    expect(isEmailFormat(value)).toBe(expected)
  })
})

describe('passwordProblem', () => {
  it.each([
    ['dongne2026', null],
    ['abc123', 'rule'],
    ['abcdefgh', 'rule'],
    ['12345678', 'rule'],
    ['Abcdefg1', null],
    // 상한 20자
    ['abcdefghij1234567890', null],
    ['abcdefghij12345678901', 'rule'],
    // 공백은 어떤 공백 문자든 막는다
    ['dongne 2026', 'rule'],
    ['dongne\t2026', 'rule'],
    ['dongne\u30002026', 'rule'],
    [' dongne2026', 'rule'],
    // 특수문자는 써도 된다
    ['dongne2026!', null],
  ])('%s → %s', (value, expected) => {
    expect(passwordProblem(value)).toBe(expected)
  })
})

describe('confirmProblem', () => {
  it('같으면 문제가 없고 다르면 mismatch 다', () => {
    expect(confirmProblem('dongne2026', 'dongne2026')).toBeNull()
    expect(confirmProblem('dongne2026', 'dongne2025')).toBe('mismatch')
  })
})

describe('nickname', () => {
  it('앞뒤 공백을 빼고 한 글자씩 센다', () => {
    expect(nicknameLength(' 동네지기 ')).toBe(4)
    expect(nicknameLength('😀동네')).toBe(3)
  })

  it.each([
    ['동', 'length'],
    ['동네', null],
    ['우리동네건강지킴이짱', null],
    ['우리동네건강지킴이짱짱', 'length'],
  ])('%s → %s', (value, expected) => {
    expect(nicknameProblem(value)).toBe(expected)
  })
})
